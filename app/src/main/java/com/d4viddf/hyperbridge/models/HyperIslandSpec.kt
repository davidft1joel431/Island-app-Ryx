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
    const val COLLAPSED_WIDTH_DP = 196
    const val COLLAPSED_HEIGHT_DP = 36
    const val TOUCH_TARGET_HEIGHT_DP = 48
    const val EXPANDED_HEIGHT_DP = 104
    const val EXPANDED_CORNER_DP = 26
    const val EXPANDED_MAX_WIDTH_DP = 380
    const val EXPAND_DURATION_MS = 440L
    const val EXPAND_OVERSHOOT_TENSION = 0.32f
    const val SCREEN_SIDE_MARGIN_DP = 16
    const val ICON_SIZE_DP = 40
    const val ICON_CORNER_DP = 12
    const val CONTENT_START_DP = 12
    const val CONTENT_END_DP = 16
    const val ALIGNMENT_MARGIN_DP = 12

    /** Vertical travel (dp) needed to dismiss the island with a swipe. */
    const val TOUCH_SENSITIVITY_DP = 24

    /** Simulated status-bar height used when a real inset is unavailable. */
    const val PREVIEW_STATUS_BAR_DP = 34

    // Capsule width extras for collapsed presentations.
    const val DOMINANT_FUNCTION_WIDTH_EXTRA_DP = 28
    const val COUNTER_WIDTH_EXTRA_DP = 16
    const val PENDING_STACK_WIDTH_EXTRA_DP = 20
    const val NOTIFICATION_WIDTH_EXTRA_DP = 22

    // HyperOS-style palette: black glass and white copy.
    val COLOR_BLACK = 0xFF000000.toInt()
    val COLOR_BLACK_SOFT = 0xFF08080A.toInt()
    val COLOR_SURFACE = 0xFF050506.toInt()
    val COLOR_STROKE = 0x1AFFFFFF.toInt()
    val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    val COLOR_TEXT_SECONDARY = 0xFFB9B9BE.toInt()
    val COLOR_TEXT_TERTIARY = 0xFF8E8E93.toInt()
    val COLOR_ICON_TILE = 0xFF1A1A1D.toInt()
    val COLOR_BRAND = 0xFFFFC83D.toInt()
    val COLOR_BRAND_GREEN = 0xFF35D07A.toInt()
    val COLOR_LIVE = 0xFF4ADE80.toInt()

    // HyperOS-style accents used by the live system functions.
    val COLOR_ACCENT_BLUE = 0xFF4C9AFF.toInt()
    val COLOR_ACCENT_RED = 0xFFFF5A5F.toInt()
    val COLOR_ACCENT_ORANGE = 0xFFFF6900.toInt()
    val COLOR_ACCENT_CYAN = 0xFF3EDDD2.toInt()
    val COLOR_ACCENT_GREEN = 0xFF3ED16B.toInt()

    fun mutedTileColor(color: Int): Int = when {
        color == COLOR_BLACK || color == COLOR_BLACK_SOFT -> COLOR_ICON_TILE
        color == COLOR_TEXT_PRIMARY || color == COLOR_TEXT_SECONDARY || color == COLOR_TEXT_TERTIARY -> COLOR_ICON_TILE
        else -> color
    }

    fun systemFunctionColor(id: String): Int = when (id) {
        "hotspot" -> COLOR_ACCENT_GREEN
        "flashlight" -> COLOR_BRAND
        "bluetooth" -> COLOR_ACCENT_BLUE
        "recorder" -> COLOR_ACCENT_RED
        "music" -> COLOR_ACCENT_ORANGE
        else -> COLOR_LIVE
    }

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

    private const val GESTURE_DISMISS_DP = 14
    private const val GESTURE_DISMISS_MIN_DP = 10
    private const val GESTURE_CYCLE_DP = 90
    private const val GESTURE_CYCLE_MIN_DP = 70
    const val MAX_STACKED_ITEMS = 12
}
