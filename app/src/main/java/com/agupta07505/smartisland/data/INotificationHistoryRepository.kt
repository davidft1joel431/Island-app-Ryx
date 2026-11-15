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
    suspend fun getBackupEntries(): List<NotificationHistoryEntry>
    suspend fun exportSqliteBackup(output: OutputStream): Int
    suspend fun inspectSqliteBackup(input: InputStream): Int?
    suspend fun restoreSqliteBackup(input: InputStream): Int?
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
        const val MAX_VISIBLE_ENTRIES = 25_000
        const val MAX_BACKUP_ENTRIES = 100_000
        const val MAX_SQLITE_BACKUP_ENTRIES = 1_000_000
    }
}
