package com.d4viddf.hyperbridge.data

import android.content.Context
import com.d4viddf.hyperbridge.data.db.AppDatabase
import com.d4viddf.hyperbridge.data.db.NotificationHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Stores a small, local-only notification history. Entries are capped and
 * truncated so the feature stays cheap in both storage and memory.
 */
class NotificationHistoryRepository(context: Context) {

    private val dao = AppDatabase.getDatabase(context).notificationHistoryDao()

    val history: Flow<List<NotificationHistoryEntry>> = dao.getHistoryFlow(MAX_ENTRIES)

    suspend fun record(
        notificationKey: String,
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        type: String,
        postedAt: Long = System.currentTimeMillis()
    ) {
        if (notificationKey.isBlank() || (title.isBlank() && text.isBlank())) return

        // Keep only the latest state of each notification instead of every
        // progress update. This avoids noisy history and extra writes.
        dao.deleteByKey(notificationKey)
        dao.insert(
            NotificationHistoryEntry(
                notificationKey = notificationKey,
                packageName = packageName,
                appLabel = appLabel.take(MAX_LABEL_LENGTH),
                title = title.take(MAX_TITLE_LENGTH),
                text = text.take(MAX_TEXT_LENGTH),
                type = type,
                postedAt = postedAt
            )
        )
        dao.trim(MAX_ENTRIES)
    }

    suspend fun clear() = dao.clear()

    suspend fun delete(id: Long) = dao.deleteById(id)

    private companion object {
        const val MAX_ENTRIES = 80
        const val MAX_LABEL_LENGTH = 64
        const val MAX_TITLE_LENGTH = 160
        const val MAX_TEXT_LENGTH = 400
    }
}
