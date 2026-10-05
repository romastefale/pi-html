package com.example.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.local.SavedWebAppEntity
import java.io.ByteArrayInputStream

class WebViewPoolManager(
    private val context: Context,
    private val htmlContentProvider: (SavedWebAppEntity) -> String,
    private val onTitleUpdated: (Long, String) -> Unit,
    private val onStateCaptured: (Long, String?, Int, Int, String?) -> Unit,
    private val onDownloadCompleted: (String) -> Unit = {},
    private val onDownloadError: () -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewPool = mutableMapOf<Long, WebView>()
    private val appMetadataMap = mutableMapOf<Long, SavedWebAppEntity>()
    private val initialScrollRestored = mutableSetOf<Long>()

    var fileChooserLauncher: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean)? = null

    private inner class PiDownloadJsBridge {
        @JavascriptInterface
        fun saveDataUrlToDownloads(dataUrl: String, suggestedFileName: String?, mimeType: String?) {
            val result = DownloadHelper.saveDataUrlToDownloads(
                context = context,
                dataUrlOrBase64 = dataUrl,
                suggestedFileName = suggestedFileName,
                explicitMimeType = mimeType
            )
            mainHandler.post {
                result.onSuccess { savedName ->
                    onDownloadCompleted(savedName)
                }.onFailure {
                    onDownloadError()
                }
            }
        }
    }

    fun updateAppMetadata(app: SavedWebAppEntity) {
        appMetadataMap[app.id] = app
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtainWebView(app: SavedWebAppEntity, hostContext: Context = context): WebView {
        appMetadataMap[app.id] = app
        val existing = webViewPool[app.id]
        if (existing != null) {
            (existing.context as? MutableContextWrapper)?.baseContext = hostContext
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        val contextWrapper = MutableContextWrapper(hostContext)
        val webView = WebView(contextWrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(true)
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = true
            }

            addJavascriptInterface(PiDownloadJsBridge(), "PiDownloadBridge")

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            setDownloadListener(
                DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                    when {
                        url.startsWith("blob:", ignoreCase = true) -> {
                            val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
                            val escapedMime = (mimetype ?: "").replace("\\", "\\\\").replace("'", "\\'")
                            evaluateJavascript(
                                "window.__piDownloadBlobUrl && window.__piDownloadBlobUrl('$escapedUrl', '', '$escapedMime');",
                                null
                            )
                        }
                        url.startsWith("data:", ignoreCase = true) -> {
                            DownloadHelper.saveDataUrlToDownloads(
                                context = context,
                                dataUrlOrBase64 = url,
                                suggestedFileName = null,
                                explicitMimeType = mimetype
                            ).onSuccess { savedName ->
                                onDownloadCompleted(savedName)
                            }.onFailure {
                                onDownloadError()
                            }
                        }
                        url.startsWith("http://", ignoreCase = true) ||
                            url.startsWith("https://", ignoreCase = true) -> {
                            DownloadHelper.enqueueHttpDownload(
                                context = context,
                                url = url,
                                userAgent = userAgent,
                                contentDisposition = contentDisposition,
                                mimeType = mimetype
                            ).onSuccess { savedName ->
                                onDownloadCompleted(savedName)
                            }.onFailure {
                                onDownloadError()
                            }
                        }
                    }
                }
            )

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    if (!title.isNullOrBlank()) {
                        onTitleUpdated(app.id, title)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    val launcher = fileChooserLauncher
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
                    val expectedHost = "app-${app.id}.webbox.local"
                    if (reqUrl.host.equals(expectedHost, ignoreCase = true)) {
                        val path = reqUrl.path ?: "/"
                        if (path == "/" || path == "/index.html") {
                            val latestApp = appMetadataMap[app.id] ?: app
                            val html = htmlContentProvider(latestApp)
                            val headers = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Cache-Control" to "no-store"
                            )
                            return WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                200,
                                "OK",
                                headers,
                                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                            )
                        } else if (path.endsWith(".js")) {
                            return WebResourceResponse(
                                "application/javascript",
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        } else if (path.endsWith(".webmanifest") || path.endsWith(".json")) {
                            return WebResourceResponse(
                                "application/json",
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8))
                            )
                        } else if (path.endsWith(".ico") || path.endsWith(".png")) {
                            return WebResourceResponse(
                                "image/png",
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view == null) return
                    val currentApp = appMetadataMap[app.id] ?: app
                    view.evaluateJavascript(AUTO_RESUME_AND_DOWNLOAD_JS, null)
                    if (initialScrollRestored.add(app.id)) {
                        if (currentApp.scrollX > 0 || currentApp.scrollY > 0) {
                            view.postDelayed({
                                view.scrollTo(currentApp.scrollX, currentApp.scrollY)
                            }, 180L)
                        }
                    }
                    CookieManager.getInstance().flush()
                }
            }
        }

        restoreOrLoadInitialContent(webView, app)
        webViewPool[app.id] = webView
        return webView
    }

    private fun restoreOrLoadInitialContent(webView: WebView, app: SavedWebAppEntity) {
        if (app.isLocalHtml) {
            val virtualUrl = app.targetUrl.ifBlank {
                "https://app-${app.id}.webbox.local/index.html"
            }
            webView.loadUrl(virtualUrl)
            return
        }

        val restoredBundle = deserializeBundle(app.webViewStateBase64)
        if (restoredBundle != null) {
            val stateResult = webView.restoreState(restoredBundle)
            if (stateResult != null && !webView.url.isNullOrBlank()) {
                return
            }
        }

        val urlToLoad = app.lastVisitedUrl?.takeIf { it.isNotBlank() } ?: app.targetUrl
        webView.loadUrl(urlToLoad)
    }

    fun saveAppState(appId: Long) {
        val webView = webViewPool[appId] ?: return
        webView.evaluateJavascript("window.__piSaveDomState && window.__piSaveDomState();", null)
        CookieManager.getInstance().flush()

        val currentUrl = webView.url
        val scrollX = webView.scrollX
        val scrollY = webView.scrollY
        val app = appMetadataMap[appId]
        val serializedState = if (app?.isLocalHtml == true) {
            null
        } else {
            val bundle = Bundle()
            webView.saveState(bundle)
            serializeBundleSafely(bundle)
        }

        onStateCaptured(appId, currentUrl, scrollX, scrollY, serializedState)
    }

    fun saveAllStates() {
        webViewPool.keys.forEach { id ->
            saveAppState(id)
        }
    }

    fun canGoBack(appId: Long?): Boolean {
        if (appId == null) return false
        return webViewPool[appId]?.canGoBack() == true
    }

    fun goBack(appId: Long?): Boolean {
        if (appId == null) return false
        val webView = webViewPool[appId] ?: return false
        return if (webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    fun removeAndDestroyWebView(appId: Long) {
        initialScrollRestored.remove(appId)
        appMetadataMap.remove(appId)
        webViewPool.remove(appId)?.let { webView ->
            (webView.context as? MutableContextWrapper)?.baseContext = context.applicationContext
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.destroy()
        }
    }

    fun destroyAll() {
        saveAllStates()
        val ids = webViewPool.keys.toList()
        ids.forEach { removeAndDestroyWebView(it) }
    }

    private fun serializeBundleSafely(bundle: Bundle): String? {
        return runCatching {
            val parcel = Parcel.obtain()
            try {
                bundle.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                if (bytes.size > 180_000) {
                    null
                } else {
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            } finally {
                parcel.recycle()
            }
        }.getOrNull()
    }

    private fun deserializeBundle(base64: String?): Bundle? {
        if (base64.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                Bundle.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }.getOrNull()
    }

    companion object {
        // Lightweight DOM & Scroll auto-resume + Blob/Data attachment download interceptor
        private val AUTO_RESUME_AND_DOWNLOAD_JS = """
            (function() {
                if (window.__piAutoResumeInitialized) return;
                window.__piAutoResumeInitialized = true;

                // 1. Blob & Data URL download support to device Downloads folder
                window.__piDownloadBlobUrl = function(blobUrl, fileName, fallbackMime) {
                    if (!window.PiDownloadBridge) return;
                    fetch(blobUrl)
                        .then(function(res) { return res.blob(); })
                        .then(function(blob) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (typeof reader.result === "string") {
                                    window.PiDownloadBridge.saveDataUrlToDownloads(
                                        reader.result,
                                        fileName || "",
                                        blob.type || fallbackMime || ""
                                    );
                                }
                            };
                            reader.readAsDataURL(blob);
                        })
                        .catch(function() {});
                };

                function interceptAnchorDownload(anchor) {
                    if (!anchor || !window.PiDownloadBridge) return false;
                    var href = anchor.href || "";
                    var downloadAttr = anchor.getAttribute("download");
                    var hasDownload = downloadAttr !== null;
                    var suggestedName = downloadAttr || "";

                    if (href.indexOf("blob:") === 0) {
                        window.__piDownloadBlobUrl(href, suggestedName, "");
                        return true;
                    }
                    if (href.indexOf("data:") === 0 && hasDownload) {
                        window.PiDownloadBridge.saveDataUrlToDownloads(href, suggestedName, "");
                        return true;
                    }
                    return false;
                }

                var origClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    if (interceptAnchorDownload(this)) {
                        return;
                    }
                    return origClick.apply(this, arguments);
                };

                document.addEventListener("click", function(e) {
                    var anchor = e.target && e.target.closest ? e.target.closest("a") : null;
                    if (anchor && interceptAnchorDownload(anchor)) {
                        e.preventDefault();
                        e.stopPropagation();
                    }
                }, true);

                // 2. DOM & Scroll state persistence per app origin
                var KEY = "__pi_web_runner_dom_state_v1__";
                function getSelector(el, idx) {
                    if (el.id) return "#" + el.id;
                    if (el.name) return el.tagName + "[name='" + el.name + "']:" + idx;
                    return el.tagName + ":" + idx;
                }
                window.__piSaveDomState = function() {
                    try {
                        var fields = {};
                        var inputs = document.querySelectorAll("input, textarea, select");
                        for (var i = 0; i < inputs.length; i++) {
                            var el = inputs[i];
                            if (el.type === "file" || el.type === "password" || el.type === "hidden") continue;
                            var k = getSelector(el, i);
                            if (el.type === "checkbox" || el.type === "radio") {
                                fields[k] = { c: el.checked };
                            } else {
                                fields[k] = { v: el.value };
                            }
                        }
                        var state = {
                            sx: window.scrollX || 0,
                            sy: window.scrollY || 0,
                            f: fields
                        };
                        localStorage.setItem(KEY, JSON.stringify(state));
                    } catch (e) {}
                };
                function restoreState() {
                    try {
                        var raw = localStorage.getItem(KEY);
                        if (!raw) return;
                        var state = JSON.parse(raw);
                        if (state && state.f) {
                            var inputs = document.querySelectorAll("input, textarea, select");
                            for (var i = 0; i < inputs.length; i++) {
                                var el = inputs[i];
                                if (el.type === "file" || el.type === "password" || el.type === "hidden") continue;
                                var k = getSelector(el, i);
                                var entry = state.f[k];
                                if (entry) {
                                    if (typeof entry.c === "boolean") {
                                        el.checked = entry.c;
                                    } else if (typeof entry.v === "string" && !el.value) {
                                        el.value = entry.v;
                                        el.dispatchEvent(new Event("input", { bubbles: true }));
                                    }
                                }
                            }
                        }
                        if (state && (state.sx || state.sy)) {
                            window.scrollTo(state.sx || 0, state.sy || 0);
                        }
                    } catch (e) {}
                }
                restoreState();
                var timer = null;
                function scheduleSave() {
                    clearTimeout(timer);
                    timer = setTimeout(window.__piSaveDomState, 250);
                }
                document.addEventListener("input", scheduleSave, true);
                document.addEventListener("change", scheduleSave, true);
                window.addEventListener("scroll", scheduleSave, { passive: true });
                document.addEventListener("visibilitychange", window.__piSaveDomState);
            })();
        """.trimIndent()
    }
}
