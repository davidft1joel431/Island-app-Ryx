package com.d4viddf.hyperbridge.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Very slow ambient light behind the whole app. The two glows use the logo
 * colors and drift almost imperceptibly, which keeps the interface alive
 * without competing with the content.
 */
fun Modifier.premiumBackground(): Modifier = composed {
    val background = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val dark = background.luminance() < 0.5f

    val transition = rememberInfiniteTransition(label = "premiumBackground")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "premiumBackgroundPhase"
    )

    drawBehind {
        drawRect(background)
        val glowRadius = size.minDimension * 1.15f
        val glowCenter = Offset(
            x = size.width * (0.10f + 0.06f * phase),
            y = size.height * 0.05f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = if (dark) 0.10f else 0.18f),
                    Color.Transparent
                ),
                center = glowCenter,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = glowCenter
        )

        val greenRadius = size.minDimension * 1.05f
        val greenCenter = Offset(
            x = size.width * (0.92f - 0.05f * phase),
            y = size.height * (0.78f + 0.04f * phase)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondary.copy(alpha = if (dark) 0.075f else 0.12f),
                    Color.Transparent
                ),
                center = greenCenter,
                radius = greenRadius
            ),
            radius = greenRadius,
            center = greenCenter
        )
    }
}
