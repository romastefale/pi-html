package com.example.webview

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

object DownloadHelper {

    private const val TAG = "DownloadHelper"
    private val INVALID_FILENAME_REGEX = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")
    private const val IO_BUFFER_SIZE = 64 * 1024

    const val MAX_CHUNK_SESSION_BYTES: Long = 128L * 1024L * 1024L // 128 MiB max per chunked session
    const val MAX_CONCURRENT_CHUNK_SESSIONS: Int = 5

    private data class ChunkedDownloadSession(
        val suggestedFileName: String?,
        val mimeType: String?,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream(IO_BUFFER_SIZE)
    )

    private val activeChunkSessions = ConcurrentHashMap<String, ChunkedDownloadSession>()

    fun beginChunkedDownload(sessionId: String, suggestedFileName: String?, mimeType: String?): Boolean {
        if (activeChunkSessions.size >= MAX_CONCURRENT_CHUNK_SESSIONS && !activeChunkSessions.containsKey(sessionId)) {
            Log.w(TAG, "Exceeded max concurrent chunk download sessions ($MAX_CONCURRENT_CHUNK_SESSIONS)")
            return false
        }
        activeChunkSessions[sessionId] = ChunkedDownloadSession(
            suggestedFileName = suggestedFileName,
            mimeType = mimeType
        )
        return true
    }

