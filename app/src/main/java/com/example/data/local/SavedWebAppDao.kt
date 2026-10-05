package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedWebAppDao {
    @Query("SELECT * FROM saved_web_apps ORDER BY lastOpenedAt DESC")
    fun getAllApps(): Flow<List<SavedWebAppEntity>>

    @Query("SELECT * FROM saved_web_apps WHERE id = :id LIMIT 1")
    suspend fun getAppById(id: Long): SavedWebAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: SavedWebAppEntity): Long

    @Update
    suspend fun updateApp(app: SavedWebAppEntity)

    @Query(
        """
        UPDATE saved_web_apps 
        SET lastVisitedUrl = :lastVisitedUrl,
            scrollX = :scrollX,
            scrollY = :scrollY,
            webViewStateBase64 = :webViewStateBase64,
            lastOpenedAt = :lastOpenedAt
        WHERE id = :id
        """
    )
    suspend fun updateResumeState(
        id: Long,
        lastVisitedUrl: String?,
        scrollX: Int,
        scrollY: Int,
        webViewStateBase64: String?,
        lastOpenedAt: Long
    )

    @Query("UPDATE saved_web_apps SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun touchLastOpened(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE saved_web_apps SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("DELETE FROM saved_web_apps WHERE id = :id")
    suspend fun deleteAppById(id: Long)
}
