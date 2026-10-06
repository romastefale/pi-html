package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.AppDatabase
import com.example.data.repository.WebAppRepository
import com.example.service.RunnerKeepAliveService
import com.example.ui.RunnerScreen
import com.example.ui.RunnerViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.webview.WebViewPoolManager

class MainActivity : ComponentActivity() {

    private val database by lazy { AppDatabase.getInstance(applicationContext) }
    val repository by lazy { WebAppRepository(applicationContext, database.savedWebAppDao()) }

    val viewModel: RunnerViewModel by viewModels {
        RunnerViewModel.Factory(repository)
    }

    lateinit var webViewPoolManager: WebViewPoolManager
        private set

    private var pendingWebFileCallback: ValueCallback<Array<Uri>>? = null

    private val webFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingWebFileCallback
        pendingWebFileCallback = null
        if (callback != null) {
            val uris = if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                    ?: result.data?.data?.let { arrayOf(it) }
            } else {
                null
            }
            callback.onReceiveValue(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        enableHighRefreshRate()
        enableEdgeToEdge()

        // Initialize WebViewPoolManager with centralized callbacks and error recovery
        webViewPoolManager = WebViewPoolManager(
            context = applicationContext,
            htmlBytesProvider = { app -> repository.readSavedHtmlBytes(app) },
            onTitleUpdated = { appId, title -> viewModel.onWebViewTitleUpdated(appId, title) },
            onStateCaptured = { appId, lastUrl, sx, sy, stateBase64 ->
                viewModel.onWebViewStateCaptured(appId, lastUrl, sx, sy, stateBase64)
            },
            onViewportColorsUpdated = { appId, topColor, bottomColor ->
                viewModel.onViewportColorsUpdated(appId, topColor, bottomColor)
            },
            onDownloadCompleted = { savedFileName ->
                viewModel.showToast(getString(R.string.toast_download_saved, savedFileName))
            },
            onDownloadError = {
                viewModel.showToast(getString(R.string.toast_download_error))
            },
            onRenderProcessGone = { appId ->
                viewModel.onRenderProcessGone(appId)
            }
        ).apply {
            fileChooserLauncher = { callback, params ->
                pendingWebFileCallback?.onReceiveValue(null)
                pendingWebFileCallback = callback
                runCatching {
                    val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    webFileChooserLauncher.launch(intent)
                    true
                }.getOrElse {
                    pendingWebFileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

        handleIncomingHtmlIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(uiState.isDarkTheme, uiState.activeAppId, uiState.activeViewportColors) {
                val activeColors = uiState.activeViewportColors
                val isLightTopBar = if (uiState.activeApp != null && activeColors != null) {
                    ColorUtils.calculateLuminance(activeColors.topColor) >= 0.5
                } else {
                    !uiState.isDarkTheme
                }
                val isLightBottomBar = if (uiState.activeApp != null && activeColors != null) {
                    ColorUtils.calculateLuminance(activeColors.bottomColor) >= 0.5
                } else {
                    !uiState.isDarkTheme
                }

                val statusStyle = if (isLightTopBar) {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                } else {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                }

                val navStyle = if (isLightBottomBar) {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                } else {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                }

                enableEdgeToEdge(
                    statusBarStyle = statusStyle,
                    navigationBarStyle = navStyle
                )

                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = isLightTopBar
                    isAppearanceLightNavigationBars = isLightBottomBar
                }
            }

            val notifPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted && uiState.activeApp != null) {
                    RunnerKeepAliveService.start(applicationContext)
                }
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permission = android.Manifest.permission.POST_NOTIFICATIONS
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            permission
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermissionLauncher.launch(permission)
                    }
                }
            }

            val htmlPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    viewModel.importHtmlUri(
                        uri = uri,
                        onErrorToast = getString(R.string.toast_html_error)
                    )
                }
            }

            MyApplicationTheme(darkTheme = uiState.isDarkTheme) {
                RunnerScreen(
                    uiState = uiState,
                    urlInputState = viewModel.urlInput,
                    webViewPoolManager = webViewPoolManager,
                    onUrlInputChange = viewModel::onUrlInputChange,
                    onSubmitUrl = viewModel::submitUrl,
                    onSelectHtmlFileClick = {
                        htmlPickerLauncher.launch(
                            arrayOf("text/html", "application/xhtml+xml", "text/plain", "*/*")
                        )
                    },
                    onOpenSidebar = viewModel::openSidebar,
                    onCloseSidebar = viewModel::closeSidebar,
                    onShowOverlayControls = viewModel::showOverlayControls,
                    onHideOverlayControls = viewModel::hideOverlayControls,
                    onNewAppClick = viewModel::showLauncherHome,
                    onSelectSavedApp = viewModel::selectSavedApp,
                    onDeleteSavedApp = { appId ->
                        viewModel.deleteSavedApp(appId) { deletedId ->
                            webViewPoolManager.removeAndDestroyWebView(deletedId)
                        }
                    },
                    onToggleTheme = viewModel::toggleTheme,
                    onMoveToBackground = {
                        if (uiState.activeApp != null) {
                            RunnerKeepAliveService.start(applicationContext)
                        }
                        moveTaskToBack(true)
                    }
                )
            }
        }
    }

    private fun enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    this.display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val highestMode = display?.supportedModes?.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    window.attributes = params
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingHtmlIntent(intent)
    }

    private fun handleIncomingHtmlIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val dataUri = intent.data ?: return
            if (dataUri.scheme == "content") {
                viewModel.importHtmlUri(
                    uri = dataUri,
                    onErrorToast = getString(R.string.toast_html_error)
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::webViewPoolManager.isInitialized) {
            webViewPoolManager.onTrimMemory(level)
        }
    }

    override fun onPause() {
        if (::webViewPoolManager.isInitialized) {
            webViewPoolManager.saveAllStates()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::webViewPoolManager.isInitialized) {
            webViewPoolManager.saveAllStates()
            // Keep WebView instances alive in RAM; only detach Activity reference
            webViewPoolManager.detachActivityContext()
        }
        super.onDestroy()
    }
}
