package com.d4viddf.hyperbridge.ui.screens.design

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.theme.ThemeRepository
import com.d4viddf.hyperbridge.data.widget.WidgetManager
import com.d4viddf.hyperbridge.models.IslandAlignment
import com.d4viddf.hyperbridge.models.theme.CallModule
import com.d4viddf.hyperbridge.models.theme.GlobalConfig
import com.d4viddf.hyperbridge.models.theme.HyperTheme
import com.d4viddf.hyperbridge.models.theme.ResourceType
import com.d4viddf.hyperbridge.models.theme.ThemeMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignScreen(
    onNavigateToWidgets: () -> Unit,
    onNavigateToThemes: () -> Unit,
    onEditTheme: (String) -> Unit,
    onLaunchPicker: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context.applicationContext) }
    val themeRepo = remember { ThemeRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val activeThemeId by preferences.activeThemeIdFlow.collectAsState(initial = null)
    val savedWidgetIds by preferences.savedWidgetIdsFlow.collectAsState(initial = emptyList())
    val islandPositionY by preferences.islandPositionYFlow.collectAsState(initial = 0)
    val islandAlignment by preferences.islandAlignmentFlow.collectAsState(initial = IslandAlignment.CENTER)
    val islandAnimations by preferences.islandAnimationsEnabledFlow.collectAsState(initial = true)

    var availableThemes by remember { mutableStateOf<List<HyperTheme>>(emptyList()) }
    var widgetIcons by remember { mutableStateOf<List<Drawable>>(emptyList()) }
    var themeIcons by remember { mutableStateOf<Map<String, ImageBitmap?>>(emptyMap()) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) {
        val themes = themeRepo.getAvailableThemes()
        availableThemes = themes

        // Only decode the icons that the carousel can actually display.
        withContext(Dispatchers.IO) {
            val iconMap = mutableMapOf<String, ImageBitmap?>()
            themes.take(5).forEach { theme ->
                val iconRes = theme.meta.customIcon
                if (iconRes != null && iconRes.type == ResourceType.LOCAL_FILE) {
                    runCatching {
                        val file = File(themeRepo.getThemesDir(), "${theme.id}/${iconRes.value}")
                        if (file.exists()) {
                            BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                                iconMap[theme.id] = bitmap.asImageBitmap()
                            }
                        }
                    }
                }
            }
            themeIcons = iconMap
        }
    }

    LaunchedEffect(savedWidgetIds) {
        if (savedWidgetIds.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                widgetIcons = savedWidgetIds.take(5).mapNotNull { id ->
                    runCatching {
                        val info = WidgetManager.getWidgetInfo(context, id)
                        val pkg = info?.provider?.packageName ?: return@mapNotNull null
                        context.packageManager.getApplicationIcon(pkg)
                    }.getOrNull()
                }
            }
        } else {
            widgetIcons = emptyList()
        }
    }

    DesignScreenContent(
        activeThemeId = activeThemeId,
        availableThemes = availableThemes,
        themeIcons = themeIcons,
        savedWidgetCount = savedWidgetIds.size,
        widgetIcons = widgetIcons,
        islandPositionY = islandPositionY,
        islandAlignment = islandAlignment,
        islandAnimationsEnabled = islandAnimations,
        onIslandPositionChange = { value -> scope.launch { preferences.setIslandPositionY(value) } },
        onIslandAlignmentChange = { value -> scope.launch { preferences.setIslandAlignment(value) } },
        onIslandAnimationsChange = { enabled -> scope.launch { preferences.setIslandAnimationsEnabled(enabled) } },
        onResetIslandAppearance = {
            scope.launch {
                preferences.setIslandPositionY(0)
                preferences.setIslandAlignment(IslandAlignment.CENTER)
                preferences.setIslandAnimationsEnabled(true)
            }
        },
        onNavigateToWidgets = onNavigateToWidgets,
        onNavigateToThemes = onNavigateToThemes,
        onEditTheme = onEditTheme,
        onFabClick = { showBottomSheet = true },
        onSettingsClick = onSettingsClick,
        onNavigateToHistory = onNavigateToHistory
    )

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.design_add_to_island),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Button(
                    onClick = {
                        showBottomSheet = false
                        onLaunchPicker()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Widgets, null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.design_system_widget_beta), style = MaterialTheme.typography.titleMedium)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        showBottomSheet = false
                        onNavigateToThemes()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Palette, null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.design_get_themes), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignScreenContent(
    activeThemeId: String?,
    availableThemes: List<HyperTheme>,
    themeIcons: Map<String, ImageBitmap?>,
    savedWidgetCount: Int,
    widgetIcons: List<Drawable>,
    islandPositionY: Int,
    islandAlignment: IslandAlignment,
    islandAnimationsEnabled: Boolean,
    onIslandPositionChange: (Int) -> Unit,
    onIslandAlignmentChange: (IslandAlignment) -> Unit,
    onIslandAnimationsChange: (Boolean) -> Unit,
    onResetIslandAppearance: () -> Unit,
    onNavigateToWidgets: () -> Unit,
    onNavigateToThemes: () -> Unit,
    onEditTheme: (String) -> Unit,
    onFabClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.design_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Surface(
                        modifier = Modifier
                            .size(40.dp)
                            .padding(end = 8.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = onSettingsClick
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Settings, null, modifier = Modifier.size(20.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onFabClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.design_add_design))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            IslandTuningCard(
                positionY = islandPositionY,
                alignment = islandAlignment,
                animationsEnabled = islandAnimationsEnabled,
                onPositionChange = onIslandPositionChange,
                onAlignmentChange = onIslandAlignmentChange,
                onAnimationsChange = onIslandAnimationsChange,
                onReset = onResetIslandAppearance
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(stringResource(R.string.design_section_themes), onNavigateToThemes)
                ThemesRow(
                    themes = availableThemes,
                    themeIcons = themeIcons,
                    activeId = activeThemeId,
                    onNavigateToThemes = onNavigateToThemes,
                    onEditTheme = onEditTheme
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(stringResource(R.string.design_section_widgets), onNavigateToWidgets)
                WidgetsRow(
                    savedCount = savedWidgetCount,
                    icons = widgetIcons,
                    onNavigateToWidgets = onNavigateToWidgets,
                    onAddWidget = onFabClick
                )
            }

            HistoryShortcutCard(onNavigateToHistory)

            InspirationFooter()

            Spacer(Modifier.height(88.dp))
        }
    }
}

