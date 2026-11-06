package com.d4viddf.hyperbridge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.models.HyperIslandSpec
import com.d4viddf.hyperbridge.models.IslandAlignment
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val IslandBlack = Color(HyperIslandSpec.COLOR_BLACK)
private val IslandBlackSoft = Color(HyperIslandSpec.COLOR_BLACK_SOFT)
private val IslandStroke = Color(HyperIslandSpec.COLOR_STROKE)
private val IslandTile = Color(HyperIslandSpec.COLOR_ICON_TILE)
private val BrandYellow = Color(HyperIslandSpec.COLOR_BRAND)
private val BrandGreen = Color(HyperIslandSpec.COLOR_BRAND_GREEN)

/**
 * Real-size HyperOS-style preview. The hero is full-bleed so the expanded
 * island can use exactly the same width and top-position calculations as the
 * system overlay.
 */
@Composable
fun HyperIslandPreviewHero(
    positionY: Int,
    alignment: IslandAlignment,
    animationsEnabled: Boolean,
    animationKey: Int,
    modifier: Modifier = Modifier,
    permanent: Boolean = false,
    autoPlay: Boolean = true,
    height: Dp = 238.dp
) {
    var expanded by remember { mutableStateOf(false) }
    var autoPlayed by remember { mutableStateOf(false) }

    LaunchedEffect(animationKey) {
        if (animationKey > 0 && animationsEnabled) {
            expanded = false
            delay(70)
            expanded = true
            delay(1900)
            expanded = false
        }
    }

    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) {
            expanded = false
        } else if (autoPlay && !permanent && !autoPlayed) {
            autoPlayed = true
            delay(800)
            expanded = true
            delay(1900)
            expanded = false
        }
    }

    val density = LocalDensity.current
    val realStatusBarDp = with(density) {
        WindowInsets.statusBars.getTop(this).toDp().value.roundToInt()
    }.takeIf { it > 0 } ?: HyperIslandSpec.PREVIEW_STATUS_BAR_DP

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp))
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF07110E),
                            Color(0xFF07070A),
                            Color(0xFF130F05)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x3D35D07A), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.04f),
                        radius = size.minDimension * 0.92f
                    ),
                    radius = size.minDimension * 0.92f,
                    center = Offset(size.width * 0.18f, size.height * 0.04f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x30FFC83D), Color.Transparent),
                        center = Offset(size.width * 0.86f, size.height * 0.72f),
                        radius = size.minDimension * 0.78f
                    ),
                    radius = size.minDimension * 0.78f,
                    center = Offset(size.width * 0.86f, size.height * 0.72f)
                )
            }
    ) {
        val chromeAlpha by animateFloatAsState(
            targetValue = if (expanded) 0.18f else 1f,
            animationSpec = tween(200),
            label = "previewChrome"
        )

        StatusBarChrome(
            statusBarHeight = realStatusBarDp.dp,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = chromeAlpha }
        )

        val expandedWidth = HyperIslandSpec
            .expandedWidthDp(maxWidth.value.roundToInt())
            .dp
        val top = HyperIslandSpec
            .collapsedTopDp(realStatusBarDp, positionY)
            .dp
        val horizontal = when (alignment) {
            IslandAlignment.LEFT -> Modifier.padding(start = HyperIslandSpec.ALIGNMENT_MARGIN_DP.dp)
            IslandAlignment.CENTER -> Modifier
            IslandAlignment.RIGHT -> Modifier.padding(end = HyperIslandSpec.ALIGNMENT_MARGIN_DP.dp)
        }
        val boxAlignment = when (alignment) {
            IslandAlignment.LEFT -> Alignment.TopStart
            IslandAlignment.CENTER -> Alignment.TopCenter
            IslandAlignment.RIGHT -> Alignment.TopEnd
        }

        HyperIslandMock(
            expanded = expanded && !permanent,
            expandedWidth = expandedWidth,
            animationsEnabled = animationsEnabled,
            permanent = permanent,
            modifier = Modifier
                .align(boxAlignment)
                .padding(top = top)
                .then(horizontal)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(BrandGreen, CircleShape)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (permanent) {
                    stringResource(R.string.permanent_island_title)
                } else {
                    stringResource(R.string.design_preview_hint)
                },
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 10.sp,
                letterSpacing = 0.3.sp
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 13.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color.White.copy(alpha = 0.07f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            Text(
                text = buildString {
                    append(HyperIslandSpec.COLLAPSED_WIDTH_DP)
                    append("×")
                    append(HyperIslandSpec.COLLAPSED_HEIGHT_DP)
                    append("  ·  ")
                    append(expandedWidth.value.roundToInt())
                    append("×")
                    append(HyperIslandSpec.EXPANDED_HEIGHT_DP)
                    append(" dp")
                },
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
@Composable
private fun StatusBarChrome(statusBarHeight: Dp, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(statusBarHeight)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.design_preview_time),
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.SignalCellular4Bar,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.size(13.dp)
            )
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.size(13.dp)
            )
            Icon(
                Icons.Filled.BatteryFull,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
@Composable
private fun HyperIslandMock(
    expanded: Boolean,
    expandedWidth: Dp,
    animationsEnabled: Boolean,
    permanent: Boolean,
    modifier: Modifier = Modifier
) {
    val width by animateDpAsState(
        targetValue = if (expanded) expandedWidth else HyperIslandSpec.COLLAPSED_WIDTH_DP.dp,
        animationSpec = if (animationsEnabled) {
            spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow)
        } else {
            snap()
        },
        label = "islandPreviewWidth"
    )
    val height by animateDpAsState(
        targetValue = if (expanded) HyperIslandSpec.EXPANDED_HEIGHT_DP.dp else HyperIslandSpec.COLLAPSED_HEIGHT_DP.dp,
        animationSpec = if (animationsEnabled) {
            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
        } else {
            snap()
        },
        label = "islandPreviewHeight"
    )
    val shape = RoundedCornerShape(height / 2)

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .shadow(
                elevation = if (expanded) 18.dp else 10.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(IslandBlack, IslandBlackSoft)))
            .border(1.dp, IslandStroke, shape)
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                (
                    fadeIn(tween(240, delayMillis = 120)) +
                        scaleIn(
                            initialScale = 0.94f,
                            animationSpec = spring(
                                dampingRatio = 0.8f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    ) togetherWith (
                    fadeOut(tween(110)) +
                        scaleOut(targetScale = 0.96f, animationSpec = tween(110))
                    )
            },
            label = "islandPreviewContent"
        ) { isExpanded ->
            if (isExpanded) {
                ExpandedIslandContent()
            } else if (permanent) {
                PermanentIslandContent()
            } else {
                CollapsedIslandContent()
            }
        }

    }
}

