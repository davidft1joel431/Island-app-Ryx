package com.d4viddf.hyperbridge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.ui.graphics.ColorFilter
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
private val IslandExpandEasing = Easing { fraction ->
    val t = fraction - 1f
    val tension = HyperIslandSpec.EXPAND_OVERSHOOT_TENSION
    t * t * ((tension + 1f) * t + tension) + 1f
}

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
        val boxAlignment = if (expanded) Alignment.TopCenter else when (alignment) {
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
                .then(if (expanded) Modifier else horizontal)
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
                    append(HyperIslandSpec.MEDIA_EXPANDED_HEIGHT_DP)
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
        animationSpec = if (animationsEnabled && expanded) {
            tween(HyperIslandSpec.EXPAND_DURATION_MS.toInt(), easing = IslandExpandEasing)
        } else if (animationsEnabled) {
            tween(240, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
        } else {
            snap()
        },
        label = "islandPreviewWidth"
    )
    val height by animateDpAsState(
        targetValue = if (expanded) HyperIslandSpec.MEDIA_EXPANDED_HEIGHT_DP.dp else HyperIslandSpec.COLLAPSED_HEIGHT_DP.dp,
        animationSpec = if (animationsEnabled && expanded) {
            tween(HyperIslandSpec.EXPAND_DURATION_MS.toInt(), easing = IslandExpandEasing)
        } else if (animationsEnabled) {
            tween(240, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
        } else {
            snap()
        },
        label = "islandPreviewHeight"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (expanded) HyperIslandSpec.EXPANDED_CORNER_DP.dp else height / 2,
        animationSpec = if (animationsEnabled) tween(HyperIslandSpec.EXPAND_DURATION_MS.toInt()) else snap(),
        label = "islandPreviewCornerRadius"
    )
    val shape = RoundedCornerShape(cornerRadius)

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
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(23.dp)
                .clip(CircleShape)
                .background(Color(0xFF282733)),
            contentAlignment = Alignment.Center
        ) {
            Text("♫", color = Color(0xFF9E91FF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "03:58",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        MiniControl("Ⅱ", Color(0xFF5D8EFF))
        MiniControl("×", Color(0xFF39393F))
    }
}

@Composable
private fun MiniControl(glyph: String, tint: Color) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
        Text(glyph, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            SystemFunctionBadge(R.drawable.ic_system_music, 18.dp)
            SystemFunctionBadge(R.drawable.ic_system_hotspot, 18.dp)
            SystemFunctionBadge(R.drawable.ic_system_battery, 18.dp)
        }
    }
}

@Composable
private fun ExpandedIslandContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF8C4CFF), Color(0xFFFF805D)))),
                contentAlignment = Alignment.Center
            ) {
                Text("♫", color = Color.White.copy(alpha = 0.92f), fontSize = 31.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("Far Closer", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("David E. Smith", color = Color.White.copy(alpha = 0.64f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("⌁", color = Color.White.copy(alpha = 0.82f), fontSize = 24.sp)
        }

        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("2:54", color = Color.White.copy(alpha = 0.62f), fontSize = 10.sp)
                Text("3:56", color = Color.White.copy(alpha = 0.62f), fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth(0.68f).height(4.dp).clip(CircleShape).background(Color.White))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("⇄", color = Color.White.copy(alpha = 0.74f), fontSize = 18.sp)
            Spacer(Modifier.width(25.dp))
            Text("‹", color = Color.White, fontSize = 29.sp)
            Spacer(Modifier.width(21.dp))
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Text("Ⅱ", color = IslandBlack, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(21.dp))
            Text("›", color = Color.White, fontSize = 29.sp)
            Spacer(Modifier.width(25.dp))
            Text("♡", color = Color.White.copy(alpha = 0.74f), fontSize = 20.sp)
        }
    }
}

@Composable
private fun SystemFunctionBadge(iconRes: Int, size: Dp, badge: Int? = null) {
    val accent = when (iconRes) {
        R.drawable.ic_system_hotspot -> HyperIslandSpec.systemFunctionColor("hotspot")
        R.drawable.ic_system_flashlight -> HyperIslandSpec.systemFunctionColor("flashlight")
        R.drawable.ic_system_bluetooth -> HyperIslandSpec.systemFunctionColor("bluetooth")
        R.drawable.ic_system_recorder -> HyperIslandSpec.systemFunctionColor("recorder")
        R.drawable.ic_system_music -> HyperIslandSpec.systemFunctionColor("music")
        else -> HyperIslandSpec.COLOR_LIVE
    }
    Box {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(accent))
                .border(1.dp, IslandStroke, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(size * 0.62f),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(Color(HyperIslandSpec.mutedTileColor(accent)))
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
