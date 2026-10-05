package com.example.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.local.SavedWebAppEntity
import com.example.ui.components.BottomComposerBar
import com.example.ui.components.FloatingMenuButton
import com.example.ui.components.SidebarDrawer
import com.example.ui.theme.ToastBackground
import com.example.ui.theme.ToastText
import com.example.webview.WebViewPoolManager
import kotlin.math.roundToInt

@Composable
fun RunnerScreen(
    uiState: RunnerUiState,
    webViewPoolManager: WebViewPoolManager,
    onUrlInputChange: (String) -> Unit,
    onSubmitUrl: () -> Unit,
    onSelectHtmlFileClick: () -> Unit,
    onOpenSidebar: () -> Unit,
    onCloseSidebar: () -> Unit,
    onNewAppClick: () -> Unit,
    onSelectSavedApp: (Long) -> Unit,
    onDeleteSavedApp: (Long) -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val activeApp = uiState.activeApp

    // Handle system Back navigation for Sidebar and Active Fullscreen WebApp
    BackHandler(enabled = uiState.isSidebarOpen || activeApp != null) {
        when {
            uiState.isSidebarOpen -> onCloseSidebar()
            activeApp != null -> {
                if (!webViewPoolManager.goBack(activeApp.id)) {
                    webViewPoolManager.saveAppState(activeApp.id)
                    onNewAppClick()
                }
            }
        }
    }

    var menuDragOffsetX by remember { mutableFloatStateOf(0f) }
    var menuDragOffsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("runner_root")
    ) {
        // Main Content: Either Fullscreen WebView (when an app is active) or Minimalist Welcome Screen
        if (activeApp != null) {
            FullscreenWebAppContainer(
                activeApp = activeApp,
                webViewPoolManager = webViewPoolManager,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .imePadding()
                    .testTag("fullscreen_webview_container")
            )
        } else {
            WelcomeCenterView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            )
        }

        // Top-Left Floating Menu Button (supports drag if user needs to move it over a fullscreen web app)
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 16.dp, start = 20.dp)
                .offset {
                    IntOffset(
                        x = menuDragOffsetX.roundToInt(),
                        y = menuDragOffsetY.roundToInt()
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        menuDragOffsetX = (menuDragOffsetX + dragAmount.x).coerceIn(-20f, 700f)
                        menuDragOffsetY = (menuDragOffsetY + dragAmount.y).coerceIn(-10f, 1200f)
                    }
                }
                .align(Alignment.TopStart)
        ) {
            FloatingMenuButton(
                onClick = {
                    focusManager.clearFocus()
                    activeApp?.let { webViewPoolManager.saveAppState(it.id) }
                    onOpenSidebar()
                }
            )
        }

        // Bottom Floating Pill Input Bar (shown on the launcher screen to open URL or HTML file)
        AnimatedVisibility(
            visible = activeApp == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomComposerBar(
                urlInput = uiState.urlInput,
                onUrlInputChange = onUrlInputChange,
                onSelectHtmlClick = {
                    focusManager.clearFocus()
                    onSelectHtmlFileClick()
                },
                onSubmitUrl = {
                    focusManager.clearFocus()
                    onSubmitUrl()
                }
            )
        }

        // Minimal Toast Notification
        AnimatedVisibility(
            visible = uiState.toastMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(ToastBackground)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("toast_banner")
            ) {
                Text(
                    text = uiState.toastMessage.orEmpty(),
                    color = ToastText,
                    fontSize = 12.sp
                )
            }
        }

        // Slide-out Sidebar Drawer
        SidebarDrawer(
            isOpen = uiState.isSidebarOpen,
            savedApps = uiState.savedApps,
            activeAppId = uiState.activeAppId,
            isDarkTheme = uiState.isDarkTheme,
            onClose = onCloseSidebar,
            onNewAppClick = {
                activeApp?.let { webViewPoolManager.saveAppState(it.id) }
                onNewAppClick()
            },
            onSelectApp = { targetId ->
                activeApp?.let { current ->
                    if (current.id != targetId) {
                        webViewPoolManager.saveAppState(current.id)
                    }
                }
                onSelectSavedApp(targetId)
            },
            onDeleteApp = onDeleteSavedApp,
            onToggleTheme = onToggleTheme
        )
    }
}

@Composable
private fun WelcomeCenterView(
    modifier: Modifier = Modifier
) {
    val markBg = MaterialTheme.colorScheme.tertiary
    val markText = MaterialTheme.colorScheme.onTertiary
    val headingColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier.testTag("welcome_view"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 64.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(55.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(markBg)
                    .testTag("welcome_mark"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.brand_short),
                    color = markText,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.displaySmall,
                color = headingColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("welcome_title")
            )
        }
    }
}

@Composable
private fun FullscreenWebAppContainer(
    activeApp: SavedWebAppEntity,
    webViewPoolManager: WebViewPoolManager,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { container ->
            webViewPoolManager.updateAppMetadata(activeApp)
            val currentChild = container.getChildAt(0)
            val targetWebView = webViewPoolManager.obtainWebView(activeApp, container.context)
            if (currentChild !== targetWebView) {
                container.removeAllViews()
                (targetWebView.parent as? ViewGroup)?.removeView(targetWebView)
                container.addView(
                    targetWebView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
    )
}
