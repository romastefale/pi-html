package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.AppDatabase
import com.example.data.repository.WebAppRepository
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
        enableEdgeToEdge()

        webViewPoolManager = WebViewPoolManager(
            context = applicationContext,
            htmlContentProvider = { app -> repository.readSavedHtmlContent(app) },
            onTitleUpdated = { appId, title -> viewModel.onWebViewTitleUpdated(appId, title) },
            onStateCaptured = { appId, lastUrl, sx, sy, stateBase64 ->
                viewModel.onWebViewStateCaptured(appId, lastUrl, sx, sy, stateBase64)
            },
            onDownloadCompleted = { savedFileName ->
                viewModel.showToast(getString(R.string.toast_download_saved, savedFileName))
            },
            onDownloadError = {
                viewModel.showToast(getString(R.string.toast_download_error))
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

            LaunchedEffect(uiState.isDarkTheme) {
                if (uiState.isDarkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        ),
                        navigationBarStyle = SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    )
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
                    onNewAppClick = viewModel::showLauncherHome,
                    onSelectSavedApp = viewModel::selectSavedApp,
                    onDeleteSavedApp = { appId ->
                        viewModel.deleteSavedApp(appId) { deletedId ->
                            webViewPoolManager.removeAndDestroyWebView(deletedId)
                        }
                    },
                    onToggleTheme = viewModel::toggleTheme
                )
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
            viewModel.importHtmlUri(
                uri = dataUri,
                onErrorToast = getString(R.string.toast_html_error)
            )
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
            if (isFinishing) {
                webViewPoolManager.destroyAll()
            }
        }
        super.onDestroy()
    }
}
