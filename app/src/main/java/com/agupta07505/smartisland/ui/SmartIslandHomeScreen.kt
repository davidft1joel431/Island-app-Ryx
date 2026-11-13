/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import com.agupta07505.smartisland.ui.sections.UpdatesAndDownloadsSection
import com.agupta07505.smartisland.ui.sections.SupportSection
import com.agupta07505.smartisland.ui.sections.DeveloperOptionsSection
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Code

import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.agupta07505.smartisland.ui.glass.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import com.agupta07505.smartisland.ui.glass.LocalGlassDark
import com.agupta07505.smartisland.ui.glass.LocalLiquidGlass
import com.agupta07505.smartisland.ui.glass.ThemedBackdrop
import com.agupta07505.smartisland.ui.glass.glassEdge
import com.agupta07505.smartisland.ui.glass.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import com.agupta07505.smartisland.ui.components.CollapsibleCard
import com.agupta07505.smartisland.ui.components.SettingsControlsTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agupta07505.smartisland.R
import com.agupta07505.smartisland.data.INotificationRepository
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.data.SmartIslandSettingsRepository
import com.agupta07505.smartisland.di.SmartIslandRepositories
import com.agupta07505.smartisland.ui.sections.DemoSection
import com.agupta07505.smartisland.ui.sections.EventTogglesSection
import com.agupta07505.smartisland.ui.sections.IslandFontPicker
import com.agupta07505.smartisland.ui.sections.LandscapeSection
import com.agupta07505.smartisland.ui.sections.OrientationTab
import com.agupta07505.smartisland.ui.sections.OrientationTabs
import com.agupta07505.smartisland.ui.sections.AppShortcutsSection
import com.agupta07505.smartisland.ui.sections.AnimationSpeedSection
import com.agupta07505.smartisland.ui.sections.MergeStyleSection
import com.agupta07505.smartisland.ui.sections.IdleWidgetsSection
import com.agupta07505.smartisland.ui.sections.IdleWidgetsSettingsSection
import com.agupta07505.smartisland.ui.sections.WeatherSettingsCard
import com.agupta07505.smartisland.ui.sections.NetSpeedSettingsCard
import com.agupta07505.smartisland.ui.sections.CalendarSettingsCard
import com.agupta07505.smartisland.ui.search.searchAnchor
import com.agupta07505.smartisland.ui.sections.AboutSection
import com.agupta07505.smartisland.ui.sections.BackupRestoreSection
import com.agupta07505.smartisland.ui.sections.CustomizationsSection
import com.agupta07505.smartisland.ui.sections.DiagnosticsSection
import com.agupta07505.smartisland.ui.sections.FaceIdSettingsSection
import com.agupta07505.smartisland.ui.sections.TimingSection
import com.agupta07505.smartisland.ui.sections.GesturesSection
import com.agupta07505.smartisland.ui.sections.NotificationHistorySection
import com.agupta07505.smartisland.ui.sections.NotificationsAndPrivacySection
import com.agupta07505.smartisland.ui.sections.PermissionsSection
import com.agupta07505.smartisland.ui.sections.PositionsSection
import com.agupta07505.smartisland.util.SystemServiceRecovery
import com.agupta07505.smartisland.util.runCatchingLogged
import com.agupta07505.smartisland.ui.search.LocalSearchFocus
import com.agupta07505.smartisland.ui.search.SEARCH_FOCUS_ARMED_MS
import com.agupta07505.smartisland.ui.search.SearchFocusState
import com.agupta07505.smartisland.ui.search.SearchTarget
import com.agupta07505.smartisland.ui.search.SettingsSearch
import com.agupta07505.smartisland.ui.search.SettingsSearchEntry
import com.agupta07505.smartisland.ui.search.SettingsSearchField
import com.agupta07505.smartisland.ui.search.SettingsSearchResults
import com.agupta07505.smartisland.ui.search.buildSettingsSearchIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal enum class StudioTab {
    Studio,
    Position,
    Demo,
    Settings
}

private data class PermissionSnapshot(
    val overlay: Boolean,
    val notifications: Boolean,
    val batteryIgnored: Boolean,
    val bluetooth: Boolean
)

internal enum class FeatureDetailSection {
    GameMode,
    NotificationRules,
    AppShortcuts,
    NotificationHistory,
    ColorStudio,
    GesturesGuide,
    PermissionsCenter,
    BackupRestore,
    Diagnostics,
    Timing,
    IdleWidgets,
    About,
    UpdatesAndDownloads,
    SupportCommunity,
    DeveloperOptions,
    FaceIdSettings,
    WeatherSettings,
    NetSpeedSettings,
    CalendarSettings
}

private val IDLE_SETTINGS_PAGES = setOf(
    FeatureDetailSection.WeatherSettings,
    FeatureDetailSection.NetSpeedSettings,
    FeatureDetailSection.CalendarSettings
)

