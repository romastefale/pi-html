package com.example.webview

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.example.data.local.SavedWebAppEntity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Immutable
data class ViewportBoundaryColors(
    val topColor: Int,
    val bottomColor: Int
)

data class PoolCallbacksHolder(
    val context: Context,
    val htmlBytesProvider: (SavedWebAppEntity) -> ByteArray,
    val onTitleUpdated: (Long, String) -> Unit,
    val onStateCaptured: (Long, String?, Int, Int, String?) -> Unit,
    val onViewportColorsUpdated: (Long, Int, Int) -> Unit,
    val onDownloadCompleted: (String) -> Unit,
    val onDownloadError: () -> Unit,
    val onRenderProcessGone: (Long) -> Unit
)

@Stable
class WebViewPoolManager(
    private val context: Context,
    private val htmlBytesProvider: (SavedWebAppEntity) -> ByteArray,
    private val onTitleUpdated: (Long, String) -> Unit,
    private val onStateCaptured: (Long, String?, Int, Int, String?) -> Unit,
    private val onViewportColorsUpdated: (Long, Int, Int) -> Unit = { _, _, _ -> },
    private val onDownloadCompleted: (String) -> Unit = {},
    private val onDownloadError: () -> Unit = {},
    private val onRenderProcessGone: (Long) -> Unit = {}
) {
    val freezeStateStorage = FreezeStateStorage(context.applicationContext)

    var fileChooserLauncher: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean)?
        get() = activeFileChooserLauncher
        set(value) {
            activeFileChooserLauncher = value
        }

    init {
        // Centralize active callbacks in a static holder so reused WebViews always route to current instance
        currentCallbacks = PoolCallbacksHolder(
            context = context.applicationContext,
            htmlBytesProvider = htmlBytesProvider,
            onTitleUpdated = onTitleUpdated,
            onStateCaptured = onStateCaptured,
            onViewportColorsUpdated = onViewportColorsUpdated,
            onDownloadCompleted = onDownloadCompleted,
            onDownloadError = onDownloadError,
            onRenderProcessGone = onRenderProcessGone
        )

        // Schedule maintenance runnable once globally across activity recreations
        if (isMaintenanceScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(maintenanceRunnable, MAINTENANCE_INTERVAL_MS)
        }
    }

    private class PiDownloadJsBridge(
        private val appId: Long,
        private val storage: FreezeStateStorage
    ) {
        @JavascriptInterface
        fun saveDataUrlToDownloads(dataUrl: String, suggestedFileName: String?, mimeType: String?) {
            ioExecutor.execute {
                val ctx = currentCallbacks?.context ?: return@execute
                val result = DownloadHelper.saveDataUrlToDownloads(
                    context = ctx,
                    dataUrlOrBase64 = dataUrl,
                    suggestedFileName = suggestedFileName,
                    explicitMimeType = mimeType
                )
                mainHandler.post {
                    result.onSuccess { savedName ->
                        currentCallbacks?.onDownloadCompleted?.invoke(savedName)
                    }.onFailure { err ->
                        Log.e(TAG, "saveDataUrlToDownloads failed for appId=$appId", err)
                        currentCallbacks?.onDownloadError?.invoke()
                    }
                }
            }
        }

        @JavascriptInterface
        fun beginChunkedDownload(sessionId: String, suggestedFileName: String?, mimeType: String?) {
            DownloadHelper.beginChunkedDownload(sessionId, suggestedFileName, mimeType)
        }

        @JavascriptInterface
        fun appendDownloadChunk(sessionId: String, base64Chunk: String) {
            if (!DownloadHelper.appendChunkBase64(sessionId, base64Chunk)) {
                mainHandler.post { currentCallbacks?.onDownloadError?.invoke() }
            }
        }

        @JavascriptInterface
        fun finishChunkedDownload(sessionId: String) {
            ioExecutor.execute {
                val ctx = currentCallbacks?.context ?: return@execute
                val result = DownloadHelper.finishChunkedDownload(ctx, sessionId)
                mainHandler.post {
                    result.onSuccess { savedName ->
                        currentCallbacks?.onDownloadCompleted?.invoke(savedName)
                    }.onFailure { err ->
                        Log.e(TAG, "finishChunkedDownload failed for session=$sessionId", err)
                        currentCallbacks?.onDownloadError?.invoke()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onDownloadFailed(reason: String?) {
            Log.e(TAG, "JS download reported error for appId=$appId: $reason")
            mainHandler.post {
                currentCallbacks?.onDownloadError?.invoke()
            }
        }

        @JavascriptInterface
        fun onFreezeSnapshotCaptured(jsonSnapshot: String?) {
            if (jsonSnapshot.isNullOrBlank()) return
            ioExecutor.execute {
                storage.saveDomFreezeSnapshot(appId, jsonSnapshot)
            }
        }

        @JavascriptInterface
        fun onViewportColorsDetected(topHex: String?, bottomHex: String?) {
            val topColor = parseHexColorSafely(topHex) ?: return
            val bottomColor = parseHexColorSafely(bottomHex) ?: topColor
            val previous = ramViewportColors[appId]
            if (previous?.topColor == topColor && previous.bottomColor == bottomColor) {
                return
            }
            val updated = ViewportBoundaryColors(topColor = topColor, bottomColor = bottomColor)
            ramViewportColors[appId] = updated
            mainHandler.post {
                ramWebViewPool[appId]?.setBackgroundColor(bottomColor)
                currentCallbacks?.onViewportColorsUpdated?.invoke(appId, topColor, bottomColor)
            }
        }
    }

    fun getCachedViewportColors(appId: Long?): ViewportBoundaryColors? {
        if (appId == null) return null
        return ramViewportColors[appId]
    }

    fun isAppFrozen(appId: Long): Boolean = frozenAppIds.contains(appId)

    fun isAppLiveInRam(appId: Long): Boolean = ramWebViewPool.containsKey(appId)

    fun updateAppMetadata(app: SavedWebAppEntity) {
        ramMetadataMap[app.id] = app
    }

    /**
     * Activates the target app's WebView in the foreground while saving & throttling the previous
     * WebView and enforcing the 20-minute inactivity freeze and 6 GB RAM ceiling.
     */
    fun activateWebView(app: SavedWebAppEntity, hostContext: Context = context): WebView {
        val now = System.currentTimeMillis()
        val prevId = currentActiveAppId
        currentActiveAppId = app.id
        lastAccessTimestamp[app.id] = now

        if (prevId != null && prevId != app.id) {
            lastAccessTimestamp[prevId] = now
            saveAppState(prevId)
            ramWebViewPool[prevId]?.let { previousWebView ->
                previousWebView.onPause()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    previousWebView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
                }
            }
        }

        val targetWebView = obtainWebView(app, hostContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetWebView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        targetWebView.onResume()
        targetWebView.resumeTimers()

        ramViewportColors[app.id]?.let { cached ->
            currentCallbacks?.onViewportColorsUpdated?.invoke(app.id, cached.topColor, cached.bottomColor)
        }
        targetWebView.evaluateJavascript(
            "window.__piDetectViewportColors && window.__piDetectViewportColors();",
            null
        )

        enforceRamBudgetAndInactivityFreeze(now)
        return targetWebView
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtainWebView(app: SavedWebAppEntity, hostContext: Context = context): WebView {
        ramMetadataMap[app.id] = app
        lastAccessTimestamp[app.id] = System.currentTimeMillis()
        frozenAppIds.remove(app.id)

        val existing = ramWebViewPool[app.id]
        if (existing != null) {
            val wrapper = existing.context as? MutableContextWrapper
            if (wrapper != null && wrapper.baseContext !== hostContext) {
                wrapper.baseContext = hostContext
            }
            return existing
        }

        val expectedHost = "app-${app.id}.webbox.local"
        val contextWrapper = MutableContextWrapper(hostContext)
        val webView = WebView(contextWrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val initialBg = ramViewportColors[app.id]?.bottomColor ?: Color.TRANSPARENT
            setBackgroundColor(initialBg)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            overScrollMode = View.OVER_SCROLL_NEVER

            // Native Chromium GPU Tile & WebGL2 Compositor (direct window surface rendering)
            setLayerType(View.LAYER_TYPE_NONE, null)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    offscreenPreRaster = false
                }
            }

            // Only register JavascriptBridge for local HTML apps to protect remote URLs
            if (app.isLocalHtml) {
                addJavascriptInterface(PiDownloadJsBridge(app.id, freezeStateStorage), "PiDownloadBridge")
            }

            if (!cookieManagerInitialized) {
                cookieManagerInitialized = true
                CookieManager.getInstance().setAcceptCookie(true)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            setDownloadListener(
                DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                    when {
                        url.startsWith("blob:", ignoreCase = true) -> {
                            val quotedUrl = JSONObject.quote(url)
                            val quotedMime = JSONObject.quote(mimetype ?: "")
                            evaluateJavascript(
                                "window.__piDownloadBlobUrl && window.__piDownloadBlobUrl($quotedUrl, '', $quotedMime);",
                                null
                            )
                        }
                        url.startsWith("data:", ignoreCase = true) -> {
                            ioExecutor.execute {
                                val ctx = currentCallbacks?.context ?: return@execute
                                val res = DownloadHelper.saveDataUrlToDownloads(
                                    context = ctx,
                                    dataUrlOrBase64 = url,
                                    suggestedFileName = null,
                                    explicitMimeType = mimetype
                                )
                                mainHandler.post {
                                    res.onSuccess { currentCallbacks?.onDownloadCompleted?.invoke(it) }
                                        .onFailure { currentCallbacks?.onDownloadError?.invoke() }
                                }
                            }
                        }
                        url.startsWith("http://", ignoreCase = true) ||
                            url.startsWith("https://", ignoreCase = true) -> {
                            ioExecutor.execute {
                                val ctx = currentCallbacks?.context ?: return@execute
                                val res = DownloadHelper.enqueueHttpDownload(
                                    context = ctx,
                                    url = url,
                                    userAgent = userAgent,
                                    contentDisposition = contentDisposition,
                                    mimeType = mimetype
                                )
                                mainHandler.post {
                                    res.onSuccess { currentCallbacks?.onDownloadCompleted?.invoke(it) }
                                        .onFailure { currentCallbacks?.onDownloadError?.invoke() }
                                }
                            }
                        }
                    }
                }
            )

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    if (!title.isNullOrBlank() && title != ramMetadataMap[app.id]?.title) {
                        currentCallbacks?.onTitleUpdated?.invoke(app.id, title)
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    val allowed = mutableListOf<String>()
                    for (res in request.resources) {
                        when (res) {
                            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> allowed.add(res)
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                val ctx = currentCallbacks?.context ?: context
                                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        ctx,
                                        android.Manifest.permission.RECORD_AUDIO
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    allowed.add(res)
                                }
                            }
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                val ctx = currentCallbacks?.context ?: context
                                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        ctx,
                                        android.Manifest.permission.CAMERA
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    allowed.add(res)
                                }
                            }
                        }
                    }
                    if (allowed.isNotEmpty()) {
                        request.grant(allowed.toTypedArray())
                    } else {
                        request.deny()
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    val launcher = activeFileChooserLauncher
                    return if (launcher != null) {
                        launcher.invoke(filePathCallback, fileChooserParams)
                    } else {
                        filePathCallback.onReceiveValue(null)
                        false
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url ?: return null
                    val host = reqUrl.host ?: return null
                    if (!host.equals(expectedHost, ignoreCase = true)) {
                        return null
                    }
                    val path = reqUrl.path ?: "/"
                    val latestApp = ramMetadataMap[app.id] ?: app

                    if (path == "/" || path == "/index.html") {
                        val provider = currentCallbacks?.htmlBytesProvider ?: htmlBytesProvider
                        val htmlBytes = provider(latestApp)
                        return WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            200,
                            "OK",
                            HTML_RESPONSE_HEADERS,
                            ByteArrayInputStream(htmlBytes)
                        )
                    }

                    // Try to resolve relative subresources (CSS, JS, images, JSON, fonts) from the local HTML directory
                    val localPath = latestApp.localFilePath
                    if (!localPath.isNullOrBlank()) {
                        try {
                            val baseDir = java.io.File(localPath).parentFile
                            if (baseDir != null && baseDir.exists()) {
                                val cleanRelPath = path.removePrefix("/").replace("..", "")
                                val assetFile = java.io.File(baseDir, cleanRelPath)
                                if (assetFile.exists() && assetFile.isFile && assetFile.canRead()) {
                                    val mime = resolveMimeTypeForPath(path)
                                    return WebResourceResponse(
                                        mime,
                                        "UTF-8",
                                        200,
                                        "OK",
                                        CORS_HEADERS,
                                        java.io.FileInputStream(assetFile)
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error resolving local asset for path=$path", e)
                        }
                    }

                    // Fallback responses with appropriate MIME types
                    val mime = resolveMimeTypeForPath(path)
                    val emptyPayload = if (mime == "application/json") EMPTY_JSON_BYTES else EMPTY_BYTES
                    return WebResourceResponse(
                        mime,
                        "UTF-8",
                        200,
                        "OK",
                        CORS_HEADERS,
                        ByteArrayInputStream(emptyPayload)
                    )
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    view?.evaluateJavascript(WebViewRuntimeBridgeScripts.RUNTIME_BOOTSTRAP_JS, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view == null) return
                    val currentApp = ramMetadataMap[app.id] ?: app
                    view.evaluateJavascript(WebViewRuntimeBridgeScripts.RUNTIME_BOOTSTRAP_JS, null)

                    // Restore any frozen DOM/Storage snapshot saved on disk
                    ioExecutor.execute {
                        val frozenJson = freezeStateStorage.loadDomFreezeSnapshot(app.id)
                        if (!frozenJson.isNullOrBlank()) {
                            val restoreScript = WebViewRuntimeBridgeScripts.buildRestoreFrozenStateEvalScript(frozenJson)
                            mainHandler.post {
                                view.evaluateJavascript(restoreScript, null)
                            }
                        }
                    }

                    view.evaluateJavascript(
                        "window.__piDetectViewportColors && window.__piDetectViewportColors();",
                        null
                    )

                    if (initialScrollRestored.add(app.id)) {
                        if (currentApp.scrollX > 0 || currentApp.scrollY > 0) {
                            view.postDelayed({
                                view.scrollTo(currentApp.scrollX, currentApp.scrollY)
                            }, 60L)
                        }
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    Log.w(TAG, "WebView renderer process gone for appId=${app.id}; recovering from frozen state")
                    ramWebViewPool.remove(app.id)
                    initialScrollRestored.remove(app.id)
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                    mainHandler.post {
                        currentCallbacks?.onRenderProcessGone?.invoke(app.id)
                    }
                    return true
                }
            }
        }

        webView.onResume()
        webView.resumeTimers()
        restoreOrLoadInitialContent(webView, app)
        ramWebViewPool[app.id] = webView
        return webView
    }

    private fun restoreOrLoadInitialContent(webView: WebView, app: SavedWebAppEntity) {
        val restoredBundle = freezeStateStorage.loadWebViewBundle(app.id, app.webViewStateBase64)
        if (restoredBundle != null) {
            val stateResult = webView.restoreState(restoredBundle)
            if (stateResult != null && !webView.url.isNullOrBlank()) {
                return
            }
        }

        if (app.isLocalHtml) {
            val virtualUrl = app.targetUrl.ifBlank {
                "https://app-${app.id}.webbox.local/index.html"
            }
            webView.loadUrl(virtualUrl)
            return
        }

        val urlToLoad = app.lastVisitedUrl?.takeIf { it.isNotBlank() } ?: app.targetUrl
        webView.loadUrl(urlToLoad)
    }

    /**
     * Captures and persists the complete state of an app (DOM inputs, storage, scroll, and WebView bundle)
     * to atomic disk storage and Room metadata without dropping large states.
     */
    fun saveAppState(appId: Long) {
        val webView = ramWebViewPool[appId] ?: return

        // Trigger synchronous JS bridge snapshot persistence (PiDownloadBridge.onFreezeSnapshotCaptured)
        webView.evaluateJavascript("window.__piSaveDomState && window.__piSaveDomState();", null)

        val currentUrl = webView.url
        val scrollX = webView.scrollX
        val scrollY = webView.scrollY
        val bundle = Bundle()
        webView.saveState(bundle)

        val base64Bundle = try {
            val parcel = android.os.Parcel.obtain()
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.recycle()
            if (bytes.isNotEmpty() && bytes.size < 500_000) {
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        ioExecutor.execute {
            freezeStateStorage.saveWebViewBundle(appId, bundle)
            currentCallbacks?.onStateCaptured?.invoke(appId, currentUrl, scrollX, scrollY, base64Bundle)
        }
    }

    /**
     * Freezes (hibernates) an inactive WebView to disk after 20 minutes of inactivity or when
     * the 6 GB RAM ceiling is reached, preserving 100% of its work for instant restoration on reopen.
     */
    fun freezeAndHibernateApp(appId: Long) {
        if (appId == currentActiveAppId) return
        val webView = ramWebViewPool[appId] ?: return

        saveAppState(appId)
        ramWebViewPool.remove(appId)?.let { target ->
            frozenAppIds.add(appId)
            initialScrollRestored.remove(appId)
            (target.context as? MutableContextWrapper)?.baseContext = context.applicationContext
            (target.parent as? ViewGroup)?.removeView(target)
            target.stopLoading()
            target.onPause()
            target.destroy()
        }
    }

    /**
     * Enforces:
     *  1. Automatic state freeze (hibernation) for any background app inactive for >= 20 minutes.
     *  2. Simultaneous RAM usage cap of 6 GB (freezing least-recently-used background apps first).
     */
    fun enforceRamBudgetAndInactivityFreeze(
        nowMillis: Long = System.currentTimeMillis(),
        forceRamCeilingEviction: Boolean = false
    ) {
        val activeId = currentActiveAppId

        // 1. Freeze any background app that has been inactive for >= 20 minutes
        for ((appId, lastUsedAt) in lastAccessTimestamp.entries) {
            if (appId != activeId && ramWebViewPool.containsKey(appId)) {
                val inactiveMs = nowMillis - lastUsedAt
                if (inactiveMs >= FreezeStateStorage.INACTIVE_HIBERNATION_TIMEOUT_MS) {
                    freezeAndHibernateApp(appId)
                }
            }
        }

        // 2. Enforce 6 GB RAM ceiling by freezing the oldest inactive WebView if needed
        if (forceRamCeilingEviction || freezeStateStorage.isRamBudgetExceeded(ramWebViewPool.size)) {
            val oldestInactive = lastAccessTimestamp.entries
                .filter { it.key != activeId && ramWebViewPool.containsKey(it.key) }
                .minByOrNull { it.value }
            oldestInactive?.key?.let { oldestId ->
                freezeAndHibernateApp(oldestId)
            }
        }
    }

    fun recordLastAccessForTesting(appId: Long, timestampMillis: Long) {
        lastAccessTimestamp[appId] = timestampMillis
    }

    fun saveAllStates() {
        val activeId = currentActiveAppId
        if (activeId != null && ramWebViewPool.containsKey(activeId)) {
            saveAppState(activeId)
        } else {
            ramWebViewPool.keys.firstOrNull()?.let { saveAppState(it) }
        }
        flushCookiesAsync()
    }

    fun onTrimMemory(level: Int) {
        val forceEvict = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        enforceRamBudgetAndInactivityFreeze(
            nowMillis = System.currentTimeMillis(),
            forceRamCeilingEviction = forceEvict
        )
    }

    private fun flushCookiesAsync() {
        ioExecutor.execute {
            try {
                CookieManager.getInstance().flush()
            } catch (e: Exception) {
                Log.w(TAG, "CookieManager.flush failed", e)
            }
        }
    }

    fun canGoBack(appId: Long?): Boolean {
        if (appId == null) return false
        return ramWebViewPool[appId]?.canGoBack() == true
    }

    fun goBack(appId: Long?): Boolean {
        if (appId == null) return false
        val webView = ramWebViewPool[appId] ?: return false
        return if (webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    fun detachActivityContext() {
        ramWebViewPool.values.forEach { webView ->
            (webView.context as? MutableContextWrapper)?.baseContext = context.applicationContext
        }
    }

    fun removeAndDestroyWebView(appId: Long) {
        if (currentActiveAppId == appId) {
            currentActiveAppId = null
        }
        initialScrollRestored.remove(appId)
        ramMetadataMap.remove(appId)
        ramViewportColors.remove(appId)
        lastAccessTimestamp.remove(appId)
        frozenAppIds.remove(appId)
        ioExecutor.execute {
            freezeStateStorage.deleteFrozenState(appId)
        }
        ramWebViewPool.remove(appId)?.let { webView ->
            (webView.context as? MutableContextWrapper)?.baseContext = context.applicationContext
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.clearHistory()
            webView.destroy()
        }
    }

    companion object {
        private const val TAG = "WebViewPoolManager"
        private const val MAINTENANCE_INTERVAL_MS = 60_000L

        private val mainHandler = Handler(Looper.getMainLooper())
        private val isMaintenanceScheduled = AtomicBoolean(false)

        @Volatile
        private var currentCallbacks: PoolCallbacksHolder? = null

        @Volatile
        private var activeFileChooserLauncher: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean)? = null

        @Volatile
        private var currentActiveAppId: Long? = null

        @Volatile
        private var cookieManagerInitialized: Boolean = false

        private val ramWebViewPool = ConcurrentHashMap<Long, WebView>()
        private val ramMetadataMap = ConcurrentHashMap<Long, SavedWebAppEntity>()
        private val ramViewportColors = ConcurrentHashMap<Long, ViewportBoundaryColors>()
        private val lastAccessTimestamp = ConcurrentHashMap<Long, Long>()
        private val frozenAppIds = ConcurrentHashMap.newKeySet<Long>()
        private val initialScrollRestored = ConcurrentHashMap.newKeySet<Long>()
        private val ioExecutor = Executors.newSingleThreadExecutor()

        private val EMPTY_BYTES = ByteArray(0)
        private val EMPTY_JSON_BYTES = "{}".toByteArray(Charsets.UTF_8)
        private val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*")
        private val HTML_RESPONSE_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store"
        )

        private val maintenanceRunnable = object : Runnable {
            override fun run() {
                val activeId = currentActiveAppId
                val nowMillis = System.currentTimeMillis()
                for ((appId, lastUsedAt) in lastAccessTimestamp.entries) {
                    if (appId != activeId && ramWebViewPool.containsKey(appId)) {
                        val inactiveMs = nowMillis - lastUsedAt
                        if (inactiveMs >= FreezeStateStorage.INACTIVE_HIBERNATION_TIMEOUT_MS) {
                            val webView = ramWebViewPool[appId]
                            if (webView != null) {
                                // Trigger state save and clean destruction
                                val currentUrl = webView.url
                                val scrollX = webView.scrollX
                                val scrollY = webView.scrollY
                                val bundle = Bundle()
                                webView.saveState(bundle)
                                ioExecutor.execute {
                                    currentCallbacks?.context?.let { ctx ->
                                        FreezeStateStorage(ctx).saveWebViewBundle(appId, bundle)
                                    }
                                    currentCallbacks?.onStateCaptured?.invoke(appId, currentUrl, scrollX, scrollY, null)
                                }
                                ramWebViewPool.remove(appId)?.let { target ->
                                    frozenAppIds.add(appId)
                                    initialScrollRestored.remove(appId)
                                    (target.context as? MutableContextWrapper)?.baseContext =
                                        currentCallbacks?.context ?: target.context
                                    (target.parent as? ViewGroup)?.removeView(target)
                                    target.stopLoading()
                                    target.onPause()
                                    target.destroy()
                                }
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, MAINTENANCE_INTERVAL_MS)
            }
        }

        private fun parseHexColorSafely(hex: String?): Int? {
            if (hex.isNullOrBlank()) return null
            return try {
                Color.parseColor(hex.trim())
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun resolveMimeTypeForPath(path: String): String {
            val lower = path.lowercase()
            return when {
                lower.endsWith(".css") -> "text/css"
                lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
                lower.endsWith(".json") || lower.endsWith(".webmanifest") -> "application/json"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".ico") -> "image/x-icon"
                lower.endsWith(".woff2") -> "font/woff2"
                lower.endsWith(".woff") -> "font/woff"
                lower.endsWith(".ttf") -> "font/ttf"
                lower.endsWith(".otf") -> "font/otf"
                lower.endsWith(".mp3") -> "audio/mpeg"
                lower.endsWith(".wav") -> "audio/wav"
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".webm") -> "video/webm"
                lower.endsWith(".txt") -> "text/plain"
                lower.endsWith(".xml") -> "application/xml"
                else -> "application/octet-stream"
            }
        }
    }
}
