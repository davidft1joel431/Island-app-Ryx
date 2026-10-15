package com.dav.isla

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Notification
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotifListener? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { collapse() }
    private lateinit var wm: WindowManager
    private var lp: WindowManager.LayoutParams? = null
    private var island: LinearLayout? = null
    private var iconView: ImageView? = null
    private var textCol: LinearLayout? = null
    private var appView: TextView? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var attached = false
    private var currentPkg: String? = null

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onListenerConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onListenerDisconnected() {
        main.post { removeNow() }
        instance = null
    }

    override fun onDestroy() {
        removeNow()
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (sbn.isOngoing) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title == null && text == null) return
        val large: Drawable? = try {
            n.getLargeIcon()?.loadDrawable(this)
        } catch (e: Exception) {
            null
        }
        show(sbn.packageName, title, text, large)
    }

    fun showTest() {
        show(packageName, "Ryx Island", "Funciona. Toca para abrir en ventana flotante.", null)
    }

    // ---------- Vista de la isla ----------

    private fun buildIsland() {
        if (island != null) return
        val gold = getColor(R.color.gold)

        val bg = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = px(40).toFloat()
            setStroke(px(1), 0x33FFFFFF)
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, px(10).toFloat())
                }
            }
            clipToOutline = true
        }
        val app = TextView(this).apply {
            setTextColor(gold)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }
        val body = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(app)
            addView(title)
            addView(body)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(px(12), px(10), px(18), px(10))
            addView(icon, LinearLayout.LayoutParams(px(38), px(38)))
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(10)
            })
            setOnClickListener { openCurrent() }
            setOnLongClickListener { collapse(); true }
        }
        island = row
        iconView = icon
        textCol = col
        appView = app
        titleView = title
        bodyView = body

        lp = WindowManager.LayoutParams(
            px(90),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = px(8)
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun expandedWidth(): Int =
        minOf(resources.displayMetrics.widthPixels - px(24), px(340))

    private fun setContentAlpha(a: Float) {
        iconView?.alpha = a
        textCol?.alpha = a
    }

    private fun show(pkg: String, t: CharSequence?, b: CharSequence?, large: Drawable?) {
        main.post {
            if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) return@post
            buildIsland()
            val v = island ?: return@post
            val params = lp ?: return@post
            currentPkg = pkg
            appView?.text = appLabel(pkg)
            titleView?.text = t
            bodyView?.text = b
            iconView?.setImageDrawable(
                large ?: try {
                    packageManager.getApplicationIcon(pkg)
                } catch (e: Exception) {
                    null
                }
            )
            main.removeCallbacks(hideTask)
            if (!attached) {
                params.width = px(90)
                setContentAlpha(0f)
                try {
                    wm.addView(v, params)
                } catch (e: Exception) {
                    return@post
                }
                attached = true
                animateWidth(params.width, expandedWidth(), true, null)
            } else {
                pulse()
            }
            main.postDelayed(hideTask, Prefs.durationMs(this))
        }
    }

    @Suppress("DEPRECATION")
    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun animateWidth(from: Int, to: Int, expanding: Boolean, end: (() -> Unit)?) {
        ValueAnimator.ofInt(from, to).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val params = lp ?: return@addUpdateListener
                params.width = anim.animatedValue as Int
                val f = anim.animatedFraction
                setContentAlpha(if (expanding) f else 1f - f)
                if (attached) {
                    try {
                        wm.updateViewLayout(island, params)
                    } catch (e: Exception) {
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    end?.invoke()
                }
            })
            start()
        }
    }

    private fun pulse() {
        island?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.setDuration(110)?.withEndAction {
            island?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
        }?.start()
    }

    private fun collapse() {
        main.removeCallbacks(hideTask)
        val params = lp ?: return
        if (!attached) return
        animateWidth(params.width, px(90), false) { removeNow() }
    }

    private fun removeNow() {
        main.removeCallbacks(hideTask)
        if (attached && ::wm.isInitialized) {
            try {
                wm.removeView(island)
            } catch (e: Exception) {
            }
        }
        attached = false
    }

    // ---------- Abrir app ----------

    private fun openCurrent() {
        val pkg = currentPkg ?: return
        val freeform = Prefs.freeform(this)
        // Primero se lanza la app (con la isla aun visible) y luego se cierra la isla
        Thread { openApp(pkg, freeform) }.start()
        collapse()
    }

    private fun openApp(pkg: String, freeform: Boolean) {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (freeform) {
            // 1) Con root: windowingMode 5 = freeform
            val comp = launch.component?.flattenToShortString()
            if (comp != null) {
                try {
                    val p = Runtime.getRuntime()
                        .exec(arrayOf("su", "-c", "am start --windowingMode 5 -n $comp"))
                    if (p.waitFor() == 0) return
                } catch (e: Exception) {
                }
            }
            // 2) Sin root: API oculta de ActivityOptions (depende de la ROM)
            try {
                val opts = ActivityOptions.makeBasic()
                try {
                    ActivityOptions::class.java
                        .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                        .invoke(opts, 5)
                } catch (e: Throwable) {
                }
                val m = resources.displayMetrics
                opts.setLaunchBounds(
                    Rect(m.widthPixels / 10, m.heightPixels / 6, m.widthPixels * 9 / 10, m.heightPixels * 2 / 3)
                )
                startActivity(launch, opts.toBundle())
                return
            } catch (e: Exception) {
            }
        }

        // 3) Apertura normal
        try {
            startActivity(launch)
        } catch (e: Exception) {
        }
    }
}
