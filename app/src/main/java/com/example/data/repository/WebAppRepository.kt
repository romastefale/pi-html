package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.SavedWebAppDao
import com.example.data.local.SavedWebAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

class WebAppRepository(
    private val context: Context,
    private val dao: SavedWebAppDao
) {
    private val prefs = context.getSharedPreferences("pi_web_runner_prefs", Context.MODE_PRIVATE)

    val allApps: Flow<List<SavedWebAppEntity>> = dao.getAllApps()

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
            val htmlContent = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IllegalArgumentException("Empty HTML stream")

            if (htmlContent.isBlank()) {
                throw IllegalArgumentException("Blank HTML file")
            }

            val fileName = resolveFileName(uri)
            val extractedTitle = extractHtmlTitle(htmlContent, fileName)
            val now = System.currentTimeMillis()

            val initialEntity = SavedWebAppEntity(
                title = extractedTitle,
                sourceType = SavedWebAppEntity.SOURCE_HTML,
                targetUrl = "",
                localFilePath = null,
                htmlContent = htmlContent,
                lastVisitedUrl = null,
                createdAt = now,
                lastOpenedAt = now
            )

            val insertedId = dao.insertApp(initialEntity)
            val storageDir = File(context.filesDir, "saved_html_apps").apply { mkdirs() }
            val localFile = File(storageDir, "app_${insertedId}.html")
            localFile.writeText(htmlContent, Charsets.UTF_8)

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
            val title = extractHtmlTitle(rawHtml, fallbackTitle)
            val now = System.currentTimeMillis()
            val initialEntity = SavedWebAppEntity(
                title = title,
                sourceType = SavedWebAppEntity.SOURCE_HTML,
                targetUrl = "",
                htmlContent = rawHtml,
                createdAt = now,
                lastOpenedAt = now
            )
            val insertedId = dao.insertApp(initialEntity)
            val storageDir = File(context.filesDir, "saved_html_apps").apply { mkdirs() }
            val localFile = File(storageDir, "app_${insertedId}.html")
            localFile.writeText(rawHtml, Charsets.UTF_8)

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
        entity.copy(id = insertedId)
    }

    suspend fun updateResumeState(
        id: Long,
        lastVisitedUrl: String?,
        scrollX: Int,
        scrollY: Int,
        webViewStateBase64: String?
    ) = withContext(Dispatchers.IO) {
        dao.updateResumeState(
            id = id,
            lastVisitedUrl = lastVisitedUrl,
            scrollX = scrollX,
            scrollY = scrollY,
            webViewStateBase64 = webViewStateBase64,
            lastOpenedAt = System.currentTimeMillis()
        )
    }

    suspend fun touchLastOpened(id: Long) = withContext(Dispatchers.IO) {
        dao.touchLastOpened(id, System.currentTimeMillis())
    }

    suspend fun updateTitleIfMeaningful(id: Long, newTitle: String) = withContext(Dispatchers.IO) {
        val cleaned = newTitle.trim()
        if (cleaned.isNotEmpty() &&
            !cleaned.startsWith("http://", ignoreCase = true) &&
            !cleaned.startsWith("https://", ignoreCase = true) &&
            !cleaned.contains(".webbox.local", ignoreCase = true) &&
            cleaned != "about:blank"
        ) {
            dao.updateTitle(id, cleaned.take(60))
        }
    }

    suspend fun deleteApp(id: Long) = withContext(Dispatchers.IO) {
        val existing = dao.getAppById(id)
        existing?.localFilePath?.let { path ->
            runCatching { File(path).delete() }
        }
        dao.deleteAppById(id)
    }

    fun readSavedHtmlContent(app: SavedWebAppEntity): String {
        app.localFilePath?.let { path ->
            val file = File(path)
            if (file.exists() && file.canRead()) {
                runCatching { return file.readText(Charsets.UTF_8) }
            }
        }
        return app.htmlContent ?: "<!DOCTYPE html><html><body></body></html>"
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

    companion object {
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_LAST_ACTIVE_APP_ID = "last_active_app_id"

        fun buildVirtualHtmlUrl(appId: Long): String {
            return "https://app-${appId}.webbox.local/index.html"
        }

        fun extractHtmlTitle(html: String, fileName: String?): String {
            val titleRegex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = titleRegex.find(html)?.groupValues?.getOrNull(1)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
            if (!match.isNullOrEmpty()) {
                return match.take(50)
            }
            val cleanFileName = fileName
                ?.substringBeforeLast('.')
                ?.replace(Regex("[_-]+"), " ")
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
            if (lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("file://") ||
                lower.startsWith("data:")
            ) {
                return trimmed
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