@Composable
private fun IslandTuningCard(
    positionY: Int,
    alignment: IslandAlignment,
    animationsEnabled: Boolean,
    onPositionChange: (Int) -> Unit,
    onAlignmentChange: (IslandAlignment) -> Unit,
    onAnimationsChange: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(positionY.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    var animationKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(positionY) {
        if (!isDragging) sliderValue = positionY.toFloat()
    }

    val previewFraction = (sliderValue - AppPreferences.MIN_POSITION_Y) /
        (AppPreferences.MAX_POSITION_Y - AppPreferences.MIN_POSITION_Y).toFloat()
    val previewOffset by animateDpAsState(
        targetValue = (8f + previewFraction * 72f).dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "islandPreviewOffset"
    )
    val previewAlignment = when (alignment) {
        IslandAlignment.LEFT -> Alignment.TopStart
        IslandAlignment.CENTER -> Alignment.TopCenter
        IslandAlignment.RIGHT -> Alignment.TopEnd
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.design_position_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.design_position_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onReset) {
                    Icon(
                        Icons.Rounded.RestartAlt,
                        contentDescription = stringResource(R.string.design_reset_position),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(188.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.colorScheme.surfaceContainer
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.design_preview_time),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                    }
                }

                IslandPreviewPill(
                    animationKey = animationKey,
                    animationsEnabled = animationsEnabled,
                    modifier = Modifier
                        .align(previewAlignment)
                        .padding(top = previewOffset, start = 16.dp, end = 16.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        isDragging = true
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onPositionChange(sliderValue.roundToInt())
                    },
                    valueRange = AppPreferences.MIN_POSITION_Y.toFloat()..AppPreferences.MAX_POSITION_Y.toFloat(),
                    steps = 35,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                Icon(
                    Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(R.string.design_position_vertical),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.design_position_value, sliderValue.roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.design_alignment),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlignmentChip(
                    label = stringResource(R.string.design_alignment_left),
                    selected = alignment == IslandAlignment.LEFT,
                    onClick = { onAlignmentChange(IslandAlignment.LEFT) }
                )
                AlignmentChip(
                    label = stringResource(R.string.design_alignment_center),
                    selected = alignment == IslandAlignment.CENTER,
                    onClick = { onAlignmentChange(IslandAlignment.CENTER) }
                )
                AlignmentChip(
                    label = stringResource(R.string.design_alignment_right),
                    selected = alignment == IslandAlignment.RIGHT,
                    onClick = { onAlignmentChange(IslandAlignment.RIGHT) }
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.design_animations),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.design_animations_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = animationsEnabled,
                    onCheckedChange = onAnimationsChange
                )
            }

            Spacer(Modifier.height(6.dp))

            OutlinedButton(
                onClick = { animationKey++ },
                enabled = animationsEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.design_test_animation))
            }
        }
    }
}

