package com.d4viddf.hyperbridge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationHistoryDao {
    @Query("SELECT * FROM notification_history ORDER BY postedAt DESC LIMIT :limit")
    fun getHistoryFlow(limit: Int = 80): Flow<List<NotificationHistoryEntry>>

    @Query("DELETE FROM notification_history WHERE notificationKey = :key")
    suspend fun deleteByKey(key: String)

    @Insert
    suspend fun insert(entry: NotificationHistoryEntry)

    @Query("DELETE FROM notification_history WHERE id NOT IN (SELECT id FROM notification_history ORDER BY postedAt DESC LIMIT :maxEntries)")
    suspend fun trim(maxEntries: Int)

    @Query("DELETE FROM notification_history")
    suspend fun clear()

    @Query("DELETE FROM notification_history WHERE id = :id")
    suspend fun deleteById(id: Long)
}
