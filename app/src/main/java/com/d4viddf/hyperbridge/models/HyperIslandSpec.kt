package com.d4viddf.hyperbridge.models

/**
 * Single source of truth for the floating island. Both the real overlay and
 * the in-app preview use these values, so the preview can no longer drift from
 * what is actually drawn on top of the system UI.
 *
 * The compact capsule is intentionally small and sits centered inside the
 * status bar, exactly like HyperOS. The expanded card grows from that capsule.
 */
object HyperIslandSpec {
    const val COLLAPSED_WIDTH_DP = 120
    const val COLLAPSED_HEIGHT_DP = 32
    const val EXPANDED_HEIGHT_DP = 68
    const val EXPANDED_MAX_WIDTH_DP = 380
    const val SCREEN_SIDE_MARGIN_DP = 16
    const val CAMERA_DOT_DP = 12
    const val CAMERA_END_MARGIN_DP = 11
    const val ICON_SIZE_DP = 40
    const val ICON_CORNER_DP = 12
    const val CONTENT_START_DP = 12
    const val CONTENT_END_DP = 30
    const val ALIGNMENT_MARGIN_DP = 12

    /** Vertical travel (dp) needed to dismiss the island with a swipe. */
    const val TOUCH_SENSITIVITY_DP = 50

    /** Simulated status-bar height used when a real inset is unavailable. */
    const val PREVIEW_STATUS_BAR_DP = 34

    // HyperOS-style palette: black glass, white copy and a dark camera lens.
    val COLOR_BLACK = 0xFF000000.toInt()
    val COLOR_BLACK_SOFT = 0xFF08080A.toInt()
    val COLOR_SURFACE = 0xFF050506.toInt()
    val COLOR_STROKE = 0x1AFFFFFF.toInt()
    val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    val COLOR_TEXT_SECONDARY = 0xFFB9B9BE.toInt()
    val COLOR_TEXT_TERTIARY = 0xFF8E8E93.toInt()
    val COLOR_CAMERA = 0xFF0B0B0D.toInt()
    val COLOR_CAMERA_RING = 0xFF2B2B30.toInt()
    val COLOR_CAMERA_HIGHLIGHT = 0xFF55555F.toInt()
    val COLOR_ICON_TILE = 0xFF1A1A1D.toInt()
    val COLOR_BRAND = 0xFFFFC83D.toInt()
    val COLOR_BRAND_GREEN = 0xFF35D07A.toInt()
    val COLOR_LIVE = 0xFF4ADE80.toInt()

    /** Width of the expanded island in dp for the current screen width. */
    fun expandedWidthDp(screenWidthDp: Int): Int {
        return (screenWidthDp - SCREEN_SIDE_MARGIN_DP * 2)
            .coerceAtMost(EXPANDED_MAX_WIDTH_DP)
            .coerceAtLeast(COLLAPSED_WIDTH_DP)
    }

    /**
     * Top position of the collapsed island. It is vertically centered inside
     * the status bar, so the pill actually lives in the status-bar area
     * instead of appearing below it.
     */
    fun collapsedTopDp(statusBarHeightDp: Int, positionOffsetDp: Int = 0): Int {
        val centered = (statusBarHeightDp - COLLAPSED_HEIGHT_DP) / 2
        return (centered + positionOffsetDp).coerceAtLeast(0)
    }

    /** The island is always a stadium: the radius follows the animated height. */
    fun cornerRadiusPx(heightPx: Float): Float = heightPx.coerceAtLeast(0f) / 2f
}
