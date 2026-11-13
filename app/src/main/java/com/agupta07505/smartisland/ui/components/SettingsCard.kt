/*
 * Smart Island (2026)
 * Â© Animesh Gupta â€” github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.agupta07505.smartisland.ui.glass.Card

private val SettingsCardShape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)

/** Shared surface styling for cards used by the settings detail screens. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun SettingsCard(
    modifier: Modifier = Modifier,
    shape: Shape = SettingsCardShape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.animateSettingsCardSize(),
        shape = SettingsCardShape,
        colors = colors,
        elevation = elevation,
        border = border ?: settingsCardBorder(),
        content = content
    )
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun SettingsCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = SettingsCardShape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.animateSettingsCardSize(),
        enabled = enabled,
        shape = SettingsCardShape,
        colors = colors,
        elevation = elevation,
        border = border ?: settingsCardBorder(),
        content = content
    )
}

@Composable
private fun settingsCardBorder() = BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
)

private fun Modifier.animateSettingsCardSize(): Modifier =
    this.animateContentSize()
