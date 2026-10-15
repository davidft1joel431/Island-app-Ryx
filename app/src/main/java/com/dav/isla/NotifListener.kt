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
import android.graphics.drawable.LayerDrawable
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
    private var style: IslandStyle = Styles.HYPEROS
    private var attached = false
    private var expanded = false
    private var anim: ValueAnimator? = null
    private var currentPkg: String? = null

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun px(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ---------- Ciclo de vida ----------

    override fun onListenerConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        main.post { if (Prefs.permanent(this)) showIdle() }
        if (Prefs.hideHeadsUp(this)) Thread { Root.setHeadsUp(false) }.start()
    }

    override fun onListenerDisconnected() {
        main.post { removeNow() }
        restoreHeadsUp()
        instance = null
    }

    override fun onDestroy() {
        anim?.cancel()
        removeNow()
        restoreHeadsUp()
        instance = null
        super.onDestroy()
    }

    // Si la isla deja de funcionar, se devuelven los avisos del sistema para no perder notificaciones
    private fun restoreHeadsUp() {
        if (Prefs.hideHeadsUp(this)) Thread { Root.setHeadsUp(true) }.start()
    }

    /** Se llama desde la pantalla de ajustes cuando cambia estilo, posicion o modo permanente. */
    fun applySettings() {
        main.post {
            if (!::wm.isInitialized) return@post
            anim?.cancel()
            removeNow()
            island = null
            if (Prefs.permanent(this)) showIdle()
        }
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

    // ---------- Construccion de la isla ----------

    private fun buildBackground(s: IslandStyle): Drawable {
        val r = px(s.cornerDp).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(s.bgTop, s.bgBottom)
        ).apply {
            cornerRadius = r
            if (s.strokeDp > 0f) setStroke(maxOf(1, px(s.strokeDp)), s.strokeColor)
        }
        if (s.highlight == 0) return base
        val glow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(s.highlight, 0x00FFFFFF)
        ).apply { cornerRadius = r }
        return LayerDrawable(arrayOf(base, glow)).apply {
            val i = px(1)
            setLayerInset(1, i, i, i, i)
        }
    }

    private fun buildIsland() {
        if (island != null) return
        val s = Styles.byId(Prefs.style(this))
        style = s

        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (s.iconCircle) {
                        outline.setOval(0, 0, view.width, view.height)
                    } else {
                        outline.setRoundRect(0, 0, view.width, view.height, px(s.iconCornerDp).toFloat())
                    }
                }
            }
            clipToOutline = true
        }
        val app = TextView(this).apply {
            setTextColor(getColor(R.color.gold))
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = if (s.showAppName) View.VISIBLE else View.GONE
        }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = s.titleSp
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }
        val body = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = s.bodySp
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
            background = buildBackground(s)
            minimumHeight = px(32)
            setPadding(px(s.padH), px(s.padV), px(s.padH + 4), px(s.padV))
            addView(icon, LinearLayout.LayoutParams(px(s.iconDp), px(s.iconDp)))
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(10)
            })
            setOnClickListener { if (expanded) openCurrent() }
            setOnLongClickListener {
                if (expanded) {
                    collapse()
                    true
                } else {
                    false
                }
            }
        }
        island = row
        iconView = icon
        textCol = col
        appView = app
        titleView = title
        bodyView = body

        val yPx = px(Prefs.yOffsetDp(this))
        lp = WindowManager.LayoutParams(
            idleWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = yPx
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun idleWidth(): Int = px(Prefs.idleWidthDp(this))

    private fun expandedWidth(): Int =
        minOf(resources.displayMetrics.widthPixels - px(24), px(style.expandedWidthDp))

    private fun setContentAlpha(a: Float) {
        iconView?.alpha = a
        textCol?.alpha = a
    }

    private fun setIdleContent() {
        iconView?.visibility = View.GONE
        textCol?.visibility = View.GONE
        setContentAlpha(1f)
    }

    // ---------- Estados: oculta / reposo (permanente) / expandida ----------

    private fun showIdle() {
        if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) return
        buildIsland()
        val v = island ?: return
        val params = lp ?: return
        if (!attached) {
            params.width = idleWidth()
            setIdleContent()
            try {
                wm.addView(v, params)
            } catch (e: Exception) {
                return
            }
            attached = true
        }
        expanded = false
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
                params.width = idleWidth()
                setIdleContent()
                try {
                    wm.addView(v, params)
                } catch (e: Exception) {
                    return@post
                }
                attached = true
            }
            if (!expanded) {
                expanded = true
                iconView?.visibility = View.VISIBLE
                textCol?.visibility = View.VISIBLE
                setContentAlpha(0f)
                animateWidth(params.width, expandedWidth(), true, null)
            } else {
                pulse()
            }
            main.postDelayed(hideTask, Prefs.durationMs(this))
        }
    }

    private fun collapse() {
        main.removeCallbacks(hideTask)
        val params = lp ?: return
        if (!attached || !expanded) return
        expanded = false
        animateWidth(params.width, idleWidth(), false) {
            if (Prefs.permanent(this)) setIdleContent() else removeNow()
        }
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
        expanded = false
    }

    private fun animateWidth(from: Int, to: Int, expanding: Boolean, end: (() -> Unit)?) {
        anim?.cancel()
        val a = ValueAnimator.ofInt(from, to)
        a.duration = 280
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { va ->
            val params = lp ?: return@addUpdateListener
            params.width = va.animatedValue as Int
            val f = va.animatedFraction
            setContentAlpha(if (expanding) f else 1f - f)
            if (attached) {
                try {
                    wm.updateViewLayout(island, params)
                } catch (e: Exception) {
                }
            }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false
            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!canceled) end?.invoke()
            }
        })
        anim = a
        a.start()
    }

    private fun pulse() {
        island?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.setDuration(110)?.withEndAction {
            island?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
        }?.start()
    }

    @Suppress("DEPRECATION")
    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
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
            // 1) Con root. Si la app ya estaba abierta en pantalla completa, "am start" solo
            //    la trae al frente en ese mismo modo; por eso se fuerza una tarea nueva.
            val comp = launch.component?.flattenToShortString()
            if (comp != null) {
                val extra = if (Prefs.forceNew(this)) "--activity-new-task --activity-multiple-task" else ""
                val cmd = "settings put global enable_freeform_support 1; " +
                    "settings put global force_resizable_activities 1; " +
                    "am start --user 0 --windowingMode 5 $extra -n $comp"
                val res = Root.run(cmd)
                Prefs.setLastLog(this, "Comando:\n$cmd\n\nCodigo: ${res.first}\n${res.second}")
                if (res.first == 0 && !res.second.contains("Error", ignoreCase = true)) return
            } else {
                Prefs.setLastLog(this, "No se encontro la actividad principal de $pkg")
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
