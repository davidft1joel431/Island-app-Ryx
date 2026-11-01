package com.d4viddf.hyperbridge.models

/**
 * HyperOS island capsule model.
 *
 * A single system function can become dominant and take over the pill. When
 * that happens the dominant function name plus its accent badge sits on the
 * left, and all other accumulated icons are collected in a small circle on the
 * right of the island.
 */
data class HyperIslandCapsule(
    val dominant: HyperIslandFunction?,
    val accumulated: List<HyperIslandFunction>
)

data class HyperIslandFunction(
    val id: String,
    val iconRes: Int,
    val label: String,
    val badge: Int = 0,
    val accentColor: Int = 0xFF3ED16B.toInt()
)
