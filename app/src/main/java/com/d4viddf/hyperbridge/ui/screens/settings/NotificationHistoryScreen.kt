package com.d4viddf.hyperbridge.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.NotificationHistoryRepository
import com.d4viddf.hyperbridge.data.db.NotificationHistoryEntry
import com.d4viddf.hyperbridge.models.NotificationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationHistoryScreen(
    onBack: () -> Unit,
    preferences: AppPreferences
) {
    val context = LocalContext.current
    val repository = remember { NotificationHistoryRepository(context) }
    val scope = rememberCoroutineScope()
    val history by repository.history.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf(preferences.notificationHistoryQuerySync()) }
    var filter by rememberSaveable {
        mutableStateOf(HistoryFilter.fromName(preferences.notificationHistoryFilterSync()))
    }
    var grouping by rememberSaveable {
        mutableStateOf(HistoryGrouping.fromName(preferences.notificationHistoryGroupingSync()))
    }
    var sort by rememberSaveable {
        mutableStateOf(HistorySort.fromName(preferences.notificationHistorySortSync()))
    }
    // Restore only after the first composition, so a still-cold preference
    // cache can never overwrite a saved value with the defaults.
    var stateReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { stateReady = true }
    LaunchedEffect(query) {
        if (stateReady) preferences.setNotificationHistoryQuery(query)
    }
    LaunchedEffect(filter) {
        if (stateReady) preferences.setNotificationHistoryFilter(filter.name)
    }
    LaunchedEffect(grouping) {
        if (stateReady) preferences.setNotificationHistoryGrouping(grouping.name)
    }
    LaunchedEffect(sort) {
        if (stateReady) preferences.setNotificationHistorySort(sort.name)
    }

    NotificationHistoryContent(
        history = history,
        query = query,
        onQueryChange = { query = it },
        filter = filter,
        onFilterChange = { filter = it },
        grouping = grouping,
        onGroupingChange = { grouping = it },
        sort = sort,
        onSortChange = { sort = it },
        onBack = onBack,
        onDelete = { id -> scope.launch { repository.delete(id) } },
        onClearHistory = { scope.launch { repository.clear() } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationHistoryContent(
    history: List<NotificationHistoryEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    grouping: HistoryGrouping,
    onGroupingChange: (HistoryGrouping) -> Unit,
    sort: HistorySort,
    onSortChange: (HistorySort) -> Unit,
    onBack: () -> Unit,
    onDelete: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val filtered = remember(history, query, filter) {
        filterHistory(history, query, filter)
    }
    val dayGroups = remember(filtered, sort) {
        groupHistoryByDay(filtered, sort)
    }
    val appGroups = remember(filtered, sort) {
        groupHistoryByApp(filtered, sort)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notification_history_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(R.string.notification_history_clear)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text(stringResource(R.string.notification_history_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.notification_history_search_clear))
                        }
                    }
                }
            )

            LazyRow(
                modifier = Modifier.padding(vertical = 2.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(HistoryFilter.entries) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilterChange(option) },
                        label = { Text(stringResource(option.labelRes)) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = grouping == HistoryGrouping.DAY,
                    onClick = { onGroupingChange(HistoryGrouping.DAY) },
                    label = { Text(stringResource(R.string.notification_history_group_by_day)) }
                )
                FilterChip(
                    selected = grouping == HistoryGrouping.APP,
                    onClick = { onGroupingChange(HistoryGrouping.APP) },
                    label = { Text(stringResource(R.string.notification_history_group_by_app)) }
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(
                            Icons.Rounded.Sort,
                            contentDescription = stringResource(R.string.notification_history_sort),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notification_history_sort_recent)) },
                            leadingIcon = if (sort == HistorySort.RECENT) {
                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                onSortChange(HistorySort.RECENT)
                                sortMenuOpen = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notification_history_sort_app)) },
                            leadingIcon = if (sort == HistorySort.APP) {
                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                onSortChange(HistorySort.APP)
                                sortMenuOpen = false
                            }
                        )
                    }
                }
            }

            Text(
                text = if (query.isBlank() && filter == HistoryFilter.ALL) {
                    stringResource(R.string.notification_history_count, filtered.size)
                } else {
                    stringResource(R.string.notification_history_results, filtered.size)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp)
            )

            if (filtered.isEmpty()) {
                val hasFilters = query.isNotBlank() || filter != HistoryFilter.ALL
                EmptyNotificationHistory(
                    title = stringResource(
                        if (hasFilters) R.string.notification_history_empty_search_title
                        else R.string.notification_history_empty_title
                    ),
                    description = stringResource(
                        if (hasFilters) R.string.notification_history_empty_search_desc
                        else R.string.notification_history_empty_desc
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (grouping) {
                        HistoryGrouping.DAY -> dayGroups.forEach { group ->
                            item(key = "day-${group.dayStart}") {
                                DayHeader(dayStart = group.dayStart, count = group.entries.size)
                            }
                            items(group.entries, key = { it.id }) { entry ->
                                NotificationHistoryCard(
                                    entry = entry,
                                    modifier = Modifier.animateContentSize(),
                                    onDelete = { onDelete(entry.id) }
                                )
                            }
                        }

                        HistoryGrouping.APP -> appGroups.forEach { group ->
                            item(key = "app-${group.packageName}") {
                                AppGroupHeader(
                                    packageName = group.packageName,
                                    appLabel = group.displayLabel,
                                    count = group.entries.size
                                )
                            }
                            items(group.entries, key = { it.id }) { entry ->
                                NotificationHistoryCard(
                                    entry = entry,
                                    modifier = Modifier.animateContentSize(),
                                    onDelete = { onDelete(entry.id) }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.size(16.dp)) }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text(stringResource(R.string.notification_history_clear_title)) },
            text = { Text(stringResource(R.string.notification_history_clear_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearHistory()
                    }
                ) {
                    Text(stringResource(R.string.notification_history_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AppGroupHeader(packageName: String, appLabel: String, count: Int) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(64, 64).asImageBitmap()
            }.getOrNull()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon!!,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(
                    Icons.Rounded.NotificationsNone,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = appLabel.ifBlank { packageName },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.notification_history_count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayHeader(dayStart: Long, count: Int) {
    val label = dayLabel(dayStart)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.notification_history_count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun dayLabel(dayStart: Long): String {
    val now = System.currentTimeMillis()
    val today = dayStartOf(now)
    val yesterday = remember(today) {
        Calendar.getInstance().apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
    }
    val formatted = remember(dayStart) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dayStart))
    }
    return when (dayStart) {
        today -> stringResource(R.string.notification_history_today)
        yesterday -> stringResource(R.string.notification_history_yesterday)
        else -> formatted
    }
}

@Composable
private fun EmptyNotificationHistory(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.NotificationsNone,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.size(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotificationHistoryCard(
    entry: NotificationHistoryEntry,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            HistoryAppIcon(entry.packageName, entry.type)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.appLabel.ifBlank { entry.packageName },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            entry.postedAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE
                        ).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.title.isNotBlank()) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.text.isNotBlank()) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TypeBadge(type = entry.type)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.notification_history_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val labelRes = runCatching { NotificationType.valueOf(type).labelRes }
        .getOrElse { R.string.type_standard }
    val accent = historyAccent(type)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.16f),
        modifier = Modifier.padding(top = 6.dp)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun historyAccent(type: String): Color = when (type) {
    NotificationType.CALL.name -> MaterialTheme.colorScheme.tertiary
    NotificationType.MESSAGE.name -> MaterialTheme.colorScheme.primary
    NotificationType.DOWNLOAD.name, NotificationType.PROGRESS.name -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun HistoryAppIcon(packageName: String, type: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }

    val accent = historyAccent(type)

    Box {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon!!,
                    contentDescription = null,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Icon(
                    Icons.Rounded.NotificationsNone,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(10.dp)
                .clip(CircleShape)
                .background(accent)
        )
    }
}
