package com.d4viddf.hyperbridge.ui.screens.settings

import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.db.NotificationHistoryEntry
import com.d4viddf.hyperbridge.models.NotificationType
import java.util.Calendar

internal enum class HistoryGrouping {
    DAY,
    APP;

    companion object {
        fun fromName(name: String): HistoryGrouping =
            entries.firstOrNull { it.name == name } ?: DAY
    }
}

internal enum class HistorySort {
    RECENT,
    APP;

    companion object {
        fun fromName(name: String): HistorySort =
            entries.firstOrNull { it.name == name } ?: RECENT
    }
}

internal enum class HistoryFilter(val labelRes: Int) {
    ALL(R.string.notification_history_filter_all),
    MESSAGES(R.string.notification_history_filter_messages),
    CALLS(R.string.notification_history_filter_calls),
    DOWNLOADS(R.string.notification_history_filter_downloads),
    OTHER(R.string.notification_history_filter_other);

    fun matches(type: String): Boolean = when (this) {
        ALL -> true
        MESSAGES -> type == NotificationType.MESSAGE.name
        CALLS -> type == NotificationType.CALL.name
        DOWNLOADS -> type == NotificationType.DOWNLOAD.name || type == NotificationType.PROGRESS.name
        OTHER -> type !in KNOWN_TYPES
    }

    companion object {
        private val KNOWN_TYPES = setOf(
            NotificationType.MESSAGE.name,
            NotificationType.CALL.name,
            NotificationType.DOWNLOAD.name,
            NotificationType.PROGRESS.name
        )

        fun fromName(name: String): HistoryFilter =
            entries.firstOrNull { it.name == name } ?: ALL
    }
}

internal data class HistoryDayGroup(
    val dayStart: Long,
    val entries: List<NotificationHistoryEntry>
)

internal data class HistoryAppGroup(
    val packageName: String,
    val appLabel: String,
    val entries: List<NotificationHistoryEntry>
) {
    val displayLabel: String get() = appLabel.ifBlank { packageName }
}

/** Filters by notification type and by a case-insensitive query over app, title and text. */
internal fun filterHistory(
    entries: List<NotificationHistoryEntry>,
    query: String,
    filter: HistoryFilter
): List<NotificationHistoryEntry> {
    val needle = query.trim()
    return entries.filter { entry ->
        filter.matches(entry.type) && (
            needle.isEmpty() ||
                entry.appLabel.contains(needle, ignoreCase = true) ||
                entry.title.contains(needle, ignoreCase = true) ||
                entry.text.contains(needle, ignoreCase = true)
            )
    }
}

internal fun sortHistory(
    entries: List<NotificationHistoryEntry>,
    sort: HistorySort
): List<NotificationHistoryEntry> = when (sort) {
    HistorySort.RECENT -> entries.sortedByDescending { it.postedAt }
    HistorySort.APP -> entries.sortedWith(
        compareBy<NotificationHistoryEntry, String>(String.CASE_INSENSITIVE_ORDER) { entry ->
            entry.appLabel.ifBlank { entry.packageName }
        }.thenByDescending { it.postedAt }
    )
}

/** Groups by calendar day, newest day first, with the entries sorted inside each day. */
internal fun groupHistoryByDay(
    entries: List<NotificationHistoryEntry>,
    sort: HistorySort
): List<HistoryDayGroup> = entries
    .groupBy { dayStartOf(it.postedAt) }
    .map { (day, dayEntries) -> HistoryDayGroup(day, sortHistory(dayEntries, sort)) }
    .sortedByDescending { it.dayStart }

/** Groups by package, ordered by newest notification or by app name, with sorted entries. */
internal fun groupHistoryByApp(
    entries: List<NotificationHistoryEntry>,
    sort: HistorySort
): List<HistoryAppGroup> = entries
    .groupBy { it.packageName }
    .map { (packageName, appEntries) ->
        val sorted = sortHistory(appEntries, sort)
        HistoryAppGroup(
            packageName = packageName,
            appLabel = sorted.first().appLabel,
            entries = sorted
        )
    }
    .sortedWith(
        when (sort) {
            HistorySort.RECENT -> compareByDescending<HistoryAppGroup> { group ->
                group.entries.maxOf { entry -> entry.postedAt }
            }

            HistorySort.APP -> compareBy<HistoryAppGroup, String>(String.CASE_INSENSITIVE_ORDER) { group ->
                group.displayLabel
            }
        }
    )

internal fun dayStartOf(timestamp: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timestamp
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
