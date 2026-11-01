package com.d4viddf.hyperbridge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One lightweight record shown in the local notification history. */
@Entity(tableName = "notification_history")
data class NotificationHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val type: String,
    val postedAt: Long
)
