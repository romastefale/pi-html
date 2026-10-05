package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.WebAppRepository
import com.example.webview.DownloadHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        assertEquals("pi.html", appName)
    }

    @Test
    fun `extractHtmlTitle parses title tag and falls back to file name`() {
        val htmlWithTitle = "<!DOCTYPE html><html><head><title>AI-pi</title></head><body></body></html>"
        assertEquals("AI-pi", WebAppRepository.extractHtmlTitle(htmlWithTitle, "index.html"))

        val htmlWithoutTitle = "<!DOCTYPE html><html><body><h1>Ola</h1></body></html>"
        assertEquals("meu app offline", WebAppRepository.extractHtmlTitle(htmlWithoutTitle, "meu_app_offline.html"))
    }

    @Test
    fun `normalizeUrl adds https or http appropriately`() {
        assertEquals("https://example.com", WebAppRepository.normalizeUrl("example.com"))
        assertEquals("http://localhost:8080", WebAppRepository.normalizeUrl("localhost:8080"))
        assertEquals("https://romastefale.github.io/AI-pi/", WebAppRepository.normalizeUrl("https://romastefale.github.io/AI-pi/"))
    }

    @Test
    fun `downloadHelper parses data URLs and sanitizes filenames`() {
        val parsed = DownloadHelper.parseDataUrl("data:text/plain;base64,SGVsbG8gUGk=", "text/plain")
        assertEquals("text/plain", parsed.mimeType)
        assertEquals("Hello Pi", String(parsed.bytes, Charsets.UTF_8))
        assertEquals("conversa.txt", DownloadHelper.sanitizeFileName("conversa.txt", "text/plain"))
        assertEquals("relatorio.txt", DownloadHelper.sanitizeFileName("relatorio", "text/plain"))
    }

    @Test
    fun `launcher UI displays bottom bar, saves HTML copy and switches apps via sidebar`() {
        composeTestRule.waitForIdle()

        // Verify minimalist welcome view and bottom floating pill bar are displayed
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

        // Import a static HTML application and verify local file copy is saved
        val sampleHtml = "<!DOCTYPE html><html><head><title>Meu App HTML</title></head><body><h1>Teste</h1></body></html>"
        composeTestRule.activity.viewModel.importRawHtmlForTestingOrIntent(sampleHtml, "app.html")
        composeTestRule.waitForIdle()

        // Open sidebar via top-left menu button and verify both saved applications appear
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

        // Toggle theme in sidebar and return to launcher home via '+ AI-pi' button
        composeTestRule.onNodeWithTag("theme_toggle_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.activity.viewModel.uiState.value.isDarkTheme)

        composeTestRule.onNodeWithTag("new_app_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("welcome_view").assertIsDisplayed()
    }
}
