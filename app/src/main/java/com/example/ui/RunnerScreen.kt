package com.example.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.SavedWebAppEntity
import com.example.ui.components.BottomComposerBar
import com.example.ui.components.FloatingMenuButton
import com.example.ui.components.SidebarDrawer
import com.example.ui.theme.ToastBackground
import com.example.ui.theme.ToastText
import com.example.webview.WebViewPoolManager
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

private val ToastShape = RoundedCornerShape(11.dp)
private val WelcomeMarkShape = RoundedCornerShape(18.dp)

@Composable
fun RunnerScreen(
    uiState: RunnerUiState,
    urlInputState: StateFlow<String>,
    webViewPoolManager: WebViewPoolManager,
    onUrlInputChange: (String) -> Unit,
    onSubmitUrl: () -> Unit,
    onSelectHtmlFileClick: () -> Unit,
    onOpenSidebar: () -> Unit,
    onCloseSidebar: () -> Unit,
    onShowOverlayControls: () -> Unit,
    onHideOverlayControls: () -> Unit,
    onNewAppClick: () -> Unit,
    onSelectSavedApp: (Long) -> Unit,
    onDeleteSavedApp: (Long) -> Unit,
    onToggleTheme: () -> Unit,
    onMoveToBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val activeApp = uiState.activeApp
    val themeBg = MaterialTheme.colorScheme.background
    val viewportColors = uiState.activeViewportColors

    // Adopt the HTML viewport's top and bottom boundary colors when an app is active
    val topStatusBarColor = if (activeApp != null && viewportColors != null) {
        Color(viewportColors.topColor)
    } else {
        themeBg
    }
    val bottomSystemBarColor = if (activeApp != null && viewportColors != null) {
        Color(viewportColors.bottomColor)
    } else {
        themeBg
    }

    // Handle system Back navigation without blocking the UI thread
    BackHandler(enabled = true) {
        when {
            uiState.isSidebarOpen -> onCloseSidebar()
            activeApp != null && uiState.isOverlayControlsVisible -> onHideOverlayControls()
            activeApp != null -> {
                if (!webViewPoolManager.goBack(activeApp.id)) {
                    onShowOverlayControls()
                }
            }
            else -> onMoveToBackground()
        }
    }

    var menuDragOffsetX by remember { mutableFloatStateOf(0f) }
    var menuDragOffsetY by remember { mutableFloatStateOf(0f) }

    val handleSwipeRightOpenSidebar = remember(onOpenSidebar) {
        {
            focusManager.clearFocus()
            onOpenSidebar()
        }
    }
    val handleSwipeDownShowControls = remember(onShowOverlayControls) {
        {
            onShowOverlayControls()
        }
    }
    val handleTapHideControls = remember(onHideOverlayControls) {
        {
            focusManager.clearFocus()
            onHideOverlayControls()
        }
    }
    val handleSelectHtml = remember(onSelectHtmlFileClick) {
        {
            focusManager.clearFocus()
            onSelectHtmlFileClick()
        }
    }
    val handleSubmitUrl = remember(onSubmitUrl) {
        {
            focusManager.clearFocus()
            onSubmitUrl()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bottomSystemBarColor)
            .pointerInput(uiState.isSidebarOpen) {
                var startX = 0f
                var startY = 0f
                var totalDx = 0f
                var totalDy = 0f
                var triggered = false
                detectDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x
                        startY = offset.y
                        totalDx = 0f
                        totalDy = 0f
                        triggered = false
                    },
                    onDrag = { change, dragAmount ->
                        totalDx += dragAmount.x
                        totalDy += dragAmount.y
                        if (!triggered) {
                            if (!uiState.isSidebarOpen &&
                                startX <= size.width * 0.5f &&
                                totalDx > 36.dp.toPx() &&
                                abs(totalDx) > abs(totalDy) * 1.35f
                            ) {
                                triggered = true
                                change.consume()
                                handleSwipeRightOpenSidebar()
                            } else if (startY <= size.height * 0.22f &&
                                totalDy > 34.dp.toPx() &&
                                abs(totalDy) > abs(totalDx) * 1.35f
                            ) {
                                triggered = true
                                change.consume()
                                handleSwipeDownShowControls()
                            }
                        }
                    }
                )
            }
            .testTag("runner_root")
    ) {
        // Top Status Bar Background (under the system clock) adopting the HTML viewport's top boundary color
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(topStatusBarColor)
                .align(Alignment.TopCenter)
                .testTag("status_bar_html_bg")
        )

        // Bottom Navigation Bar Background adopting the HTML viewport's bottom boundary color
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(bottomSystemBarColor)
                .align(Alignment.BottomCenter)
                .testTag("nav_bar_html_bg")
        )

        // Main Content: Isolated Fullscreen Multi-WebView RAM Container or Minimalist Welcome Screen
        if (activeApp != null) {
            FullscreenWebAppContainer(
                activeApp = activeApp,
                webViewPoolManager = webViewPoolManager,
                isOverlayVisible = uiState.isOverlayControlsVisible,
                isSidebarOpen = uiState.isSidebarOpen,
                onSwipeRightFromLeftHalf = handleSwipeRightOpenSidebar,
                onSwipeDownFromTop = handleSwipeDownShowControls,
                onTapInsideWebAppWhenOverlayVisible = handleTapHideControls,
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

        // Top-Left Floating Menu Button (shown on Welcome screen or when revealed via top-down swipe over running HTML)
        AnimatedVisibility(
            visible = uiState.shouldShowTopAndBottomControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
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
            ) {
                FloatingMenuButton(
                    onClick = handleSwipeRightOpenSidebar
                )
            }
        }

        // Bottom Floating Pill Input Bar (isolated state observation so typing never recomposes RunnerScreen/WebView)
        AnimatedVisibility(
            visible = uiState.shouldShowTopAndBottomControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            IsolatedBottomComposerBar(
                urlInputState = urlInputState,
                onUrlInputChange = onUrlInputChange,
                onSelectHtmlClick = handleSelectHtml,
                onSubmitUrl = handleSubmitUrl
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
                    .clip(ToastShape)
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

        // Slide-out Sidebar Drawer (zero main-thread blocking on switch)
        SidebarDrawer(
            isOpen = uiState.isSidebarOpen,
            savedApps = uiState.savedApps,
            activeAppId = uiState.activeAppId,
            isDarkTheme = uiState.isDarkTheme,
            onClose = onCloseSidebar,
            onNewAppClick = onNewAppClick,
            onSelectApp = onSelectSavedApp,
            onDeleteApp = onDeleteSavedApp,
            onToggleTheme = onToggleTheme
        )
    }
}

@Composable
private fun IsolatedBottomComposerBar(
    urlInputState: StateFlow<String>,
    onUrlInputChange: (String) -> Unit,
    onSelectHtmlClick: () -> Unit,
    onSubmitUrl: () -> Unit
) {
    val urlInput by urlInputState.collectAsStateWithLifecycle()
    BottomComposerBar(
        urlInput = urlInput,
        onUrlInputChange = onUrlInputChange,
        onSelectHtmlClick = onSelectHtmlClick,
        onSubmitUrl = onSubmitUrl
    )
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
                    .clip(WelcomeMarkShape)
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

/**
 * Custom FrameLayout that keeps all opened WebViews attached in RAM for 0ms instant switching
 * while intercepting:
 *  - Single-finger left-half swipe to the right -> opens Sidebar
 *  - Single-finger top-edge swipe down -> reveals the top menu button & bottom address bar over the running HTML app
 */
private class MultiAppGestureFrameLayout(context: Context) : FrameLayout(context) {
    var onSwipeRightFromLeft: (() -> Unit)? = null
    var onSwipeDownFromTop: (() -> Unit)? = null
    var onTapWhenOverlayVisible: (() -> Unit)? = null
    var isOverlayCurrentlyVisible: Boolean = false
    var isSidebarCurrentlyOpen: Boolean = false
    var currentDisplayedAppId: Long = -1L

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var gestureHandled = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isSidebarCurrentlyOpen) return true
        if (ev.pointerCount > 1) {
            gestureHandled = false
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                gestureHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureHandled) return true
                val dx = ev.x - downX
                val dy = ev.y - downY

                // 1. Swipe right starting from left half -> Open Sidebar
                val leftHalfZone = width * 0.5f
                if (downX <= leftHalfZone &&
                    dx > maxOf(touchSlop * 2.2f, 30f * density) &&
                    abs(dx) > abs(dy) * 1.5f
                ) {
                    gestureHandled = true
                    onSwipeRightFromLeft?.invoke()
                    return true
                }

                // 2. Swipe down starting from top zone -> Reveal top button & bottom bar over HTML
                val topZone = maxOf(height * 0.14f, 64f * density)
                if (!isOverlayCurrentlyVisible &&
                    downY <= topZone &&
                    dy > maxOf(touchSlop * 2.0f, 24f * density) &&
                    abs(dy) > abs(dx) * 1.5f
                ) {
                    gestureHandled = true
                    onSwipeDownFromTop?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (!gestureHandled && isOverlayCurrentlyVisible && dx < touchSlop && dy < touchSlop) {
                    onTapWhenOverlayVisible?.invoke()
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureHandled || isSidebarCurrentlyOpen) return true
        return super.onTouchEvent(event)
    }
}

@Composable
private fun FullscreenWebAppContainer(
    activeApp: SavedWebAppEntity,
    webViewPoolManager: WebViewPoolManager,
    isOverlayVisible: Boolean,
    isSidebarOpen: Boolean,
    onSwipeRightFromLeftHalf: () -> Unit,
    onSwipeDownFromTop: () -> Unit,
    onTapInsideWebAppWhenOverlayVisible: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MultiAppGestureFrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { container ->
            container.isOverlayCurrentlyVisible = isOverlayVisible
            container.isSidebarCurrentlyOpen = isSidebarOpen
            container.onSwipeRightFromLeft = onSwipeRightFromLeftHalf
            container.onSwipeDownFromTop = onSwipeDownFromTop
            container.onTapWhenOverlayVisible = onTapInsideWebAppWhenOverlayVisible

            webViewPoolManager.updateAppMetadata(activeApp)

            // Skip redundant view hierarchy manipulations if this app is already the displayed top WebView
            if (container.currentDisplayedAppId != activeApp.id) {
                container.currentDisplayedAppId = activeApp.id
                val targetWebView = webViewPoolManager.activateWebView(activeApp, container.context)

                if (targetWebView.parent !== container) {
                    (targetWebView.parent as? ViewGroup)?.removeView(targetWebView)
                    container.addView(
                        targetWebView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                // Keep inactive WebViews attached in RAM with View.GONE so they incur 0 measure/layout/draw cost
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    child.visibility = if (child === targetWebView) View.VISIBLE else View.GONE
                }
                targetWebView.requestFocus()
            }
        }
    )
}
