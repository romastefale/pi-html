package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_web_apps")
data class SavedWebAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceType: String, // "HTML" or "URL"
    val targetUrl: String,
    val localFilePath: String? = null,
    val htmlContent: String? = null,
    val lastVisitedUrl: String? = null,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val webViewStateBase64: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis()
) {
    val isLocalHtml: Boolean
        get() = sourceType == SOURCE_HTML

    companion object {
        const val SOURCE_HTML = "HTML"
        const val SOURCE_URL = "URL"
    }
}