@SuppressLint("BatteryLife")
@Composable
fun SmartIslandHomeScreen(
    repository: SmartIslandSettingsRepository? = null,
    notificationRepository: INotificationRepository? = null,
    /** A section the island asked the app to open, such as the Utilities page's settings button; null when none. */
    openSection: String? = null,
    onOpenSectionHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val resolvedRepository = remember(repository, context) {
        repository ?: runCatching {
            SmartIslandRepositories.settingsRepository(context)
        }.getOrElse {
            SmartIslandSettingsRepository(context.applicationContext)
        }
    }
    val resolvedNotificationRepository = remember(notificationRepository, context) {
        notificationRepository ?: runCatching {
            SmartIslandRepositories.notificationRepository(context)
        }.getOrNull()
    }

    val settings by resolvedRepository.settings.collectAsStateWithLifecycle(initialValue = SmartIslandSettings.Default)
    val scope = rememberCoroutineScope()

    var showWelcomeDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!resolvedRepository.isWelcomeDialogShown()) {
            showWelcomeDialog = true
        }
    }

    var showTour by rememberSaveable { mutableStateOf(false) }

    if (showWelcomeDialog) {
        WelcomeDialog(
            onDismiss = {
                showWelcomeDialog = false
                scope.launch { resolvedRepository.setWelcomeDialogShown(true) }
            },
            onTakeTour = {
                showWelcomeDialog = false
                showTour = true
                scope.launch { resolvedRepository.setWelcomeDialogShown(true) }
            }
        )
    }

    if (showTour) {
        com.agupta07505.smartisland.ui.sections.NewUserTour(settings = settings, onFinish = { showTour = false })
    }

    var overlayGranted by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }
    var batteryIgnored by remember { mutableStateOf(false) }
    var bluetoothGranted by remember { mutableStateOf(false) }

    fun refreshBatteryOptimizationWithRetry() {
        scope.launch {
            val initialState = withContext(Dispatchers.IO) { isBatteryOptimizationIgnored(context) }
            batteryIgnored = initialState
            repeat(8) {
                delay(150L)
                val currentState = withContext(Dispatchers.IO) { isBatteryOptimizationIgnored(context) }
                batteryIgnored = currentState
                if (currentState != initialState) return@launch
            }
        }
    }

    fun refreshPermissionState() {
        scope.launch {
            val state = withContext(Dispatchers.IO) {
                PermissionSnapshot(
                    overlay = isAccessibilityServiceEnabled(context),
                    notifications = isNotificationListenerEnabled(context),
                    batteryIgnored = isBatteryOptimizationIgnored(context),
                    bluetooth = isBluetoothConnectPermissionGranted(context)
                )
            }
            overlayGranted = state.overlay
            notificationGranted = state.notifications
            batteryIgnored = state.batteryIgnored
            bluetoothGranted = state.bluetooth
            refreshBatteryOptimizationWithRetry()
        }
    }

    LaunchedEffect(context) {
        refreshPermissionState()
    }

    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            bluetoothGranted = granted

            if (granted) {
                SystemServiceRecovery.resetAccessibilityRefreshAttempt()
                scope.launch(Dispatchers.IO) { SystemServiceRecovery.requestRecovery(context) }
            }
        }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedTab by rememberSaveable { mutableStateOf(StudioTab.Studio) }
    var orientationTab by rememberSaveable { mutableStateOf(OrientationTab.Portrait) }
    var activeDesignMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var activeDetailSection by rememberSaveable { mutableStateOf<FeatureDetailSection?>(null) }
    var transitionDirection by rememberSaveable { mutableIntStateOf(1) }

    // Settings search: the query survives rotation; the focus state tells the chosen row to
    // scroll into view and flash once its screen is showing.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var homeSearchQuery by rememberSaveable { mutableStateOf("") }
    var voiceSearchTargetsSettings by remember { mutableStateOf(false) }
    val searchFocus = remember { SearchFocusState() }
    val configuration = LocalConfiguration.current
    var searchIndex by remember(context, configuration) {
        mutableStateOf<List<SettingsSearchEntry>>(emptyList())
    }
    LaunchedEffect(context, configuration) {
        searchIndex = withContext(Dispatchers.Default) {
            buildSettingsSearchIndex { id -> context.getString(id) }
        }
    }
    val searchResults = remember(searchIndex, searchQuery) {
        val byId = searchIndex.associateBy { it.item.id }
        SettingsSearch.search(searchIndex.map { it.item }, searchQuery).mapNotNull { byId[it.id] }
    }
    val homeSearchResults = remember(searchIndex, homeSearchQuery) {
        val byId = searchIndex.associateBy { it.item.id }
        SettingsSearch.search(searchIndex.map { it.item }, homeSearchQuery).mapNotNull { byId[it.id] }
    }
    val voiceIntent = remember(context) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.search_voice_prompt))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }
    val voiceRecognitionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spokenQuery = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (spokenQuery.isNotBlank()) {
            if (voiceSearchTargetsSettings) searchQuery = spokenQuery else homeSearchQuery = spokenQuery
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { voiceRecognitionLauncher.launch(voiceIntent) }
                .onFailure { Toast.makeText(context, R.string.search_voice_unavailable, Toast.LENGTH_SHORT).show() }
        } else {
            Toast.makeText(context, R.string.search_voice_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }
    val startVoiceSearch: (settingsSearch: Boolean) -> Unit = { settingsSearch ->
        voiceSearchTargetsSettings = settingsSearch
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching { voiceRecognitionLauncher.launch(voiceIntent) }
                .onFailure { Toast.makeText(context, R.string.search_voice_unavailable, Toast.LENGTH_SHORT).show() }
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // A jump stays armed for a few seconds, then is forgotten so the row never flashes again
    // when the user later opens the same screen by hand.
    LaunchedEffect(searchFocus.token) {
        if (searchFocus.key != null) {
            delay(SEARCH_FOCUS_ARMED_MS)
            searchFocus.clear()
        }
    }
    val onSearchPick: (SettingsSearchEntry) -> Unit = { entry ->
        homeSearchQuery = ""
        when (val target = entry.target) {
            is SearchTarget.Tab -> {
                transitionDirection = if (target.tab.ordinal > selectedTab.ordinal) 1 else -1
                selectedTab = target.tab
                target.landscape?.let {
                    orientationTab = if (it) OrientationTab.Landscape else OrientationTab.Portrait
                }
                // Already on the Settings tab: show the normal overview, where the item is.
                if (target.tab == StudioTab.Settings) searchQuery = ""
            }
            is SearchTarget.Detail -> {
                transitionDirection = 1
                activeDetailSection = target.section
            }
        }
        searchFocus.jumpTo(entry.rowKey)
    }

    val canEnable = overlayGranted && notificationGranted
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val pullRefreshThresholdPx = with(LocalDensity.current) { 86.dp.toPx() }
    var pullDistance by remember { mutableStateOf(0f) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    val runPullRefresh by rememberUpdatedState(newValue = {
        if (!isRefreshing) {
            isRefreshing = true
            pullDistance = 0f
            refreshVersion++
            refreshPermissionState()
            com.agupta07505.smartisland.util.IdleWeather.notifyRefreshed()
            scope.launch {
                delay(900L)
                isRefreshing = false
            }
        }
    })
    val pullRefreshConnection = remember(pullRefreshThresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (available.y > 0f && !isRefreshing) {
                    pullDistance = (pullDistance + available.y).coerceAtMost(pullRefreshThresholdPx * 1.4f)
                    if (pullDistance >= pullRefreshThresholdPx) runPullRefresh()
                } else if (available.y < 0f) {
                    pullDistance = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pullDistance = 0f
                return Velocity.Zero
            }
        }
    }

    fun selectStudioTab(tab: StudioTab) {
        if (tab == selectedTab) return
        transitionDirection = if (tab.ordinal > selectedTab.ordinal) 1 else -1
        selectedTab = tab
        if (tab != StudioTab.Position) activeDesignMenu = null
    }

    // The island's settings button: open the App Shortcuts configuration, then forget the request.
    LaunchedEffect(openSection) {
        if (openSection == com.agupta07505.smartisland.util.UtilityActions.SECTION_APP_SHORTCUTS) {
            transitionDirection = 1
            activeDetailSection = FeatureDetailSection.AppShortcuts
            onOpenSectionHandled()
        }
    }

    BackHandler(enabled = activeDetailSection != null) {
        transitionDirection = -1
        // The weather page is opened from the Idle widgets page, so back goes there; every other page goes home.
        activeDetailSection = if (activeDetailSection in IDLE_SETTINGS_PAGES) FeatureDetailSection.IdleWidgets else null
    }
    BackHandler(enabled = activeDetailSection == null && selectedTab == StudioTab.Position && activeDesignMenu != null) {
        activeDesignMenu = null
    }

    CompositionLocalProvider(LocalSearchFocus provides searchFocus) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (activeDetailSection == null) {
                StudioBottomNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        selectStudioTab(tab)
                    }
                )
            }
        }
    ) { scaffoldPadding ->
        AnimatedContent(
            targetState = activeDetailSection,
            modifier = Modifier.padding(scaffoldPadding),
            transitionSpec = {
                if (transitionDirection == 1) {
                    (slideInHorizontally(initialOffsetX = { it }) + fadeIn())
                        .togetherWith(slideOutHorizontally(targetOffsetX = { -it }) + fadeOut())
                } else {
                    (slideInHorizontally(initialOffsetX = { -it }) + fadeIn())
                        .togetherWith(slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
                }
            },
            label = "ScreenTransition"
        ) { detailSection ->
            Box(Modifier.fillMaxSize().nestedScroll(pullRefreshConnection)) {
            ThemedBackdrop()
            if (pullDistance > 0f || isRefreshing) {
                Row(
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .zIndex(1f)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isRefreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(if (isRefreshing) R.string.pull_to_refresh_running else R.string.pull_to_refresh_hint), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (detailSection == null) {
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                val horizontalPad = contentSidePadding(screenWidthDp)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                    .pointerInput(selectedTab) {
                        var dragDistance = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragDistance = 0f },
                            onHorizontalDrag = { _, amount -> dragDistance += amount },
                            onDragEnd = {
                                if (abs(dragDistance) >= swipeThresholdPx) {
                                    if (selectedTab == StudioTab.Demo) {
                                        // Demo owns its internal category gestures; keep them from changing root tabs.
                                    } else if (selectedTab == StudioTab.Position && activeDesignMenu != null) {
                                        val menus = listOf("position", "animation", "merge", "widgets", "events")
                                        val next = menus.indexOf(activeDesignMenu) + if (dragDistance < 0) 1 else -1
                                        activeDesignMenu = menus.getOrNull(next)
                                    } else {
                                        val nextIndex = (selectedTab.ordinal + if (dragDistance < 0) 1 else -1)
                                            .coerceIn(StudioTab.entries.indices)
                                        selectStudioTab(StudioTab.entries[nextIndex])
                                    }
                                }
                                dragDistance = 0f
                            }
                        )
                    }
                        .padding(
                            start = horizontalPad,
                            end = horizontalPad,
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                            bottom = 32.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StudioTopHeader(
                        isIslandEnabled = settings.enabled,
                        canEnable = canEnable,
                        onHealthClick = {
                            transitionDirection = 1
                            activeDetailSection = FeatureDetailSection.PermissionsCenter
                        }
                    )
                    SettingsSearchField(
                        query = homeSearchQuery,
                        onQueryChange = { homeSearchQuery = it },
                        placeholder = stringResource(R.string.home_search_hint),
                        onVoiceSearch = { startVoiceSearch(false) }
                    )
                    if (homeSearchQuery.isNotBlank()) {
                        SettingsSearchResults(query = homeSearchQuery, results = homeSearchResults, onPick = onSearchPick)
                    } else {
                    if (!(selectedTab == StudioTab.Position && activeDesignMenu != null)) {
                        StudioPageControls(
                            selectedTab = selectedTab,
                            onPrevious = { selectStudioTab(StudioTab.entries[(selectedTab.ordinal - 1).coerceAtLeast(0)]) },
                            onNext = { selectStudioTab(StudioTab.entries[(selectedTab.ordinal + 1).coerceAtMost(StudioTab.entries.lastIndex)]) }
                        )
                    }

                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            val movingForward = targetState.ordinal > initialState.ordinal
                            if (movingForward) {
                                (slideInHorizontally(tween(300)) { it / 6 } + fadeIn(tween(220)))
                                    .togetherWith(slideOutHorizontally(tween(240)) { -it / 10 } + fadeOut(tween(180)))
                            } else {
                                (slideInHorizontally(tween(300)) { -it / 6 } + fadeIn(tween(220)))
                                    .togetherWith(slideOutHorizontally(tween(240)) { it / 10 } + fadeOut(tween(180)))
                            }
                        },
                        label = "StudioTabTransition"
                    ) { tab ->
                    when (tab) {
                        StudioTab.Studio -> {
                            HomeMinimalMenu(
                                enabled = settings.enabled,
                                canEnable = canEnable,
                                overlayGranted = overlayGranted,
                                notificationGranted = notificationGranted,
                                batteryIgnored = batteryIgnored,
                                onEnabledChange = { turnOn ->
                                    if (turnOn) {
                                        scope.launch(Dispatchers.IO) { SystemServiceRecovery.requestRecovery(context) }
                                    }
                                    scope.launch { resolvedRepository.setEnabled(turnOn) }
                                },
                                onOpenLayout = { activeDesignMenu = null; selectStudioTab(StudioTab.Position) },
                                onOpenDemo = { selectStudioTab(StudioTab.Demo) },
                                onOpenSettings = { selectStudioTab(StudioTab.Settings) },
                                onOpenHistory = {
                                    transitionDirection = 1
                                    activeDetailSection = FeatureDetailSection.NotificationHistory
                                },
                                onOpenGestures = {
                                    transitionDirection = 1
                                    activeDetailSection = FeatureDetailSection.GesturesGuide
                                },
                                onOpenPermissions = {
                                    transitionDirection = 1
                                    activeDetailSection = FeatureDetailSection.PermissionsCenter
                                }
                            )
                        }

                        StudioTab.Position -> {
                            OrientationTabs(
                                selected = orientationTab,
                                onSelected = { orientationTab = it }
                            )
                            AnimatedContent(
                                targetState = activeDesignMenu,
                                transitionSpec = {
                                    if (targetState != null) {
                                        (slideInHorizontally(tween(320)) { it / 5 } + fadeIn(tween(220)) + scaleIn(tween(320), initialScale = 0.98f))
                                            .togetherWith(slideOutHorizontally(tween(220)) { -it / 12 } + fadeOut(tween(180)))
                                    } else {
                                        (slideInHorizontally(tween(320)) { -it / 5 } + fadeIn(tween(220)) + scaleIn(tween(320), initialScale = 0.98f))
                                            .togetherWith(slideOutHorizontally(tween(220)) { it / 12 } + fadeOut(tween(180)))
                                    }
                                },
                                label = "DesignSubmenuTransition"
                            ) { designMenu ->
                            if (designMenu == null) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        HomeMenuTile(Modifier.weight(1f), stringResource(R.string.sec_positions), stringResource(R.string.sec_positions_desc), Icons.Rounded.Tune, BadgeGreen) { activeDesignMenu = "position" }
                                        HomeMenuTile(Modifier.weight(1f), stringResource(R.string.design_animation_title), stringResource(R.string.anim_speed_desc), Icons.Rounded.Timer, BadgeAmber) { activeDesignMenu = "animation" }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        HomeMenuTile(Modifier.weight(1f), stringResource(R.string.design_merge_title), stringResource(R.string.merge_style_desc), Icons.Rounded.Palette, BadgeViolet) { activeDesignMenu = "merge" }
                                        HomeMenuTile(Modifier.weight(1f), stringResource(R.string.idle_widgets_title), stringResource(R.string.card_idle_widgets_subtitle), Icons.Rounded.Widgets, BadgeSky) { activeDesignMenu = "widgets" }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        HomeMenuTile(Modifier.weight(1f), stringResource(R.string.design_events_title), stringResource(R.string.design_events_summary), Icons.Rounded.Notifications, BadgePink) { activeDesignMenu = "events" }
                                        HomeMenuTile(Modifier.weight(1f), stringResource(R.string.detail_title_backup_restore), stringResource(R.string.card_backup_restore_desc), Icons.Rounded.SettingsBackupRestore, BadgeIndigo) {
                                            transitionDirection = 1
                                            activeDetailSection = FeatureDetailSection.BackupRestore
                                        }
                                    }
                                }
                            } else {
                                val pageTitle = when (designMenu) {
                                    "position" -> stringResource(R.string.sec_positions)
                                    "animation" -> stringResource(R.string.design_animation_title)
                                    "merge" -> stringResource(R.string.design_merge_title)
                                    "widgets" -> stringResource(R.string.idle_widgets_title)
                                    else -> stringResource(R.string.design_events_title)
                                }
                                OutlinedButton(onClick = { activeDesignMenu = null }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.design_back))
                                }
                                Text(pageTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                when (designMenu) {
                                    "position" -> if (orientationTab == OrientationTab.Landscape) {
                                        LandscapeSection(settings = settings, repository = resolvedRepository)
                                    } else {
                                        PositionsSection(
                                            settings = settings,
                                            repository = resolvedRepository,
                                            onNavigateToBackup = {
                                                activeDesignMenu = null
                                                transitionDirection = 1
                                                activeDetailSection = FeatureDetailSection.BackupRestore
                                            }
                                        )
                                    }
                                    "animation" -> AnimationSpeedSection(settings, resolvedRepository, orientationTab == OrientationTab.Landscape)
                                    "merge" -> MergeStyleSection(settings, resolvedRepository, orientationTab == OrientationTab.Landscape)
                                    "widgets" -> IdleWidgetsSection(settings, resolvedRepository, onOpenSettings = {
                                        transitionDirection = 1
                                        activeDetailSection = FeatureDetailSection.IdleWidgets
                                    })
                                    "events" -> EventTogglesSection(
                                        settings = settings,
                                        repository = resolvedRepository,
                                        landscape = orientationTab == OrientationTab.Landscape,
                                        onOpenFaceIdSettings = {
                                            transitionDirection = 1
                                            activeDetailSection = FeatureDetailSection.FaceIdSettings
                                        }
                                    )
                                }
                            }
                            }
                        }

                        StudioTab.Demo -> {
                            DemoSection(
                                settings = settings,
                                notificationRepository = resolvedNotificationRepository
                            )
                        }

                        StudioTab.Settings -> {
                            SettingsOverviewSection(
                                settings = settings,
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                onVoiceSearch = { startVoiceSearch(true) },
                                refreshVersion = refreshVersion,
                                searchResults = searchResults,
                                onSearchPick = onSearchPick,
                                overlayGranted = overlayGranted,
                                notificationGranted = notificationGranted,
                                batteryIgnored = batteryIgnored,
                                onThemeModeChange = { mode ->
                                    scope.launch { resolvedRepository.setThemeMode(mode) }
                                },
                                onLiquidGlassChange = { on -> scope.launch { resolvedRepository.setLiquidGlassTheme(on) } },
                                onMaterialYouChange = { on -> scope.launch { resolvedRepository.setMaterialYou(on) } },
                                onAccentChange = { argb -> scope.launch { resolvedRepository.setAccentColor(argb) } },
                                onIslandFontChange = { font ->
                                    scope.launch { resolvedRepository.setIslandFont(font) }
                                },
                                onFontMixChange = { enabled, light, regular, medium ->
                                    scope.launch { resolvedRepository.setFontMix(enabled, light, regular, medium) }
                                },
                                onNavigateTo = { section ->
                                    transitionDirection = 1
                                    activeDetailSection = section
                                }
                            )
                        }
                    }
                    }
                    }

                }
            } else {
                DetailScreenHost(
                    section = detailSection,
                    settings = settings,
                    repository = resolvedRepository,
                    refreshVersion = refreshVersion,
                    overlayGranted = overlayGranted,
                    notificationGranted = notificationGranted,
                    batteryIgnored = batteryIgnored,
                    bluetoothGranted = bluetoothGranted,
                    onBack = {
                        transitionDirection = -1
                        activeDetailSection = if (detailSection in IDLE_SETTINGS_PAGES) FeatureDetailSection.IdleWidgets else null
                    },
                    onBluetoothClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (!bluetoothGranted) {
                                bluetoothPermissionLauncher.launch(
                                    Manifest.permission.BLUETOOTH_CONNECT
                                )
                            } else {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        }
                    },
                    onRefreshPermissions = {
                        overlayGranted = isAccessibilityServiceEnabled(context)
                        notificationGranted = isNotificationListenerEnabled(context)
                        refreshBatteryOptimizationWithRetry()
                        bluetoothGranted = isBluetoothConnectPermissionGranted(context)
                    },
                    onNavigateTo = { section ->
                        transitionDirection = 1
                        activeDetailSection = section
                    }
                )
            }
            }
        }
    }
    }
}

