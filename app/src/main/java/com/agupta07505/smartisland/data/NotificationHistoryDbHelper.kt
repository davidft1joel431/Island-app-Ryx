/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.agupta07505.smartisland.util.runCatchingLogged
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class NotificationHistoryDbHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "NotificationHistoryDb"
        private const val DATABASE_NAME = "smart_island_notification_history.db"
        private const val DATABASE_VERSION = 3
        private const val BACKUP_TABLE = "ryx_backup_metadata"
        private const val BACKUP_FORMAT = "ryx-isla-notification-history-sqlite"
        private const val BACKUP_VERSION = 2

        const val TABLE_HISTORY = "notification_history"
        const val COL_ID = "_id"
        const val COL_NOTIF_KEY = "notification_key"
        const val COL_PACKAGE = "package_name"
        const val COL_APP_NAME = "app_name"
        const val COL_TITLE = "title"
        const val COL_TEXT = "text"
        const val COL_SUB_TEXT = "sub_text"
        const val COL_POST_TIME = "post_time"
        const val COL_CATEGORY = "category"
        const val COL_CHANNEL_ID = "channel_id"
        const val COL_MODE = "mode"
        const val COL_ACTIONS_JSON = "actions_json"
        const val COL_WAS_OPENED = "was_opened"
        const val COL_WAS_DISMISSED = "was_dismissed"
        const val COL_WAS_SILENT = "was_silent"
        const val COL_HAS_VIBRATION = "has_vibration"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createHistorySchema(db)
    }

    private fun createHistorySchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NOTIF_KEY TEXT NOT NULL,
                $COL_PACKAGE TEXT NOT NULL,
                $COL_APP_NAME TEXT NOT NULL,
                $COL_TITLE TEXT,
                $COL_TEXT TEXT,
                $COL_SUB_TEXT TEXT,
                $COL_POST_TIME INTEGER NOT NULL,
                $COL_CATEGORY TEXT,
                $COL_CHANNEL_ID TEXT,
                $COL_MODE TEXT NOT NULL,
                $COL_ACTIONS_JSON TEXT,
                $COL_WAS_OPENED INTEGER DEFAULT 0,
                $COL_WAS_DISMISSED INTEGER DEFAULT 0,
                $COL_WAS_SILENT INTEGER DEFAULT 0,
                $COL_HAS_VIBRATION INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_notif_post_time ON $TABLE_HISTORY ($COL_POST_TIME DESC)")
        db.execSQL("CREATE INDEX idx_notif_pkg ON $TABLE_HISTORY ($COL_PACKAGE)")
        db.execSQL("CREATE UNIQUE INDEX idx_notif_key_post_time ON $TABLE_HISTORY ($COL_NOTIF_KEY, $COL_POST_TIME)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COL_WAS_SILENT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COL_HAS_VIBRATION INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // The listener may receive the same active notification through both callbacks.
            // Keep the newest saved copy before enforcing one history row per posted instance.
            db.execSQL("DELETE FROM $TABLE_HISTORY WHERE $COL_ID NOT IN (SELECT MAX($COL_ID) FROM $TABLE_HISTORY GROUP BY $COL_NOTIF_KEY, $COL_POST_TIME)")
            db.execSQL("CREATE UNIQUE INDEX idx_notif_key_post_time ON $TABLE_HISTORY ($COL_NOTIF_KEY, $COL_POST_TIME)")
        }
    }

    fun insertEntry(entry: NotificationHistoryEntry): Long {
        return runCatchingLogged(TAG, "Failed to insert notification history entry") {
            writableDatabase.insertWithOnConflict(TABLE_HISTORY, null, contentValues(entry), SQLiteDatabase.CONFLICT_IGNORE)
        } ?: -1L
    }

    fun exportSqliteBackup(output: OutputStream, limit: Int): Int {
        val file = File.createTempFile("ryx-history-", ".db", appContext.cacheDir)
        try {
            val backup = SQLiteDatabase.openOrCreateDatabase(file, null)
            val count: Int
            try {
                createHistorySchema(backup)
                backup.execSQL("CREATE TABLE $BACKUP_TABLE (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                backup.execSQL("INSERT INTO $BACKUP_TABLE (name, value) VALUES ('format', ?)", arrayOf(BACKUP_FORMAT))
                backup.execSQL("INSERT INTO $BACKUP_TABLE (name, value) VALUES ('version', ?)", arrayOf(BACKUP_VERSION.toString()))
                val source = readableDatabase.query(
                    TABLE_HISTORY, null, null, null, null, null,
                    "$COL_POST_TIME DESC, $COL_ID DESC", limit.toString()
                )
                backup.beginTransaction()
                try {
                    source.use { cursor ->
                        while (cursor.moveToNext()) {
                            val entry = cursorToEntry(cursor)
                            backup.insertOrThrow(TABLE_HISTORY, null, contentValues(entry).apply {
                                if (entry.id > 0L) put(COL_ID, entry.id)
                            })
                        }
                    }
                    backup.setTransactionSuccessful()
                } finally {
                    backup.endTransaction()
                }
                count = backup.rawQuery("SELECT COUNT(*) FROM $TABLE_HISTORY", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                backup.execSQL("INSERT INTO $BACKUP_TABLE (name, value) VALUES ('count', ?)", arrayOf(count.toString()))
            } finally {
                backup.close()
            }
            file.inputStream().buffered().use { it.copyTo(output) }
            return count
        } finally {
            file.delete()
        }
    }

    /** Validates a standalone SQLite history backup and optionally replaces this database atomically. */
    fun inspectSqliteBackup(input: InputStream, restore: Boolean): Int? {
        val file = File.createTempFile("ryx-history-import-", ".db", appContext.cacheDir)
        try {
            file.outputStream().buffered().use { output -> input.copyTo(output) }
            val backup = try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (_: Exception) {
                return null
            }
            val count = try {
                val format = backup.rawQuery("SELECT value FROM $BACKUP_TABLE WHERE name='format'", null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val version = backup.rawQuery("SELECT value FROM $BACKUP_TABLE WHERE name='version'", null).use {
                    if (it.moveToFirst()) it.getString(0)?.toIntOrNull() else null
                }
                val rows = backup.rawQuery("SELECT COUNT(*) FROM $TABLE_HISTORY", null).use {
                    if (it.moveToFirst()) it.getLong(0) else -1L
                }
                if (format != BACKUP_FORMAT || version != BACKUP_VERSION ||
                    rows !in 0..com.agupta07505.smartisland.data.INotificationHistoryRepository.MAX_SQLITE_BACKUP_ENTRIES.toLong()
                ) return null
                rows.toInt()
            } catch (_: Exception) {
                return null
            } finally {
                backup.close()
            }
            if (!restore) return count

            val db = writableDatabase
            db.execSQL("ATTACH DATABASE ? AS history_backup", arrayOf(file.absolutePath))
            try {
                db.beginTransaction()
                try {
                    db.delete(TABLE_HISTORY, null, null)
                    db.execSQL(
                        """INSERT INTO $TABLE_HISTORY (
                            $COL_ID, $COL_NOTIF_KEY, $COL_PACKAGE, $COL_APP_NAME, $COL_TITLE, $COL_TEXT,
                            $COL_SUB_TEXT, $COL_POST_TIME, $COL_CATEGORY, $COL_CHANNEL_ID, $COL_MODE,
                            $COL_ACTIONS_JSON, $COL_WAS_OPENED, $COL_WAS_DISMISSED, $COL_WAS_SILENT, $COL_HAS_VIBRATION
                        ) SELECT $COL_ID, $COL_NOTIF_KEY, $COL_PACKAGE, $COL_APP_NAME, $COL_TITLE, $COL_TEXT,
                            $COL_SUB_TEXT, $COL_POST_TIME, $COL_CATEGORY, $COL_CHANNEL_ID, $COL_MODE,
                            $COL_ACTIONS_JSON, $COL_WAS_OPENED, $COL_WAS_DISMISSED, $COL_WAS_SILENT, $COL_HAS_VIBRATION
                        FROM history_backup.$TABLE_HISTORY""".trimIndent()
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE history_backup")
            }
            return count
        } finally {
            file.delete()
        }
    }

    fun replaceAll(entries: List<NotificationHistoryEntry>): Boolean = runCatchingLogged(
        TAG, "Failed to replace notification history from backup"
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_HISTORY, null, null)
            entries.take(INotificationHistoryRepository.MAX_BACKUP_ENTRIES).forEach { entry ->
                db.insertOrThrow(TABLE_HISTORY, null, contentValues(entry))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        true
    } ?: false

    private fun contentValues(entry: NotificationHistoryEntry): ContentValues = ContentValues().apply {
        put(COL_NOTIF_KEY, entry.notificationKey)
        put(COL_PACKAGE, entry.packageName)
        put(COL_APP_NAME, entry.appName)
        put(COL_TITLE, entry.title)
        put(COL_TEXT, entry.text)
        put(COL_SUB_TEXT, entry.subText)
        put(COL_POST_TIME, entry.postTimeMillis)
        put(COL_CATEGORY, entry.category)
        put(COL_CHANNEL_ID, entry.channelId)
        put(COL_MODE, entry.mode)
        put(COL_ACTIONS_JSON, JSONArray(entry.actionTitles).toString())
        put(COL_WAS_OPENED, if (entry.wasOpened) 1 else 0)
        put(COL_WAS_DISMISSED, if (entry.wasDismissed) 1 else 0)
        put(COL_WAS_SILENT, if (entry.wasSilent) 1 else 0)
        put(COL_HAS_VIBRATION, if (entry.hasVibration) 1 else 0)
    }

    fun getAllEntries(limit: Int = INotificationHistoryRepository.MAX_VISIBLE_ENTRIES): List<NotificationHistoryEntry> {
        return runCatchingLogged(TAG, "Failed to fetch notification history") {
            val list = mutableListOf<NotificationHistoryEntry>()
            val cursor = readableDatabase.query(
                TABLE_HISTORY,
                null,
                null,
                null,
                null,
                null,
                "$COL_POST_TIME DESC",
                limit.toString()
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    list.add(cursorToEntry(c))
                }
            }
            list
        } ?: emptyList()
    }

    fun searchEntries(query: String, limit: Int = 200): List<NotificationHistoryEntry> {
        return runCatchingLogged(TAG, "Failed to search notification history") {
            val list = mutableListOf<NotificationHistoryEntry>()
            val searchPattern = "%$query%"
            val cursor = readableDatabase.query(
                TABLE_HISTORY,
                null,
                "$COL_APP_NAME LIKE ? OR $COL_TITLE LIKE ? OR $COL_TEXT LIKE ? OR $COL_PACKAGE LIKE ?",
                arrayOf(searchPattern, searchPattern, searchPattern, searchPattern),
                null,
                null,
                "$COL_POST_TIME DESC",
                limit.toString()
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    list.add(cursorToEntry(c))
                }
            }
            list
        } ?: emptyList()
    }

    fun deleteById(id: Long): Int {
        return runCatchingLogged(TAG, "Failed to delete entry $id") {
            writableDatabase.delete(TABLE_HISTORY, "$COL_ID = ?", arrayOf(id.toString()))
        } ?: 0
    }

    fun deleteByPackage(packageName: String): Int {
        return runCatchingLogged(TAG, "Failed to delete entries for package $packageName") {
            writableDatabase.delete(TABLE_HISTORY, "$COL_PACKAGE = ?", arrayOf(packageName))
        } ?: 0
    }

    fun deleteAll(): Int {
        return runCatchingLogged(TAG, "Failed to clear all history") {
            writableDatabase.delete(TABLE_HISTORY, null, null)
        } ?: 0
    }

    fun deleteOlderThan(cutoffMillis: Long): Int {
        return runCatchingLogged(TAG, "Failed to purge old notification history") {
            writableDatabase.delete(TABLE_HISTORY, "$COL_POST_TIME < ?", arrayOf(cutoffMillis.toString()))
        } ?: 0
    }

    fun markAsOpened(notificationKey: String): Int {
        return runCatchingLogged(TAG, "Failed to mark notification as opened") {
            val values = ContentValues().apply {
                put(COL_WAS_OPENED, 1)
            }
            writableDatabase.update(TABLE_HISTORY, values, "$COL_NOTIF_KEY = ?", arrayOf(notificationKey))
        } ?: 0
    }

    private fun cursorToEntry(cursor: Cursor): NotificationHistoryEntry {
        val actionsJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_ACTIONS_JSON))
        val actionsList = runCatching {
            if (!actionsJson.isNullOrBlank()) {
                val jsonArr = JSONArray(actionsJson)
                List(jsonArr.length()) { jsonArr.getString(it) }
            } else emptyList()
        }.getOrElse { emptyList() }

        return NotificationHistoryEntry(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            notificationKey = cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTIF_KEY)),
            packageName = cursor.getString(cursor.getColumnIndexOrThrow(COL_PACKAGE)),
            appName = cursor.getString(cursor.getColumnIndexOrThrow(COL_APP_NAME)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)) ?: "",
            text = cursor.getString(cursor.getColumnIndexOrThrow(COL_TEXT)) ?: "",
            subText = cursor.getString(cursor.getColumnIndexOrThrow(COL_SUB_TEXT)),
            postTimeMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_POST_TIME)),
            category = cursor.getString(cursor.getColumnIndexOrThrow(COL_CATEGORY)),
            channelId = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHANNEL_ID)),
            mode = cursor.getString(cursor.getColumnIndexOrThrow(COL_MODE)) ?: "Notification",
            actionTitles = actionsList,
            wasOpened = cursor.getInt(cursor.getColumnIndexOrThrow(COL_WAS_OPENED)) == 1,
            wasDismissed = cursor.getInt(cursor.getColumnIndexOrThrow(COL_WAS_DISMISSED)) == 1,
            wasSilent = cursor.getInt(cursor.getColumnIndexOrThrow(COL_WAS_SILENT)) == 1,
            hasVibration = cursor.getInt(cursor.getColumnIndexOrThrow(COL_HAS_VIBRATION)) == 1
        )
    }
}
