package com.d4viddf.hyperbridge.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*;
import android.graphics.Rect;
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
import com.d4viddf.hyperbridge.data.widget.WidgetManager;
import com.d4viddf.hyperbridge.models.HyperIslandSpec;
import com.d4viddf.hyperbridge.models.IslandAlignment;
import com.d4viddf.hyperbridge.models.NotificationCloneDetector;
import kotlin.math.abs;
import kotlin.math.roundToInt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.SupervisorJob;
import kotlinx.coroutines.flow.first;
import kotlinx.coroutines.flow.collect;
import kotlinx.coroutines.launch;
import kotlinx.coroutines.withContext;

const val GLURRY_SIZE = 24f;
const val GLURRY_SAMPLING = 5;

/** Draws the Ryx Isla dynamic island following HyperOS 4 visual language exactly. */
class GenericIslandOverlay(
    private val context: Context,
    private val preferences: AppPreferences,
    private val onNotificationOpened: (String) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var widgetPreviewJob: Job? = null
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
        val contentIntent: PendingIntent?,
        val actions: List<Pair<String, PendingIntent>>,
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
            if (activeNotificationKey == null) {
                val function = functions.firstOrNull()
                if (function == null) {
                    collapseCurrent()
                    return
                }
                view.bindSystemCapsule(
                    function = function,
                    pendingCount = items.size,
                    pendingIcon = selectedItem()?.smallIcon,
                    accent = HyperIslandSpec.systemFunctionColor(function.id)
                )
                view.setSystemFunctions(functions)
                view.expandInstant()
                view.setOnClickListener { openSystemFunctionSettings(function, inFreeform = false) }
                attachGestures(view, selectedItem())
                return
            }
            view.setSystemFunctions(functions)
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

        val accent = function?.let { HyperIslandSpec.systemFunctionColor(it.id) }
            ?: HyperIslandSpec.COLOR_ICON_TILE

        if (function != null) {
            val appIcon = item?.smallIcon
            activeNotificationKey = item?.key
            permanentVisible = false
            view.bindSystemCapsule(
                function = function,
                pendingCount = items.size,
                pendingIcon = appIcon,
                accent = accent
            )
            view.setStackCount(items.size)
            view.setOnClickListener { expandSystemStatus() }
            view.onPendingCircleClick = { expandSelected() }
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
            actions = item.actions,
            isClone = item.isClone
        )
        view.setStackCount(items.size)
        view.setPendingCircle(appIcon, pendingCountFor(item))
        view.setOnClickListener {
            if (expanded) openSelectedNotification() else expandSelected()
        }
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
            params.gravity = Gravity.TOP or if (expanded) Gravity.CENTER_HORIZONTAL else horizontalGravity()
            params.x = if (expanded) 0 else alignmentOffset()
            params.y = islandTopOffset()
            if (expanded) {
                params.width = expandedWidthPx()
                params.height = dp(HyperIslandSpec.EXPANDED_HEIGHT_DP)
                view.setExpandedGeometry(true)
            } else {
                params.width = collapsedWidthPx()
                params.height = dp(HyperIslandSpec.TOUCH_TARGET_HEIGHT_DP)
                view.setExpandedGeometry(false)
            }
            val visualHeight = if (expanded) params.height else dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)
            view.updateCornerRadius(if (expanded) dp(HyperIslandSpec.EXPANDED_CORNER_DP).toFloat() else HyperIslandSpec.cornerRadiusPx(visualHeight.toFloat()))
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
        isWorkProfile: Boolean = false,
        contentIntent: PendingIntent? = null,
        actions: List<Pair<String, PendingIntent>> = emptyList()
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
                contentIntent = contentIntent,
                actions = actions,
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
                    view.renderNotificationActions(item.actions)
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
            widgetPreviewJob?.cancel()
            widgetScope.coroutineContext[Job]?.cancel()
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
            actions = item.actions,
            isClone = item.isClone
        )
        view.setSystemFunctions(currentFunctions)
        view.setPendingCircle(appIcon, pendingCountFor(item))
        view.setNotificationCount(items.size)
        view.contentDescription = "${item.title}. ${item.text}"

        val params = newParams(
            collapsedWidthPx(view),
            dp(HyperIslandSpec.TOUCH_TARGET_HEIGHT_DP)
        )
        try {
            windowManager.addView(view, params)
            root = view
            rootParams = params
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP).toFloat()))
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
            dp(HyperIslandSpec.TOUCH_TARGET_HEIGHT_DP)
        )
        try {
            windowManager.addView(view, params)
            root = view
            rootParams = params
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP).toFloat()))
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
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.x = 0
            view.setExpandedGeometry(true)

            view.bindNotification(
                appLabel = item.appLabel,
                title = item.title,
                text = item.text,
                largeIcon = item.largeIcon ?: appIcon,
                smallIcon = appIcon,
                actions = item.actions,
                isClone = item.isClone
            )
            view.setStackCount(items.size)
            view.setOnClickListener { openNotification(item) }
            attachGestures(view, item)
            view.setSystemFunctions(systemStatus.snapshot())
            loadSavedWidgetPreview(view)

            val expandedWidth = expandedWidthPx()
            val expandedHeight = dp(HyperIslandSpec.EXPANDED_HEIGHT_DP)
            if (!preferences.islandAnimationsEnabledSync()) {
                view.expandInstant()
                params.width = expandedWidth
                params.height = expandedHeight
                view.updateCornerRadius(dp(HyperIslandSpec.EXPANDED_CORNER_DP).toFloat())
                runCatching { windowManager.updateViewLayout(view, params) }
            } else {
                view.expand()
                animateSize(
                    view = view,
                    params = params,
                    targetWidth = expandedWidth,
                    targetHeight = expandedHeight,
                    durationMs = HyperIslandSpec.EXPAND_DURATION_MS,
                    interpolator = OvershootInterpolator(HyperIslandSpec.EXPAND_OVERSHOOT_TENSION)
                )
            }
        }
    }

    private fun openSelectedNotification() {
        val key = selectedNotificationKey ?: activeNotificationKey ?: return
        items[key]?.let(::openNotification)
    }

    private fun openNotification(item: IslandItem, inFreeform: Boolean = false) {
        val options = freeformOptions(inFreeform)
        val opened = if (item.contentIntent != null) {
            if (options == null) {
                runCatching { item.contentIntent.send() }.isSuccess
            } else {
                runCatching {
                    item.contentIntent.send(context, 0, null, null, null, null, options)
                }.recoverCatching {
                    item.contentIntent.send()
                }.isSuccess
            } || launchApp(item.packageName, inFreeform)
        } else {
            launchApp(item.packageName, inFreeform)
        }
        if (opened) onNotificationOpened(item.key)
    }

    private fun openSystemFunctionSettings(
        function: SystemStatusReader.Function,
        inFreeform: Boolean
    ) {
        if (function.id == "music" && function.packageName != null &&
            launchApp(function.packageName, inFreeform)
        ) {
            collapseCurrent()
            return
        }
        collapseCurrent()
        val intents = when (function.id) {
            "hotspot" -> listOf(
                Intent("android.settings.TETHER_SETTINGS"),
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            )
            "bluetooth" -> listOf(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            )
            "music" -> listOf(
                Intent(Settings.ACTION_SOUND_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            else -> listOf(Intent(Settings.ACTION_SETTINGS))
        }
        intents.firstOrNull { launchIntent(it, inFreeform) }
    }

    private fun launchApp(packageName: String, inFreeform: Boolean = false): Boolean {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return false
        return launchIntent(intent, inFreeform)
    }

    private fun launchIntent(intent: Intent, inFreeform: Boolean): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = freeformOptions(inFreeform)
        val launched = runCatching {
            if (options != null) context.startActivity(intent, options)
            else context.startActivity(intent)
        }.isSuccess
        if (launched || options == null) return launched
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun freeformOptions(enabled: Boolean): android.os.Bundle? {
        if (!enabled) return null
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }
            .getOrElse {
                android.graphics.Rect(
                    0,
                    0,
                    context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels
                )
            }
        val width = (bounds.width() * 0.78f).roundToInt().coerceAtLeast(dp(240))
        val height = (bounds.height() * 0.68f).roundToInt().coerceAtLeast(dp(320))
        val left = bounds.left + (bounds.width() - width) / 2
        val top = bounds.top + (bounds.height() - height) / 3
        return ActivityOptions.makeBasic()
            .setLaunchBounds(android.graphics.Rect(left, top, left + width, top + height))
            .toBundle()
    }

    /** Expands the dominant system function when there are no notifications. */
    private fun expandSystemStatus() {
        mainHandler.post {
            val function = dominantFunction() ?: run {
                if (items.isNotEmpty()) expandSelected()
                return@post
            }
            if (expanded) return@post

            var view = root
            if (view == null) {
                showSystemCapsule()
                view = root
            }
            view = view ?: return@post
            val params = rootParams ?: return@post

            expanded = true
            view.setExpandedGeometry(true)
            permanentVisible = false
            activeNotificationKey = null
            view.bindSystemCapsule(
                function = function,
                pendingCount = 0,
                pendingIcon = null,
                accent = HyperIslandSpec.systemFunctionColor(function.id)
            )
            view.setOnClickListener { openSystemFunctionSettings(function, inFreeform = false) }
            attachGestures(view, null)
            view.setSystemFunctions(currentFunctions)
            loadSavedWidgetPreview(view)

            val expandedWidth = expandedWidthPx()
            val expandedHeight = dp(HyperIslandSpec.EXPANDED_HEIGHT_DP)
            if (!preferences.islandAnimationsEnabledSync()) {
                view.expandInstant()
                params.width = expandedWidth
                params.height = expandedHeight
                view.updateCornerRadius(dp(HyperIslandSpec.EXPANDED_CORNER_DP).toFloat())
                runCatching { windowManager.updateViewLayout(view, params) }
            } else {
                view.expand()
                animateSize(
                    view = view,
                    params = params,
                    targetWidth = expandedWidth,
                    targetHeight = expandedHeight,
                    durationMs = HyperIslandSpec.EXPAND_DURATION_MS,
                    interpolator = OvershootInterpolator(HyperIslandSpec.EXPAND_OVERSHOOT_TENSION)
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
        val targetHeight = dp(HyperIslandSpec.TOUCH_TARGET_HEIGHT_DP)
        if (preferences.islandAnimationsEnabledSync()) {
            animateSize(
                view = view,
                params = params,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                durationMs = 240L,
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f),
                onEnd = {
                    params.gravity = Gravity.TOP or horizontalGravity()
                    params.x = alignmentOffset()
                    view.setExpandedGeometry(false)
                    view.updateCornerRadius(
                        HyperIslandSpec.cornerRadiusPx(dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP).toFloat())
                    )
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
            )
        } else {
            params.width = targetWidth
            params.height = targetHeight
            params.gravity = Gravity.TOP or horizontalGravity()
            params.x = alignmentOffset()
            view.setExpandedGeometry(false)
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP).toFloat()))
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    /** Full gesture system: vertical swipes dismiss, horizontal swipes cycle */
    private fun attachGestures(view: HyperIslandView, item: IslandItem?) {
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var downTime = 0L
        var dragging = false
        var tracker: VelocityTracker? = null

        fun start(event: MotionEvent) {
            downX = event.rawX
            downY = event.rawY
            lastX = downX
            lastY = downY
            downTime = event.eventTime
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

        val gestureListener = View.OnTouchListener { touched, event ->
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
                    val heldLongEnough = event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout()
                    val vertical = abs(dy) >= abs(dx)
                    val fling = abs(vy) > minimumFlingVelocity
                    val currentKey = activeNotificationKey ?: item?.key
                    finish(touched)
                    when {
                        !dragging && heldLongEnough -> {
                            val function = dominantFunction()
                                ?.takeIf { !expanded || activeNotificationKey == null }
                            val targetItem = if (function != null && touched === view) {
                                null
                            } else {
                                selectedItem() ?: item
                            }
                            when {
                                targetItem != null -> openNotification(targetItem, inFreeform = true)
                                function != null -> openSystemFunctionSettings(function, inFreeform = true)
                                else -> launchApp(context.packageName, inFreeform = true)
                            }
                        }
                        dragging && vertical && (
                            abs(dy) > dp(HyperIslandSpec.TOUCH_SENSITIVITY_DP) ||
                                (abs(dy) > dp(GESTURE_DISMISS_MIN_DP) && fling)
                        ) -> when {
                            currentKey != null -> {
                                if (dy < 0f) {
                                    items[currentKey]?.let(::openNotification)
                                } else {
                                    dismiss(currentKey)
                                }
                            }
                            expanded -> {
                                if (dy > 0f) {
                                    dominantFunction()?.let { openSystemFunctionSettings(it, inFreeform = true) }
                                } else collapseCurrent()
                            }
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
                        if (currentKey != null) dismiss(currentKey)
                        else if (expanded && dy > 0f) {
                            dominantFunction()?.let { openSystemFunctionSettings(it, inFreeform = true) }
                        } else collapseCurrent()
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
        view.setOnTouchListener(gestureListener)
        fun attachToClickableChildren(parent: ViewGroup) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (child.hasOnClickListeners()) child.setOnTouchListener(gestureListener)
                if (child is ViewGroup) attachToClickableChildren(child)
            }
        }
        attachToClickableChildren(view)
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
            actions = nextItem.actions,
            showCollapsed = !expanded,
            isClone = nextItem.isClone
        )
        view.setStackCount(items.size)
        view.setPendingCircle(appIcon, pendingCountFor(nextItem))
        view.setOnClickListener {
            if (expanded) openNotification(nextItem) else expandSelected()
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
            dp(HyperIslandSpec.TOUCH_TARGET_HEIGHT_DP)
        )

        try {
            windowManager.addView(view, params)
            root = view
            rootParams = params
            permanentVisible = true
            view.showPermanent()
            view.updateCornerRadius(HyperIslandSpec.cornerRadiusPx(dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP).toFloat()))
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
                val compactRadius = HyperIslandSpec.cornerRadiusPx(dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP).toFloat())
                val expandedRadius = dp(HyperIslandSpec.EXPANDED_CORNER_DP).toFloat()
                val targetRadius = if (targetHeight > dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)) expandedRadius else compactRadius
                val startRadius = if (startHeight > dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)) expandedRadius else compactRadius
                view.updateCornerRadius(startRadius + (targetRadius - startRadius) * fraction)
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
        val touchTargetExtra = dp(HyperIslandSpec.TOUCH_TARGET_HEIGHT_DP - HyperIslandSpec.COLLAPSED_HEIGHT_DP) / 2
        val offset = preferences.islandPositionYSync()
            .coerceIn(AppPreferences.MIN_POSITION_Y, AppPreferences.MAX_POSITION_Y)
        return centeredPx - touchTargetExtra + dp(offset)
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

    private fun loadSavedWidgetPreview(view: HyperIslandView) {
        widgetPreviewJob?.cancel()
        view.setWidgetPreview(null)
        widgetPreviewJob = widgetScope.launch {
            val widgetId = runCatching { preferences.savedWidgetIdsFlow.first().firstOrNull() }.getOrNull()
                ?: return@launch
            suspend fun refreshPreview() {
                val bitmap = runCatching {
                    WidgetManager.init(context)
                    WidgetManager.getWidgetBitmap(context, widgetId, dp(144), dp(88))
                }.getOrNull()
                if (root === view && expanded) view.setWidgetPreview(bitmap)
            }
            refreshPreview()
            WidgetManager.widgetUpdates.collect { updatedId ->
                if (updatedId == widgetId && root === view && expanded) refreshPreview()
            }
        }
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
        private val COLOR_BLACK = HyperIslandSpec.COLOR_BLACK
        private val COLOR_BLACK_SOFT = HyperIslandSpec.COLOR_BLACK_SOFT
        private val COLOR_ICON_TILE = HyperIslandSpec.COLOR_ICON_TILE
        private val COLOR_TEXT_PRIMARY = HyperIslandSpec.COLOR_TEXT_PRIMARY
        private val COLOR_TEXT_SECONDARY = HyperIslandSpec.COLOR_TEXT_SECONDARY
        private val COLOR_TEXT_TERTIARY = HyperIslandSpec.COLOR_TEXT_TERTIARY
        private val COLOR_STROKE = HyperIslandSpec.COLOR_STROKE
        private val COLOR_BRAND_GREEN = HyperIslandSpec.COLOR_BRAND_GREEN

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
        private val collapsedAction: TextView
        private val centerAnchor: View
        private val pendingCircle: FrameLayout
        private val pendingIcon: ImageView
        private val permanentContent: LinearLayout
        private val permanentSystemRow: LinearLayout
        private val expandedContent: LinearLayout
        private val expandedIcon: ImageView
        private val appLabelView: TextView
        private val titleView: TextView
        private val bodyView: TextView
        private val systemRow: LinearLayout
        private val musicControlRow: LinearLayout
        private val actionsRow: LinearLayout
        private val widgetPreviewView: ImageView
        private val systemStatusDot: View
        private var primarySystemFunctionId: String? = null
        private val highlightSweep: View
        private var currentAppLabel: String = ""
        private var currentIsClone: Boolean = false
        private var currentActions: List<Pair<String, PendingIntent>> = emptyList()
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
            collapsedAction = TextView(context)
            centerAnchor = View(context)
            pendingCircle = FrameLayout(context)
            pendingIcon = ImageView(context)
            permanentContent = LinearLayout(context)
            permanentSystemRow = LinearLayout(context)
            expandedContent = LinearLayout(context)
            expandedIcon = ImageView(context)
            appLabelView = TextView(context)
            titleView = TextView(context)
            bodyView = TextView(context)
            systemRow = LinearLayout(context)
            musicControlRow = LinearLayout(context)
            actionsRow = LinearLayout(context)
            widgetPreviewView = ImageView(context)
            systemStatusDot = View(context)

            // ── background: pure black rectangle with soft inner shadow ────
            backgroundDrawable = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(COLOR_BLACK, COLOR_BLACK_SOFT)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(dp(1).coerceAtLeast(1), COLOR_STROKE)
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
                dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP),
                Gravity.CENTER
            )
            addView(pill, pillParams)

            // ── camera lens (right edge) ────────────────────────────────────

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
        }

        /** ── bind a regular notification ──────────────────────────────── */
        fun bindNotification(
            appLabel: String,
            title: String,
            text: String,
            largeIcon: Drawable,
            smallIcon: Drawable,
            actions: List<Pair<String, PendingIntent>> = emptyList(),
            showCollapsed: Boolean = true,
            isClone: Boolean = false
        ) {
            currentAppLabel = appLabel
            currentIsClone = isClone
            currentActions = actions
            primarySystemFunctionId = null
            systemStatusDot.visibility = GONE
            val displayLabel = decoratedAppLabel()

            // icons – expanded gets the big one, collapsed the small one
            expandedIcon.clearColorFilter()
            collapsedIcon.clearColorFilter()
            expandedIcon.background = roundedTile(COLOR_ICON_TILE, dp(HyperIslandSpec.ICON_CORNER_DP), dp(1))
            expandedIcon.setImageDrawable(largeIcon)
            collapsedIcon.setImageDrawable(smallIcon)

            // collapsed capsule shows the (possibly “cloned”) app label
            collapsedTitle.text = displayLabel
            collapsedAction.text = "Notificación"
            appLabelView.text = displayLabel
            titleView.text = title
            bodyView.text = text
            bodyView.visibility = if (text.isBlank() || text == title) GONE else VISIBLE
            renderNotificationActions(actions)

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
            pendingIcon: Drawable?,
            accent: Int
        ) {
            currentAppLabel = function.label
            currentIsClone = false
            primarySystemFunctionId = function.id
            systemStatusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_BRAND_GREEN)
            }
            systemStatusDot.visibility = VISIBLE

            // tint both icons with the function’s accent colour
            val icon = tintedIcon(function.iconRes, accent)
            collapsedIcon.setImageDrawable(icon)
            expandedIcon.setImageDrawable(icon)
            collapsedIcon.setColorFilter(accent)
            expandedIcon.background = roundedTile(accent, dp(HyperIslandSpec.ICON_CORNER_DP), 0)
            expandedIcon.setColorFilter(Color.WHITE)

            collapsedTitle.text = function.label
            collapsedAction.text = function.detail.ifBlank { "Activo" }
            appLabelView.text = context.getString(R.string.island_system_label)
            titleView.text = function.label
            bodyView.text = function.detail.ifBlank { "Activo" }
            bodyView.visibility = VISIBLE

            setEndIcon(pendingIcon)
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
            val signature = functions.joinToString("|") {
                "${it.id}:${it.label}:${it.detail}:${it.badge}:${it.packageName}:${it.isPlaying}"
            }
            if (signature == lastSystemSignature) return
            lastSystemSignature = signature
            renderFunctionRow(
                systemRow,
                if (currentActions.isEmpty()) functions.filterNot { it.id == primarySystemFunctionId }.take(MAX_SYSTEM_ICONS) else emptyList(),
                24,
                5,
                8f
            )
            renderMusicControls(if (currentActions.isEmpty()) functions.firstOrNull { it.id == "music" } else null)
            renderFunctionRow(permanentSystemRow, functions.take(MAX_COLLAPSED_SYSTEM_ICONS), 18, 4, 7f)
        }

        fun setWidgetPreview(bitmap: android.graphics.Bitmap?) {
            widgetPreviewView.setImageBitmap(bitmap)
            widgetPreviewView.visibility = if (bitmap == null) GONE else VISIBLE
        }

        fun renderNotificationActions(actions: List<Pair<String, PendingIntent>>) {
            actionsRow.removeAllViews()
            actions.take(2).forEachIndexed { index, (label, intent) ->
                val lower = label.lowercase()
                val accent = when {
                    lower.contains("rechaz") || lower.contains("declin") || lower.contains("reject") -> HyperIslandSpec.COLOR_ACCENT_RED
                    lower.contains("acept") || lower.contains("answer") || lower.contains("respond") -> HyperIslandSpec.COLOR_BRAND_GREEN
                    index == 0 && actions.size > 1 -> HyperIslandSpec.COLOR_ACCENT_RED
                    index == 1 -> HyperIslandSpec.COLOR_BRAND_GREEN
                    else -> HyperIslandSpec.COLOR_ICON_TILE
                }
                val button = TextView(context).apply {
                    text = label.take(1).uppercase()
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    contentDescription = label
                    isClickable = true
                    isFocusable = true
                    background = circularTile(accent)
                    setOnClickListener { runCatching { intent.send() } }
                }
                actionsRow.addView(button, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
            actionsRow.visibility = if (actions.isEmpty()) GONE else VISIBLE
        }

        private fun renderMusicControls(music: SystemStatusReader.Function?) {
            musicControlRow.removeAllViews()
            val controller = music?.mediaController
            if (controller == null) {
                musicControlRow.visibility = GONE
                return
            }
            musicControlRow.visibility = VISIBLE
            val controls = controller.transportControls
            val buttons = listOf(
                Triple("‹", "Pista anterior") { controls.skipToPrevious() },
                Triple(if (music.isPlaying) "Ⅱ" else "▶", if (music.isPlaying) "Pausar" else "Reproducir") {
                    if (music.isPlaying) controls.pause() else controls.play()
                },
                Triple("›", "Pista siguiente") { controls.skipToNext() }
            )
            buttons.forEach { (glyph, description, action) ->
                val control = TextView(context).apply {
                    text = glyph
                    gravity = Gravity.CENTER
                    setTextColor(COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    contentDescription = description
                    isClickable = true
                    isFocusable = true
                    background = circularTile(HyperIslandSpec.COLOR_ICON_TILE)
                    setOnClickListener { runCatching { action() } }
                }
                musicControlRow.addView(control, LinearLayout.LayoutParams(dp(30), dp(34)).apply {
                    marginStart = dp(3)
                })
            }
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

        fun setExpandedGeometry(expanded: Boolean) {
            pillParams.height = if (expanded) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                dp(HyperIslandSpec.COLLAPSED_HEIGHT_DP)
            }
            pillParams.gravity = Gravity.CENTER
            pill.requestLayout()
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
                visibility = GONE
                setPadding(
                    dp(HyperIslandSpec.CONTENT_START_DP),   // left padding
                    dp(6),
                    dp(HyperIslandSpec.CONTENT_END_DP),   // right padding (space for circle)
                    dp(6)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(2), 0, 0)
            }
            copy.addView(titleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

            bodyView.apply {
                setTextColor(COLOR_TEXT_TERTIARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setLineSpacing(dp(1).toFloat(), 1f)
                setPadding(0, dp(2), 0, 0)
            }
            copy.addView(bodyView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

            expandedContent.addView(copy,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            widgetPreviewView.apply {
                visibility = GONE
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedTile(COLOR_ICON_TILE, dp(9), dp(1))
                clipToOutline = true
                contentDescription = "Widget guardado"
            }
            expandedContent.addView(widgetPreviewView,
                LinearLayout.LayoutParams(dp(72), dp(56)).apply { marginStart = dp(6) })

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
            musicControlRow.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = GONE
            }
            expandedContent.addView(musicControlRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(5) })
            actionsRow.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = GONE
            }
            expandedContent.addView(actionsRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) })
            expandedContent.addView(
                systemStatusDot,
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(8) }
            )

            // The expanded card must be attached to the pill: without this the
            // island grows but shows an empty black capsule.
            pill.addView(
                expandedContent,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        /** ── permanent‑resting pill UI ─────────────────────────────────── */
        fun bindPermanent() {
            currentIsClone = false
            showPermanent()
        }

        fun showCollapsedNotification() {
            expandedContent.visibility = GONE
            expandedContent.alpha = 0f
            expandedContent.scaleX = 1f
            expandedContent.scaleY = 1f
            expandedContent.translationY = 0f
            permanentContent.visibility = GONE
            collapsedContent.visibility = VISIBLE
            collapsedContent.alpha = 1f
            collapsedContent.scaleX = 1f
            collapsedContent.scaleY = 1f
            collapsedContent.translationY = 0f
        }

        fun showPermanent() {
            primarySystemFunctionId = null
            systemStatusDot.visibility = GONE
            collapsedContent.visibility = GONE
            expandedContent.visibility = GONE
            expandedContent.alpha = 0f
            permanentContent.visibility = VISIBLE
            permanentContent.alpha = 1f
            pendingCircle.visibility = GONE
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
            expandedContent.translationY = 0f
        }

        /** ── permanent‑rest UI ─────────────────────────────────────────── */
        // ── UI builder methods ──────────────────────────────────────────

        private fun buildCollapsedContent() {
            collapsedContent.orientation = LinearLayout.HORIZONTAL
            collapsedContent.gravity = Gravity.CENTER_VERTICAL
            collapsedContent.setPadding(dp(10), 0, dp(10), 0)

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
            collapsedContent.addView(iconHolder, LinearLayout.LayoutParams(dp(22), dp(22)))

            // app name – truncated, left of the circle
            collapsedTitle.apply {
                setTextColor(COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(54)
                includeFontPadding = false
                letterSpacing = 0.01f
            }
            collapsedContent.addView(collapsedTitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(7) }
            )

            // Overlay the marker on the pill's true geometric midpoint.
            centerAnchor.apply {
                background = circularTile(0xFF66666B.toInt())
                contentDescription = "Centro de expansión"
            }

            collapsedAction.apply {
                setTextColor(COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(64)
                includeFontPadding = false
                gravity = Gravity.END
            }
            collapsedContent.addView(collapsedAction,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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

            // The tiny center marker replaces the old detached app icon; the
            // pending count remains available through the separate badge.

            // pill itself
            pill.addView(collapsedContent,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            pill.addView(centerAnchor, FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER))
        }

        private fun buildPermanentContent() {
            permanentContent.orientation = LinearLayout.HORIZONTAL
            permanentContent.gravity = Gravity.CENTER
            permanentContent.setPadding(0, 0, 0, 0)


            // system‑function row (hotspot, wifi, etc.) – hidden by default
            permanentSystemRow.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = GONE
            }
            permanentContent.addView(permanentSystemRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            permanentContent.visibility = GONE
            pill.addView(permanentContent,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // ── camera lens (right‑hand circle) ─────────────────────────────
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
            highlightSweep.animate().cancel()
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val TAG = "RyxIslandOverlay"
        // Short swipes and flicks dismiss the island without requiring a long drag.
        const val GESTURE_DISMISS_MIN_DP = 16
        const val GESTURE_CYCLE_DP = 56
        const val GESTURE_CYCLE_MIN_DP = 28
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