@Composable
private fun CollapsedIslandContent() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 11.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(IslandTile)
                .border(1.dp, IslandStroke, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ryx_logo),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .height(14.dp),
                contentScale = ContentScale.Fit
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.design_preview_island_title),
            color = Color.White,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        // HyperOS-style app circle on the right; tap expands the island.
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(IslandTile)
                    .border(1.dp, IslandStroke, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ryx_logo),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(BrandGreen)
                    .border(1.dp, IslandBlack, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "3",
                    color = IslandBlack,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun PermanentIslandContent() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SystemFunctionBadge(R.drawable.ic_system_flashlight, 18.dp)
            SystemFunctionBadge(R.drawable.ic_system_hotspot, 18.dp, badge = 2)
            SystemFunctionBadge(R.drawable.ic_system_bluetooth, 18.dp)
        }
    }
}

@Composable
private fun ExpandedIslandContent() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = HyperIslandSpec.CONTENT_START_DP.dp, end = HyperIslandSpec.CONTENT_END_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(HyperIslandSpec.ICON_SIZE_DP.dp)
                .clip(RoundedCornerShape(HyperIslandSpec.ICON_CORNER_DP.dp))
                .background(IslandTile)
                .border(
                    1.dp,
                    IslandStroke,
                    RoundedCornerShape(HyperIslandSpec.ICON_CORNER_DP.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ryx_logo),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .height(26.dp),
                contentScale = ContentScale.Fit
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.design_preview_now),
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.design_preview_island_title),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.design_preview_island_text),
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SystemFunctionBadge(R.drawable.ic_system_flashlight, 24.dp)
            SystemFunctionBadge(R.drawable.ic_system_hotspot, 24.dp, badge = 2)
            SystemFunctionBadge(R.drawable.ic_system_bluetooth, 24.dp)
            SystemFunctionBadge(R.drawable.ic_system_recorder, 24.dp)
            SystemFunctionBadge(R.drawable.ic_system_music, 24.dp)
        }
    }
}

@Composable
private fun SystemFunctionBadge(iconRes: Int, size: Dp, badge: Int? = null) {
    Box {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(IslandTile)
                .border(1.dp, IslandStroke, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(size * 0.62f),
                contentScale = ContentScale.Fit
            )
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(size * 0.62f)
                    .clip(CircleShape)
                    .background(BrandGreen)
                    .border(1.dp, IslandBlack, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badge > 9) "9+" else badge.toString(),
                    color = IslandBlack,
                    fontSize = (size.value * 0.28f).sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
