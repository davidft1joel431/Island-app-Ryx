/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

interface INotificationHistoryRepository {
    val history: StateFlow<List<NotificationHistoryEntry>>
    suspend fun getPage(
        query: String,
        packageName: String?,
        beforePostTimeMillis: Long? = null,
        beforeId: Long? = null,
        limit: Int
    ): List<NotificationHistoryEntry>
    suspend fun getCount(query: String = "", packageName: String? = null): Int
    suspend fun getCountSince(postTimeMillis: Long): Int
    suspend fun getAppSummaries(): List<NotificationHistoryAppSummary>
    suspend fun getBackupEntries(): List<NotificationHistoryEntry>
    suspend fun exportSqliteBackup(output: OutputStream): Int
    suspend fun inspectSqliteBackup(input: InputStream): Int?
    suspend fun mergeSqliteBackup(input: InputStream): NotificationHistoryRestoreResult?
    suspend fun replaceSqliteBackup(input: InputStream): NotificationHistoryRestoreResult?
    suspend fun mergeEntries(entries: List<NotificationHistoryEntry>): NotificationHistoryRestoreResult?
    suspend fun saveEntry(entry: NotificationHistoryEntry)
    suspend fun replaceAll(entries: List<NotificationHistoryEntry>): Boolean
    suspend fun markAsOpened(notificationKey: String)
    suspend fun deleteEntry(id: Long)
    suspend fun deleteByPackage(packageName: String): Int
    suspend fun clearAll()
    suspend fun search(query: String): List<NotificationHistoryEntry>
    suspend fun cleanupOldEntries(retentionHours: Int)
    suspend fun reload()

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_VISIBLE_ENTRIES = PAGE_SIZE
        const val MAX_BACKUP_ENTRIES = 100_000
        const val MAX_SQLITE_BACKUP_ENTRIES = 1_000_000
    }
}

data class NotificationHistoryRestoreResult(
    val importedCount: Int,
    val duplicatesSkipped: Int,
    val totalCount: Int
)
