package com.dav.isla

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Notification
import android.content.BroadcastReceiver
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
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
        main.post { if (Prefs.islandOn(this) && Prefs.permanent(this)) showIdle() }
        registerSystemReceivers()
        if (Prefs.hideHeadsUp(this)) Thread { Root.setHeadsUp(false) }.start()
    }

    override fun onListenerDisconnected() {
        try { unregisterReceiver(sysReceiver) } catch (e: Exception) {}
        main.post { removeNow() }
        restoreHeadsUp()
        instance = null
    }

    override fun onDestroy() {
        try { unregisterReceiver(sysReceiver) } catch (e: Exception) {}
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
            if (Prefs.islandOn(this) && Prefs.permanent(this)) showIdle()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        var title: CharSequence? = n.extras.getCharSequence(Notification.EXTRA_TITLE)
        var text: CharSequence? = n.extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title == null && text == null) return

        // Palabras ocultas (privacidad): se reemplaza el contenido
        val words = Prefs.hiddenWords(this)
        if (words.isNotEmpty()) {
            val all = "${title ?: ""} ${text ?: ""}".lowercase()
            if (words.any { all.contains(it) }) { title = appLabel(sbn.packageName); text = "Contenido oculto" }
        }

        // 1) La isla REGISTRA todo en el historial, por aplicación
        if (Prefs.keepHistory(this) && !sbn.isOngoing) {
            Prefs.addHist(this, sbn.packageName, title?.toString() ?: "", text?.toString() ?: "")
        }

        // 2) Filtros de la isla
        if (!Prefs.islandOn(this)) return
        if (sbn.packageName in Prefs.blocked(this)) return
        if (!Prefs.lockScreen(this) &&
            (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked) return
        if (sbn.isOngoing) return

        // 3) Solo la isla muestra la notificación: se quita la del sistema
        if (Prefs.blockSystem(this) && sbn.isClearable && !keepInSystem(n)) {
            try { cancelNotification(sbn.key) } catch (e: Exception) {}
        }

        val large: Drawable? = try { n.getLargeIcon()?.loadDrawable(this) } catch (e: Exception) { null }
        show(sbn.packageName, title, text, large)
    }

    /** Llamadas, multimedia y navegación se quedan en el sistema para no romper sus controles. */
    private fun keepInSystem(n: Notification): Boolean =
        n.category in setOf("call", "transport", "navigation", "alarm", "progress")

    // ---------- Eventos del sistema ----------

    private val sysReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> showEvent("plugged")
                Intent.ACTION_POWER_DISCONNECTED -> showEvent("unplugged")
                Intent.ACTION_BATTERY_LOW -> showEvent("low")
                Intent.ACTION_BATTERY_OKAY -> {}
                Intent.ACTION_BATTERY_CHANGED -> {
                    val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    if (st == BatteryManager.BATTERY_STATUS_FULL && !fullShown) {
                        fullShown = true; showEvent("full")
                    } else if (st != BatteryManager.BATTERY_STATUS_FULL) fullShown = false
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isPowerSaveMode) showEvent("saver")
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    if (i.getBooleanExtra("state", false)) showEvent("airplane")
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> showEvent("bt")
            }
        }
    }
    private var fullShown = false

    private fun registerSystemReceivers() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(sysReceiver, f, RECEIVER_EXPORTED)
            else registerReceiver(sysReceiver, f)
        } catch (e: Exception) {}
        // Wi-Fi / datos móviles / VPN
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                private var last = ""
                override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) {
                    val t = when {
                        c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                        c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "data"
                        else -> ""
                    }
                    if (t.isNotEmpty() && t != last) { last = t; showEvent(t) }
                }
            })
        } catch (e: Exception) {}
    }

    /** Muestra un evento (real o de demostración) respetando los ajustes de Inicio. */
    fun showEvent(id: String, force: Boolean = false) {
        val ev = Events.find(id) ?: return
        if (!force && (!Prefs.islandOn(this) || !Prefs.eventOn(this, id))) return
        showCard(ev, force)
    }

    fun clearDemo() { main.post { main.removeCallbacks(hideTask); collapse() } }

    private fun showCard(ev: IslandEvent, force: Boolean) {
        val shouldExpand = !force || Prefs.demoExpanded(this)
        show(packageName, ev.title, ev.body, eventIcon(ev.symbol), ev.short, shouldExpand && Prefs.eventExpand(this, ev.id))
    }

    /** Icono vectorial del evento sobre un círculo de cristal. */
    private fun eventIcon(name: String): Drawable? {
        val ic = Glass.icon(this, name, Color.WHITE) ?: return null
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FF8A4C) }
        return LayerDrawable(arrayOf(bg, ic)).apply { val i = px(8); setLayerInset(1, i, i, i, i) }
    }

    fun showTest() {
        show(packageName, "Ryx Island", "Funciona. Toca para abrir en ventana flotante.", null, true, true)
    }

    // ---------- Construccion de la isla ----------

    private var bgShape: GradientDrawable? = null

    private fun buildBackground(s: IslandStyle, accent: Int? = null): Drawable {
        val cd = Prefs.cornerDp(this)
        val r = px(if (cd >= 0) cd.toFloat() else s.cornerDp).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(s.bgTop, s.bgBottom)
        ).apply {
            cornerRadius = r
            if (accent != null) setStroke(maxOf(1, px(1.6f)), (accent and 0x00FFFFFF) or 0xB0000000.toInt())
            else if (s.strokeDp > 0f) setStroke(maxOf(1, px(s.strokeDp)), s.strokeColor)
        }
        bgShape = base
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

        val icon = ShapedImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            shape = Prefs.iconShape(this@NotifListener)
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
            setTypeface(typeface, if (Prefs.fontBold(this@NotifListener)) Typeface.BOLD else Typeface.NORMAL)
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
            minimumHeight = px(Prefs.heightDp(this@NotifListener))
            alpha = Prefs.opacity(this@NotifListener) / 100f
            if (Prefs.shadow(this@NotifListener)) elevation = px(10).toFloat()
            setPadding(px(s.padH), px(s.padV), px(s.padH + 4), px(s.padV))
            addView(icon, LinearLayout.LayoutParams(px(s.iconDp), px(s.iconDp)))
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(10)
            })
            setOnClickListener { if (expanded && Prefs.tapOpens(this@NotifListener)) openCurrent() }
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
            x = px(Prefs.xOffsetDp(this@NotifListener))
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
        minOf(resources.displayMetrics.widthPixels - px(2 * Prefs.sideMarginDp(this)),
            px(Prefs.expandedWidthDp(this).let { if (it > 0) it else style.expandedWidthDp }))

    private fun setContentAlpha(a: Float) {
        iconView?.alpha = a
        textCol?.alpha = a
    }

    private fun setIdleContent() {
        island?.minimumHeight = px(Prefs.heightDp(this))
        iconView?.scaleX = 1f; iconView?.scaleY = 1f; textCol?.translationX = 0f
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

    private fun show(pkg: String, t: CharSequence?, b: CharSequence?, large: Drawable?, autoHide: Boolean = true, expand: Boolean = true) {
        main.post {
            if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) return@post
            buildIsland()
            val v = island ?: return@post
            val params = lp ?: return@post
            currentPkg = pkg
            appView?.text = appLabel(pkg)
            titleView?.text = t
            bodyView?.text = b
            val appIcon: Drawable? = try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
            val shown: Drawable? = when {
                pkg == packageName && large != null -> large                      // iconos de eventos
                Prefs.iconMode(this) == 1 -> large ?: appIcon                     // el de la notificación
                Prefs.iconMode(this) == 2 -> appIcon?.constantState?.newDrawable()?.mutate()?.also {
                    it.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) } // monocromo
                else -> appIcon                                                   // el del sistema
            }
            iconView?.setImageDrawable(shown)
            (iconView as? ShapedImageView)?.shape = Prefs.iconShape(this)
            val accent = if (Prefs.smartColor(this) && pkg != packageName) dominantColor(appIcon) else null
            v.background = buildBackground(style, accent)
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
            if (!expand && !expanded) {
                pulse()
            } else if (!expanded) {
                expanded = true
                iconView?.visibility = View.VISIBLE
                textCol?.visibility = View.VISIBLE
                setContentAlpha(0f)
                morph(true, null)
            } else {
                pulse()
            }
            if (autoHide) main.postDelayed(hideTask, Prefs.durationMs(this))
            if (Prefs.vibrate(this)) buzz()
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }

    private fun collapse() {
        main.removeCallbacks(hideTask)
        val params = lp ?: return
        if (!attached || !expanded) return
        expanded = false
        morph(false) {
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

    /** Color más vivo del icono (para el borde de la isla). */
    private fun dominantColor(d: Drawable?): Int? {
        if (d == null) return null
        return try {
            val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, 24, 24); d.draw(cv)
            var best = 0; var bestScore = 0f
            val hsv = FloatArray(3)
            for (y in 0 until 24) for (x in 0 until 24) {
                val c = bmp.getPixel(x, y)
                if ((c ushr 24) < 200) continue
                Color.colorToHSV(c, hsv)
                val score = hsv[1] * hsv[2]
                if (score > bestScore) { bestScore = score; best = c }
            }
            if (bestScore < 0.25f) null else best
        } catch (e: Exception) { null }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * Animación tipo HyperOS 3: ancho y alto con resorte (sube un poco y se asienta), la esquina pasa de
     * píldora a tarjeta, y el contenido aparece escalado y deslizado con retraso. Cerrar es suave y sin rebote.
     */
    private fun morph(toExpanded: Boolean, end: (() -> Unit)?) {
        anim?.cancel()
        val params = lp ?: return
        val row = island
        val idleH = px(Prefs.heightDp(this))
        val expH = px(maxOf(style.iconDp + 2 * style.padV, 64))
        val fromW = params.width; val toW = if (toExpanded) expandedWidth() else idleWidth()
        val fromH = (row?.minimumHeight ?: idleH); val toH = if (toExpanded) expH else idleH
        val cd = Prefs.cornerDp(this)
        val cornerPx = px(if (cd >= 0) cd.toFloat() else style.cornerDp).toFloat()
        val fromR = bgShape?.cornerRadius ?: (idleH / 2f)
        val toR = if (toExpanded) minOf(cornerPx, toH / 2f + px(4)) else idleH / 2f
        val maxW = resources.displayMetrics.widthPixels
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (toExpanded) Motion.expandMs(this) else Motion.collapseMs(this)
        a.interpolator = if (toExpanded) Motion.spring() else Motion.smooth()
        a.addUpdateListener { va ->
            val t = va.animatedValue as Float
            val time = va.currentPlayTime.toFloat() / va.duration.toFloat()
            params.width = lerp(fromW.toFloat(), toW.toFloat(), t).toInt().coerceIn(px(40), maxW)
            row?.minimumHeight = lerp(fromH.toFloat(), toH.toFloat(), t).toInt().coerceAtLeast(idleH)
            bgShape?.cornerRadius = lerp(fromR, toR, t.coerceIn(0f, 1f))
            val c = if (toExpanded) ((time - 0.22f) / 0.5f).coerceIn(0f, 1f) else (1f - time / 0.45f).coerceIn(0f, 1f)
            setContentAlpha(c)
            iconView?.scaleX = 0.6f + 0.4f * c; iconView?.scaleY = 0.6f + 0.4f * c
            textCol?.translationX = (1f - c) * px(14)
            if (attached) {
                try { wm.updateViewLayout(island, params) } catch (e: Exception) {}
            }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false
            override fun onAnimationCancel(animation: Animator) { canceled = true }
            override fun onAnimationEnd(animation: Animator) { if (!canceled) end?.invoke() }
        })
        anim = a
        a.start()
    }

    private fun pulse() {
        island?.animate()?.scaleX(1.06f)?.scaleY(1.06f)?.setDuration(110)?.withEndAction {
            island?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration((420 * Motion.factor(this)).toLong())
                ?.setInterpolator(Motion.spring())?.start()
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
        if (freeform) { FloatingWindow.open(this, pkg); return }
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(launch) } catch (e: Exception) {}
    }
}
