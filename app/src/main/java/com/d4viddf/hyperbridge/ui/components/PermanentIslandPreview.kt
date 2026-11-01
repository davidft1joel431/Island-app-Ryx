package com.d4viddf.hyperbridge.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.d4viddf.hyperbridge.models.IslandAlignment

/**
 * Real-size preview of the permanent capsule. It reuses the exact same mock
 * used by the dynamic island preview, so size and position cannot drift.
 */
@Composable
fun PermanentIslandPreview(
    positionY: Int,
    alignment: IslandAlignment,
    modifier: Modifier = Modifier
) {
    HyperIslandPreviewHero(
        positionY = positionY,
        alignment = alignment,
        animationsEnabled = false,
        animationKey = 0,
        modifier = modifier.fillMaxWidth(),
        permanent = true,
        autoPlay = false,
        height = 176.dp
    )
}
