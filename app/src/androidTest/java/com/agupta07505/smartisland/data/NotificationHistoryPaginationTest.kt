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
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationHistoryPaginationTest {
    @Test
    fun pagesOverTwentyFiveThousandRows_keepFiltersAndConcurrentInsertsGapFree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "history-pagination-${System.nanoTime()}.db"
        val db = NotificationHistoryDbHelper(context, databaseName)
        try {
            val rowCount = 25_137
            val entries = List(rowCount) { index ->
                NotificationHistoryEntry(
                    notificationKey = "pagination-key-$index",
                    packageName = if (index % 2 == 0) "example.app.a" else "example.app.b",
                    appName = if (index % 2 == 0) "App A" else "App B",
                    title = if (index % 17 == 0) "needle-$index" else "message-$index",
                    text = "row-$index",
                    postTimeMillis = BASE_TIME_MILLIS + (index / 3)
                )
            }
            assertEquals(true, db.replaceAll(entries))
            assertEquals(rowCount, db.countEntries())

            val firstPage = db.getPage("", null, null, null, INotificationHistoryRepository.PAGE_SIZE)
            assertEquals(INotificationHistoryRepository.PAGE_SIZE, firstPage.size)

            // Insert a new, newest row after page one. Keyset pagination should still return
            // every row from the original traversal exactly once, then show the new row on refresh.
            val concurrentEntry = NotificationHistoryEntry(
                notificationKey = "pagination-key-inserted-during-walk",
                packageName = "example.app.c",
                appName = "App C",
                title = "arrived-during-walk",
                text = "new row",
                postTimeMillis = BASE_TIME_MILLIS + rowCount + 100L
            )
            assertTrueInserted(db, concurrentEntry)

            val traversed = collectPages(db, firstPage)
            val expectedOriginalIds = entries.indices
                .sortedWith(compareByDescending<Int> { entries[it].postTimeMillis }.thenByDescending { it })
                .map { it + 1L }
            assertEquals(expectedOriginalIds, traversed.map { it.id })
            assertEquals(traversed.size, traversed.map { it.id }.toSet().size)
            assertEquals(rowCount + 1, db.countEntries())
            assertEquals(concurrentEntry.notificationKey, db.getPage("", null, null, null, 1).first().notificationKey)

            val filteredPage = collectPages(
                db,
                query = "needle",
                packageName = "example.app.a"
            )
            val expectedFilteredKeys = entries.withIndex()
                .filter { (index, entry) -> index % 2 == 0 && entry.title.contains("needle") }
                .sortedWith(compareByDescending<IndexedValue<NotificationHistoryEntry>> { it.value.postTimeMillis }
                    .thenByDescending { it.index })
                .map { it.value.notificationKey }
            assertEquals(expectedFilteredKeys, filteredPage.map { it.notificationKey })
            assertEquals(filteredPage.size, filteredPage.map { it.id }.toSet().size)
            assertEquals(expectedFilteredKeys.size, db.countEntries("needle", "example.app.a"))

            val appBPage = collectPages(db, query = "", packageName = "example.app.b")
            val expectedAppBCount = entries.count { it.packageName == "example.app.b" }
            assertEquals(expectedAppBCount, appBPage.size)
            assertEquals(appBPage.size, appBPage.map { it.id }.toSet().size)
            assertEquals(expectedAppBCount, db.countEntries(packageName = "example.app.b"))
            assertFalse(appBPage.any { it.packageName != "example.app.b" })
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun assertTrueInserted(db: NotificationHistoryDbHelper, entry: NotificationHistoryEntry) {
        assertEquals(true, db.insertEntry(entry) > 0L)
    }

    private fun collectPages(
        db: NotificationHistoryDbHelper,
        firstPage: List<NotificationHistoryEntry> = emptyList(),
        query: String = "",
        packageName: String? = null
    ): List<NotificationHistoryEntry> {
        val result = firstPage.toMutableList()
        var page = firstPage
        if (page.isEmpty()) {
            page = db.getPage(query, packageName, null, null, INotificationHistoryRepository.PAGE_SIZE)
            result += page
        }
        while (page.isNotEmpty()) {
            val last = page.last()
            page = db.getPage(
                query = query,
                packageName = packageName,
                beforePostTimeMillis = last.postTimeMillis,
                beforeId = last.id,
                limit = INotificationHistoryRepository.PAGE_SIZE
            )
            result += page
        }
        return result
    }

    private companion object {
        const val BASE_TIME_MILLIS = 1_800_000_000_000L
    }
}