private const val MAX_CONTENT_WIDTH_DP = 640

private fun contentSidePadding(screenWidthDp: Int): androidx.compose.ui.unit.Dp {
    val base = when {
        screenWidthDp < 360 -> 14
        screenWidthDp > 600 -> 28
        else -> 20
    }
    val centred = (screenWidthDp - MAX_CONTENT_WIDTH_DP) / 2
    return maxOf(base, centred).dp
}

@Composable
private fun StudioTopHeader(
    isIslandEnabled: Boolean,
    canEnable: Boolean,
    onHealthClick: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(context) {
        runCatchingLogged("HeaderSection", "Failed to get application icon") {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 144
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 144
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = "Logotipo de Ryx Isla",
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            brush = Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("SI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.app_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val statusColor = when {
            !canEnable -> Color(0xFFE88C25)
            isIslandEnabled -> Color(0xFF0F9F6E)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        }
        val statusText = when {
            !canEnable -> stringResource(R.string.status_setup_needed)
            isIslandEnabled -> stringResource(R.string.status_active)
            else -> stringResource(R.string.status_ready)
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(statusColor.copy(alpha = 0.12f))
                .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                .clickable(onClick = onHealthClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HomeMinimalMenu(
    enabled: Boolean,
    canEnable: Boolean,
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    batteryIgnored: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenLayout: () -> Unit,
    onOpenDemo: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGestures: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeIslandLivePreview()

        Text(
            text = stringResource(R.string.home_menu_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 3.dp, top = 2.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeMenuTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_layout_title),
                    subtitle = stringResource(R.string.home_layout_summary),
                    icon = Icons.Rounded.Tune,
                    iconColor = BadgeGreen,
                    onClick = onOpenLayout
                )
                HomeMenuTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_preview_title),
                    subtitle = stringResource(R.string.home_preview_summary),
                    icon = Icons.Rounded.Science,
                    iconColor = BadgeViolet,
                    onClick = onOpenDemo
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeMenuTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_history_title),
                    subtitle = stringResource(R.string.home_history_summary),
                    icon = Icons.Rounded.History,
                    iconColor = BadgeIndigo,
                    onClick = onOpenHistory
                )
                HomeMenuTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_gestures_title),
                    subtitle = stringResource(R.string.home_gestures_summary),
                    icon = Icons.Rounded.Gesture,
                    iconColor = BadgePink,
                    onClick = onOpenGestures
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeMenuTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_settings_title),
                    subtitle = stringResource(R.string.home_settings_summary),
                    icon = Icons.Rounded.Settings,
                    iconColor = MaterialTheme.colorScheme.primary,
                    onClick = onOpenSettings
                )
                HomeMenuTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_permissions_title),
                    subtitle = stringResource(if (canEnable) R.string.home_permissions_ready else R.string.home_permissions_summary),
                    icon = Icons.Rounded.Shield,
                    iconColor = if (canEnable) BadgeGreen else Color(0xFFE89531),
                    onClick = onOpenPermissions
                )
            }
        }

        MasterPowerCard(
            enabled = enabled,
            canEnable = canEnable,
            overlayGranted = overlayGranted,
            notificationGranted = notificationGranted,
            batteryIgnored = batteryIgnored,
            onCheckedChange = onEnabledChange,
            onSetupPermissionsClick = onOpenPermissions
        )
    }
}

