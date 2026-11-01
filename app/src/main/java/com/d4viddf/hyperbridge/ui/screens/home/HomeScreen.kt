package com.d4viddf.hyperbridge.ui.screens.home

// Ryx Isla modification: theme search opens with the derivative app's branding.

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.ui.AppInfo
import com.d4viddf.hyperbridge.ui.AppListViewModel
import com.d4viddf.hyperbridge.ui.components.AppConfigBottomSheet
import com.d4viddf.hyperbridge.ui.components.premiumBackground
import com.d4viddf.hyperbridge.ui.screens.design.DesignScreen
import com.d4viddf.hyperbridge.ui.screens.design.SavedAppWidgetsScreen
import com.d4viddf.hyperbridge.ui.screens.design.WidgetConfigScreen
import com.d4viddf.hyperbridge.ui.screens.design.WidgetPickerScreen
import com.d4viddf.hyperbridge.ui.screens.theme.ThemeCreatorScreen
import com.d4viddf.hyperbridge.ui.screens.theme.ThemeManagerScreen

private enum class DesignRoute {
    DASHBOARD,
    WIDGET_LIST,
    THEME_MANAGER,
    THEME_CREATOR
}

private data class RyxNavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppListViewModel = viewModel(),
    onSettingsClick: () -> Unit,
    onNotificationHistoryClick: () -> Unit,
    onNavConfigClick: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(1) }
    var designRoute by remember { mutableStateOf(DesignRoute.DASHBOARD) }
    var editingThemeId by remember { mutableStateOf<String?>(null) }

    var showWidgetPicker by remember { mutableStateOf(false) }
    var editingWidgetId by remember { mutableStateOf<Int?>(null) }
    var configApp by remember { mutableStateOf<AppInfo?>(null) }
    val pageStateHolder = rememberSaveableStateHolder()

    val activeApps by viewModel.activeAppsState.collectAsState()
    val libraryApps by viewModel.libraryAppsState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val context = LocalContext.current

    if (configApp != null) BackHandler { configApp = null }
    if (editingWidgetId != null) BackHandler { editingWidgetId = null }
    if (showWidgetPicker) BackHandler { showWidgetPicker = false }

    if (selectedTab == 0 && designRoute != DesignRoute.DASHBOARD) {
        BackHandler {
            designRoute = when (designRoute) {
                DesignRoute.THEME_CREATOR -> {
                    editingThemeId = null
                    DesignRoute.THEME_MANAGER
                }
                else -> DesignRoute.DASHBOARD
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().premiumBackground()) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                AnimatedVisibility(
                    visible = (selectedTab != 0 || designRoute == DesignRoute.DASHBOARD) &&
                        !showWidgetPicker && editingWidgetId == null,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(360)
                    ) + fadeIn(tween(240)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(260)
                    ) + fadeOut(tween(160))
                ) {
                    RyxNavigationBar(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(bottom = padding.calculateBottomPadding())
                    .fillMaxSize()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (
                                slideInHorizontally(
                                    initialOffsetX = { if (forward) it / 7 else -it / 7 },
                                    animationSpec = tween(340)
                                ) + fadeIn(tween(240))
                                ) togetherWith (
                                slideOutHorizontally(
                                    targetOffsetX = { if (forward) -it / 12 else it / 12 },
                                    animationSpec = tween(260)
                                ) + fadeOut(tween(160))
                                )
                        },
                        label = "HomeTabs"
                    ) { tab ->
                        pageStateHolder.SaveableStateProvider(tab) {
                            when (tab) {
                                0 -> DesignTab(
                                    designRoute = designRoute,
                                    editingThemeId = editingThemeId,
                                    onNavigateToWidgets = { designRoute = DesignRoute.WIDGET_LIST },
                                    onNavigateToThemes = { designRoute = DesignRoute.THEME_MANAGER },
                                    onEditTheme = { themeId ->
                                        editingThemeId = themeId
                                        designRoute = DesignRoute.THEME_CREATOR
                                    },
                                    onLaunchPicker = { showWidgetPicker = true },
                                    onSettingsClick = onSettingsClick,
                                    onNavigateToHistory = onNotificationHistoryClick,
                                    onRouteChange = { designRoute = it },
                                    onEditingThemeChange = { editingThemeId = it },
                                    onEditWidget = { editingWidgetId = it },
                                    context = context
                                )

                                1 -> ActiveAppsPage(
                                    apps = activeApps,
                                    isLoading = isLoading,
                                    viewModel = viewModel,
                                    onConfig = { configApp = it },
                                    onSettingsClick = onSettingsClick
                                )

                                else -> LibraryPage(
                                    apps = libraryApps,
                                    isLoading = isLoading,
                                    viewModel = viewModel,
                                    onConfig = { configApp = it },
                                    onSettingsClick = onSettingsClick
                                )
                            }
                        }
                    }
                }
            }

            // --- OVERLAYS ---
            AnimatedVisibility(
                visible = showWidgetPicker,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeIn(),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeOut(),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                WidgetPickerScreen(
                    onBack = { showWidgetPicker = false },
                    onWidgetSelected = { newId ->
                        showWidgetPicker = false
                        editingWidgetId = newId
                    }
                )
            }

            AnimatedVisibility(
                visible = editingWidgetId != null,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeIn(),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeOut(),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                if (editingWidgetId != null) {
                    WidgetConfigScreen(
                        widgetId = editingWidgetId!!,
                        onBack = { editingWidgetId = null }
                    )
                }
            }

            if (configApp != null) {
                val currentConfigApp = configApp!!
                AppConfigBottomSheet(
                    app = currentConfigApp,
                    viewModel = viewModel,
                    onDismiss = { configApp = null },
                    onNavConfigClick = {
                        onNavConfigClick(currentConfigApp.packageName)
                        configApp = null
                    }
                )
            }
        }
    }
}

