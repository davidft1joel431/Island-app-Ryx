package com.d4viddf.hyperbridge.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import android.util.Log
import android.view.ViewGroup
import android.graphics.drawable.Drawable

/** Renders a small Android overlay for devices without Xiaomi's native HyperIsland renderer. */
class GenericIslandOverlay(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var activeNotificationKey: String? = null
    private var dismissTask: Runnable? = null
    private var touchStartY = 0f

    fun show(
        notificationKey: String,
        packageName: String,
        title: String,
        text: String,
        isOngoing: Boolean,
        timeoutSeconds: Int
    ) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(context)) return@post

            removeCurrentView()
            activeNotificationKey = notificationKey

            val appLabel = getApplicationLabel(packageName)
            val icon = getNotificationIcon(packageName)
            val container = createIslandView(appLabel, title.ifBlank { appLabel }, text, icon)
            container.contentDescription = "$appLabel. ${title.ifBlank { appLabel }}. $text"
            container.setOnClickListener {
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    }
                }
                dismiss(notificationKey)
            }
            container.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (touchStartY - event.rawY > dp(36)) dismiss(notificationKey)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (touchStartY - event.rawY > dp(28)) {
                            dismiss(notificationKey)
                            true
                        } else {
                            view.performClick()
                            true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }

            val screenWidth = context.resources.displayMetrics.widthPixels
            val params = WindowManager.LayoutParams(
                (screenWidth - dp(28)).coerceAtLeast(dp(240)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBarHeight() + dp(12)
            }

            try {
                windowManager.addView(container, params)
                root = container
            } catch (error: Exception) {
                Log.w(TAG, "Could not show the generic island overlay", error)
                root = null
                activeNotificationKey = null
                return@post
            }

            dismissTask?.let(mainHandler::removeCallbacks)
            if (!isOngoing) {
                val visibleFor = timeoutSeconds.coerceIn(3, 15) * 1000L
                dismissTask = Runnable { dismiss(notificationKey) }.also {
                    mainHandler.postDelayed(it, visibleFor)
                }
            }
        }
    }

    fun dismiss(notificationKey: String? = null) {
        mainHandler.post {
            if (notificationKey != null && notificationKey != activeNotificationKey) return@post
            dismissTask?.let(mainHandler::removeCallbacks)
            dismissTask = null
            removeCurrentView()
            activeNotificationKey = null
        }
    }

    private fun createIslandView(appLabel: String, title: String, text: String, icon: Drawable): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(18), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(27, 24, 17), Color.rgb(47, 37, 16))
            ).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.rgb(221, 177, 54))
            }
            elevation = dp(12).toFloat()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val appIcon = ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(Color.rgb(238, 184, 39))
            }
            clipToOutline = true
        }
        card.addView(appIcon, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(13)
        })

        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(context).apply {
            this.text = appLabel
            setTextColor(Color.rgb(242, 193, 68))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        copy.addView(TextView(context).apply {
            this.text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (text.isNotBlank() && text != title) {
            copy.addView(TextView(context).apply {
                this.text = text
                setTextColor(Color.rgb(220, 215, 202))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        card.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun getApplicationLabel(packageName: String): String = runCatching {
        val app = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(app).toString()
    }.getOrDefault(packageName)

    private fun getNotificationIcon(packageName: String): Drawable = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrElse {
        context.getDrawable(android.R.drawable.sym_def_app_icon)!!
    }

    private fun removeCurrentView() {
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "RyxIslandOverlay"
    }
}