@Composable
private fun AlignmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}

@Composable
private fun IslandPreviewPill(
    animationKey: Int,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(animationKey, animationsEnabled) {
        if (!animationsEnabled) {
            scale.snapTo(1f)
            alpha.snapTo(1f)
        } else {
            scale.snapTo(0.72f)
            alpha.snapTo(0f)
            scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
            alpha.animateTo(1f, tween(220))
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 330.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
        shape = RoundedCornerShape(50),
        color = Color(0xFF15110A),
        border = BorderStroke(1.dp, Color(0xFFDDB136)),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0xFFF2C144)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = Color(0xFF1B1508)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.design_preview_island_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF2C144),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.design_preview_island_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF5CE1A0), CircleShape)
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ThemesRow(
    themes: List<HyperTheme>,
    themeIcons: Map<String, ImageBitmap?>,
    activeId: String?,
    onNavigateToThemes: () -> Unit,
    onEditTheme: (String) -> Unit
) {
    val displayThemes = themes.take(5)

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ThemePreviewCard(
                title = stringResource(R.string.theme_system_default_title),
                subtitle = stringResource(R.string.theme_system_default_desc),
                color = MaterialTheme.colorScheme.secondary,
                isActive = activeId == null,
                icon = Icons.Rounded.PhoneAndroid,
                onClick = onNavigateToThemes
            )
        }
        items(displayThemes, key = { it.id }) { theme ->
            val color = runCatching {
                Color((theme.global.highlightColor ?: "#000000").toColorInt())
            }.getOrDefault(MaterialTheme.colorScheme.primary)

            ThemePreviewCard(
                title = theme.meta.name,
                subtitle = stringResource(R.string.theme_card_author_format, theme.meta.author),
                color = color,
                isActive = theme.id == activeId,
                icon = Icons.Rounded.Palette,
                customIcon = themeIcons[theme.id],
                onClick = { onEditTheme(theme.id) }
            )
        }
        item {
            ActionPreviewCard(
                label = stringResource(R.string.design_browse_more),
                icon = Icons.Rounded.Add,
                onClick = onNavigateToThemes
            )
        }
    }
}

