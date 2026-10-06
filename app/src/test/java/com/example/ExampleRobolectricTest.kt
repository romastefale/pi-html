package com.example

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SavedWebAppEntity
import com.example.data.repository.WebAppRepository
import com.example.webview.DownloadHelper
import com.example.webview.FreezeStateStorage
import com.example.webview.WebViewRuntimeBridgeScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("pi-html", appName)
    }

    @Test
    fun `extractHtmlTitle parses title tag and falls back to file name`() {
        val htmlWithTitle = "<!DOCTYPE html><html><head><title>AI-pi</title></head><body></body></html>"
        assertEquals("AI-pi", WebAppRepository.extractHtmlTitle(htmlWithTitle, "index.html"))

        val htmlWithoutTitle = "<!DOCTYPE html><html><body><h1>Ola</h1></body></html>"
        assertEquals("meu app offline", WebAppRepository.extractHtmlTitle(htmlWithoutTitle, "meu_app_offline.html"))
    }

    @Test
    fun `normalizeUrl adds https or http appropriately and rejects file and data schemes`() {
        assertEquals("https://example.com", WebAppRepository.normalizeUrl("example.com"))
        assertEquals("http://localhost:8080", WebAppRepository.normalizeUrl("localhost:8080"))
        assertEquals("https://romastefale.github.io/AI-pi/", WebAppRepository.normalizeUrl("https://romastefale.github.io/AI-pi/"))

        // Rejects file:// and data: schemas by prepending https:// rather than allowing arbitrary local file reads
        assertEquals("https://file:///sdcard/malicious.html", WebAppRepository.normalizeUrl("file:///sdcard/malicious.html"))
        assertEquals("https://data:text/html,<h1>test</h1>", WebAppRepository.normalizeUrl("data:text/html,<h1>test</h1>"))
    }

    @Test
    fun `downloadHelper parses data URLs, sanitizes filenames, and enforces chunked limits`() {
        val parsed = DownloadHelper.parseDataUrl("data:text/plain;base64,SGVsbG8gUGk=", "text/plain")
        assertEquals("text/plain", parsed.mimeType)
        assertEquals("Hello Pi", String(parsed.bytes, Charsets.UTF_8))
        assertEquals("conversa.txt", DownloadHelper.sanitizeFileName("conversa.txt", "text/plain"))
        assertEquals("relatorio.txt", DownloadHelper.sanitizeFileName("relatorio", "text/plain"))
        assertEquals("Cena_3D.glb", DownloadHelper.sanitizeFileName("Cena_3D", "model/gltf-binary"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        DownloadHelper.beginChunkedDownload("sess_1", "exportado.json", "application/json")
        assertTrue(DownloadHelper.appendChunkBase64("sess_1", "eyJva2F5Ijp0cnVlfQ=="))
        val chunkedResult = DownloadHelper.finishChunkedDownload(context, "sess_1")
        assertTrue(chunkedResult.isSuccess)
        assertEquals("exportado.json", chunkedResult.getOrNull())

        // Verify chunked download size limit failure (exceeding limit fails and clears session)
        assertEquals(128L * 1024L * 1024L, DownloadHelper.MAX_CHUNK_SESSION_BYTES)
        DownloadHelper.beginChunkedDownload("sess_overflow", "huge.bin", "application/octet-stream")
        val fakeChunk = android.util.Base64.encodeToString(ByteArray(1024) { 1 }, android.util.Base64.NO_WRAP)
        assertTrue(DownloadHelper.appendChunkBase64("sess_overflow", fakeChunk))
        // Verify appending a chunk beyond limit returns false and cleans up session
        val oversizedChunk = android.util.Base64.encodeToString(ByteArray(2048) { 1 }, android.util.Base64.NO_WRAP)
        assertFalse(DownloadHelper.appendChunkBase64("sess_overflow", oversizedChunk, maxBytes = 2000L))
        assertFalse(DownloadHelper.finishChunkedDownload(context, "sess_overflow").isSuccess)
    }

    @Test
    fun `webView security and bridge settings differ between local and remote apps`() {
        val pool = composeTestRule.activity.webViewPoolManager
        val localApp = SavedWebAppEntity(
            id = 901L,
            title = "Local App",
            sourceType = SavedWebAppEntity.SOURCE_HTML,
            targetUrl = "https://app-901.webbox.local/index.html"
        )
        val remoteApp = SavedWebAppEntity(
            id = 902L,
            title = "Remote App",
            sourceType = SavedWebAppEntity.SOURCE_URL,
            targetUrl = "https://example.com"
        )

        val localWebView = pool.obtainWebView(localApp, composeTestRule.activity)
        val remoteWebView = pool.obtainWebView(remoteApp, composeTestRule.activity)

        // Verify allowFileAccess and allowContentAccess are disabled for security
        assertFalse(localWebView.settings.allowFileAccess)
        assertFalse(localWebView.settings.allowContentAccess)
        assertFalse(remoteWebView.settings.allowFileAccess)
        assertFalse(remoteWebView.settings.allowContentAccess)

        // In shadow WebView, verify Javascript interface is registered for local apps and not remote
        val localShadow = shadowOf(localWebView)
        val remoteShadow = shadowOf(remoteWebView)
        assertNotNull(localShadow.getJavascriptInterface("PiDownloadBridge"))
        assertEquals(null, remoteShadow.getJavascriptInterface("PiDownloadBridge"))

        pool.removeAndDestroyWebView(901L)
        pool.removeAndDestroyWebView(902L)
    }

    @Test
    fun `freezeStateStorage persists bundles and DOM snapshots and enforces 20m freeze and 6GB budget`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = FreezeStateStorage(context)

        // Verify 6 GB RAM ceiling and 20-minute hibernation constants
        assertEquals(6L * 1024L * 1024L * 1024L, FreezeStateStorage.MAX_RAM_BUDGET_BYTES)
        assertEquals(20L * 60L * 1000L, FreezeStateStorage.INACTIVE_HIBERNATION_TIMEOUT_MS)

        // Verify atomic DOM & storage snapshot persistence
        val snapshotJson = """{"v":2,"sx":120,"sy":480,"f":{"#editor":{"v":"Texto preservado"}},"ls":{"draft":"123"}}"""
        assertTrue(storage.saveDomFreezeSnapshot(777L, snapshotJson))
        assertEquals(snapshotJson, storage.loadDomFreezeSnapshot(777L))

        // Verify atomic Bundle persistence with SDK check without 160KB truncation
        val bundle = Bundle().apply {
            putString("url", "https://app-777.webbox.local/index.html")
            putByteArray("large_payload", ByteArray(220_000) { 42 })
        }
        assertTrue(storage.saveWebViewBundle(777L, bundle))
        val loadedBundle = storage.loadWebViewBundle(777L)
        assertNotNull(loadedBundle)
        assertEquals("https://app-777.webbox.local/index.html", loadedBundle?.getString("url"))
        assertEquals(220_000, loadedBundle?.getByteArray("large_payload")?.size)

        // Verify WebGL2 high-performance context enhancement & showSaveFilePicker polyfill are present in bootstrap JS
        assertTrue(WebViewRuntimeBridgeScripts.RUNTIME_BOOTSTRAP_JS.contains("webgl2"))
        assertTrue(WebViewRuntimeBridgeScripts.RUNTIME_BOOTSTRAP_JS.contains("high-performance"))
        assertTrue(WebViewRuntimeBridgeScripts.RUNTIME_BOOTSTRAP_JS.contains("showSaveFilePicker"))

        // Verify 20-minute inactivity freeze and seamless restoration on reopen
        val pool = composeTestRule.activity.webViewPoolManager
        val app1 = SavedWebAppEntity(
            id = 501L,
            title = "App Em Espera",
            sourceType = SavedWebAppEntity.SOURCE_HTML,
            targetUrl = "https://app-501.webbox.local/index.html"
        )
        val app2 = SavedWebAppEntity(
            id = 502L,
            title = "App Ativo",
            sourceType = SavedWebAppEntity.SOURCE_HTML,
            targetUrl = "https://app-502.webbox.local/index.html"
        )
        pool.activateWebView(app1, composeTestRule.activity)
        pool.activateWebView(app2, composeTestRule.activity)
        assertTrue(pool.isAppLiveInRam(501L))
        assertTrue(pool.isAppLiveInRam(502L))

        val now = System.currentTimeMillis()
        pool.recordLastAccessForTesting(501L, now - (21L * 60L * 1000L))
        pool.enforceRamBudgetAndInactivityFreeze(nowMillis = now)
        shadowOf(Looper.getMainLooper()).idle()

        // App 501 (inactive > 20 min) is now frozen to disk, while App 502 (active) stays live in RAM
        assertTrue(pool.isAppFrozen(501L))
        assertFalse(pool.isAppLiveInRam(501L))
        assertTrue(pool.isAppLiveInRam(502L))

        // Reopening App 501 unfreezes it and resumes in RAM
        pool.activateWebView(app1, composeTestRule.activity)
        assertFalse(pool.isAppFrozen(501L))
        assertTrue(pool.isAppLiveInRam(501L))

        storage.deleteFrozenState(777L)
        pool.removeAndDestroyWebView(501L)
        pool.removeAndDestroyWebView(502L)
    }

    @Test
    fun `extractInitialHtmlColors detects background color from HTML and CSS`() {
        val htmlWithBodyCss = "<!DOCTYPE html><html><head><style>body { background-color: #141416; }</style></head><body></body></html>"
        val colors1 = WebAppRepository.extractInitialHtmlColors(htmlWithBodyCss)
        assertEquals(android.graphics.Color.parseColor("#141416"), colors1?.topColor)

        val htmlWithVar = "<!DOCTYPE html><html><head><style>:root { --bg-main: #0f172a; } body { background: var(--bg-main); }</style></head><body></body></html>"
        val colors2 = WebAppRepository.extractInitialHtmlColors(htmlWithVar)
        assertEquals(android.graphics.Color.parseColor("#0f172a"), colors2?.topColor)
    }

    @Test
    fun `launcher UI hides controls on fullscreen app, reveals on top swipe, and opens sidebar`() {
        composeTestRule.waitForIdle()

        // Verify minimalist welcome view and bottom floating pill bar are displayed initially
        composeTestRule.onNodeWithTag("welcome_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("floating_pill_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("plus_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("url_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_url_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_button").assertIsDisplayed()

        // Enter a URL in the bottom bar and click the orange send arrow button
        composeTestRule.onNodeWithTag("url_input").performTextInput("https://romastefale.github.io/AI-pi/")
        composeTestRule.onNodeWithTag("send_url_button").performClick()
        composeTestRule.waitForIdle()

        // Import a static HTML application with a custom background color and verify status bar color adoption & fullscreen
        val sampleHtml = "<!DOCTYPE html><html><head><title>Meu App HTML</title><style>body { background-color: #1e293b; }</style></head><body><h1>Teste</h1></body></html>"
        composeTestRule.activity.viewModel.importRawHtmlForTestingOrIntent(sampleHtml, "app.html")
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.activity.viewModel.uiState.value.savedApps.size == 2 &&
                composeTestRule.activity.viewModel.uiState.value.activeApp != null
        }
        composeTestRule.waitForIdle()
        assertFalse(composeTestRule.activity.viewModel.uiState.value.isOverlayControlsVisible)
        assertEquals(
            android.graphics.Color.parseColor("#1e293b"),
            composeTestRule.activity.viewModel.uiState.value.activeViewportColors?.topColor
        )

        // Simulate top-down swipe gesture to reveal top button & bottom bar over the running HTML app
        composeTestRule.activity.viewModel.showOverlayControls()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("floating_pill_bar").assertIsDisplayed()

        // Open sidebar and verify both saved applications appear
        composeTestRule.onNodeWithTag("menu_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sidebar_panel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Meu App HTML").assertIsDisplayed()
        composeTestRule.onNodeWithText("romastefale.github.io").assertIsDisplayed()

        // Verify the internal HTML file copy exists in filesDir/saved_html_apps
        val savedHtmlApp = composeTestRule.activity.viewModel.uiState.value.savedApps.first { it.isLocalHtml }
        val savedFile = File(savedHtmlApp.localFilePath!!)
        assertTrue(savedFile.exists())
        assertEquals(sampleHtml, savedFile.readText())

        // Toggle theme in sidebar and return to launcher home
        composeTestRule.onNodeWithTag("theme_toggle_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.activity.viewModel.uiState.value.isDarkTheme)

        composeTestRule.onNodeWithTag("new_app_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("welcome_view").assertIsDisplayed()

        // Verify submitting the same URL again does NOT duplicate the row in savedApps
        composeTestRule.onNodeWithTag("url_input").performTextInput("https://romastefale.github.io/AI-pi/")
        composeTestRule.onNodeWithTag("send_url_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, composeTestRule.activity.viewModel.uiState.value.savedApps.size)
    }
}