    fun appendChunkBase64(
        sessionId: String,
        base64Chunk: String,
        maxBytes: Long = MAX_CHUNK_SESSION_BYTES
    ): Boolean {
        val session = activeChunkSessions[sessionId] ?: return false
        return try {
            val decoded = Base64.decode(base64Chunk, Base64.DEFAULT)
            synchronized(session) {
                if (session.buffer.size().toLong() + decoded.size.toLong() > maxBytes) {
                    Log.e(TAG, "Chunked download session $sessionId exceeded size limit of $maxBytes bytes")
                    activeChunkSessions.remove(sessionId)
                    return false
                }
                session.buffer.write(decoded)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode chunk for session=$sessionId", e)
            activeChunkSessions.remove(sessionId)
            false
        }
    }

    fun finishChunkedDownload(context: Context, sessionId: String): Result<String> {
        val session = activeChunkSessions.remove(sessionId)
            ?: return Result.failure(IllegalStateException("Download session not found: $sessionId"))
        return try {
            val bytes = synchronized(session) { session.buffer.toByteArray() }
            val resolvedMime = session.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            saveBytesToDownloads(
                context = context,
                bytes = bytes,
                suggestedFileName = session.suggestedFileName,
                mimeType = resolvedMime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize chunked download session=$sessionId", e)
            Result.failure(e)
        }
    }

    fun saveDataUrlToDownloads(
        context: Context,
        dataUrlOrBase64: String,
        suggestedFileName: String?,
        explicitMimeType: String?
    ): Result<String> {
        return try {
            val parsed = parseDataUrl(dataUrlOrBase64, explicitMimeType)
            saveBytesToDownloads(
                context = context,
                bytes = parsed.bytes,
                suggestedFileName = suggestedFileName,
                mimeType = parsed.mimeType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save data URL to Downloads (fileName=$suggestedFileName)", e)
            Result.failure(e)
        }
    }

    fun saveBytesToDownloads(
        context: Context,
        bytes: ByteArray,
        suggestedFileName: String?,
        mimeType: String
    ): Result<String> {
        return try {
            val normalizedMime = mimeType.substringBefore(';').trim().ifBlank { "application/octet-stream" }
            val finalFileName = sanitizeFileName(
                suggestedFileName = suggestedFileName,
                mimeType = normalizedMime
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, normalizedMime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collectionUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collectionUri, contentValues)
                    ?: throw IllegalStateException("MediaStore.Downloads.insert returned null for $finalFileName")

                try {
                    resolver.openOutputStream(itemUri)?.use { rawOut ->
                        BufferedOutputStream(rawOut, IO_BUFFER_SIZE).use { output ->
                            output.write(bytes)
                            output.flush()
                        }
                    } ?: throw IllegalStateException("ContentResolver.openOutputStream returned null for $itemUri")

                    val completeValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, completeValues, null, null)
                } catch (writeError: Exception) {
                    resolver.delete(itemUri, null, null)
                    throw writeError
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    ?.apply { mkdirs() }
                    ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
                    ?: context.filesDir

                val outFile = resolveUniqueFile(downloadsDir, finalFileName)
                BufferedOutputStream(FileOutputStream(outFile), IO_BUFFER_SIZE).use { output ->
                    output.write(bytes)
                    output.flush()
                }
            }

            Result.success(finalFileName)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file to Downloads directory", e)
            Result.failure(e)
        }
    }

    fun enqueueHttpDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): Result<String> {
        return try {
            val resolvedMime = mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() } ?: "*/*"
            val rawGuessed = URLUtil.guessFileName(url, contentDisposition, resolvedMime)
            val fileName = sanitizeFileName(rawGuessed, resolvedMime)

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
            Result.success(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue HTTP download for url=$url", e)
            Result.failure(e)
        }
    }

    internal data class ParsedDataPayload(
        val bytes: ByteArray,
        val mimeType: String
    )

    internal fun parseDataUrl(
        dataUrlOrBase64: String,
        explicitMimeType: String?
    ): ParsedDataPayload {
        var startIdx = 0
        val len = dataUrlOrBase64.length
        while (startIdx < len && dataUrlOrBase64[startIdx].isWhitespace()) {
            startIdx++
        }
        if (dataUrlOrBase64.regionMatches(startIdx, "data:", 0, 5, ignoreCase = true)) {
            val commaIndex = dataUrlOrBase64.indexOf(',', startIdx + 5)
            require(commaIndex != -1) { "Malformed data URL: missing comma separator" }
            val header = dataUrlOrBase64.substring(startIdx + 5, commaIndex)
            val payload = dataUrlOrBase64.substring(commaIndex + 1)
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
            val bytes = Base64.decode(dataUrlOrBase64.substring(startIdx), Base64.DEFAULT)
            val resolvedMime = explicitMimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            return ParsedDataPayload(bytes = bytes, mimeType = resolvedMime)
        }
    }

    internal fun sanitizeFileName(
        suggestedFileName: String?,
        mimeType: String
    ): String {
        val decodedSuggestion = suggestedFileName?.let { raw ->
            try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                raw
            }
        }
        val cleaned = decodedSuggestion
            ?.replace(INVALID_FILENAME_REGEX, "_")
            ?.trim()
            ?.trim('.')
            ?.takeIf { it.isNotEmpty() && !it.equals("download", ignoreCase = true) && !it.equals("blob", ignoreCase = true) }

        val cleanMime = mimeType.substringBefore(';').trim().lowercase()
        val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)
            ?: when {
                cleanMime.contains("text/plain") -> "txt"
                cleanMime.contains("text/html") -> "html"
                cleanMime.contains("application/json") || cleanMime.contains("+json") -> "json"
                cleanMime.contains("text/csv") -> "csv"
                cleanMime.contains("text/markdown") -> "md"
                cleanMime.contains("application/pdf") -> "pdf"
                cleanMime.contains("image/png") -> "png"
                cleanMime.contains("image/jpeg") -> "jpg"
                cleanMime.contains("image/webp") -> "webp"
                cleanMime.contains("image/svg") -> "svg"
                cleanMime.contains("image/gif") -> "gif"
                cleanMime.contains("audio/mpeg") || cleanMime.contains("audio/mp3") -> "mp3"
                cleanMime.contains("audio/wav") || cleanMime.contains("audio/x-wav") -> "wav"
                cleanMime.contains("audio/ogg") -> "ogg"
                cleanMime.contains("video/mp4") -> "mp4"
                cleanMime.contains("video/webm") -> "webm"
                cleanMime.contains("application/zip") || cleanMime.contains("x-zip") -> "zip"
                cleanMime.contains("javascript") -> "js"
                cleanMime.contains("text/css") -> "css"
                cleanMime.contains("xml") -> "xml"
                cleanMime.contains("model/gltf-binary") -> "glb"
                cleanMime.contains("model/gltf+json") -> "gltf"
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