@Composable
private fun HomeIslandLivePreview() {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.home_live_preview_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.home_live_preview_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(164.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF111512))
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.TopCenter
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("9:41", color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.width(44.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)))
                    Text("5G  100%", color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.labelSmall)
                }

                Row(
                    modifier = Modifier
                        .padding(top = 38.dp)
                        .fillMaxWidth(if (expanded) 0.88f else 0.52f)
                        .height(if (expanded) 78.dp else 42.dp)
                        .clip(RoundedCornerShape(if (expanded) 25.dp else 24.dp))
                        .background(Color(0xFF050606))
                        .animateContentSize()
                        .padding(horizontal = if (expanded) 12.dp else 10.dp, vertical = if (expanded) 10.dp else 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (expanded) 38.dp else 25.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF28764B), Color(0xFF75A941)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Notifications,
                            contentDescription = null,
                            tint = Color(0xFFFFD65A),
                            modifier = Modifier.size(if (expanded) 21.dp else 15.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            if (expanded) stringResource(R.string.home_preview_chat_name) else stringResource(R.string.mode_notification),
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (expanded) stringResource(R.string.home_preview_chat_message) else stringResource(R.string.home_preview_chat_name),
                            color = Color.White,
                            style = if (expanded) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (expanded) {
                        Text(
                            stringResource(R.string.home_preview_reply),
                            color = Color(0xFFFFD65A),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF8CD653)))
                    }
                }

                Text(
                    text = stringResource(if (expanded) R.string.home_preview_expanded_label else R.string.home_preview_collapsed_label),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 11.dp),
                    color = Color.White.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun HomeMenuTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        iconColor.copy(alpha = 0.20f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .glassEdge(shape, glass = LocalLiquidGlass.current, dark = LocalGlassDark.current)
            .border(1.dp, iconColor.copy(alpha = 0.24f), shape)
            .bounceClick(onClick)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(iconColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(30.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MasterPowerCard(
    enabled: Boolean,
    canEnable: Boolean,
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    batteryIgnored: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onSetupPermissionsClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF15583D), Color(0xFF267B4F), Color(0xFF719A3E))
                    )
                )
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFFD65A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.FlashOn, contentDescription = null, tint = Color(0xFF214E32), modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.master_switch_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    text = if (canEnable) {
                        if (enabled) stringResource(R.string.master_switch_active_desc)
                        else stringResource(R.string.master_switch_ready_desc)
                    } else stringResource(R.string.master_switch_missing_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.86f)
                )
            }
            Switch(checked = enabled, enabled = canEnable || enabled, onCheckedChange = onCheckedChange)
        }

        if (!canEnable) {
            // What is still missing, at a glance: one chip per required permission.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SetupChip(stringResource(R.string.permission_accessibility_short), overlayGranted, onSetupPermissionsClick)
                SetupChip(stringResource(R.string.permission_notifications_short), notificationGranted, onSetupPermissionsClick)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSetupPermissionsClick)
                    .background(Color(0xFFE88C25).copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = Color(0xFFE88C25),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.btn_grant_required_permissions),
                        color = Color(0xFFE88C25),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(selected: String, onSelected: (String) -> Unit) {
    val options = listOf(
        "system" to stringResource(R.string.theme_mode_system),
        "light" to stringResource(R.string.theme_mode_light),
        "dark" to stringResource(R.string.theme_mode_dark)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (id, label) ->
            val isSelected = selected == id
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelected(id) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SetupChip(label: String, granted: Boolean, onClick: () -> Unit) {
    val color = if (granted) Color(0xFF0F9F6E) else Color(0xFFE88C25)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (granted) "\u2713" else "!",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsOverviewSection(
    settings: SmartIslandSettings,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    refreshVersion: Int,
    searchResults: List<SettingsSearchEntry>,
    onSearchPick: (SettingsSearchEntry) -> Unit,
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    batteryIgnored: Boolean,
    onThemeModeChange: (String) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onMaterialYouChange: (Boolean) -> Unit,
    onAccentChange: (Long) -> Unit,
    onIslandFontChange: (String) -> Unit,
    onFontMixChange: (Boolean, String, String, String) -> Unit,
    onNavigateTo: (FeatureDetailSection) -> Unit
) {
    val canEnable = overlayGranted && notificationGranted

    val SystemGroup: @Composable () -> Unit = {
        SettingsCategoryGroup(title = stringResource(R.string.category_system_core)) {
            SettingsBentoGrid(
                entries = listOf(
                    SettingsBentoEntry(
                        stringResource(R.string.card_permissions_setup_title),
                        stringResource(R.string.settings_permissions_summary),
                        Icons.Rounded.Shield,
                        if (canEnable) BadgeGreen else BadgeAmber,
                        stringResource(if (canEnable) R.string.status_active else R.string.status_action_required)
                    ) { onNavigateTo(FeatureDetailSection.PermissionsCenter) },
                    SettingsBentoEntry(
                        stringResource(R.string.card_backup_restore_title),
                        stringResource(R.string.settings_backup_summary),
                        Icons.Rounded.SettingsBackupRestore,
                        BadgeBlue
                    ) { onNavigateTo(FeatureDetailSection.BackupRestore) },
                    SettingsBentoEntry(
                        stringResource(R.string.card_updates_downloads_title),
                        stringResource(R.string.settings_updates_summary),
                        Icons.Rounded.Download,
                        BadgeTeal
                    ) { onNavigateTo(FeatureDetailSection.UpdatesAndDownloads) },
                    SettingsBentoEntry(
                        stringResource(R.string.card_support_requests_title),
                        stringResource(R.string.settings_support_summary),
                        Icons.Rounded.People,
                        BadgePink
                    ) { onNavigateTo(FeatureDetailSection.SupportCommunity) },
                    SettingsBentoEntry(
                        stringResource(R.string.card_developer_options_title),
                        stringResource(R.string.settings_developer_summary),
                        Icons.Rounded.Code,
                        BadgeSlate,
                        stringResource(R.string.status_recording).takeIf { settings.recordLogs }
                    ) { onNavigateTo(FeatureDetailSection.DeveloperOptions) },
                    SettingsBentoEntry(
                        stringResource(R.string.card_about_title),
                        stringResource(R.string.card_about_subtitle),
                        Icons.Rounded.Info,
                        BadgeSlate
                    ) { onNavigateTo(FeatureDetailSection.About) }
                )
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSearchField(query = searchQuery, onQueryChange = onSearchQueryChange, onVoiceSearch = onVoiceSearch)

        if (searchQuery.isNotBlank()) {
            SettingsSearchResults(query = searchQuery, results = searchResults, onPick = onSearchPick)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.settings_hub_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_hub_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FeatureStudioNavigationCard(
                title = stringResource(R.string.game_mode_title),
                subtitle = stringResource(R.string.game_mode_summary),
                icon = Icons.Rounded.SportsEsports,
                iconColor = BadgeGreen,
                onClick = { onNavigateTo(FeatureDetailSection.GameMode) }
            )
            // Appearance: the app's own theme, then the island's colours. (Position, size, shadow and the
            // animation choices are on the Layout tab.)
            SettingsCategoryGroup(title = stringResource(R.string.category_appearance_controls)) {
                ThemeModeSelector(selected = settings.themeMode, onSelected = onThemeModeChange)

                com.agupta07505.smartisland.ui.sections.AppThemeOptions(
                    settings = settings,
                    onLiquidGlassChange = onLiquidGlassChange,
                    onMaterialYouChange = onMaterialYouChange,
                    onAccentChange = onAccentChange
                )

                FeatureStudioNavigationCard(
                    title = stringResource(R.string.card_color_studio_title),
                    subtitle = stringResource(R.string.settings_colours_summary),
                    icon = Icons.Rounded.Palette,
                    iconColor = BadgeViolet,
                    statusText = stringResource(R.string.card_color_studio_status, (settings.opacity * 100).toInt()),
                    onClick = { onNavigateTo(FeatureDetailSection.ColorStudio) }
                )

                CollapsibleCard(
                    title = stringResource(R.string.island_font_title),
                    stateKey = "settings_appearance_fonts",
                    summary = stringResource(R.string.settings_fonts_summary),
                    icon = Icons.Rounded.FontDownload,
                    initiallyExpanded = false
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IslandFontPicker(selected = settings.islandFont, onSelected = onIslandFontChange)
                        com.agupta07505.smartisland.ui.sections.IslandFontMixSection(settings = settings, onChange = onFontMixChange)
                    }
                }
            }

            // Compact, two-column Ryx feature grid: quick routes into the existing settings screens.
            SettingsCategoryGroup(title = stringResource(R.string.category_behaviors_launcher)) {
                SettingsBentoGrid(
                    entries = listOf(
                        SettingsBentoEntry(stringResource(R.string.card_notifications_privacy_title), stringResource(R.string.settings_notifications_summary), Icons.Rounded.Notifications, BadgeGreen, stringResource(R.string.card_notifications_privacy_status_lock).takeIf { settings.showOnLockScreen }) { onNavigateTo(FeatureDetailSection.NotificationRules) },
                        SettingsBentoEntry(stringResource(R.string.card_notification_history_title), stringResource(R.string.settings_history_summary), Icons.Rounded.History, BadgeIndigo, stringResource(R.string.card_notification_history_status_active).takeIf { settings.enableNotificationHistory }) { onNavigateTo(FeatureDetailSection.NotificationHistory) },
                        SettingsBentoEntry(stringResource(R.string.card_app_shortcuts_title), stringResource(R.string.settings_shortcuts_summary), Icons.Rounded.Apps, BadgeTeal, stringResource(R.string.card_app_shortcuts_status, settings.shortcutPackages.size).takeIf { settings.enableAppShortcuts }) { onNavigateTo(FeatureDetailSection.AppShortcuts) },
                        SettingsBentoEntry(stringResource(R.string.card_gestures_guide_title), stringResource(R.string.settings_gestures_summary), Icons.Rounded.Gesture, BadgePink) { onNavigateTo(FeatureDetailSection.GesturesGuide) },
                        SettingsBentoEntry(stringResource(R.string.idle_widgets_title), stringResource(R.string.card_idle_widgets_subtitle), Icons.Rounded.Widgets, BadgeAmber, stringResource(R.string.status_active).takeIf { settings.idleWidgetsEnabled || settings.idleWidgetsEnabledLandscape }) { onNavigateTo(FeatureDetailSection.IdleWidgets) },
                        SettingsBentoEntry(stringResource(R.string.card_timing_title), stringResource(R.string.card_timing_subtitle), Icons.Rounded.Timer, BadgeSky) { onNavigateTo(FeatureDetailSection.Timing) }
                    )
                )
            }

            // System: permissions, backup, developer options, about.
            SystemGroup()
        }
    }
}

private data class SettingsBentoEntry(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val tint: Color,
    val status: String? = null,
    val onClick: () -> Unit
)

@Composable
private fun SettingsBentoGrid(entries: List<SettingsBentoEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.chunked(2).forEachIndexed { rowIndex, rowEntries ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowEntries.forEachIndexed { columnIndex, entry ->
                    SettingsBentoTile(
                        entry = entry,
                        index = rowIndex * 2 + columnIndex,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsBentoTile(
    entry: SettingsBentoEntry,
    index: Int,
    modifier: Modifier = Modifier
) {
    var visible by remember(entry.title) { mutableStateOf(false) }
    LaunchedEffect(entry.title) {
        delay((index * 42L).coerceAtMost(210L))
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(240)) +
            slideInVertically(tween(300)) { it / 12 } +
            scaleIn(tween(300), initialScale = 0.97f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            entry.tint.copy(alpha = 0.10f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(entry.tint.copy(alpha = 0.34f), entry.tint.copy(alpha = 0.10f))
                    ),
                    RoundedCornerShape(22.dp)
                )
                .bounceClick(entry.onClick)
                .animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(entry.tint.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(entry.icon, contentDescription = null, tint = entry.tint, modifier = Modifier.size(21.dp))
                }
                entry.status?.let {
                    Text(
                        it,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(entry.tint.copy(alpha = 0.13f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        color = entry.tint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GameModeCard(
    hideIdleIsland: Boolean,
    showNotifications: Boolean,
    playSound: Boolean,
    onHideIdleIslandChange: (Boolean) -> Unit,
    onShowNotificationsChange: (Boolean) -> Unit,
    onPlaySoundChange: (Boolean) -> Unit,
    islandOverrides: Map<String, Boolean>,
    soundOverrides: Map<String, Boolean>,
    onIslandOverridesChange: (Map<String, Boolean>) -> Unit,
    onSoundOverridesChange: (Map<String, Boolean>) -> Unit,
    additionalGamePackages: Set<String>,
    onAdditionalGamePackagesChange: (Set<String>) -> Unit,
    refreshVersion: Int
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var manualPickerExpanded by rememberSaveable { mutableStateOf(false) }
    var manualGameSearch by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    var installedApps by remember(context) { mutableStateOf<List<Pair<com.agupta07505.smartisland.data.LaunchableApp, Boolean>>>(emptyList()) }
    LaunchedEffect(context, refreshVersion) {
        installedApps = withContext(Dispatchers.IO) {
            runCatching {
                com.agupta07505.smartisland.data.AppShortcutProvider.installedApps(context, includeClones = true)
                    .mapNotNull { app ->
                        val pkg = com.agupta07505.smartisland.data.AppIdentity.parse(app.packageName).packageName
                        val isSystemGame = runCatching {
                            context.packageManager.getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                        }.getOrDefault(false)
                        com.agupta07505.smartisland.data.LaunchableApp(pkg, app.label) to isSystemGame
                    }
                    .distinctBy { it.first.packageName }
                    .sortedBy { it.first.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow))
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f))
            .glassEdge(RoundedCornerShape(22.dp), glass = LocalLiquidGlass.current, dark = LocalGlassDark.current)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.game_mode_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.game_mode_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = hideIdleIsland, onCheckedChange = onHideIdleIslandChange)
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (expanded) R.string.game_mode_less else R.string.game_mode_settings))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GameModeOption(stringResource(R.string.game_mode_show_notifications), stringResource(R.string.game_mode_show_notifications_summary), showNotifications, onShowNotificationsChange)
                GameModeOption(stringResource(R.string.game_mode_play_sound), stringResource(R.string.game_mode_play_sound_summary), playSound, onPlaySoundChange)
                Text(
                    stringResource(R.string.game_mode_installed_games),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                )
                val games = installedApps.filter { (app, isSystemGame) -> isSystemGame || app.packageName in additionalGamePackages }
                if (games.isEmpty()) {
                    Text(
                        stringResource(R.string.game_mode_no_games),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                } else {
                    games.forEach { (game, isSystemGame) ->
                        GameAppSettings(
                            game = game,
                            islandEnabled = islandOverrides[game.packageName] ?: showNotifications,
                            soundEnabled = soundOverrides[game.packageName] ?: playSound,
                            onIslandChange = { enabled -> onIslandOverridesChange(islandOverrides + (game.packageName to enabled)) },
                            onSoundChange = { enabled -> onSoundOverridesChange(soundOverrides + (game.packageName to enabled)) },
                            onReset = {
                                onIslandOverridesChange(islandOverrides - game.packageName)
                                onSoundOverridesChange(soundOverrides - game.packageName)
                            },
                            hasOverrides = game.packageName in islandOverrides || game.packageName in soundOverrides,
                            manuallyAdded = !isSystemGame && game.packageName in additionalGamePackages,
                            onRemoveManualGame = { onAdditionalGamePackagesChange(additionalGamePackages - game.packageName) }
                        )
                    }
                }
                TextButton(onClick = { manualPickerExpanded = !manualPickerExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (manualPickerExpanded) R.string.game_mode_hide_manual_picker else R.string.game_mode_add_manual_games))
                }
                AnimatedVisibility(visible = manualPickerExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.game_mode_manual_picker_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.material3.OutlinedTextField(
                            value = manualGameSearch,
                            onValueChange = { manualGameSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.game_mode_search_apps)) }
                        )
                        val normalizedQuery = manualGameSearch.trim()
                        val appCandidates = installedApps.filter { (app, _) ->
                            normalizedQuery.isBlank() || app.label.contains(normalizedQuery, ignoreCase = true) ||
                                app.packageName.contains(normalizedQuery, ignoreCase = true)
                        }
                        if (appCandidates.isEmpty()) {
                            Text(stringResource(R.string.game_mode_no_matching_apps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        appCandidates.forEach { (app, isSystemGame) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                GameAppIcon(packageName = app.packageName, label = app.label)
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when {
                                    app.packageName in additionalGamePackages -> TextButton(onClick = { onAdditionalGamePackagesChange(additionalGamePackages - app.packageName) }) {
                                        Text(stringResource(R.string.game_mode_remove_game))
                                    }
                                    isSystemGame -> Text(stringResource(R.string.game_mode_detected_game), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    else -> TextButton(onClick = { onAdditionalGamePackagesChange(additionalGamePackages + app.packageName) }) {
                                        Text(stringResource(R.string.game_mode_add_game))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameAppSettings(
    game: com.agupta07505.smartisland.data.LaunchableApp,
    islandEnabled: Boolean,
    soundEnabled: Boolean,
    onIslandChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    hasOverrides: Boolean,
    manuallyAdded: Boolean,
    onRemoveManualGame: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameAppIcon(packageName = game.packageName, label = game.label)
            Column(Modifier.weight(1f)) {
                Text(game.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(game.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasOverrides) TextButton(onClick = onReset) { Text(stringResource(R.string.game_mode_reset)) }
            if (manuallyAdded) TextButton(onClick = onRemoveManualGame) { Text(stringResource(R.string.game_mode_remove_game)) }
        }
        GameModeOption(stringResource(R.string.game_mode_per_game_island), stringResource(R.string.game_mode_per_game_island_summary), islandEnabled, onIslandChange)
        GameModeOption(stringResource(R.string.game_mode_per_game_sound), stringResource(R.string.game_mode_per_game_sound_summary), soundEnabled, onSoundChange)
    }
}

@Composable
private fun GameAppIcon(packageName: String, label: String) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(packageName, context) {
        icon = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = com.agupta07505.smartisland.data.AppShortcutProvider.icon(context, packageName)
                    ?: return@runCatching null
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier.padding(end = 10.dp).size(42.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(bitmap = icon!!, contentDescription = label, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Apps, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GameModeOption(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsCategoryGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow))
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.055f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.46f)
                    )
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.padding(start = 3.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 15.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )
        }
        content()
    }
}

private val BadgeIndigo = Color(0xFF6366F1)
private val BadgeTeal = Color(0xFF14B8A6)
private val BadgePink = Color(0xFFEC4899)
private val BadgeAmber = Color(0xFFF59E0B)
private val BadgeSky = Color(0xFF0EA5E9)
private val BadgeViolet = Color(0xFF8B5CF6)
private val BadgeBlue = Color(0xFF3B82F6)
private val BadgeSlate = Color(0xFF64748B)
private val BadgeGreen = Color(0xFF16A34A)

@Composable
private fun FeatureStudioNavigationCard(
    title: String,
    icon: ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    statusText: String? = null,
    statusColor: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .glassEdge(RoundedCornerShape(18.dp), glass = LocalLiquidGlass.current, dark = LocalGlassDark.current)
            .bounceClick(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(iconColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (statusText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun StudioPageControls(
    selectedTab: StudioTab,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onPrevious, enabled = selectedTab != StudioTab.entries.first()) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.studio_page_previous),
                tint = if (selectedTab == StudioTab.entries.first()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.primary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioTab.entries.forEach { tab ->
                Box(
                    modifier = Modifier
                        .size(width = if (tab == selectedTab) 22.dp else 7.dp, height = 7.dp)
                        .clip(CircleShape)
                        .background(if (tab == selectedTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        IconButton(onClick = onNext, enabled = selectedTab != StudioTab.entries.last()) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.studio_page_next),
                tint = if (selectedTab == StudioTab.entries.last()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StudioBottomNavigationBar(
    selectedTab: StudioTab,
    onTabSelected: (StudioTab) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        NavigationBarItem(
            selected = selectedTab == StudioTab.Studio,
            onClick = { onTabSelected(StudioTab.Studio) },
            icon = { Icon(Icons.Rounded.FlashOn, contentDescription = stringResource(R.string.tab_studio)) },
            label = { Text(stringResource(R.string.tab_studio), fontWeight = FontWeight.SemiBold) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary
            )
        )
        NavigationBarItem(
            selected = selectedTab == StudioTab.Position,
            onClick = { onTabSelected(StudioTab.Position) },
            icon = { Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.tab_position)) },
            label = { Text(stringResource(R.string.tab_position), fontWeight = FontWeight.SemiBold) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary
            )
        )
        NavigationBarItem(
            selected = selectedTab == StudioTab.Demo,
            onClick = { onTabSelected(StudioTab.Demo) },
            icon = { Icon(Icons.Rounded.Science, contentDescription = stringResource(R.string.tab_demo)) },
            label = { Text(stringResource(R.string.tab_demo), fontWeight = FontWeight.SemiBold) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary
            )
        )
        NavigationBarItem(
            selected = selectedTab == StudioTab.Settings,
            onClick = { onTabSelected(StudioTab.Settings) },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tab_settings)) },
            label = { Text(stringResource(R.string.tab_settings), fontWeight = FontWeight.SemiBold) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun DetailScreenHost(
    section: FeatureDetailSection,
    settings: SmartIslandSettings,
    repository: SmartIslandSettingsRepository,
    refreshVersion: Int,
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    batteryIgnored: Boolean,
    bluetoothGranted: Boolean,
    onBack: () -> Unit,
    onBluetoothClick: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onNavigateTo: (FeatureDetailSection) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isScrollableParent = section != FeatureDetailSection.NotificationHistory
    val scrollModifier = if (isScrollableParent) Modifier.verticalScroll(rememberScrollState()) else Modifier
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val horizontalPad = contentSidePadding(screenWidthDp)
    val detailSwipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(scrollModifier)
            .pointerInput(section) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onHorizontalDrag = { _, amount -> dragDistance += amount },
                    onDragEnd = {
                        if (dragDistance >= detailSwipeThresholdPx) onBack()
                    }
                )
            }
            .padding(
                start = horizontalPad,
                end = horizontalPad,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                bottom = if (isScrollableParent) 28.dp else 12.dp
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f)
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f), RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val sectionIcon = when (section) {
                FeatureDetailSection.GameMode -> Icons.Rounded.SportsEsports
                FeatureDetailSection.NotificationRules -> Icons.Rounded.Notifications
                FeatureDetailSection.AppShortcuts -> Icons.Rounded.Apps
                FeatureDetailSection.NotificationHistory -> Icons.Rounded.History
                FeatureDetailSection.ColorStudio -> Icons.Rounded.Palette
                FeatureDetailSection.GesturesGuide -> Icons.Rounded.Gesture
                FeatureDetailSection.PermissionsCenter -> Icons.Rounded.Shield
                FeatureDetailSection.BackupRestore -> Icons.Rounded.SettingsBackupRestore
                FeatureDetailSection.Diagnostics -> Icons.Rounded.BugReport
                FeatureDetailSection.Timing -> Icons.Rounded.Timer
                FeatureDetailSection.IdleWidgets -> Icons.Rounded.Widgets
                FeatureDetailSection.About -> Icons.Rounded.Info
                FeatureDetailSection.UpdatesAndDownloads -> Icons.Rounded.Download
                FeatureDetailSection.SupportCommunity -> Icons.Rounded.People
                FeatureDetailSection.DeveloperOptions -> Icons.Rounded.Code
                FeatureDetailSection.FaceIdSettings -> Icons.Rounded.Shield
                FeatureDetailSection.WeatherSettings -> Icons.Rounded.Science
                FeatureDetailSection.NetSpeedSettings -> Icons.Rounded.FlashOn
                FeatureDetailSection.CalendarSettings -> Icons.Rounded.History
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(sectionIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            val title = when (section) {
                FeatureDetailSection.GameMode -> stringResource(R.string.game_mode_title)
                FeatureDetailSection.NotificationRules -> stringResource(R.string.detail_title_notifications_privacy)
                FeatureDetailSection.AppShortcuts -> stringResource(R.string.detail_title_app_shortcuts)
                FeatureDetailSection.NotificationHistory -> stringResource(R.string.detail_title_notification_history)
                FeatureDetailSection.ColorStudio -> stringResource(R.string.detail_title_color_studio)
                FeatureDetailSection.GesturesGuide -> stringResource(R.string.detail_title_gestures_guide)
                FeatureDetailSection.PermissionsCenter -> stringResource(R.string.detail_title_permissions_center)
                FeatureDetailSection.BackupRestore -> stringResource(R.string.detail_title_backup_restore)
                FeatureDetailSection.Diagnostics -> "Diagnostic log"
                FeatureDetailSection.Timing -> stringResource(R.string.detail_title_timing)
                FeatureDetailSection.IdleWidgets -> stringResource(R.string.idle_widgets_title)
                FeatureDetailSection.About -> stringResource(R.string.detail_title_about)
                FeatureDetailSection.UpdatesAndDownloads -> stringResource(R.string.detail_title_updates_downloads)
                FeatureDetailSection.SupportCommunity -> stringResource(R.string.detail_title_support_community)
                FeatureDetailSection.DeveloperOptions -> stringResource(R.string.detail_title_developer_options)
                FeatureDetailSection.FaceIdSettings -> "Face ID settings"
                FeatureDetailSection.WeatherSettings -> stringResource(R.string.weather_cfg_title)
                FeatureDetailSection.NetSpeedSettings -> stringResource(R.string.net_cfg_title)
                FeatureDetailSection.CalendarSettings -> stringResource(R.string.cal_cfg_title)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.tab_settings).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        SettingsControlsTheme {
        when (section) {
            FeatureDetailSection.GameMode -> {
                GameModeCard(
                    hideIdleIsland = settings.hideIslandInGames,
                    showNotifications = settings.showNotificationIslandInGames,
                    playSound = settings.playNotificationSoundInGames,
                    onHideIdleIslandChange = { scope.launch { repository.setHideIslandInGames(it) } },
                    onShowNotificationsChange = { scope.launch { repository.setShowNotificationIslandInGames(it) } },
                    onPlaySoundChange = { scope.launch { repository.setPlayNotificationSoundInGames(it) } },
                    islandOverrides = settings.gameIslandVisibilityOverrides,
                    soundOverrides = settings.gameSoundOverrides,
                    onIslandOverridesChange = { scope.launch { repository.setGameIslandVisibilityOverrides(it) } },
                    onSoundOverridesChange = { scope.launch { repository.setGameSoundOverrides(it) } },
                    additionalGamePackages = settings.additionalGamePackages,
                    onAdditionalGamePackagesChange = { scope.launch { repository.setAdditionalGamePackages(it) } },
                    refreshVersion = refreshVersion
                )
            }
            FeatureDetailSection.NotificationRules -> {
                NotificationsAndPrivacySection(settings = settings, repository = repository)
            }
            FeatureDetailSection.AppShortcuts -> {
                AppShortcutsSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.NotificationHistory -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    NotificationHistorySection(settings = settings, repository = repository)
                }
            }
            FeatureDetailSection.ColorStudio -> {
                CustomizationsSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.GesturesGuide -> {
                GesturesSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.PermissionsCenter -> {
                PermissionsSection(
                    overlayGranted = overlayGranted,
                    notificationGranted = notificationGranted,
                    batteryIgnored = batteryIgnored,
                    bluetoothGranted = bluetoothGranted,
                    onOverlayClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onNotificationClick = {
                        val detailIntent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS").apply {
                            val component = ComponentName(context, com.agupta07505.smartisland.service.SmartIslandNotificationListenerService::class.java)
                            putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component.flattenToString())
                        }
                        runCatching {
                            context.startActivity(detailIntent)
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    },
                    onBatteryClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    onBluetoothClick = onBluetoothClick,
                    onRefreshPermissions = onRefreshPermissions
                )
            }
            FeatureDetailSection.BackupRestore -> {
                BackupRestoreSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.Diagnostics -> {
                DeveloperOptionsSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.Timing -> {
                TimingSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.IdleWidgets -> {
                IdleWidgetsSettingsSection(
                    settings = settings,
                    repository = repository,
                    onOpenWeatherSettings = { onNavigateTo(FeatureDetailSection.WeatherSettings) },
                    onOpenNetSpeedSettings = { onNavigateTo(FeatureDetailSection.NetSpeedSettings) },
                    onOpenCalendarSettings = { onNavigateTo(FeatureDetailSection.CalendarSettings) }
                )
            }
            FeatureDetailSection.About -> {
                AboutSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.UpdatesAndDownloads -> {
                UpdatesAndDownloadsSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.SupportCommunity -> {
                SupportSection()
            }
            FeatureDetailSection.DeveloperOptions -> {
                DeveloperOptionsSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.FaceIdSettings -> {
                FaceIdSettingsSection(settings = settings, repository = repository)
            }
            FeatureDetailSection.WeatherSettings -> {
                WeatherSettingsCard(settings = settings, repository = repository, modifier = Modifier.searchAnchor("idle.weather"))
            }
            FeatureDetailSection.NetSpeedSettings -> {
                NetSpeedSettingsCard(settings = settings, repository = repository, modifier = Modifier.searchAnchor("idle.netspeed"))
            }
            FeatureDetailSection.CalendarSettings -> {
                CalendarSettingsCard(modifier = Modifier.searchAnchor("idle.calendar"))
            }
        }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    return enabled?.split(":")?.any {
        ComponentName.unflattenFromString(it)?.packageName == context.packageName
    } == true
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, com.agupta07505.smartisland.service.SmartIslandOverlayService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
}

@Composable
private fun WelcomeDialog(
    onDismiss: () -> Unit,
    onTakeTour: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(context) {
        runCatchingLogged("WelcomeDialog", "Failed to get app icon") {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 144
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 144
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(26.dp),
            // Fully opaque: the glass theme makes `surface` translucent, which left this dialog hard to read.
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = "Logotipo de Ryx Isla",
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("SI", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Text(
                    text = stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.welcome_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        runCatchingLogged("WelcomeDialog", "Failed to open GitHub link") {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.agupta07505.smartisland.util.RyxLinks.REPOSITORY)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondary
                        )
                        Text(
                            stringResource(R.string.star_on_github),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        runCatchingLogged("WelcomeDialog", "Failed to open Ryx Isla Discord") {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.agupta07505.smartisland.util.RyxLinks.DISCORD)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.People,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(stringResource(R.string.join_discord), fontWeight = FontWeight.Bold)
                    }
                }


                ElevatedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(R.string.btn_get_started), fontWeight = FontWeight.Bold)
                }

                TextButton(onClick = onTakeTour, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver una guía rápida de los gestos", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun isBluetoothConnectPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return true
    }

    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
}

@Preview(showBackground = true, name = "Light Mode")
@Composable
fun SmartIslandHomeScreenLightPreview() {
    SmartIslandTheme(darkTheme = false) {
        SmartIslandHomeScreen()
    }
}

@Preview(showBackground = true, name = "Dark Mode")
@Composable
fun SmartIslandHomeScreenDarkPreview() {
    SmartIslandTheme(darkTheme = true) {
        SmartIslandHomeScreen()
    }
}
