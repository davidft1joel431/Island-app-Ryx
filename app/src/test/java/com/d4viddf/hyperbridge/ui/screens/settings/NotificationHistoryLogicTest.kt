package com.d4viddf.hyperbridge.ui.screens.settings

import com.d4viddf.hyperbridge.data.db.NotificationHistoryEntry
import com.d4viddf.hyperbridge.models.NotificationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Covers the pure filtering, sorting and grouping used by the notification
 * history screen, independently from Compose and the database.
 */
class NotificationHistoryLogicTest {

    // ---------------------------------------------------------------------
    //  Filtering
    // ---------------------------------------------------------------------

    @Test
    fun `all filter keeps every entry`() {
        val entries = listOf(
            entry(type = type(NotificationType.MESSAGE)),
            entry(type = type(NotificationType.CALL)),
            entry(type = "SOMETHING_ELSE")
        )

        assertEquals(entries, filterHistory(entries, query = "", filter = HistoryFilter.ALL))
    }

    @Test
    fun `type filters separate the known notification kinds`() {
        val message = entry(type = type(NotificationType.MESSAGE))
        val call = entry(type = type(NotificationType.CALL))
        val download = entry(type = type(NotificationType.DOWNLOAD))
        val progress = entry(type = type(NotificationType.PROGRESS))
        val other = entry(type = "SOMETHING_ELSE")
        val entries = listOf(message, call, download, progress, other)

        assertEquals(listOf(message), filterHistory(entries, "", HistoryFilter.MESSAGES))
        assertEquals(listOf(call), filterHistory(entries, "", HistoryFilter.CALLS))
        assertEquals(listOf(download, progress), filterHistory(entries, "", HistoryFilter.DOWNLOADS))
        assertEquals(listOf(other), filterHistory(entries, "", HistoryFilter.OTHER))
    }

    @Test
    fun `query matches app title and text ignoring case and spaces`() {
        val byApp = entry(appLabel = "WhatsApp", title = "Mensaje", text = "Hola")
        val byTitle = entry(appLabel = "Banco", title = "Pago recibido", text = "Detalle")
        val byText = entry(appLabel = "Correo", title = "Nuevo", text = "Código de acceso")
        val entries = listOf(byApp, byTitle, byText)

        assertEquals(listOf(byApp), filterHistory(entries, "  whatsapp  ", HistoryFilter.ALL))
        assertEquals(listOf(byTitle), filterHistory(entries, "PAGO", HistoryFilter.ALL))
        assertEquals(listOf(byText), filterHistory(entries, "código", HistoryFilter.ALL))
    }

    @Test
    fun `query and type filter combine`() {
        val message = entry(type = type(NotificationType.MESSAGE), text = "hola")
        val call = entry(type = type(NotificationType.CALL), text = "hola")
        val entries = listOf(message, call)

        assertEquals(listOf(message), filterHistory(entries, "hola", HistoryFilter.MESSAGES))
        assertTrue(filterHistory(entries, "adiós", HistoryFilter.ALL).isEmpty())
    }

    @Test
    fun `fromName restores saved values and falls back to defaults`() {
        assertEquals(HistoryFilter.MESSAGES, HistoryFilter.fromName("MESSAGES"))
        assertEquals(HistoryFilter.ALL, HistoryFilter.fromName("not-a-filter"))
        assertEquals(HistoryGrouping.APP, HistoryGrouping.fromName("APP"))
        assertEquals(HistoryGrouping.DAY, HistoryGrouping.fromName(""))
        assertEquals(HistorySort.APP, HistorySort.fromName("APP"))
        assertEquals(HistorySort.RECENT, HistorySort.fromName("not-a-sort"))
    }

    // ---------------------------------------------------------------------
    //  Sorting
    // ---------------------------------------------------------------------

    @Test
    fun `recent sort is newest first`() {
        val oldest = entry(postedAt = at(2026, Calendar.NOVEMBER, 1, 9))
        val newest = entry(postedAt = at(2026, Calendar.NOVEMBER, 3, 9))
        val middle = entry(postedAt = at(2026, Calendar.NOVEMBER, 2, 9))

        assertEquals(
            listOf(newest, middle, oldest),
            sortHistory(listOf(oldest, newest, middle), HistorySort.RECENT)
        )
    }

    @Test
    fun `app sort is case insensitive and uses the package when the label is blank`() {
        val zeta = entry(appLabel = "Zeta", postedAt = at(2026, Calendar.NOVEMBER, 3, 9))
        val alphaOld = entry(appLabel = "alpha", postedAt = at(2026, Calendar.NOVEMBER, 1, 9))
        val alphaNew = entry(appLabel = "alpha", postedAt = at(2026, Calendar.NOVEMBER, 2, 9))
        val noLabel = entry(
            appLabel = "",
            packageName = "com.mi.banco",
            postedAt = at(2026, Calendar.NOVEMBER, 4, 9)
        )

        assertEquals(
            listOf(alphaNew, alphaOld, noLabel, zeta),
            sortHistory(listOf(zeta, alphaOld, alphaNew, noLabel), HistorySort.APP)
        )
    }

    // ---------------------------------------------------------------------
    //  Grouping by day
    // ---------------------------------------------------------------------

