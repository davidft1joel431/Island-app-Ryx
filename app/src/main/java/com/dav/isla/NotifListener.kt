package com.dav.isla

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Notification
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
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
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var attached = false
    private var currentPkg: String? = null

    private val dp: Float get() = resources.displayMetrics.density

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
        show(sbn.packageName, title, text)
    }

    fun showTest() {
        show(packageName, "Isla Propia", "Funciona. Toca para abrir en ventana flotante.")
    }

    // ---------- Vista de la isla ----------

    private fun buildIsland() {
        if (island != null) return
        val bg = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 40 * dp
        }
        val icon = ImageView(this)
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
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
            addView(title)
            addView(body)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            val p = (10 * dp).toInt()
            setPadding(p, p, p, p)
            addView(icon, LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()))
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (10 * dp).toInt()
            })
            setOnClickListener { openCurrent() }
            setOnLongClickListener { collapse(); true }
        }
        island = row
        iconView = icon
        titleView = title
        bodyView = body
        lp = WindowManager.LayoutParams(
            (90 * dp).toInt(),
            (52 * dp).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * dp).toInt()
        }
    }

    private fun expandedWidth(): Int =
        minOf(resources.displayMetrics.widthPixels - (24 * dp).toInt(), (340 * dp).toInt())

    private fun show(pkg: String, t: CharSequence?, b: CharSequence?) {
        main.post {
            if (!Settings.canDrawOverlays(this) || !::wm.isInitialized) return@post
            buildIsland()
            val v = island ?: return@post
            val params = lp ?: return@post
            currentPkg = pkg
            titleView?.text = t
            bodyView?.text = b
            iconView?.setImageDrawable(
                try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
            )
            main.removeCallbacks(hideTask)
            if (!attached) {
                params.width = (90 * dp).toInt()
                wm.addView(v, params)
                attached = true
                animateWidth(params.width, expandedWidth(), null)
            }
            main.postDelayed(hideTask, 4500)
        }
    }

    private fun animateWidth(from: Int, to: Int, end: (() -> Unit)?) {
        ValueAnimator.ofInt(from, to).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val params = lp ?: return@addUpdateListener
                params.width = it.animatedValue as Int
                if (attached) {
                    try { wm.updateViewLayout(island, params) } catch (e: Exception) { }
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

    private fun collapse() {
        main.removeCallbacks(hideTask)
        val params = lp ?: return
        if (!attached) return
        animateWidth(params.width, (90 * dp).toInt()) { removeNow() }
    }

    private fun removeNow() {
        main.removeCallbacks(hideTask)
        if (attached && ::wm.isInitialized) {
            try { wm.removeView(island) } catch (e: Exception) { }
        }
        attached = false
    }

    // ---------- Abrir app en ventana flotante ----------

    private fun openCurrent() {
        val pkg = currentPkg ?: return
        collapse()
        Thread { openFreeform(pkg) }.start()
    }

    private fun openFreeform(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        val comp = launch.component?.flattenToShortString()

        // 1) Con root: windowingMode 5 = freeform
        if (comp != null) {
            try {
                val p = Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "am start --windowingMode 5 -n $comp"))
                if (p.waitFor() == 0) return
            } catch (e: Exception) { }
        }

        // 2) Sin root: API de ActivityOptions (puede fallar segun la ROM)
        try {
            val opts = ActivityOptions.makeBasic()
            try {
                ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(opts, 5)
            } catch (e: Throwable) { }
            val m = resources.displayMetrics
            opts.setLaunchBounds(
                Rect(m.widthPixels / 10, m.heightPixels / 6, m.widthPixels * 9 / 10, m.heightPixels * 2 / 3)
            )
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch, opts.toBundle())
        } catch (e: Exception) { }
    }
}
