package com.dav.isla

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Notification
import android.content.BroadcastReceiver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
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
    private var badgeView: LinearLayout? = null

    /** Una tarjeta acumulada en la isla. */
    private class Card(
        val key: String, val pkg: String, val title: CharSequence?, val body: CharSequence?,
        val icon: Drawable?, val accent: Int?, val ephemeral: Boolean, val time: Long = System.currentTimeMillis()
    )
    private val cards = ArrayList<Card>()
    private var cardIndex = 0
    private val currentPkg: String? get() = cards.getOrNull(cardIndex)?.pkg

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun px(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ---------- Ciclo de vida ----------

    override fun onListenerConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        main.post { if (Prefs.islandOn(this) && Prefs.permanent(this)) showIdle() }
        registerSystemReceivers()
        if (Prefs.hideHeadsUp(this)) Thread { Shell.setHeadsUp(this, false) }.start()
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
        if (Prefs.hideHeadsUp(this)) Thread { Shell.setHeadsUp(this, true) }.start()
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
            val media = saveMedia(n)
            Prefs.addHist(this, sbn.packageName, title?.toString() ?: "", text?.toString() ?: "", media.first, media.second)
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
        show(sbn.packageName, title, text, large, true, true, sbn.key, false)
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

    fun clearDemo() {
        main.post {
            main.removeCallbacks(hideTask)
            cards.removeAll { it.pkg == packageName }
            cardIndex = 0
            if (expanded) collapse()
            else if (cards.none { !it.ephemeral } && !Prefs.permanent(this)) removeNow() else updateBadge()
        }
    }

    /** Demo de acumulación: llegan varias notificaciones y se apilan a la derecha. */
    fun demoStack() {
        val items = listOf(Triple("Ana", "¿Vamos hoy?", "message"), Triple("Banco", "Compra aprobada", "bell"),
            Triple("Correo", "Reunión a las 4", "bell"), Triple("Mamá", "Llámame cuando puedas", "phone"))
        items.forEachIndexed { i, t ->
            main.postDelayed({
                show(packageName, t.first, t.second, eventIcon(t.third), i == items.size - 1, true, "demo$i", false)
            }, i * 800L)
        }
    }

    private fun showCard(ev: IslandEvent, force: Boolean) {
        val shouldExpand = !force || Prefs.demoExpanded(this)
        show(packageName, ev.title, ev.body, eventIcon(ev.symbol), ev.short, shouldExpand && Prefs.eventExpand(this, ev.id), "ev:${ev.id}", ev.short)
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
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
        }
        badgeView = badge
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
            addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
            setOnTouchListener(IslandTouch())
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
        island?.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        updateBadge()
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

    private fun show(
        pkg: String, t: CharSequence?, b: CharSequence?, large: Drawable?, autoHide: Boolean = true,
        expand: Boolean = true, key: String = "$pkg|$t", ephemeral: Boolean = false
    ) {
        main.post {
            if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) return@post
            buildIsland()
            val v = island ?: return@post
            val params = lp ?: return@post
            val appIcon: Drawable? = try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
            val shown: Drawable? = when {
                pkg == packageName && large != null -> large                      // iconos de eventos
                Prefs.iconMode(this) == 1 -> large ?: appIcon                     // el de la notificación
                Prefs.iconMode(this) == 2 -> appIcon?.constantState?.newDrawable()?.mutate()?.also {
                    it.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) } // monocromo
                else -> appIcon                                                   // el del sistema
            }
            val accent = if (Prefs.smartColor(this) && pkg != packageName) dominantColor(appIcon) else null

            // Acumulación: la nueva queda al frente y las anteriores esperan a la derecha
            cards.removeAll { it.key == key }
            cards.add(0, Card(key, pkg, t, b, shown, accent, ephemeral))
            val limit = System.currentTimeMillis() - 15 * 60_000L
            cards.removeAll { it.time < limit }
            while (cards.size > 8) cards.removeAt(cards.size - 1)
            cardIndex = 0
            bindCard(cards[0])

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
                updateBadge(); pulse()
            } else if (!expanded) {
                expandNow()
            } else {
                updateBadge(); pulse()
            }
            if (autoHide) main.postDelayed(hideTask, Prefs.durationMs(this))
            if (Prefs.vibrate(this)) buzz()
        }
    }

    private fun expandNow() {
        expanded = true
        island?.gravity = Gravity.CENTER_VERTICAL
        iconView?.visibility = View.VISIBLE
        textCol?.visibility = View.VISIBLE
        setContentAlpha(0f)
        updateBadge()
        morph(true, null)
    }

    /** Pone el contenido de una tarjeta en la isla. */
    private fun bindCard(c: Card) {
        appView?.text = appLabel(c.pkg)
        titleView?.text = c.title
        bodyView?.text = c.body
        iconView?.setImageDrawable(c.icon?.constantState?.newDrawable()?.mutate() ?: c.icon)
        (iconView as? ShapedImageView)?.shape = Prefs.iconShape(this)
        island?.background = buildBackground(style, c.accent)
    }

    /** Acumulación a la derecha: iconos apilados al expandir, contador al reposo. */
    private fun updateBadge() {
        val b = badgeView ?: return
        b.removeAllViews()
        val others: List<Card> = if (expanded) cards.filterIndexed { i, _ -> i != cardIndex } else cards.filter { !it.ephemeral }
        if (others.isEmpty()) { b.visibility = View.GONE; return }
        b.visibility = View.VISIBLE
        if (expanded) {
            others.take(3).forEachIndexed { i, c ->
                b.addView(ShapedImageView(this).apply {
                    shape = Prefs.iconShape(this@NotifListener); scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(c.icon?.constantState?.newDrawable()?.mutate() ?: c.icon)
                    alpha = 1f - 0.18f * i
                }, LinearLayout.LayoutParams(px(20), px(20)).apply { if (i > 0) marginStart = -px(8) })
            }
            if (others.size > 3) b.addView(TextView(this).apply {
                text = "+${others.size - 3}"; setTextColor(Color.WHITE); textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = px(4) })
        } else {
            b.addView(TextView(this).apply {
                text = "${others.size}"; setTextColor(Color.BLACK); textSize = 11f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF8A4C.toInt()) }
            }, LinearLayout.LayoutParams(px(20), px(20)))
        }
    }

    // ---------- Gestos ----------

    private inner class IslandTouch : View.OnTouchListener {
        private var x0 = 0f
        private var y0 = 0f
        private var down = false
        private var longFired = false
        private val slop = ViewConfiguration.get(this@NotifListener).scaledTouchSlop
        private val longRun = Runnable {
            if (down) { longFired = true; doAction(Prefs.gLong(this@NotifListener)) }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; down = true; longFired = false
                    main.removeCallbacks(hideTask)
                    main.postDelayed(longRun, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!down) return true
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (abs(dx) > slop || abs(dy) > slop) main.removeCallbacks(longRun)
                    // la isla sigue al dedo con resistencia, como en HyperOS
                    v.translationY = (dy * 0.55f).coerceIn(-px(30).toFloat(), px(70).toFloat())
                    v.translationX = dx * 0.4f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longRun)
                    val wasDown = down; down = false
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    val th = px(Prefs.swipeDp(this@NotifListener)).toFloat()
                    v.animate().translationX(0f).translationY(0f)
                        .setDuration((380 * Motion.factor(this@NotifListener)).toLong())
                        .setInterpolator(Motion.spring()).start()
                    if (expanded && cards.isNotEmpty() && Prefs.autoHideAfterTouch(this@NotifListener))
                        main.postDelayed(hideTask, Prefs.durationMs(this@NotifListener))
                    if (!wasDown || longFired || e.actionMasked == MotionEvent.ACTION_CANCEL) return true
                    when {
                        dy > th && dy > abs(dx) -> doAction(Prefs.gDown(this@NotifListener))
                        dy < -th && -dy > abs(dx) -> doAction(Prefs.gUp(this@NotifListener))
                        dx > th && dx > abs(dy) -> doAction(Prefs.gRight(this@NotifListener))
                        dx < -th && -dx > abs(dy) -> doAction(Prefs.gLeft(this@NotifListener))
                        abs(dx) < slop && abs(dy) < slop -> doAction(Prefs.gTap(this@NotifListener))
                    }
                }
            }
            return true
        }
    }

    private fun doAction(a: Int) {
        if (a == 0) return
        if (!expanded) {                       // con la isla en reposo, cualquier gesto abre las acumuladas
            if (cards.any { !it.ephemeral }) expandPending()
            return
        }
        when (a) {
            1 -> openCurrent(false)
            2 -> openCurrent(true)
            3 -> dismissCurrent()
            4 -> page(1)
            5 -> page(-1)
            6 -> collapse()
        }
    }

    private fun expandPending() {
        val i = cards.indexOfFirst { !it.ephemeral }
        if (i < 0) return
        cardIndex = i
        bindCard(cards[i])
        main.removeCallbacks(hideTask)
        expandNow()
        main.postDelayed(hideTask, Prefs.durationMs(this))
    }

    private fun page(d: Int) {
        if (cards.size <= 1) { pulse(); return }
        cardIndex = (cardIndex + d + cards.size) % cards.size
        bindCard(cards[cardIndex])
        updateBadge()
        textCol?.translationX = px(28).toFloat() * d
        textCol?.animate()?.translationX(0f)?.setDuration((380 * Motion.factor(this)).toLong())
            ?.setInterpolator(Motion.spring())?.start()
        main.removeCallbacks(hideTask); main.postDelayed(hideTask, Prefs.durationMs(this))
    }

    private fun dismissCurrent() {
        if (cardIndex in cards.indices) cards.removeAt(cardIndex)
        if (cards.isEmpty()) { collapse(); return }
        cardIndex = cardIndex.coerceAtMost(cards.size - 1)
        bindCard(cards[cardIndex]); updateBadge(); pulse()
        main.removeCallbacks(hideTask); main.postDelayed(hideTask, Prefs.durationMs(this))
    }

    // ---------- Medios para el historial ----------

    /** Guarda la imagen o sticker de la notificación (si hay) y devuelve (archivo, tipo). */
    @Suppress("DEPRECATION")
    private fun saveMedia(n: Notification): Pair<String, String> {
        try {
            val ex = n.extras
            var bmp: Bitmap? = ex.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
            var kind = if (bmp != null) "Imagen" else ""
            if (bmp == null && Build.VERSION.SDK_INT >= 31) {
                bmp = ex.getParcelable<android.graphics.drawable.Icon>(Notification.EXTRA_PICTURE_ICON)
                    ?.loadDrawable(this)?.let { toBitmap(it) }
                if (bmp != null) kind = "Imagen"
            }
            // Mensajes con adjunto (fotos, stickers, audio, video)
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            val last = msgs?.lastOrNull() as? android.os.Bundle
            val mime = last?.getString("type") ?: ""
            val uri = last?.getString("uri")
            if (bmp == null && uri != null && mime.startsWith("image/")) {
                try {
                    contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { bmp = BitmapFactory.decodeStream(it) }
                    kind = if (mime.contains("webp") || mime.contains("png")) "Sticker" else "Imagen"
                } catch (e: Exception) {}
            }
            if (kind.isEmpty()) kind = when {
                mime.startsWith("audio/") -> "Audio"
                mime.startsWith("video/") -> "Video"
                else -> ""
            }
            val b = bmp ?: return Pair("", kind)
            val scaled = scaleDown(b, 640)
            val name = "${System.currentTimeMillis()}_${(Math.random() * 99999).toInt()}.img"
            FileOutputStream(File(Prefs.histDir(this), name)).use {
                if (kind == "Sticker") scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
                else scaled.compress(Bitmap.CompressFormat.JPEG, 82, it)
            }
            return Pair(name, kind)
        } catch (e: Throwable) {
            return Pair("", "")
        }
    }

    private fun toBitmap(d: Drawable): Bitmap {
        val w = d.intrinsicWidth.coerceAtLeast(1); val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp); d.setBounds(0, 0, w, h); d.draw(cv)
        return bmp
    }

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= max) return b
        val f = max.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
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
        // los eventos cortos se descartan al cerrarse; las notificaciones quedan acumuladas
        cards.getOrNull(cardIndex)?.let { if (it.ephemeral) cards.remove(it) }
        cardIndex = 0
        expanded = false
        morph(false) {
            if (Prefs.permanent(this) || cards.any { !it.ephemeral }) setIdleContent() else removeNow()
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
        cards.clear()
        cardIndex = 0
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

    private fun openCurrent(floating: Boolean) {
        val pkg = currentPkg ?: return
        // Primero se lanza la app (con la isla aún visible) y luego se descarta la tarjeta
        Thread { openApp(pkg, floating) }.start()
        dismissCurrent()
    }

    private fun openApp(pkg: String, freeform: Boolean) {
        if (freeform) { FloatingWindow.open(this, pkg); return }
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(launch) } catch (e: Exception) {}
    }
}