    @Test
    fun `day grouping returns newest days first with newest entries inside`() {
        val firstDayLate = entry(postedAt = at(2026, Calendar.NOVEMBER, 1, 23))
        val firstDayEarly = entry(postedAt = at(2026, Calendar.NOVEMBER, 1, 8))
        val secondDay = entry(postedAt = at(2026, Calendar.NOVEMBER, 2, 10))

        val groups = groupHistoryByDay(
            listOf(firstDayEarly, secondDay, firstDayLate),
            HistorySort.RECENT
        )

        assertEquals(2, groups.size)
        assertEquals(at(2026, Calendar.NOVEMBER, 2), groups[0].dayStart)
        assertEquals(listOf(secondDay), groups[0].entries)
        assertEquals(at(2026, Calendar.NOVEMBER, 1), groups[1].dayStart)
        assertEquals(listOf(firstDayLate, firstDayEarly), groups[1].entries)
    }

    @Test
    fun `day grouping sorts entries by app when requested`() {
        val zeta = entry(appLabel = "Zeta", postedAt = at(2026, Calendar.NOVEMBER, 1, 9))
        val alpha = entry(appLabel = "alpha", postedAt = at(2026, Calendar.NOVEMBER, 1, 10))

        val groups = groupHistoryByDay(listOf(zeta, alpha), HistorySort.APP)

        assertEquals(1, groups.size)
        assertEquals(listOf(alpha, zeta), groups[0].entries)
    }

    @Test
    fun `day start is local midnight and ignores the time of day`() {
        val morning = at(2026, Calendar.NOVEMBER, 3, 7, 45)
        val night = at(2026, Calendar.NOVEMBER, 3, 23, 59)
        val midnight = at(2026, Calendar.NOVEMBER, 3, 0, 0)

        assertEquals(midnight, dayStartOf(morning))
        assertEquals(midnight, dayStartOf(night))
        assertEquals(dayStartOf(morning), dayStartOf(night))
    }

    // ---------------------------------------------------------------------
    //  Grouping by app
    // ---------------------------------------------------------------------

    @Test
    fun `app grouping groups by package and falls back to the package as label`() {
        val whatsappA = entry(packageName = "com.whatsapp", appLabel = "WhatsApp")
        val whatsappB = entry(packageName = "com.whatsapp", appLabel = "WhatsApp")
        val unnamed = entry(packageName = "com.mi.banco", appLabel = "")

        val groups = groupHistoryByApp(listOf(whatsappA, unnamed, whatsappB), HistorySort.RECENT)

        assertEquals(2, groups.size)
        assertEquals(listOf("com.whatsapp", "com.mi.banco"), groups.map { it.packageName })
        assertEquals(2, groups[0].entries.size)
        assertEquals("com.mi.banco", groups[1].displayLabel)
    }

    @Test
    fun `app grouping orders by newest notification on recent sort`() {
        val olderInNewerApp = entry(packageName = "com.a", appLabel = "A", postedAt = at(2026, Calendar.NOVEMBER, 1, 9))
        val newerInOlderApp = entry(packageName = "com.b", appLabel = "B", postedAt = at(2026, Calendar.NOVEMBER, 3, 9))
        val newestInNewerApp = entry(packageName = "com.a", appLabel = "A", postedAt = at(2026, Calendar.NOVEMBER, 4, 9))

        val groups = groupHistoryByApp(
            listOf(olderInNewerApp, newerInOlderApp, newestInNewerApp),
            HistorySort.RECENT
        )

        assertEquals(listOf("com.a", "com.b"), groups.map { it.packageName })
        assertEquals(listOf(newestInNewerApp, olderInNewerApp), groups[0].entries)
    }

    @Test
    fun `app grouping orders alphabetically by label on app sort`() {
        val zeta = entry(packageName = "com.zeta", appLabel = "Zeta", postedAt = at(2026, Calendar.NOVEMBER, 4, 9))
        val alphaOld = entry(packageName = "com.alpha", appLabel = "alpha", postedAt = at(2026, Calendar.NOVEMBER, 1, 9))
        val alphaNew = entry(packageName = "com.alpha", appLabel = "alpha", postedAt = at(2026, Calendar.NOVEMBER, 3, 9))
        val noLabel = entry(packageName = "com.mi.banco", appLabel = "", postedAt = at(2026, Calendar.NOVEMBER, 2, 9))

        val groups = groupHistoryByApp(
            listOf(zeta, alphaOld, alphaNew, noLabel),
            HistorySort.APP
        )

        assertEquals(listOf("com.alpha", "com.mi.banco", "com.zeta"), groups.map { it.packageName })
        assertEquals(listOf(alphaNew, alphaOld), groups[0].entries)
    }

    // ---------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------

    private fun type(notificationType: NotificationType): String = notificationType.name

    private var nextId = 1L

    private fun entry(
        packageName: String = "com.whatsapp",
        appLabel: String = "WhatsApp",
        title: String = "Título",
        text: String = "Texto",
        type: String = type(NotificationType.MESSAGE),
        postedAt: Long = at(2026, Calendar.NOVEMBER, 1, 9)
    ): NotificationHistoryEntry {
        val id = nextId++
        return NotificationHistoryEntry(
            id = id,
            notificationKey = "key-$id",
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            type = type,
            postedAt = postedAt
        )
    }

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month, day, hour, minute, 0)
    }.timeInMillis
}
