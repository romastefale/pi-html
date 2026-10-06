package com.example.webview

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.Parcel
import android.os.Process
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Atomic disk storage for frozen WebView states and DOM/Storage snapshots,
 * plus real-time process RAM measurement (up to the 6 GB ceiling).
 */
class FreezeStateStorage(private val context: Context) {

    private val freezeDir: File by lazy {
        File(context.filesDir, "frozen_app_states").apply { mkdirs() }
    }

    fun saveWebViewBundle(appId: Long, bundle: Bundle): Boolean {
        if (bundle.isEmpty) return false
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(android.os.Build.VERSION.SDK_INT)
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            if (bytes.isEmpty()) return false
            writeBytesAtomically(bundleFile(appId), bytes)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist WebView bundle for appId=$appId", e)
            false
        } finally {
            parcel.recycle()
        }
    }

    fun loadWebViewBundle(appId: Long, legacyBase64: String? = null): Bundle? {
        val file = bundleFile(appId)
        val bytes: ByteArray? = when {
            file.exists() && file.length() > 0L -> {
                try {
                    BufferedInputStream(FileInputStream(file), IO_BUFFER_SIZE).use { it.readBytes() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read WebView bundle file for appId=$appId", e)
                    null
                }
            }
            !legacyBase64.isNullOrBlank() -> {
                try {
                    Base64.decode(legacyBase64, Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode legacy Base64 bundle for appId=$appId", e)
                    null
                }
            }
            else -> null
        }

        if (bytes == null || bytes.isEmpty()) return null

        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val savedSdk = parcel.readInt()
            if (savedSdk != android.os.Build.VERSION.SDK_INT) {
                Log.w(TAG, "Discarding WebView bundle due to SDK version mismatch: saved=$savedSdk current=${android.os.Build.VERSION.SDK_INT}")
                return null
            }
            Bundle.CREATOR.createFromParcel(parcel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmarshall WebView bundle for appId=$appId", e)
            null
        } finally {
            parcel.recycle()
        }
    }

    fun saveDomFreezeSnapshot(appId: Long, jsonSnapshot: String): Boolean {
        if (jsonSnapshot.isBlank()) return false
        return try {
            val bytes = jsonSnapshot.toByteArray(Charsets.UTF_8)
            writeBytesAtomically(domSnapshotFile(appId), bytes)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save DOM freeze snapshot for appId=$appId", e)
            false
        }
    }

    fun loadDomFreezeSnapshot(appId: Long): String? {
        val file = domSnapshotFile(appId)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val bytes = BufferedInputStream(FileInputStream(file), IO_BUFFER_SIZE).use { it.readBytes() }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load DOM freeze snapshot for appId=$appId", e)
            null
        }
    }

    fun hasFrozenSnapshot(appId: Long): Boolean {
        return domSnapshotFile(appId).exists() || bundleFile(appId).exists()
    }

    fun deleteFrozenState(appId: Long) {
        try {
            bundleFile(appId).delete()
            File(freezeDir, "webview_${appId}.bundle.tmp").delete()
            domSnapshotFile(appId).delete()
            File(freezeDir, "freeze_${appId}.json.tmp").delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete frozen state files for appId=$appId", e)
        }
    }

    /**
     * Returns current process RAM consumption in bytes (combining PSS / native + JVM heaps)
     * and checks whether the process or system is approaching the 6 GB RAM ceiling.
     */
    fun getCurrentRamUsageBytes(activeWebViewCount: Int): Long {
        val runtime = Runtime.getRuntime()
        val jvmUsed = runtime.totalMemory() - runtime.freeMemory()
        val nativeAllocated = Debug.getNativeHeapAllocatedSize()

        val pssBytes = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfos = am?.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            val totalPssKb = memInfos?.firstOrNull()?.totalPss?.toLong() ?: 0L
            totalPssKb * 1024L
        } catch (e: Exception) {
            0L
        }

        val measuredProcessBytes = maxOf(pssBytes, jvmUsed + nativeAllocated)
        // Account for out-of-process Chromium GPU/Renderer shared memory (~95 MB per live WebView)
        val estimatedChromiumPoolBytes = activeWebViewCount.toLong() * ESTIMATED_BYTES_PER_LIVE_WEBVIEW
        return maxOf(measuredProcessBytes, jvmUsed + nativeAllocated + estimatedChromiumPoolBytes)
    }

    fun isRamBudgetExceeded(activeWebViewCount: Int): Boolean {
        val currentUsage = getCurrentRamUsageBytes(activeWebViewCount)
        if (currentUsage >= MAX_RAM_BUDGET_BYTES) {
            return true
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                memInfo.lowMemory || (memInfo.availMem > 0L && memInfo.availMem < MIN_SAFE_SYSTEM_AVAIL_BYTES)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun bundleFile(appId: Long): File = File(freezeDir, "webview_${appId}.bundle")

    private fun domSnapshotFile(appId: Long): File = File(freezeDir, "freeze_${appId}.json")

    private fun writeBytesAtomically(targetFile: File, bytes: ByteArray) {
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        BufferedOutputStream(FileOutputStream(tmpFile), IO_BUFFER_SIZE).use { out ->
            out.write(bytes)
            out.flush()
        }
        if (!tmpFile.renameTo(targetFile)) {
            BufferedOutputStream(FileOutputStream(targetFile), IO_BUFFER_SIZE).use { out ->
                out.write(bytes)
                out.flush()
            }
            tmpFile.delete()
        }
    }

    companion object {
        private const val TAG = "FreezeStateStorage"
        private const val IO_BUFFER_SIZE = 64 * 1024

        // 6 GB maximum simultaneous RAM budget as requested
        const val MAX_RAM_BUDGET_BYTES: Long = 6L * 1024L * 1024L * 1024L

        // 20 minutes of inactivity before automatic state freeze (hibernation)
        const val INACTIVE_HIBERNATION_TIMEOUT_MS: Long = 20L * 60L * 1000L

        private const val ESTIMATED_BYTES_PER_LIVE_WEBVIEW: Long = 95L * 1024L * 1024L
        private const val MIN_SAFE_SYSTEM_AVAIL_BYTES: Long = 256L * 1024L * 1024L
    }
}
