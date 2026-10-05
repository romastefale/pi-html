package com.example.webview

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder

object DownloadHelper {

    fun saveDataUrlToDownloads(
        context: Context,
        dataUrlOrBase64: String,
        suggestedFileName: String?,
        explicitMimeType: String?
    ): Result<String> = runCatching {
        val parsed = parseDataUrl(dataUrlOrBase64, explicitMimeType)
        val finalFileName = sanitizeFileName(
            suggestedFileName = suggestedFileName,
            mimeType = parsed.mimeType
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, parsed.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collectionUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collectionUri, contentValues)
                ?: throw IllegalStateException("Failed to create MediaStore Downloads entry")

            resolver.openOutputStream(itemUri)?.use { output ->
                output.write(parsed.bytes)
                output.flush()
            } ?: throw IllegalStateException("Failed to open output stream for Downloads")

            val completeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, completeValues, null, null)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?.apply { mkdirs() }
                ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
                ?: context.filesDir

            val outFile = resolveUniqueFile(downloadsDir, finalFileName)
            FileOutputStream(outFile).use { output ->
                output.write(parsed.bytes)
                output.flush()
            }
        }

        finalFileName
    }

    fun enqueueHttpDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): Result<String> = runCatching {
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
        val fileName = URLUtil.guessFileName(url, contentDisposition, resolvedMime)
            .ifBlank { "download_${System.currentTimeMillis()}" }

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(resolvedMime)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) {
                addRequestHeader("Cookie", cookie)
            }
            if (!userAgent.isNullOrBlank()) {
                addRequestHeader("User-Agent", userAgent)
            }
            setDescription(url)
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        fileName
    }

    internal data class ParsedDataPayload(
        val bytes: ByteArray,
        val mimeType: String
    )

    internal fun parseDataUrl(
        dataUrlOrBase64: String,
        explicitMimeType: String?
    ): ParsedDataPayload {
        val trimmed = dataUrlOrBase64.trim()
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            val commaIndex = trimmed.indexOf(',')
            require(commaIndex != -1) { "Invalid data URL" }
            val header = trimmed.substring(5, commaIndex)
            val payload = trimmed.substring(commaIndex + 1)
            val isBase64 = header.contains(";base64", ignoreCase = true)
            val headerMime = header.substringBefore(';').trim()
            val resolvedMime = when {
                headerMime.isNotBlank() -> headerMime
                !explicitMimeType.isNullOrBlank() -> explicitMimeType
                else -> "application/octet-stream"
            }
            val bytes = if (isBase64) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            return ParsedDataPayload(bytes = bytes, mimeType = resolvedMime)
        } else {
            val bytes = Base64.decode(trimmed, Base64.DEFAULT)
            val resolvedMime = explicitMimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            return ParsedDataPayload(bytes = bytes, mimeType = resolvedMime)
        }
    }

    internal fun sanitizeFileName(
        suggestedFileName: String?,
        mimeType: String
    ): String {
        val cleaned = suggestedFileName
            ?.replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.substringBefore(';').trim())
            ?: when {
                mimeType.contains("text/plain", ignoreCase = true) -> "txt"
                mimeType.contains("text/html", ignoreCase = true) -> "html"
                mimeType.contains("application/json", ignoreCase = true) -> "json"
                mimeType.contains("text/csv", ignoreCase = true) -> "csv"
                mimeType.contains("application/pdf", ignoreCase = true) -> "pdf"
                mimeType.contains("image/png", ignoreCase = true) -> "png"
                mimeType.contains("image/jpeg", ignoreCase = true) -> "jpg"
                else -> "bin"
            }

        if (cleaned == null) {
            return "arquivo_${System.currentTimeMillis()}.$extFromMime"
        }
        return if (cleaned.contains('.')) {
            cleaned
        } else {
            "$cleaned.$extFromMime"
        }
    }

    private fun resolveUniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var counter = 1
        while (candidate.exists()) {
            val nextName = if (ext.isNotEmpty()) "${base}_($counter).$ext" else "${base}_($counter)"
            candidate = File(dir, nextName)
            counter++
        }
        return candidate
    }
}
