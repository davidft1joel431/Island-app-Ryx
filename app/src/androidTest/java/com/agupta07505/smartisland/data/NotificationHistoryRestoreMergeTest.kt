/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class NotificationHistoryRestoreMergeTest {
    @Test
    fun mergeKeepsLocalRowsSkipsRepeatedBackupRowsAndReplaceIsExplicit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName = "history-restore-source-${System.nanoTime()}.db"
        val targetName = "history-restore-target-${System.nanoTime()}.db"
        val source = NotificationHistoryDbHelper(context, sourceName)
        val target = NotificationHistoryDbHelper(context, targetName)
        try {
            val shared = entry("shared", "app.shared", 1_800_000_000_001L)
            val backupOnly = entry("backup-only", "app.backup", 1_800_000_000_002L)
            val localOnly = entry("local-only", "app.local", 1_800_000_000_003L)

            assertTrue(source.replaceAll(listOf(shared, backupOnly)))
            val output = ByteArrayOutputStream()
            assertEquals(2, source.exportSqliteBackup(output, 100))
            val backup = output.toByteArray()
            assertNotNull(source.inspectSqliteBackup(ByteArrayInputStream(backup)))

            assertTrue(target.replaceAll(listOf(shared, localOnly)))
            val merged = target.mergeSqliteBackup(ByteArrayInputStream(backup))
            assertNotNull(merged)
            assertEquals(1, merged?.importedCount)
            assertEquals(1, merged?.duplicatesSkipped)
            assertEquals(3, merged?.totalCount)
            assertEquals(setOf("shared", "backup-only", "local-only"), target.getAllEntries(100).map { it.notificationKey }.toSet())

            val mergedAgain = target.mergeSqliteBackup(ByteArrayInputStream(backup))
            assertNotNull(mergedAgain)
            assertEquals(0, mergedAgain?.importedCount)
            assertEquals(2, mergedAgain?.duplicatesSkipped)
            assertEquals(3, target.countEntries())

            val replaced = target.replaceSqliteBackup(ByteArrayInputStream(backup))
            assertNotNull(replaced)
            assertEquals(2, replaced?.importedCount)
            assertEquals(0, replaced?.duplicatesSkipped)
            assertEquals(setOf("shared", "backup-only"), target.getAllEntries(100).map { it.notificationKey }.toSet())
        } finally {
            source.close()
            target.close()
            context.deleteDatabase(sourceName)
            context.deleteDatabase(targetName)
        }
    }

    private fun entry(key: String, packageName: String, postTime: Long) = NotificationHistoryEntry(
        notificationKey = key,
        packageName = packageName,
        appName = packageName,
        title = key,
        text = "test",
        postTimeMillis = postTime
    )
}
