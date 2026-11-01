package com.d4viddf.hyperbridge.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.models.HyperIslandSpec
import com.d4viddf.hyperbridge.models.IslandAlignment
import kotlin.math.roundToInt

/**
 * Draws the Ryx Isla dynamic island directly as a system overlay.
 *
 * The visual language follows HyperOS: a pure-black capsule that lives inside
 * the status bar, a camera lens on the right, and a spring-like morph between
 * the compact capsule and the expanded notification card.
 *
 * Notifications are accumulated instead of replacing each other: the card
 * always shows the newest event, a counter marks how many are waiting, and
 * when one expires the next one slides in.
 */
class GenericIslandOverlay(
    private val context: Context,
    private val preferences: AppPreferences
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: HyperIslandView? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private val pendingRemovals = mutableSetOf<View>()
    private val items = LinkedHashMap<String, IslandItem>()
    private var activeNotificationKey: String? = null
    private var dismissTask: Runnable? = null
    private var touchStartY = 0f
    private var permanentEnabled = false
    private var hidePermanentInLandscape = false
    private var sizeAnimator: ValueAnimator? = null
    @Volatile private var permanentVisible = false

    private data class IslandItem(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val largeIcon: Drawable?,
        val smallIcon: Drawable?,
        val isOngoing: Boolean,
        val timeoutSeconds: Int,
        val postedAt: Long
    )

    fun setPermanentIsland(enabled: Boolean, hideInLandscape: Boolean) {
        mainHandler.post {
            val wasShowingPermanent = permanentVisible
            permanentEnabled = enabled
            hidePermanentInLandscape = hideInLandscape
            if (activeNotificationKey == null) {
                val shouldShow = shouldShowPermanent()
                when {
                    shouldShow && !wasShowingPermanent -> showPermanentView()
                    !shouldShow && wasShowingPermanent -> removeCurrentView(animated = true)
                }
            }
        }
    }

    fun isPermanentIslandVisible(): Boolean = permanentVisible

    /**
     * Re-applies position, alignment and size to a live island. Called when the
     * design settings change so the real overlay follows the in-app preview
     * immediately instead of only after a rotation.
     */
    fun refreshAppearance() {
        mainHandler.post {
            val view = root
            val params = rootParams
            if (view == null || params == null) {
                if (activeNotificationKey == null && shouldShowPermanent()) showPermanentView()
                return@post
            }
            params.gravity = Gravity.TOP or horizontalGravity()
            params.x = alignmentOffset()
            params.y = islandTopOffset()
            if (activeNotificationKey != null) {
                params.width = expandedWidthPx()
                params.height = dp(HyperIslandSpec.EXPANDED_HEIGHT_DP)
            } else {
                params.width = dp(HyperIslandSpec.COLLAPSED_WIDTH_DP)
                params.height = dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)
            }
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(params.height.toFloat()))
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    fun onOrientationChanged() {
        mainHandler.post {
            val view = root
            val params = rootParams
            if (view != null && params != null) {
                refreshAppearance()
                return@post
            }
            if (activeNotificationKey == null && shouldShowPermanent()) showPermanentView()
        }
    }

    fun show(
        notificationKey: String,
        packageName: String,
        title: String,
        text: String,
        notificationImage: Drawable? = null,
        isOngoing: Boolean,
        timeoutSeconds: Int
    ) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(context)) return@post

            val appLabel = getApplicationLabel(packageName)
            val appIcon = getNotificationIcon(packageName)
            val displayTitle = title.ifBlank { appLabel }
            val item = IslandItem(
                key = notificationKey,
                packageName = packageName,
                title = displayTitle,
                text = text,
                largeIcon = notificationImage ?: appIcon,
                smallIcon = appIcon,
                isOngoing = isOngoing,
                timeoutSeconds = timeoutSeconds.coerceIn(3, 15),
                postedAt = System.currentTimeMillis()
            )

            val isVisible = activeNotificationKey == notificationKey
            items.remove(notificationKey)
            items[notificationKey] = item
            trimStack()

            if (isVisible) {
                // Live update of the card that is already on screen: refresh the
                // content in place instead of collapsing and re-expanding it.
                root?.let { view ->
                    view.updateNotification(displayTitle, text, item.largeIcon ?: appIcon, appIcon)
                    view.setStackCount(items.size)
                }
                scheduleDismiss(item)
                return@post
            }

            if (activeNotificationKey == null) {
                showItem(item)
            } else {
                // Accumulate: keep the current card visible and surface the
                // counter so the user knows more events are waiting.
                root?.setStackCount(items.size)
            }
        }
    }

    fun dismiss(notificationKey: String? = null) {
        mainHandler.post {
            if (notificationKey != null) {
                items.remove(notificationKey)
                if (notificationKey != activeNotificationKey) {
                    // A queued item was removed from the shade: just refresh the counter.
                    root?.setStackCount(items.size)
                    return@post
                }
            } else {
                activeNotificationKey?.let(items::remove)
            }
            if (activeNotificationKey == null && permanentVisible) return@post
            dismissTask?.let(mainHandler::removeCallbacks)
            dismissTask = null

            val view = root
            val params = rootParams
            if (view != null && params != null && preferences.islandAnimationsEnabledSync()) {
                view.animateContentOut()
                animateSize(
                    view = view,
                    params = params,
                    targetWidth = dp(HyperIslandSpec.COLLAPSED_WIDTH_DP),
                    targetHeight = dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP),
                    durationMs = 240L,
                    interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                ) {
                    removeCurrentView(animated = true)
                    activeNotificationKey = null
                    showNextOrPermanent()
                }
            } else {
                removeCurrentView(animated = true)
                activeNotificationKey = null
                showNextOrPermanent()
            }
        }
    }

    fun shutdown() {
        mainHandler.post {
            dismissTask?.let(mainHandler::removeCallbacks)
            dismissTask = null
            items.clear()
            activeNotificationKey = null
            permanentVisible = false
            removeCurrentView()
            pendingRemovals.toList().forEach { view ->
                view.animate().cancel()
                runCatching { windowManager.removeView(view) }
            }
            pendingRemovals.clear()
        }
    }

    private fun trimStack() {
        while (items.size > MAX_STACKED_ITEMS) {
            val oldestQueued = items.keys.firstOrNull { it != activeNotificationKey } ?: break
            items.remove(oldestQueued)
        }
    }

    private fun showNextOrPermanent() {
        val next = items.values.lastOrNull()
        if (next != null) {
            showItem(next)
        } else if (shouldShowPermanent()) {
            showPermanentView()
        }
    }

    private fun scheduleDismiss(item: IslandItem) {
        dismissTask?.let(mainHandler::removeCallbacks)
        dismissTask = null
        if (item.isOngoing) return
        val visibleFor = item.timeoutSeconds.coerceIn(3, 15) * 1000L
        dismissTask = Runnable { dismiss(item.key) }.also {
            mainHandler.postDelayed(it, visibleFor)
        }
    }

    private fun showItem(item: IslandItem) {
        dismissTask?.let(mainHandler::removeCallbacks)
        dismissTask = null
        removeCurrentView()
        activeNotificationKey = item.key
        permanentVisible = false

        val appIcon = item.smallIcon ?: getNotificationIcon(item.packageName)
        val view = HyperIslandView(context)
        view.bindNotification(
            appLabel = getApplicationLabel(item.packageName),
            title = item.title,
            text = item.text,
            largeIcon = item.largeIcon ?: appIcon,
            smallIcon = appIcon
        )
        view.setStackCount(items.size)
        view.contentDescription = "${item.title}. ${item.text}"
        view.setOnClickListener {
            launchApp(item.packageName)
            dismiss(item.key)
        }
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.rawY
                    touched.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchStartY - event.rawY > dp(HyperIslandSpec.TOUCH_SENSITIVITY_DP)) {
                        dismiss(item.key)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    touched.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    if (touchStartY - event.rawY > dp(HyperIslandSpec.TOUCH_SENSITIVITY_DP)) {
                        dismiss(item.key)
                        true
                    } else {
                        touched.performClick()
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    touched.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    true
                }
                else -> false
            }
        }

        val params = newParams(dp(HyperIslandSpec.COLLAPSED_WIDTH_DP), dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP))
        try {
            windowManager.addView(view, params)
            root = view
            rootParams = params
            view.showCollapsedNotification()
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(params.height.toFloat()))
            animateIn(view)
        } catch (error: Exception) {
            Log.w(TAG, "Could not show the island overlay", error)
            root = null
            rootParams = null
            activeNotificationKey = null
            return
        }

        val expandedWidth = expandedWidthPx()
        val expandedHeight = dp(HyperIslandSpec.EXPANDED_HEIGHT_DP)
        if (!preferences.islandAnimationsEnabledSync()) {
            view.expandInstant()
            params.width = expandedWidth
            params.height = expandedHeight
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(expandedHeight.toFloat()))
            runCatching { windowManager.updateViewLayout(view, params) }
        } else {
            mainHandler.postDelayed({
                if (root === view && activeNotificationKey == item.key) {
                    view.expand()
                    animateSize(
                        view = view,
                        params = params,
                        targetWidth = expandedWidth,
                        targetHeight = expandedHeight,
                        durationMs = 440L,
                        interpolator = OvershootInterpolator(0.32f)
                    )
                }
            }, 60L)
        }

        scheduleDismiss(item)
    }

    private fun shouldShowPermanent(): Boolean {
        val isLandscape = context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        return permanentEnabled && Settings.canDrawOverlays(context) &&
            !(hidePermanentInLandscape && isLandscape)
    }

    private fun showPermanentView() {
        removeCurrentView()
        val view = HyperIslandView(context)
        view.bindPermanent()
        view.contentDescription = context.getString(R.string.permanent_island_title)
        view.setOnClickListener { launchApp(context.packageName) }
        val params = newParams(dp(HyperIslandSpec.COLLAPSED_WIDTH_DP), dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP))
        try {
            windowManager.addView(view, params)
            root = view
            rootParams = params
            permanentVisible = true
            view.showPermanent()
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(params.height.toFloat()))
            animateIn(view, durationMs = 300L)
        } catch (error: Exception) {
            Log.w(TAG, "Could not show the permanent island overlay", error)
            root = null
            rootParams = null
            permanentVisible = false
        }
    }

    private fun newParams(widthPx: Int, heightPx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or horizontalGravity()
            x = alignmentOffset()
            y = islandTopOffset()
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Ignore system-bar insets so (0,0) is the real top of the display.
            fitInsetsTypes = 0
            fitInsetsSides = 0
            setFitInsetsIgnoringVisibility(true)
        }
    }

    private fun animateSize(
        view: HyperIslandView,
        params: WindowManager.LayoutParams,
        targetWidth: Int,
        targetHeight: Int,
        durationMs: Long,
        interpolator: android.view.animation.Interpolator,
        onEnd: (() -> Unit)? = null
    ) {
        sizeAnimator?.cancel()
        val startWidth = params.width.coerceAtLeast(1)
        val startHeight = params.height.coerceAtLeast(1)
        val maxWidth = screenWidthPx() - dp(4)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val width = (startWidth + (targetWidth - startWidth) * fraction)
                    .roundToInt()
                    .coerceIn(dp(1), maxWidth)
                val height = (startHeight + (targetHeight - startHeight) * fraction)
                    .roundToInt()
                    .coerceAtLeast(dp(1))
                params.width = width
                params.height = height
                view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(height.toFloat()))
                if (root === view) {
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
            }
            var cancelled = false
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    if (sizeAnimator === this@apply) sizeAnimator = null
                    onEnd?.invoke()
                }
            })
            start()
        }
        sizeAnimator = animator
    }

    private fun animateIn(view: View, durationMs: Long = 320L) {
        view.animate().cancel()
        if (!preferences.islandAnimationsEnabledSync()) {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.alpha = 0f
        view.translationY = -dp(10).toFloat()
        view.scaleX = 0.72f
        view.scaleY = 0.72f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(durationMs)
            .setInterpolator(OvershootInterpolator(0.9f))
            .start()
    }

    private fun removeCurrentView(animated: Boolean = false) {
        val view = root ?: return
        root = null
        rootParams = null
        permanentVisible = false
        sizeAnimator?.cancel()
        sizeAnimator = null
        view.animate().cancel()

        if (animated && preferences.islandAnimationsEnabledSync()) {
            pendingRemovals.add(view)
            view.animate()
                .alpha(0f)
                .translationY(-dp(8).toFloat())
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(180)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    pendingRemovals.remove(view)
                    runCatching { windowManager.removeView(view) }
                }
                .start()
        } else {
            runCatching { windowManager.removeView(view) }
        }
    }

    private fun islandTopOffset(): Int {
        val barPx = statusBarInsetPx()
        val centeredPx = ((barPx - dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)) / 2).coerceAtLeast(0)
        val offset = preferences.islandPositionYSync()
            .coerceIn(AppPreferences.MIN_POSITION_Y, AppPreferences.MAX_POSITION_Y)
        return (centeredPx + dp(offset)).coerceAtLeast(0)
    }

    private fun horizontalGravity(): Int = when (preferences.islandAlignmentSync()) {
        IslandAlignment.LEFT -> Gravity.START
        IslandAlignment.RIGHT -> Gravity.END
        IslandAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
    }

    private fun alignmentOffset(): Int = when (preferences.islandAlignmentSync()) {
        IslandAlignment.LEFT -> dp(HyperIslandSpec.ALIGNMENT_MARGIN_DP)
        IslandAlignment.RIGHT -> dp(HyperIslandSpec.ALIGNMENT_MARGIN_DP)
        IslandAlignment.CENTER -> 0
    }

    private fun expandedWidthPx(): Int {
        val density = displayDensity()
        val widthDp = (screenWidthPx() / density).roundToInt().takeIf { it > 0 }
            ?: context.resources.configuration.screenWidthDp
        return dp(HyperIslandSpec.expandedWidthDp(widthDp))
            .coerceAtMost(screenWidthPx() - dp(4))
    }

    private fun screenWidthPx(): Int = runCatching {
        windowManager.currentWindowMetrics.bounds.width()
    }.getOrElse { context.resources.displayMetrics.widthPixels }

    private fun displayDensity(): Float =
        context.resources.displayMetrics.density.coerceAtLeast(0.1f)

    /**
     * Height of the real status bar. Prefers the live window inset (which
     * includes display cutouts) and falls back to the framework dimension.
     * This is what keeps the pill inside the status bar on every device.
     */
    private fun statusBarInsetPx(): Int {
        val inset = runCatching {
            val metricsInsets = windowManager.currentWindowMetrics.windowInsets
            val bars = metricsInsets.getInsets(WindowInsets.Type.statusBars()).top
            val cutout = metricsInsets.displayCutout?.safeInsetTop ?: 0
            maxOf(bars, cutout)
        }.getOrDefault(0)
        if (inset > 0) return inset
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int = (value * displayDensity()).roundToInt()

    private fun launchApp(packageName: String) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    private fun getApplicationLabel(packageName: String): String = runCatching {
        val app = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(app).toString()
    }.getOrDefault(packageName)

    private fun getNotificationIcon(packageName: String): Drawable {
        return runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrElse {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
                ?: ColorDrawable(Color.GRAY)
        }
    }

    /** The actual pill view: black glass, lens, compact icon and expanded copy. */
    private inner class HyperIslandView(context: Context) : FrameLayout(context) {
        private val backgroundDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(HyperIslandSpec.COLOR_BLACK, HyperIslandSpec.COLOR_BLACK_SOFT)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            setStroke(dp(1).coerceAtLeast(1), HyperIslandSpec.COLOR_STROKE)
            cornerRadius = dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP / 2).toFloat()
        }
        private val collapsedContent = LinearLayout(context)
        private val iconHolder = FrameLayout(context)
        private val collapsedIcon = ImageView(context)
        private val stackBadge = TextView(context)
        private val collapsedTitle = TextView(context)
        private val permanentContent = LinearLayout(context)
        private val permanentDot = View(context)
        private val expandedContent = LinearLayout(context)
        private val expandedIcon = ImageView(context)
        private val appLabelView = TextView(context)
        private val titleView = TextView(context)
        private val bodyView = TextView(context)
        private val highlightSweep = View(context)
        private var pulseAnimator: ValueAnimator? = null
        private var currentAppLabel: String = ""
        private val glassEdge = PathInterpolator(0.2f, 0f, 0f, 1f)

        init {
            background = backgroundDrawable
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dp(12).toFloat()
            outlineAmbientShadowColor = Color.BLACK
            outlineSpotShadowColor = Color.BLACK

            buildCollapsedContent()
            buildPermanentContent()
            buildExpandedContent()

            addView(
                createCameraLens(),
                LayoutParams(
                    dp(HyperIslandSpec.CAMERA_DOT_DP),
                    dp(HyperIslandSpec.CAMERA_DOT_DP),
                    Gravity.END or Gravity.CENTER_VERTICAL
                ).apply { marginEnd = dp(HyperIslandSpec.CAMERA_END_MARGIN_DP) }
            )

            highlightSweep.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, 0x2EFFFFFF, Color.TRANSPARENT)
            )
            highlightSweep.alpha = 0f
            addView(highlightSweep, LayoutParams(dp(90), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        fun bindNotification(
            appLabel: String,
            title: String,
            text: String,
            largeIcon: Drawable,
            smallIcon: Drawable
        ) {
            currentAppLabel = appLabel
            expandedIcon.setImageDrawable(largeIcon)
            collapsedIcon.setImageDrawable(smallIcon)
            collapsedTitle.text = title
            appLabelView.text = appLabel
            titleView.text = title
            bodyView.text = text
            bodyView.visibility = if (text.isBlank() || text == title) GONE else VISIBLE
            showCollapsedNotification()
        }

        /** Refreshes content without collapsing the card that is already open. */
        fun updateNotification(title: String, text: String, largeIcon: Drawable, smallIcon: Drawable) {
            expandedIcon.setImageDrawable(largeIcon)
            collapsedIcon.setImageDrawable(smallIcon)
            collapsedTitle.text = title
            titleView.text = title
            bodyView.text = text
            bodyView.visibility = if (text.isBlank() || text == title) GONE else VISIBLE
        }

        fun setStackCount(count: Int) {
            val stacked = count > 1
            stackBadge.visibility = if (stacked) VISIBLE else GONE
            stackBadge.text = if (count > 9) "9+" else count.toString()
            appLabelView.text = if (stacked) "$currentAppLabel · $count" else currentAppLabel
        }

        fun bindPermanent() {
            showPermanent()
            startPulse()
        }

        fun showCollapsedNotification() {
            stopPulse()
            expandedContent.visibility = GONE
            expandedContent.alpha = 0f
            permanentContent.visibility = GONE
            collapsedContent.visibility = VISIBLE
            collapsedContent.alpha = 1f
            collapsedContent.scaleX = 1f
            collapsedContent.scaleY = 1f
            collapsedContent.translationY = 0f
        }

        fun showPermanent() {
            collapsedContent.visibility = GONE
            expandedContent.visibility = GONE
            expandedContent.alpha = 0f
            permanentContent.visibility = VISIBLE
            permanentContent.alpha = 1f
        }

        fun expand() {
            collapsedContent.animate().cancel()
            collapsedContent.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .translationY(-px(3f))
                .setDuration(140)
                .setInterpolator(glassEdge)
                .withEndAction { collapsedContent.visibility = GONE }
                .start()

            expandedContent.visibility = VISIBLE
            expandedContent.alpha = 0f
            expandedContent.scaleX = 0.94f
            expandedContent.scaleY = 0.94f
            expandedContent.translationY = px(5f)
            expandedContent.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay(170)
                .setDuration(340)
                .setInterpolator(OvershootInterpolator(0.45f))
                .start()

            playHighlightSweep()
        }

        fun expandInstant() {
            collapsedContent.visibility = GONE
            collapsedContent.alpha = 0f
            expandedContent.visibility = VISIBLE
            expandedContent.alpha = 1f
            expandedContent.scaleX = 1f
            expandedContent.scaleY = 1f
            expandedContent.translationY = 0f
        }

        fun animateContentOut() {
            expandedContent.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .translationY(-px(2f))
                .setDuration(110)
                .start()
        }

        fun updateCornerRadius(radiusPx: Float) {
            backgroundDrawable.cornerRadius = radiusPx.coerceAtLeast(0f)
            invalidateOutline()
        }

        private fun buildCollapsedContent() {
            collapsedContent.orientation = LinearLayout.HORIZONTAL
            collapsedContent.gravity = Gravity.CENTER_VERTICAL
            collapsedContent.setPadding(0, 0, 0, 0)

            collapsedIcon.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedTile(HyperIslandSpec.COLOR_ICON_TILE, dp(7), dp(1))
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            iconHolder.addView(
                collapsedIcon,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            stackBadge.apply {
                setTextColor(HyperIslandSpec.COLOR_BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(HyperIslandSpec.COLOR_BRAND_GREEN)
                    setStroke(dp(1).coerceAtLeast(1), HyperIslandSpec.COLOR_BLACK)
                }
                visibility = GONE
            }
            iconHolder.addView(
                stackBadge,
                FrameLayout.LayoutParams(dp(13), dp(13), Gravity.TOP or Gravity.END)
            )

            collapsedContent.addView(
                iconHolder,
                LinearLayout.LayoutParams(dp(21), dp(21)).apply { marginStart = dp(11) }
            )

            collapsedTitle.apply {
                setTextColor(HyperIslandSpec.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(56)
                includeFontPadding = false
                letterSpacing = 0.01f
            }
            collapsedContent.addView(
                collapsedTitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            )

            addView(
                collapsedContent,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        private fun buildPermanentContent() {
            permanentContent.orientation = LinearLayout.HORIZONTAL
            permanentContent.gravity = Gravity.CENTER_VERTICAL
            permanentContent.setPadding(0, 0, 0, 0)

            permanentDot.apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(HyperIslandSpec.COLOR_BRAND_GREEN)
                }
                elevation = dp(2).toFloat()
            }
            permanentContent.addView(
                permanentDot,
                LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(14) }
            )

            permanentContent.visibility = GONE
            addView(
                permanentContent,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        private fun buildExpandedContent() {
            expandedContent.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(HyperIslandSpec.CONTENT_START_DP),
                    0,
                    dp(HyperIslandSpec.CONTENT_END_DP),
                    0
                )
            }

            expandedIcon.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedTile(
                    HyperIslandSpec.COLOR_ICON_TILE,
                    dp(HyperIslandSpec.ICON_CORNER_DP),
                    dp(1)
                )
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            expandedContent.addView(
                expandedIcon,
                LinearLayout.LayoutParams(
                    dp(HyperIslandSpec.ICON_SIZE_DP),
                    dp(HyperIslandSpec.ICON_SIZE_DP)
                ).apply { marginEnd = dp(12) }
            )

            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }

            appLabelView.apply {
                setTextColor(HyperIslandSpec.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                letterSpacing = 0.04f
            }
            copy.addView(
                appLabelView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            titleView.apply {
                setTextColor(HyperIslandSpec.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(1), 0, 0)
            }
            copy.addView(
                titleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            bodyView.apply {
                setTextColor(HyperIslandSpec.COLOR_TEXT_TERTIARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(1), 0, 0)
            }
            copy.addView(
                bodyView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            expandedContent.addView(
                copy,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            expandedContent.visibility = GONE
            expandedContent.alpha = 0f
            addView(
                expandedContent,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        private fun roundedTile(color: Int, radius: Int, strokeWidth: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = radius.toFloat()
                if (strokeWidth > 0) {
                    setStroke(strokeWidth, HyperIslandSpec.COLOR_STROKE)
                }
            }
        }

        private fun createCameraLens(): View {
            val lens = FrameLayout(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                    HyperIslandSpec.COLOR_CAMERA_RING,
                    HyperIslandSpec.COLOR_CAMERA
                )).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(1).coerceAtLeast(1), HyperIslandSpec.COLOR_CAMERA_HIGHLIGHT)
                }
            }
            lens.addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(HyperIslandSpec.COLOR_CAMERA)
                    }
                },
                FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER)
            )
            lens.addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(HyperIslandSpec.COLOR_CAMERA_HIGHLIGHT)
                    }
                },
                FrameLayout.LayoutParams(dp(2), dp(2), Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(2)
                    leftMargin = dp(2)
                }
            )
            return lens
        }

        private fun playHighlightSweep() {
            if (!preferences.islandAnimationsEnabledSync()) return
            highlightSweep.animate().cancel()
            val span = if (width > 0) width.toFloat() else px(360f)
            highlightSweep.translationX = -px(100f)
            highlightSweep.alpha = 0f
            highlightSweep.animate()
                .translationX(span)
                .alpha(1f)
                .setStartDelay(120)
                .setDuration(520)
                .setInterpolator(PathInterpolator(0.3f, 0f, 0.2f, 1f))
                .withEndAction {
                    highlightSweep.animate().alpha(0f).setDuration(180).start()
                }
                .start()
        }

        private fun startPulse() {
            stopPulse()
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1450
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = PathInterpolator(0.4f, 0f, 0.6f, 1f)
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    permanentDot.alpha = 0.45f + 0.55f * value
                    permanentDot.scaleX = 0.82f + 0.18f * value
                    permanentDot.scaleY = 0.82f + 0.18f * value
                }
                start()
            }
            pulseAnimator = animator
        }

        private fun stopPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            permanentDot.alpha = 1f
            permanentDot.scaleX = 1f
            permanentDot.scaleY = 1f
        }

        private fun px(value: Float): Float = value * resources.displayMetrics.density

        override fun onDetachedFromWindow() {
            stopPulse()
            highlightSweep.animate().cancel()
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val TAG = "RyxIslandOverlay"
        const val MAX_STACKED_ITEMS = 5
    }
}
