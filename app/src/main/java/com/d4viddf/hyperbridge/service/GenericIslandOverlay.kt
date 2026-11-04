package com.d4viddf.hyperbridge.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.VelocityTracker;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.d4viddf.hyperbridge.R;
import com.d4viddf.hyperbridge.data.AppPreferences;
import com.d4viddf.hyperbridge.models.HyperIslandSpec;
import com.d4viddf.hyperbridge.models.IslandAlignment;
import com.d4viddf.hyperbridge.models.NotificationCloneDetector;
import kotlin.math.abs;
import kotlin.math.roundToInt;

const val GLURRY_SIZE = 24f;
const val GLURRY_SAMPLING = 5;

/** Draws the Ryx Isla dynamic island following HyperOS 4 visual language exactly. */
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
    private var selectedNotificationKey: String? = null
    private var expanded = false
    private val touchSlop = (ViewConfiguration.get(context).scaledTouchSlop * 0.55f).toInt().coerceAtLeast(4)
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var permanentEnabled = false
    private var hidePermanentInLandscape = false
    private var sizeAnimator: ValueAnimator? = null
    @Volatile private var permanentVisible = false

    private var currentFunctions: List<SystemStatusReader.Function> = emptyList()
    private val systemStatus = SystemStatusReader(context) { functions ->
        mainHandler.post { onSystemFunctionsChanged(functions) }
    }.also { it.start() }

    private data class IslandItem(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val isClone: Boolean,
        val title: String,
        val text: String,
        val largeIcon: Drawable?,
        val smallIcon: Drawable?,
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

    private fun onSystemFunctionsChanged(functions: List<SystemStatusReader.Function>) {
        currentFunctions = functions
        val view = root
        if (view == null) {
            when {
                functions.isNotEmpty() && Settings.canDrawOverlays(context) -> showSystemCapsule()
                items.isNotEmpty() -> showCollapsed(items.values.last())
                permanentEnabled || functions.isNotEmpty() -> showPermanentView()
            }
            return
        }

        if (expanded) {
            view.setSystemFunctions(functions)
            if (functions.isEmpty() && activeNotificationKey == null) collapseCurrent()
            return
        }

        applyCollapsedPresentation(view)
    }

    private fun dominantFunction(): SystemStatusReader.Function? = currentFunctions.firstOrNull()

    private fun aggregateCount(): Int = items.size + (currentFunctions.size - 1).coerceAtLeast(0)

    private fun applyCollapsedPresentation(view: HyperIslandView) {
        if (expanded) return
        val function = dominantFunction()
        val item = selectedItem()

        val accent = if (function != null) {
            HyperIslandSpec.systemFunctionColorWithBadge(function.badge, HyperIslandSpec.systemFunctionColor(function.id))
        } else {
            HyperIslandSpec.COLOR_ICON_TILE
        }

        if (function != null) {
            val appIcon = item?.smallIcon
            activeNotificationKey = item?.key
            permanentVisible = false
            view.bindSystemCapsule(
                function = function,
                pendingCount = items.size,
                deviceCount = function.badge,
                pendingIcon = appIcon,
                accent = accent
            )
            view.setStackCount(items.size)
            view.setOnClickListener { expandSystemStatus() }
            view.onPendingCircleClick = { expandSystemStatus() }
            attachGestures(view, item)
            updateCollapsedWidth(view)
            return
        }

        if (item == null) {
            if (!expanded && root === view) {
                if (shouldShowPermanent()) showPermanentView() else removeCurrentView(animated = true)
            }
            return
        }

        val appIcon = item.smallIcon ?: getNotificationIcon(item.packageName)
        activeNotificationKey = item.key
        permanentVisible = false
        view.bindNotification(
            appLabel = item.appLabel,
            title = item.title,
            text = item.text,
            largeIcon = item.largeIcon ?: appIcon,
            smallIcon = appIcon,
            isClone = item.isClone
        )
        view.setStackCount(items.size)
        view.setPendingCircle(appIcon, pendingCountFor(item))
        view.setOnClickListener { expandSelected() }
        view.onPendingCircleClick = { expandSelected() }
        attachGestures(view, item)
        updateCollapsedWidth(view)
    }

    private fun updateCollapsedWidth(view: HyperIslandView) {
        val params = rootParams ?: return
        val target = collapsedWidthPx(view)
        if (params.width == target) return
        if (preferences.islandAnimationsEnabledSync()) {
            animateSize(
                view = view,
                params = params,
                targetWidth = target,
                targetHeight = params.height,
                durationMs = 220L,
                interpolator = OvershootInterpolator(0.5f)
            )
        } else {
            params.width = target
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun collapsedWidthPx(view: HyperIslandView? = root): Int {
        val measured = view?.measureCollapsedWidth() ?: 0
        if (measured > 0) {
            val bubble = if (view != null && view.isCountBubbleVisible()) {
                dp(BUBBLE_SIZE_DP + BUBBLE_GAP_DP)
            } else {
                0
            }
            val max = screenWidthPx() - dp(HyperIslandSpec.SCREEN_SIDE_MARGIN_DP * 2)
            return (measured + bubble).coerceAtMost(max)
        }
        return dp(HyperIslandSpec.COLLAPSED_WIDTH_DP)
    }

    private fun selectedItem(): IslandItem? {
        val key = selectedNotificationKey ?: activeNotificationKey ?: return null
        return items[key]
    }

    private fun pendingCountFor(item: IslandItem?): Int {
        val packageName = item?.packageName ?: return 0
        return items.values.count { it.packageName == packageName }
    }

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
            if (expanded) {
                params.width = expandedWidthPx()
                params.height = dp(HyperIslandSpec.EXPANDED_HEIGHT_DP)
            } else {
                params.width = collapsedWidthPx()
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
        timeoutSeconds: Int,
        appLabel: String? = null,
        isWorkProfile: Boolean = false
    ) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(context)) return@post

            val resolvedLabel = appLabel?.takeIf { it.isNotBlank() }
                ?: getApplicationLabel(packageName)
            val isClone = isWorkProfile || NotificationCloneDetector.isClone(packageName, resolvedLabel)
            val appIcon = getNotificationIcon(packageName)
            val displayTitle = title.ifBlank { resolvedLabel }
            val item = IslandItem(
                key = notificationKey,
                packageName = packageName,
                appLabel = resolvedLabel,
                isClone = isClone,
                title = displayTitle,
                text = text,
                largeIcon = notificationImage ?: appIcon,
                smallIcon = appIcon,
                postedAt = System.currentTimeMillis()
            )

            val wasVisible = activeNotificationKey == notificationKey
            items.remove(notificationKey)
            items[notificationKey] = item
            trimStack()

            val view = root
            if (view != null && expanded) {
                if (wasVisible) {
                    view.updateNotification(displayTitle, text, item.largeIcon ?: appIcon, appIcon)
                }
                view.setStackCount(items.size)
                return@post
            }

            if (view == null) {
                showCollapsed(item)
                return@post
            }

            selectedNotificationKey = notificationKey
            activeNotificationKey = item.key
            permanentVisible = false
            applyCollapsedPresentation(view)
        }
    }

    fun dismiss(notificationKey: String? = null) {
        mainHandler.post {
            val key = notificationKey ?: activeNotificationKey ?: return@post
            items.remove(key)
            if (selectedNotificationKey == key) {
                selectedNotificationKey = items.values.lastOrNull()?.key
            }

            if (key != activeNotificationKey) {
                root?.setStackCount(items.size)
                if (!expanded) {
                    root?.let { applyCollapsedPresentation(it) } ?: run {
                        if (items.isNotEmpty()) showCollapsed(items.values.last())
                    }
                }
                return@post
            }

            activeNotificationKey = null
            expanded = false
            if (items.isNotEmpty()) {
                showCollapsed(items.values.last())
                return@post
            }
            selectedNotificationKey = null
            if (currentFunctions.isNotEmpty()) {
                showSystemCapsule()
                return@post
            }
            removeCurrentView(animated = true)
            if (shouldShowPermanent()) showPermanentView()
        }
    }

    fun shutdown() {
        mainHandler.post {
            systemStatus.stop()
            items.clear()
            activeNotificationKey = null
            selectedNotificationKey = null
            expanded = false
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

    private fun showCollapsed(item: IslandItem) {
        removeCurrentView()
        activeNotificationKey = item.key
        selectedNotificationKey = item.key
        expanded = false
        permanentVisible = false

        currentFunctions = systemStatus.snapshot()
        val appIcon = item.smallIcon ?: getNotificationIcon(item.packageName)
        val view = HyperIslandView(context)
        view.bindNotification(
            appLabel = item.appLabel,
            title = item.title,
            text = item.text,
            largeIcon = item.largeIcon ?: appIcon,
            smallIcon = appIcon,
            isClone = item.isClone
        )
        view.setSystemFunctions(currentFunctions)
        view.setPendingCircle(appIcon, pendingCountFor(item))
        view.setNotificationCount(items.size)
        view.contentDescription = "${item.title}. ${item.text}"

        val params = newParams(
            collapsedWidthPx(view),
            dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)
        )
        try {
            windowManager.addView(view, params)
            root = view
            rootParams = params
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(params.height.toFloat()))
            applyCollapsedPresentation(view)
            animateIn(view)
        } catch (error: Exception) {
            Log.w(TAG, "Could not show the island overlay", error)
            root = null
            rootParams = null
            activeNotificationKey = null
        }
    }

    /** Capsule owned by a live system function when there are no notifications. */
    private fun showSystemCapsule() {
        currentFunctions = systemStatus.snapshot()
        val function = currentFunctions.firstOrNull()
        if (function == null) {
            if (items.isNotEmpty()) showCollapsed(items.values.last())
            else if (shouldShowPermanent()) showPermanentView()
            return
        }

        removeCurrentView()
        expanded = false
        permanentVisible = false
        activeNotificationKey = selectedItem()?.key

        val view = HyperIslandView(context)
        val item = selectedItem()
        view.bindSystemCapsule(
            function = function,
            pendingCount = items.size,
            deviceCount = function.badge,
            pendingIcon = item?.smallIcon,
            accent = HyperIslandSpec.systemFunctionColor(function.id)
        )
        view.setSystemFunctions(currentFunctions)
        view.contentDescription = "${function.label} activo"
        view.setOnClickListener { expandSystemStatus() }
        view.onPendingCircleClick = { expandSystemStatus() }
        attachGestures(view, item)

        val params = newParams(
            collapsedWidthPx(view),
            dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)
        )
        try {
            windowManager.addView(view, params)
            root = view
            rootParams = params
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(params.height.toFloat()))
            view.showCollapsedNotification()
            animateIn(view)
        } catch (error: Exception) {
            Log.w(TAG, "Could not show the system capsule", error)
            root = null
            rootParams = null
            activeNotificationKey = null
        }
    }

    /** Selects the newest queued item and morphs the capsule into the card. */
    private fun expandSelected() {
        mainHandler.post {
            val key = selectedNotificationKey ?: items.keys.lastOrNull()
            val item = key?.let { items[it] }
            if (item == null) {
                expandSystemStatus()
                return@post
            }
            val view = root ?: run {
                showCollapsed(item)
                return@post
            }
            if (expanded) return@post

            val params = rootParams ?: return@post
            val appIcon = item.smallIcon ?: getNotificationIcon(item.packageName)
            activeNotificationKey = item.key
            permanentVisible = false
            expanded = true

            view.bindNotification(
                appLabel = item.appLabel,
                title = item.title,
                text = item.text,
                largeIcon = item.largeIcon ?: appIcon,
                smallIcon = appIcon,
                isClone = item.isClone
            )
            view.setStackCount(items.size)
            view.setOnClickListener {
                launchApp(item.packageName)
                collapseCurrent()
            }
            attachGestures(view, item)
            view.setSystemFunctions(systemStatus.snapshot())

            val expandedWidth = expandedWidthPx()
            val expandedHeight = dp(HyperIslandSpec.EXPANDED_HEIGHT_DP)
            if (!preferences.islandAnimationsEnabledSync()) {
                view.expandInstant()
                params.width = expandedWidth
                params.height = expandedHeight
                view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(expandedHeight.toFloat()))
                runCatching { windowManager.updateViewLayout(view, params) }
            } else {
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
        }
    }

    /** Expands the dominant system function when there are no notifications. */
    private fun expandSystemStatus() {
        mainHandler.post {
            if (items.isNotEmpty()) {
                expandSelected()
                return@post
            }
            val function = dominantFunction() ?: return@post
            if (expanded) return@post

            var view = root
            if (view == null) {
                showSystemCapsule()
                view = root
            }
            view = view ?: return@post
            val params = rootParams ?: return@post

            expanded = true
            permanentVisible = false
            activeNotificationKey = null
            view.bindSystemCapsule(
                function = function,
                pendingCount = 0,
                deviceCount = function.badge,
                pendingIcon = null,
                accent = HyperIslandSpec.systemFunctionColor(function.id)
            )
            view.setOnClickListener { collapseCurrent() }
            attachGestures(view, null)
            view.setSystemFunctions(currentFunctions)

            val expandedWidth = expandedWidthPx()
            val expandedHeight = dp(HyperIslandSpec.EXPANDED_HEIGHT_DP)
            if (!preferences.islandAnimationsEnabledSync()) {
                view.expandInstant()
                params.width = expandedWidth
                params.height = expandedHeight
                view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(expandedHeight.toFloat()))
                runCatching { windowManager.updateViewLayout(view, params) }
            } else {
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
        }
    }

    private fun collapseCurrent() {
        val view = root ?: return
        val params = rootParams ?: return
        if (!expanded) return
        expanded = false
        view.showCollapsedNotification()
        applyCollapsedPresentation(view)
        val targetWidth = collapsedWidthPx()
        val targetHeight = dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)
        if (preferences.islandAnimationsEnabledSync()) {
            animateSize(
                view = view,
                params = params,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                durationMs = 240L,
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            )
        } else {
            params.width = targetWidth
            params.height = targetHeight
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(targetHeight.toFloat()))
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    /** Full gesture system: vertical swipes dismiss, horizontal swipes cycle */
    private fun attachGestures(view: HyperIslandView, item: IslandItem?) {
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var dragging = false
        var tracker: VelocityTracker? = null

        fun start(event: MotionEvent) {
            downX = event.rawX
            downY = event.rawY
            lastX = downX
            lastY = downY
            dragging = false
            tracker?.recycle()
            tracker = VelocityTracker.obtain().also { it.addMovement(event) }
        }

        fun finish(touched: View) {
            tracker?.recycle()
            tracker = null
            touched.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(120)
                .start()
        }

        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start(event)
                    touched.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    lastX = event.rawX
                    lastY = event.rawY
                    val dy = lastY - downY
                    val dx = lastX - downX
                    if (!dragging && (abs(dy) > touchSlop || abs(dx) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        touched.translationY =
                            dy.coerceIn(-dp(20).toFloat(), dp(20).toFloat()) * 0.35f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val vy = tracker?.yVelocity ?: 0f
                    val dy = event.rawY - downY
                    val dx = event.rawX - downX
                    val vertical = abs(dy) >= abs(dx)
                    val fling = abs(vy) > minimumFlingVelocity
                    val currentKey = activeNotificationKey ?: item?.key
                    finish(touched)
                    when {
                        dragging && vertical && (
                            abs(dy) > dp(HyperIslandSpec.TOUCH_SENSITIVITY_DP) ||
                                (abs(dy) > dp(GESTURE_DISMISS_MIN_DP) && fling)
                        ) -> when {
                            currentKey != null -> dismiss(currentKey)
                            expanded -> collapseCurrent()
                            permanentVisible -> hidePermanent()
                            else -> Unit
                        }
                        dragging && !vertical && (
                            abs(dx) > dp(GESTURE_CYCLE_DP) ||
                                (abs(dx) > dp(GESTURE_CYCLE_MIN_DP) && abs(vx) > minimumFlingVelocity)
                        ) -> cycleSelection(forward = dx < 0)
                        else -> touched.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val dy = lastY - downY
                    val dx = lastX - downX
                    if (dragging && abs(dy) >= abs(dx) && abs(dy) > dp(GESTURE_DISMISS_MIN_DP)) {
                        val currentKey = activeNotificationKey ?: item?.key
                        if (currentKey != null) dismiss(currentKey) else collapseCurrent()
                    } else if (dragging && abs(dx) > abs(dy) &&
                        abs(dx) > dp(GESTURE_CYCLE_MIN_DP)) {
                        cycleSelection(forward = dx < 0)
                    }
                    finish(touched)
                    true
                }
                else -> false
            }
        }
    }

    /** Moves the selection between queued apps without losing the stack. */
    private fun cycleSelection(forward: Boolean) {
        val keys = items.keys.toList()
        if (keys.size < 2) return
        val currentIndex = keys.indexOf(selectedNotificationKey).let {
            if (it < 0) keys.lastIndex else it
        }
        val nextIndex = if (forward) {
            (currentIndex + 1) % keys.size
        } else {
            (currentIndex - 1 + keys.size) % keys.size
        }
        val nextKey = keys[nextIndex]
        val nextItem = items[nextKey] ?: return
        val view = root ?: return
        selectedNotificationKey = nextKey
        activeNotificationKey = nextKey
        val appIcon = nextItem.smallIcon ?: getNotificationIcon(nextItem.packageName)
        view.bindNotification(
            appLabel = nextItem.appLabel,
            title = nextItem.title,
            text = nextItem.text,
            largeIcon = nextItem.largeIcon ?: appIcon,
            smallIcon = appIcon,
            showCollapsed = !expanded,
            isClone = nextItem.isClone
        )
        view.setStackCount(items.size)
        view.setPendingCircle(appIcon, pendingCountFor(nextItem))
        view.setOnClickListener {
            if (expanded) {
                launchApp(nextItem.packageName)
                collapseCurrent()
            } else {
                expandSelected()
            }
        }
        attachGestures(view, nextItem)
        if (!expanded) applyCollapsedPresentation(view)
    }

    /** Temporarily hides the resting island; the next event brings it back. */
    private fun hidePermanent() {
        permanentVisible = false
        removeCurrentView(animated = true)
    }

    private fun shouldShowPermanent(): Boolean {
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return permanentEnabled && Settings.canDrawOverlays(context) &&
            !(hidePermanentInLandscape && isLandscape)
    }

    private fun showPermanentView() {
        removeCurrentView()
        val view = HyperIslandView(context)
        view.bindPermanent()
        currentFunctions = systemStatus.snapshot()
        view.setSystemFunctions(currentFunctions)
        view.contentDescription = context.getString(R.string.permanent_island_title)
        view.setOnClickListener {
            if (items.isNotEmpty()) expandSelected() else launchApp(context.packageName)
        }
        attachGestures(view, null)

        val params = newParams(
            collapsedWidthPx(view),
            dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)
        )

        try {
            windowManager.addView(view, params)
            root = view
            rootParams = params
            permanentVisible = true
            view.showPermanent()
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(params.height.toFloat()))
            view.setSystemFunctions(systemStatus.snapshot())
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
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
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

    private fun displayDensity(): Float = context.resources.displayMetrics.density.coerceAtLeast(0.1f)

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

    private fun getNotificationIcon(packageName: String): Drawable = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrElse {
        ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon) ?: ColorDrawable(Color.GRAY)
    }

    private fun getSystemFunctionIcon(function: SystemStatusReader.Function): Drawable? {
        val accent = HyperIslandSpec.systemFunctionColor(function.id)
        return ContextCompat.getDrawable(context, function.iconRes)?.mutate()?.apply {
            setTint(accent)
        }
    }

    /** The actual pill view: black glass capsule with lens, compact icon and expanded copy. */
    private inner class HyperIslandView(context: Context) : FrameLayout(context) {
        // ── colors (HyperOS palette) ──────────────────────────────────────
        private val COLOR_BLACK = 0xFF000000.toInt()
        private val COLOR_BLACK_SOFT = 0xAA000000.toInt()
        private val COLOR_ICON_TILE = 0xFF1D1D1D.toInt()
        private val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
        private val COLOR_TEXT_SECONDARY = 0xB2FFFFFF.toInt()
        private val COLOR_TEXT_TERTIARY = 0x66FFFFFF.toInt()
        private val COLOR_STROKE = 0x44FFFFFF.toInt()
        private val COLOR_BRAND_GREEN = 0xFF38BD5E.toInt()
        private val COLOR_CAMERA_RING = 0xFFFFFFFF.toInt()
        private val COLOR_CAMERA = 0x4DFFFFFF.toInt()
        private val COLOR_CAMERA_HIGHLIGHT = 0x1AFFFFFF.toInt()

        private val backgroundDrawable: GradientDrawable
        private val pill: FrameLayout
        private val pillParams: FrameLayout.LayoutParams
        private val countBubble: FrameLayout
        private val countBubbleText: TextView
        private val bubbleParams: FrameLayout.LayoutParams
        private val collapsedContent: LinearLayout
        private val iconHolder: FrameLayout
        private val collapsedIcon: ImageView
        private val stackBadge: TextView
        private val collapsedTitle: TextView
        private val pendingCircle: FrameLayout
        private val pendingIcon: ImageView
        private val permanentContent: LinearLayout
        private val permanentDot: View
        private val permanentSystemRow: LinearLayout
        private val expandedContent: LinearLayout
        private val expandedIcon: ImageView
        private val appLabelView: TextView
        private val titleView: TextView
        private val bodyView: TextView
        private val systemRow: LinearLayout
        private val cameraLens: View
        private val highlightSweep: View
        private var pulseAnimator: ValueAnimator? = null
        private var currentAppLabel: String = ""
        private var currentIsClone: Boolean = false
        private var lastSystemSignature: String = ""
        var onPendingCircleClick: (() -> Unit)? = null
        private val glassEdge = PathInterpolator(0.2f, 0f, 0f, 1f)

        // Cache width once per layout pass
        private var lastWidth = 0f

        init {
            // ── content views ───────────────────────────────────────────────
            collapsedContent = LinearLayout(context)
            iconHolder = FrameLayout(context)
            collapsedIcon = ImageView(context)
            stackBadge = TextView(context)
            collapsedTitle = TextView(context)
            pendingCircle = FrameLayout(context)
            pendingIcon = ImageView(context)
            permanentContent = LinearLayout(context)
            permanentDot = View(context)
            permanentSystemRow = LinearLayout(context)
            expandedContent = LinearLayout(context)
            expandedIcon = ImageView(context)
            appLabelView = TextView(context)
            titleView = TextView(context)
            bodyView = TextView(context)
            systemRow = LinearLayout(context)

            // ── background: pure black rectangle with soft inner shadow ────
            backgroundDrawable = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(COLOR_BLACK, COLOR_BLACK_SOFT)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(dp(1).coerceAtLeast(1), COLOR_BLACK)
                // inner semi‑transparent strip creates a “glass” bottom edge
                setPadding(dp(8), dp(8), dp(8), dp(12))
            }

            pill = FrameLayout(context).apply {
                background = backgroundDrawable
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                elevation = dp(12).toFloat()
                outlineAmbientShadowColor = Color.BLACK
                outlineSpotShadowColor = Color.BLACK
                // keep pill size bounded to status‑bar height
                // (layout will set actual width later)
            }
            pillParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(pill, pillParams)

            // ── camera lens (right edge) ────────────────────────────────────
            cameraLens = createCameraLens()

            highlightSweep = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.TRANSPARENT, 0x2EFFFFFF, Color.TRANSPARENT)
                )
                alpha = 0f
            }
            // the sweep lives behind the pill, spans the full height
            pill.addView(highlightSweep, FrameLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.MATCH_PARENT))

            // ── detached counter circle (HyperOS secondary indicator) ───────
            countBubble = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_BLACK)
                }
                elevation = dp(12).toFloat()
                // keep same height as capsule so it sits flush
            }
            countBubbleText = TextView(context).apply {
                setTextColor(COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            countBubble.addView(countBubbleText,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            countBubble.visibility = GONE
            // placed on the right, same height as pill, 5dp gap
            bubbleParams = FrameLayout.LayoutParams(
                dp(BUBBLE_SIZE_DP),      // width = diameter
                dp(BUBBLE_SIZE_DP),      // height = diameter
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { setMargins(0, 0, dp(BUBBLE_GAP_DP), 0) }
            addView(countBubble, bubbleParams)

            // ── content containers ──────────────────────────────────────────
            buildCollapsedContent()
            buildPermanentContent()
            buildExpandedContent()

            // ── camera lens position on top‑right of pill ───────────────────
            pill.addView(
                cameraLens,
                FrameLayout.LayoutParams(
                    dp(HyperIslandSpec.CAMERA_DOT_DP),
                    dp(HyperIslandSpec.CAMERA_DOT_DP),
                    Gravity.END or Gravity.CENTER_VERTICAL
                ).apply { marginEnd = dp(HyperIslandSpec.CAMERA_END_MARGIN_DP) }
            )
        }

        /** ── bind a regular notification ──────────────────────────────── */
        fun bindNotification(
            appLabel: String,
            title: String,
            text: String,
            largeIcon: Drawable,
            smallIcon: Drawable,
            showCollapsed: Boolean = true,
            isClone: Boolean = false
        ) {
            currentAppLabel = appLabel
            currentIsClone = isClone
            val displayLabel = decoratedAppLabel()

            // icons – expanded gets the big one, collapsed the small one
            expandedIcon.clearColorFilter()
            collapsedIcon.clearColorFilter()
            expandedIcon.setImageDrawable(largeIcon)
            collapsedIcon.setImageDrawable(smallIcon)

            // collapsed capsule shows the (possibly “cloned”) app label
            collapsedTitle.text = displayLabel
            appLabelView.text = displayLabel
            titleView.text = title
            bodyView.text = text
            bodyView.visibility = if (text.isBlank() || text == title) GONE else VISIBLE

            if (showCollapsed) showCollapsedNotification()
        }

        /** “· clon” marker so cloned apps are recognisable. */
        private fun decoratedAppLabel(): String =
            if (currentIsClone) "$currentAppLabel · ${context.getString(R.string.clone_badge)}"
            else currentAppLabel

        /** refresh content without collapsing the open card. */
        fun updateNotification(title: String, text: String, largeIcon: Drawable, smallIcon: Drawable) {
            expandedIcon.clearColorFilter()
            collapsedIcon.clearColorFilter()
            expandedIcon.setImageDrawable(largeIcon)
            collapsedIcon.setImageDrawable(smallIcon)
            titleView.text = title
            bodyView.text = text
            bodyView.visibility = if (text.isBlank() || text == title) GONE else VISIBLE
        }

        /** ── system‑function capsule (no notification active) ──────────── */
        fun bindSystemCapsule(
            function: SystemStatusReader.Function,
            pendingCount: Int,
            deviceCount: Int,
            pendingIcon: Drawable?,
            accent: Int
        ) {
            currentAppLabel = function.label
            currentIsClone = false

            // tint both icons with the function’s accent colour
            val icon = tintedIcon(function.iconRes, accent)
            collapsedIcon.setImageDrawable(icon)
            expandedIcon.setImageDrawable(icon)
            collapsedIcon.setColorFilter(accent)
            expandedIcon.setColorFilter(accent)

            collapsedTitle.text = function.label
            appLabelView.text = function.label
            titleView.text = function.label
            bodyView.text = if (deviceCount > 0) "$deviceCount dispositivos conectados" else "Activo"
            bodyView.visibility = VISIBLE

            setEndIcon(pendingIcon)
            cameraLens.visibility = GONE
            setNotificationCount(pendingCount)
            showCollapsedNotification()
        }

        /** tint an icon resource with the given accent colour. */
        private fun tintedIcon(resId: Int, color: Int): Drawable? {
            return ContextCompat.getDrawable(context, resId)?.mutate()?.apply { setTint(color) }
        }

        /** counter in the right‑hand circle. */
        fun setNotificationCount(count: Int) {
            // HyperOS secondary indicator: capsule owns newest event, circle
            // appears once more are queued.
            val visible = count > 1 && expandedContent.visibility != VISIBLE
            countBubbleText.text = if (count > 9) "9+" else count.coerceAtLeast(0).toString()
            applyBubbleVisible(visible)
        }

        fun isCountBubbleVisible(): Boolean = countBubble.visibility == VISIBLE

        private fun applyBubbleVisible(visible: Boolean) {
            countBubble.visibility = if (visible) VISIBLE else GONE
        }

        /** stack badge inside the icon holder plus the detached counter. */
        fun setStackCount(count: Int) {
            val stacked = count > 1
            stackBadge.visibility = if (stacked) VISIBLE else GONE
            stackBadge.text = if (count > 9) "9+" else count.toString()
            setNotificationCount(count)
        }

        /** icon shown by the small circle inside the collapsed capsule. */
        fun setPendingCircle(icon: Drawable, count: Int) {
            pendingIcon.setImageDrawable(icon)
            val wasVisible = pendingCircle.visibility == VISIBLE
            pendingCircle.visibility = VISIBLE
            cameraLens.visibility = GONE

            if (!wasVisible) {
                pendingCircle.alpha = 0f
                pendingCircle.scaleX = 0.35f
                pendingCircle.scaleY = 0.35f
                pendingCircle.translationX = px(14f)
                pendingCircle.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .setDuration(280L)
                    .setInterpolator(OvershootInterpolator(1.5f))
                    .start()
            }
            setNotificationCount(count)
        }

        /** end icon for the system capsule; hides the circle when null. */
        fun setEndIcon(icon: Drawable?) {
            if (icon == null) {
                pendingCircle.visibility = GONE
                return
            }
            pendingIcon.setImageDrawable(icon)
            pendingCircle.visibility = VISIBLE
        }

        /** live system-function rows, refreshed only when the signature changes. */
        fun setSystemFunctions(functions: List<SystemStatusReader.Function>) {
            val signature = functions.joinToString("|") { "${it.id}:${it.badge}" }
            if (signature == lastSystemSignature) return
            lastSystemSignature = signature
            renderFunctionRow(systemRow, functions.take(MAX_SYSTEM_ICONS), 24, 5, 8f)
            renderFunctionRow(permanentSystemRow, functions.take(MAX_COLLAPSED_SYSTEM_ICONS), 18, 4, 7f)
        }

        private fun renderFunctionRow(
            row: LinearLayout,
            functions: List<SystemStatusReader.Function>,
            sizeDp: Int,
            gapDp: Int,
            badgeTextSp: Float
        ) {
            row.removeAllViews()
            if (functions.isEmpty()) {
                row.visibility = GONE
                return
            }
            row.visibility = VISIBLE
            val animate = preferences.islandAnimationsEnabledSync()
            functions.forEachIndexed { index, function ->
                val accent = HyperIslandSpec.systemFunctionColor(function.id)
                val holder = FrameLayout(context)
                val icon = ImageView(context).apply {
                    setImageResource(function.iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val innerPadding = if (sizeDp >= 24) 5 else 4
                    setPadding(
                        dp(innerPadding),
                        dp(innerPadding),
                        dp(innerPadding),
                        dp(innerPadding)
                    )
                    background = circularTile(accent)
                    setColorFilter(HyperIslandSpec.mutedTileColor(accent))
                    contentDescription = function.label
                }
                holder.addView(
                    icon,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                if (function.badge > 0) {
                    val badge = TextView(context).apply {
                        text = if (function.badge > 9) "9+" else function.badge.toString()
                        setTextColor(HyperIslandSpec.COLOR_TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, badgeTextSp)
                        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(accent)
                            setStroke(dp(1).coerceAtLeast(1), COLOR_BLACK)
                        }
                    }
                    holder.addView(
                        badge,
                        FrameLayout.LayoutParams(dp(13), dp(13), Gravity.TOP or Gravity.END)
                    )
                }
                row.addView(
                    holder,
                    LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                        if (index > 0) marginStart = dp(gapDp)
                    }
                )
                if (animate) {
                    holder.alpha = 0f
                    holder.scaleX = 0.5f
                    holder.scaleY = 0.5f
                    holder.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setStartDelay(index * 45L)
                        .setDuration(260L)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }
            }
        }

        fun updateCornerRadius(radiusPx: Float) {
            backgroundDrawable.cornerRadius = radiusPx.coerceAtLeast(0f)
            invalidateOutline()
        }

        /** natural width of the collapsed capsule for the current content. */
        fun measureCollapsedWidth(): Int {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val height = MeasureSpec.makeMeasureSpec(
                dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP),
                MeasureSpec.AT_MOST
            )
            val content = if (permanentContent.visibility == VISIBLE) {
                permanentContent
            } else {
                collapsedContent
            }
            content.measure(unspecified, height)
            return content.measuredWidth
        }

        /** ── expanded card (music player / live activity) ──────────────── */
        private fun buildExpandedContent() {
            expandedContent.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(HyperIslandSpec.CONTENT_START_DP),   // left padding
                    0,
                    dp(HyperIslandSpec.CONTENT_END_DP),   // right padding (space for circle)
                    0
                )
            }

            // ── left‑hand app icon ────────────────────────────────────────
            expandedIcon.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedTile(
                    COLOR_ICON_TILE,
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

            // ── centre‑left: app label + title + body ─────────────────────
            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }

            appLabelView.apply {
                setTextColor(COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                letterSpacing = 0.04f
            }
            copy.addView(appLabelView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

            titleView.apply {
                setTextColor(COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(1), 0, 0)
            }
            copy.addView(titleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

            bodyView.apply {
                setTextColor(COLOR_TEXT_TERTIARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(1), 0, 0)
            }
            copy.addView(bodyView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

            expandedContent.addView(copy,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // ── system row (hotspot, wifi, bluetooth, recorder, music) ────
            systemRow.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // each system function pill: rounded background with subtle gradient
                setPadding(dp(4), 0, dp(4), 0)
                visibility = VISIBLE
            }
            // rows are populated by setSystemFunctions()
            expandedContent.addView(systemRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            )
        }

        /** ── permanent‑resting pill UI ─────────────────────────────────── */
        fun bindPermanent() {
            currentIsClone = false
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
            stopPulse()
            collapsedContent.visibility = GONE
            expandedContent.visibility = GONE
            expandedContent.alpha = 0f
            permanentContent.visibility = VISIBLE
            permanentContent.alpha = 1f
            pendingCircle.visibility = GONE
            cameraLens.visibility = VISIBLE
            setNotificationCount(0)
        }

        /** ── collapse/expand animations ────────────────────────────────── */
        fun expand() {
            applyBubbleVisible(false)
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
            applyBubbleVisible(false)
            collapsedContent.visibility = GONE
            collapsedContent.alpha = 0f
            expandedContent.visibility = VISIBLE
            expandedContent.alpha = 1f
            expandedContent.scaleX = 1f
            expandedContent.scaleY = 1f
        }

        /** ── permanent‑rest UI ─────────────────────────────────────────── */
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

        // ── UI builder methods ──────────────────────────────────────────

        private fun buildCollapsedContent() {
            collapsedContent.orientation = LinearLayout.HORIZONTAL
            collapsedContent.gravity = Gravity.CENTER_VERTICAL
            collapsedContent.setPadding(0, 0, 0, 0)

            // app icon on the left, inside the capsule
            collapsedIcon.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedTile(COLOR_ICON_TILE, dp(7), dp(1))
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            iconHolder.addView(collapsedIcon,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            // clone badge (tiny green oval with black border)
            stackBadge.apply {
                setTextColor(COLOR_BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_BRAND_GREEN)
                    setStroke(dp(1).coerceAtLeast(1), COLOR_BLACK)
                }
                visibility = GONE
            }
            iconHolder.addView(stackBadge,
                FrameLayout.LayoutParams(dp(13), dp(13), Gravity.TOP or Gravity.END))

            // fixed‑size icon holder (22dp) – keeps capsule from expanding
            collapsedContent.addView(iconHolder,
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(10) })

            // app name – truncated, left of the circle
            collapsedTitle.apply {
                setTextColor(COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(120)
                includeFontPadding = false
                letterSpacing = 0.01f
            }
            collapsedContent.addView(collapsedTitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(7) }
            )

            // ── detached counter circle, right of the capsule ──────────────
            // same height as capsule, pure black, no ring – exactly like HyperOS
            pendingCircle.apply {
                background = circularTile(COLOR_ICON_TILE)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                visibility = GONE
                setOnClickListener { onPendingCircleClick?.invoke() }
            }
            pendingIcon.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_ICON_TILE)
                }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            pendingCircle.addView(pendingIcon,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            collapsedContent.addView(pendingCircle,
                LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginStart = dp(7)
                    marginEnd = dp(10)
                })

            // pill itself
            pill.addView(collapsedContent,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        private fun buildPermanentContent() {
            permanentContent.orientation = LinearLayout.HORIZONTAL
            permanentContent.gravity = Gravity.CENTER_VERTICAL
            // leaves room for the camera lens at the right end of the resting pill
            permanentContent.setPadding(0, 0, dp(26), 0)

            // tiny green dot ( indicator while permanent is resting )
            permanentDot.apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_BRAND_GREEN)
                }
                elevation = dp(2).toFloat()
            }
            permanentContent.addView(permanentDot,
                LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(14) })

            // system‑function row (hotspot, wifi, etc.) – hidden by default
            permanentSystemRow.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = GONE
            }
            permanentContent.addView(permanentSystemRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(9) })

            permanentContent.visibility = GONE
            pill.addView(permanentContent,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // ── camera lens (right‑hand circle) ─────────────────────────────
        private fun createCameraLens(): View {
            val lens = FrameLayout(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(COLOR_CAMERA_RING, COLOR_CAMERA)
                ).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(1).coerceAtLeast(1), COLOR_CAMERA_HIGHLIGHT)
                }
            }
            lens.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_CAMERA)
                }
            }, FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER))

            lens.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_CAMERA_HIGHLIGHT)
                }
            }, FrameLayout.LayoutParams(dp(2), dp(2)).apply {
                topMargin = dp(2)
                leftMargin = dp(2)
            })
            return lens
        }

        /** semi‑transparent gradient bar that sweeps across the pill on expand. */
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

        // ── rounding helpers ────────────────────────────────────────────
        private fun roundedTile(color: Int, radius: Int, strokeWidth: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = radius.toFloat()
                if (strokeWidth > 0) {
                    setStroke(strokeWidth, COLOR_STROKE)
                }
            }
        }

        private fun circularTile(color: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(1).coerceAtLeast(1), COLOR_STROKE)
            }
        }

        // ── width accessor used by highlightSweep ───────────────────────
        private val width: Float
            get() = resources.displayMetrics.widthPixels.toFloat() / displayDensity()

        // ── px helper ───────────────────────────────────────────────────
        private fun px(value: Float): Float = value * resources.displayMetrics.density

        override fun onDetachedFromWindow() {
            stopPulse()
            highlightSweep.animate().cancel()
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val TAG = "RyxIslandOverlay"
        // Vertical swipes need the full 50 dp HyperOS uses before dismissing;
        // a fast flick still counts once it travels a third of that distance.
        const val GESTURE_DISMISS_MIN_DP = 30
        const val GESTURE_CYCLE_DP = 90
        const val GESTURE_CYCLE_MIN_DP = 60
        /** Detached counter circle diameter and its gap from the capsule. */
        const val BUBBLE_SIZE_DP = HyperIslandSpec.COLLAPSED_HEIGHT_DP
        const val BUBBLE_GAP_DP = 5
        const val MIN_PERMANENT_WIDTH_DP = 54
        const val MAX_STACKED_ITEMS = 50
        const val MAX_SYSTEM_ICONS = 5
        const val MAX_COLLAPSED_SYSTEM_ICONS = 3
        const val SYSTEM_ICON_DP = 18
        const val SYSTEM_ICON_GAP_DP = 4
    }
}