package com.example.data.repository

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.local.SavedWebAppDao
import com.example.data.local.SavedWebAppEntity
import com.example.webview.FreezeStateStorage
import com.example.webview.ViewportBoundaryColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class WebAppRepository(
    private val context: Context,
    private val dao: SavedWebAppDao
) {
    private val prefs = context.getSharedPreferences("pi_web_runner_prefs", Context.MODE_PRIVATE)
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val freezeStateStorage = FreezeStateStorage(context.applicationContext)

    val allApps: Flow<List<SavedWebAppEntity>> = dao.getAllApps().distinctUntilChanged()

    init {
        // Pre-warm HTML bytes into RAM asynchronously so local HTML apps open in 0ms
        preloadScope.launch {
            try {
                val apps = dao.getAllAppsSnapshot()
                for (app in apps) {
                    lastKnownTitles[app.id] = app.title
                    if (app.isLocalHtml && !ramHtmlBytesCache.containsKey(app.id)) {
                        readSavedHtmlBytes(app)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-warm HTML apps into RAM", e)
            }
        }
    }

    fun isDarkThemeSaved(): Boolean {
        return prefs.getBoolean(KEY_DARK_THEME, false)
    }

    fun setDarkThemeSaved(isDark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, isDark).apply()
    }

    fun getLastActiveAppId(): Long? {
        val id = prefs.getLong(KEY_LAST_ACTIVE_APP_ID, -1L)
        return if (id > 0L) id else null
    }

    fun setLastActiveAppId(id: Long?) {
        prefs.edit().putLong(KEY_LAST_ACTIVE_APP_ID, id ?: -1L).apply()
    }

    suspend fun getAppById(id: Long): SavedWebAppEntity? {
        return dao.getAppById(id)
    }

    suspend fun importHtmlFromUri(uri: Uri): Result<SavedWebAppEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val htmlBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: throw IllegalArgumentException("Empty HTML stream")

            if (htmlBytes.isEmpty()) {
                throw IllegalArgumentException("Blank HTML file")
            }

            // Decode only the head slice (first 8KB) to extract title without allocating a huge String for multi-MB HTML files
            val headBytesLength = minOf(htmlBytes.size, 8192)
            val headString = String(htmlBytes, 0, headBytesLength, Charsets.UTF_8)
            if (htmlBytes.size < 64 && headString.isBlank()) {
                throw IllegalArgumentException("Blank HTML file")
            }

            val fileName = resolveFileName(uri)
            val extractedTitle = extractHtmlTitle(headString, fileName)
            val now = System.currentTimeMillis()

            val initialEntity = SavedWebAppEntity(
                title = extractedTitle,
                sourceType = SavedWebAppEntity.SOURCE_HTML,
                targetUrl = "",
                localFilePath = null,
                htmlContent = null,
                lastVisitedUrl = null,
                createdAt = now,
                lastOpenedAt = now
            )

            val insertedId = dao.insertApp(initialEntity)
            val storageDir = File(context.filesDir, "saved_html_apps").apply { mkdirs() }
            val localFile = File(storageDir, "app_${insertedId}.html")
            BufferedOutputStream(FileOutputStream(localFile), 64 * 1024).use { out ->
                out.write(htmlBytes)
                out.flush()
            }

            // Cache HTML bytes directly in RAM for 0ms instant serving
            ramHtmlBytesCache[insertedId] = htmlBytes
            lastKnownTitles[insertedId] = extractedTitle
            extractInitialHtmlColors(headString)?.let { initialHtmlColorsCache[insertedId] = it }

            val virtualOriginUrl = buildVirtualHtmlUrl(insertedId)
            val updatedEntity = initialEntity.copy(
                id = insertedId,
                targetUrl = virtualOriginUrl,
                localFilePath = localFile.absolutePath,
                lastVisitedUrl = virtualOriginUrl
            )
            dao.updateApp(updatedEntity)
            updatedEntity
        }
    }

    suspend fun importHtmlRawContent(rawHtml: String, fallbackTitle: String): SavedWebAppEntity =
        withContext(Dispatchers.IO) {
            val htmlBytes = rawHtml.toByteArray(Charsets.UTF_8)
            val title = extractHtmlTitle(rawHtml, fallbackTitle)
            val now = System.currentTimeMillis()
            val initialEntity = SavedWebAppEntity(
                title = title,
                sourceType = SavedWebAppEntity.SOURCE_HTML,
                targetUrl = "",
                htmlContent = null,
                createdAt = now,
                lastOpenedAt = now
            )
            val insertedId = dao.insertApp(initialEntity)
            val storageDir = File(context.filesDir, "saved_html_apps").apply { mkdirs() }
            val localFile = File(storageDir, "app_${insertedId}.html")
            BufferedOutputStream(FileOutputStream(localFile), 64 * 1024).use { out ->
                out.write(htmlBytes)
                out.flush()
            }

            ramHtmlBytesCache[insertedId] = htmlBytes
            lastKnownTitles[insertedId] = title
            extractInitialHtmlColors(rawHtml)?.let { initialHtmlColorsCache[insertedId] = it }

            val virtualOriginUrl = buildVirtualHtmlUrl(insertedId)
            val updatedEntity = initialEntity.copy(
                id = insertedId,
                targetUrl = virtualOriginUrl,
                localFilePath = localFile.absolutePath,
                lastVisitedUrl = virtualOriginUrl
            )
            dao.updateApp(updatedEntity)
            updatedEntity
        }

    suspend fun createOrOpenUrlApp(rawInput: String): SavedWebAppEntity = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(rawInput)
        val existing = dao.getAllAppsSnapshot().firstOrNull {
            it.sourceType == SavedWebAppEntity.SOURCE_URL &&
                (it.targetUrl.equals(normalizedUrl, ignoreCase = true) ||
                    it.lastVisitedUrl?.equals(normalizedUrl, ignoreCase = true) == true)
        }
        if (existing != null) {
            dao.touchLastOpened(existing.id, System.currentTimeMillis())
            return@withContext existing
        }

        val title = deriveUrlTitle(normalizedUrl)
        val now = System.currentTimeMillis()
        val entity = SavedWebAppEntity(
            title = title,
            sourceType = SavedWebAppEntity.SOURCE_URL,
            targetUrl = normalizedUrl,
            lastVisitedUrl = normalizedUrl,
            createdAt = now,
            lastOpenedAt = now
        )
        val insertedId = dao.insertApp(entity)
        lastKnownTitles[insertedId] = title
        entity.copy(id = insertedId)
    }

    suspend fun updateResumeState(
        id: Long,
        lastVisitedUrl: String?,
        scrollX: Int,
        scrollY: Int,
        webViewStateBase64: String?
    ) = withContext(Dispatchers.IO) {
        val snapshot = ResumeStateSnapshot(lastVisitedUrl, scrollX, scrollY, webViewStateBase64?.hashCode() ?: 0)
        if (lastSavedResumeState[id] == snapshot) {
            return@withContext
        }
        lastSavedResumeState[id] = snapshot
        dao.updateResumeState(
            id = id,
            lastVisitedUrl = lastVisitedUrl,
            scrollX = scrollX,
            scrollY = scrollY,
            webViewStateBase64 = webViewStateBase64
        )
    }

    suspend fun touchLastOpened(id: Long) = withContext(Dispatchers.IO) {
        dao.touchLastOpened(id, System.currentTimeMillis())
    }

    suspend fun updateTitleIfMeaningful(id: Long, newTitle: String) = withContext(Dispatchers.IO) {
        val cleaned = newTitle.trim().take(60)
        if (cleaned.isNotEmpty() &&
            !cleaned.startsWith("http://", ignoreCase = true) &&
            !cleaned.startsWith("https://", ignoreCase = true) &&
            !cleaned.contains(".webbox.local", ignoreCase = true) &&
            cleaned != "about:blank"
        ) {
            if (lastKnownTitles[id] == cleaned) {
                return@withContext
            }
            lastKnownTitles[id] = cleaned
            dao.updateTitle(id, cleaned)
        }
    }

    suspend fun deleteApp(id: Long) = withContext(Dispatchers.IO) {
        ramHtmlBytesCache.remove(id)
        lastKnownTitles.remove(id)
        lastSavedResumeState.remove(id)
        initialHtmlColorsCache.remove(id)
        freezeStateStorage.deleteFrozenState(id)
        val existing = dao.getAppById(id)
        existing?.localFilePath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete local HTML file at $path", e)
            }
        }
        dao.deleteAppById(id)
    }

    fun readSavedHtmlBytes(app: SavedWebAppEntity): ByteArray {
        ramHtmlBytesCache[app.id]?.let { return it }

        app.localFilePath?.let { path ->
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    val bytes = file.readBytes()
                    if (bytes.isNotEmpty()) {
                        ramHtmlBytesCache[app.id] = bytes
                        cacheInitialColorsFromBytes(app.id, bytes)
                        return bytes
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read saved HTML file at $path for appId=${app.id}", e)
                }
            }
        }
        val persistedContent = app.htmlContent
        if (!persistedContent.isNullOrBlank()) {
            val bytes = persistedContent.toByteArray(Charsets.UTF_8)
            ramHtmlBytesCache[app.id] = bytes
            cacheInitialColorsFromBytes(app.id, bytes)
            return bytes
        }
        Log.w(TAG, "No readable HTML bytes found on disk for appId=${app.id}")
        return ByteArray(0)
    }

    fun getInitialViewportColors(appId: Long?): ViewportBoundaryColors? {
        if (appId == null) return null
        return initialHtmlColorsCache[appId]
    }

    private fun cacheInitialColorsFromBytes(appId: Long, htmlBytes: ByteArray) {
        if (initialHtmlColorsCache.containsKey(appId)) return
        val headLen = minOf(htmlBytes.size, 16384)
        val slice = String(htmlBytes, 0, headLen, Charsets.UTF_8)
        extractInitialHtmlColors(slice)?.let { colors ->
            initialHtmlColorsCache[appId] = colors
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            return cursor.getString(idx)
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private data class ResumeStateSnapshot(
        val url: String?,
        val scrollX: Int,
        val scrollY: Int,
        val stateHash: Int
    )

    companion object {
        private const val TAG = "WebAppRepository"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_LAST_ACTIVE_APP_ID = "last_active_app_id"

        // Process-level RAM caches to eliminate redundant disk & SQLite I/O
        private val ramHtmlBytesCache = ConcurrentHashMap<Long, ByteArray>()
        private val lastKnownTitles = ConcurrentHashMap<Long, String>()
        private val lastSavedResumeState = ConcurrentHashMap<Long, ResumeStateSnapshot>()
        private val initialHtmlColorsCache = ConcurrentHashMap<Long, ViewportBoundaryColors>()

        private val TITLE_REGEX = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val META_THEME_COLOR_REGEX = Regex(
            "<meta[^>]*name=[\"']theme-color[\"'][^>]*content=[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        private val INLINE_BODY_BG_REGEX = Regex(
            "<(?:body|html)[^>]*style=[\"'][^\"']*background(?:-color)?\\s*:\\s*([^;\"'}]+)",
            RegexOption.IGNORE_CASE
        )
        private val CSS_BODY_BG_REGEX = Regex(
            "(?:body|html)\\s*\\{[^}]*?background(?:-color)?\\s*:\\s*([^;\\n}]+)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val CSS_VAR_BG_REGEX = Regex(
            "--(?:bg-main|bg|background|app-bg)\\s*:\\s*(#[0-9a-fA-F]{3,8}|rgb\\([^)]+\\))",
            RegexOption.IGNORE_CASE
        )
        private val HEX_COLOR_TOKEN_REGEX = Regex("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b")
        private val RGB_COLOR_TOKEN_REGEX = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val FILENAME_SEPARATORS_REGEX = Regex("[_-]+")

        fun extractInitialHtmlColors(html: String): ViewportBoundaryColors? {
            val slice = if (html.length > 16384) html.substring(0, 16384) else html
            val candidateRaw = META_THEME_COLOR_REGEX.find(slice)?.groupValues?.getOrNull(1)
                ?: INLINE_BODY_BG_REGEX.find(slice)?.groupValues?.getOrNull(1)
                ?: CSS_BODY_BG_REGEX.find(slice)?.groupValues?.getOrNull(1)?.takeIf { !it.contains("var(") }
                ?: CSS_VAR_BG_REGEX.find(slice)?.groupValues?.getOrNull(1)

            val parsedColor = parseCssColorToken(candidateRaw) ?: return null
            return ViewportBoundaryColors(topColor = parsedColor, bottomColor = parsedColor)
        }

        internal fun parseCssColorToken(raw: String?): Int? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            val hexMatch = HEX_COLOR_TOKEN_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
            if (hexMatch != null) {
                val expandedHex = if (hexMatch.length == 3) {
                    "#${hexMatch[0]}${hexMatch[0]}${hexMatch[1]}${hexMatch[1]}${hexMatch[2]}${hexMatch[2]}"
                } else {
                    "#$hexMatch"
                }
                return runCatching { Color.parseColor(expandedHex) }.getOrNull()
            }
            val rgbMatch = RGB_COLOR_TOKEN_REGEX.find(trimmed)
            if (rgbMatch != null) {
                val r = rgbMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
                val g = rgbMatch.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
                val b = rgbMatch.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: return null
                return Color.rgb(r, g, b)
            }
            return null
        }

        fun buildVirtualHtmlUrl(appId: Long): String {
            return "https://app-${appId}.webbox.local/index.html"
        }

        fun extractHtmlTitle(html: String, fileName: String?): String {
            val headSlice = if (html.length > 8192) html.substring(0, 8192) else html
            val match = TITLE_REGEX.find(headSlice)?.groupValues?.getOrNull(1)
                ?.replace(WHITESPACE_REGEX, " ")
                ?.trim()
            if (!match.isNullOrEmpty()) {
                return match.take(50)
            }
            val cleanFileName = fileName
                ?.substringBeforeLast('.')
                ?.replace(FILENAME_SEPARATORS_REGEX, " ")
                ?.trim()
            if (!cleanFileName.isNullOrEmpty()) {
                return cleanFileName.take(50)
            }
            return "Aplicação HTML"
        }

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "https://example.com"
            val lower = trimmed.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return trimmed
            }
            if (lower.startsWith("file://") || lower.startsWith("data:")) {
                // Reject local/internal schemes from URL submission, forcing safe web protocol prefix
                return "https://$trimmed"
            }
            if (lower.startsWith("localhost") ||
                lower.startsWith("127.0.0.1") ||
                lower.startsWith("10.") ||
                lower.startsWith("192.168.")
            ) {
                return "http://$trimmed"
            }
            return "https://$trimmed"
        }

        fun deriveUrlTitle(normalizedUrl: String): String {
            return runCatching {
                val uri = URI(normalizedUrl)
                val host = uri.host?.removePrefix("www.")
                if (!host.isNullOrBlank()) {
                    host
                } else {
                    normalizedUrl.removePrefix("https://").removePrefix("http://").take(40)
                }
            }.getOrDefault(normalizedUrl.take(40))
        }
    }
}