@Composable
fun WidgetsRow(
    savedCount: Int,
    icons: List<Drawable>,
    onNavigateToWidgets: () -> Unit,
    onAddWidget: () -> Unit
) {
    if (savedCount == 0) {
        Card(
            onClick = onAddWidget,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(150.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.Widgets,
                    null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.design_empty_widget_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.design_empty_widget_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val displayIcons = icons.take(5)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(displayIcons.size) { index ->
            WidgetPreviewCard(
                label = stringResource(R.string.widget_id_fmt, index + 1),
                icon = displayIcons[index],
                onClick = onNavigateToWidgets
            )
        }
        item {
            ActionPreviewCard(
                label = stringResource(R.string.design_add_new),
                icon = Icons.Rounded.Add,
                onClick = onAddWidget
            )
        }
    }
}

@Composable
fun ThemePreviewCard(
    title: String,
    subtitle: String,
    color: Color,
    isActive: Boolean,
    icon: ImageVector,
    customIcon: ImageBitmap? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(164.dp)
            .height(174.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.04f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (customIcon != null) {
                    Image(
                        bitmap = customIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = color
                    )
                }

                if (isActive) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) {
                        Text(
                            text = stringResource(R.string.design_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun WidgetPreviewCard(
    label: String,
    icon: Drawable?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(144.dp)
            .height(150.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon.toBitmap(128, 128).asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Icon(
                        Icons.Rounded.Widgets,
                        null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ActionPreviewCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(132.dp)
            .height(150.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryShortcutCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notification_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.design_history_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InspirationFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.design_inspiration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- PREVIEWS ---

@SuppressLint("UseKtx")
@Preview(showBackground = true)
@Composable
private fun DesignScreenPreview() {
    val mockThemes = listOf(
        HyperTheme(
            id = "1",
            meta = ThemeMetadata("Neon City", "David", 1),
            global = GlobalConfig(
                highlightColor = "#00FF00",
                backgroundColor = "#000000",
                textColor = "#FFFFFF",
                useAppColors = false,
                iconShapeId = "circle",
                iconPaddingPercent = 10
            ),
            callConfig = CallModule(null, null, "#00FF00", "#FF0000")
        ),
        HyperTheme(
            id = "2",
            meta = ThemeMetadata("Sunset", "Alice", 1),
            global = GlobalConfig(
                highlightColor = "#FF5722",
                backgroundColor = "#202124",
                textColor = "#FFFFFF",
                useAppColors = false,
                iconShapeId = "square",
                iconPaddingPercent = 15
            ),
            callConfig = CallModule(null, null, "#00FF00", "#FF0000")
        )
    )

    val mockIcons = listOf(
        android.graphics.Color.RED.toDrawable(),
        android.graphics.Color.BLUE.toDrawable()
    )

    MaterialTheme {
        DesignScreenContent(
            activeThemeId = "1",
            availableThemes = mockThemes,
            themeIcons = emptyMap(),
            savedWidgetCount = 2,
            widgetIcons = mockIcons,
            islandPositionY = 0,
            islandAlignment = IslandAlignment.CENTER,
            islandAnimationsEnabled = true,
            onIslandPositionChange = {},
            onIslandAlignmentChange = {},
            onIslandAnimationsChange = {},
            onResetIslandAppearance = {},
            onNavigateToWidgets = {},
            onNavigateToThemes = {},
            onEditTheme = {},
            onFabClick = {},
            onSettingsClick = {},
            onNavigateToHistory = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DesignScreenEmptyPreview() {
    MaterialTheme {
        DesignScreenContent(
            activeThemeId = null,
            availableThemes = emptyList(),
            themeIcons = emptyMap(),
            savedWidgetCount = 0,
            widgetIcons = emptyList(),
            islandPositionY = 0,
            islandAlignment = IslandAlignment.CENTER,
            islandAnimationsEnabled = true,
            onIslandPositionChange = {},
            onIslandAlignmentChange = {},
            onIslandAnimationsChange = {},
            onResetIslandAppearance = {},
            onNavigateToWidgets = {},
            onNavigateToThemes = {},
            onEditTheme = {},
            onFabClick = {},
            onSettingsClick = {},
            onNavigateToHistory = {}
        )
    }
}