@Composable
private fun DesignTab(
    designRoute: DesignRoute,
    editingThemeId: String?,
    onNavigateToWidgets: () -> Unit,
    onNavigateToThemes: () -> Unit,
    onEditTheme: (String) -> Unit,
    onLaunchPicker: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onRouteChange: (DesignRoute) -> Unit,
    onEditingThemeChange: (String?) -> Unit,
    onEditWidget: (Int) -> Unit,
    context: android.content.Context
) {
    AnimatedContent(
        targetState = designRoute,
        transitionSpec = {
            if (targetState.ordinal > initialState.ordinal) {
                slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it / 3 } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it / 3 } + fadeOut()
            }
        },
        label = "DesignTabNav"
    ) { route ->
        when (route) {
            DesignRoute.DASHBOARD -> {
                DesignScreen(
                    onNavigateToWidgets = onNavigateToWidgets,
                    onNavigateToThemes = onNavigateToThemes,
                    onEditTheme = onEditTheme,
                    onLaunchPicker = onLaunchPicker,
                    onSettingsClick = onSettingsClick,
                    onNavigateToHistory = onNavigateToHistory
                )
            }

            DesignRoute.WIDGET_LIST -> {
                SavedAppWidgetsScreen(
                    onBack = { onRouteChange(DesignRoute.DASHBOARD) },
                    onEditWidget = onEditWidget,
                    onAddMore = onLaunchPicker
                )
            }

            DesignRoute.THEME_MANAGER -> {
                ThemeManagerScreen(
                    onBack = { onRouteChange(DesignRoute.DASHBOARD) },
                    onFindThemes = {
                        val query = "Ryx Isla Theme"
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                "market://search?q=$query&c=apps".toUri()
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                "https://play.google.com/store/search?q=$query&c=apps".toUri()
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    },
                    onCreateTheme = {
                        onEditingThemeChange(null)
                        onRouteChange(DesignRoute.THEME_CREATOR)
                    },
                    onEditTheme = { id ->
                        onEditingThemeChange(id)
                        onRouteChange(DesignRoute.THEME_CREATOR)
                    }
                )
            }

            DesignRoute.THEME_CREATOR -> {
                ThemeCreatorScreen(
                    editThemeId = editingThemeId,
                    onBack = {
                        onRouteChange(DesignRoute.THEME_MANAGER)
                        onEditingThemeChange(null)
                    },
                    onThemeCreated = {
                        onRouteChange(DesignRoute.THEME_MANAGER)
                        onEditingThemeChange(null)
                    }
                )
            }
        }
    }
}

@Composable
private fun RyxNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val items = listOf(
        RyxNavItem(
            label = stringResource(R.string.design),
            selectedIcon = Icons.Filled.Brush,
            unselectedIcon = Icons.Outlined.Brush
        ),
        RyxNavItem(
            label = stringResource(R.string.tab_active),
            selectedIcon = Icons.Filled.ToggleOn,
            unselectedIcon = Icons.Outlined.ToggleOff
        ),
        RyxNavItem(
            label = stringResource(R.string.tab_library),
            selectedIcon = Icons.Filled.Apps,
            unselectedIcon = Icons.Outlined.Apps
        )
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 10.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        tonalElevation = 6.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedTab
                val background by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(240),
                    label = "navBackground"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(240),
                    label = "navContent"
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.92f,
                    animationSpec = spring(
                        dampingRatio = 0.62f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "navIconScale"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(background)
                        .clickable { onTabSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            tint = contentColor,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
                        ) {
                            Text(
                                text = item.label,
                                color = contentColor,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
