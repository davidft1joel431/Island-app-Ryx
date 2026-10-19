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
import android.view.HapticFeedbackConstants
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotifListener? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val histExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Notificaciones que ya leíste (firma → hora): si la app las reenvía iguales, no vuelven a la isla. */
    private val readSigs = HashMap<String, Long>()
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
    private var countView: TextView? = null
    private var detailImg: ImageView? = null
    private var actionsRow: LinearLayout? = null
    private var detail = false

    /** Una tarjeta acumulada en la isla. */
    private class Card(
        val key: String, val pkg: String, val title: CharSequence?, val body: CharSequence?,
        val icon: Drawable?, val accent: Int?, val ephemeral: Boolean,
        val detail: CharSequence? = null, val media: Bitmap? = null,
        val count: Int = 1, val lines: List<String> = emptyList(), val sig: String = "",
        val time: Long = System.currentTimeMillis(),
        val target: String? = null,      // eventos del sistema: pantalla de Ajustes a la que lleva al tocarlo
        val label: String? = null,       // eventos del sistema: nombre en lugar del de la app
        val userId: Int = -1,            // espacio de la app (clon / app dual); -1 = el principal
        val user: android.os.UserHandle? = null
    )
    private fun nameOf(c: Card): String = c.label ?: appLabel(c.pkg, c.user, c.userId)
    private val cards = ArrayList<Card>()
    /** Último destino (chat) de cada app, para abrirlo exactamente al tocar la tarjeta. */
    private val intents = HashMap<String, android.app.PendingIntent>()

    // ---------- Apps clonadas (apps duales / segundo espacio de la ROM) ----------
    // El clon y el original comparten paquete: lo que los distingue es el espacio de usuario de la notificación.
    private val myUser: Int by lazy { android.os.Process.myUid() / 100000 }
    private val ANY_USER = -2
    private fun norm(u: Int) = if (u < 0) myUser else u
    private fun isClone(u: Int) = u >= 0 && u != myUser
    /** Clave de la app: el paquete a secas para la original; «paquete@espacio» para su clon. */
    private fun appKey(pkg: String, u: Int) = if (isClone(u)) "$pkg@$u" else pkg
    /** Paquete → espacio donde ya se vio una notificación de su clon. */
    private val cloneSeen = HashMap<String, Int>()
    private val cloneCache = HashMap<String, Pair<Boolean, Long>>()

    /** ¿Esta app existe también en otro espacio (clon)? Se sabe por una notificación vista o por los perfiles del sistema. */
    private fun hasClone(pkg: String): Boolean {
        if (cloneSeen.containsKey(pkg)) return true
        val now = System.currentTimeMillis()
        cloneCache[pkg]?.let { if (now - it.second < 60_000L) return it.first }
        val r = try {
            val um = getSystemService(USER_SERVICE) as android.os.UserManager
            val la = getSystemService(LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            val me = android.os.Process.myUserHandle()
            um.userProfiles.any { it != me && la.getActivityList(pkg, it).isNotEmpty() }
        } catch (e: Exception) { false }
        cloneCache[pkg] = Pair(r, now)
        return r
    }

    /**
     * Qué copia de [pkg] tienes delante: [ANY_USER] si solo hay una, el id del espacio si se distingue, null si hay clon y no se sabe.
     * «Acceso de uso» solo ve las apps del espacio principal: si ahí está al frente es la original; si no y accesibilidad ve la app, es el clon.
     */
    private fun frontUserOf(pkg: String): Int? {
        if (!hasClone(pkg)) return ANY_USER
        if (!Foreground.hasAccess(this)) return null
        if (Foreground.pkg(this) == pkg) return myUser
        return cloneSeen[pkg]
    }

    /** Quita de la isla lo de la app que tienes delante; con clon, solo el de la copia correcta (se espera un instante a que «Acceso de uso» se ponga al día). */
    private fun readFront(pkg: String, minAge: Long) {
        if (!hasClone(pkg)) { markRead(pkg, minAge = minAge); return }
        main.postDelayed({
            if (usingNow() != pkg) return@postDelayed
            val u = frontUserOf(pkg) ?: return@postDelayed
            markRead(pkg, minAge = minAge, user = if (u == ANY_USER) null else u)
        }, 600)
    }
    private var cardIndex = 0
    private val currentPkg: String? get() = cards.getOrNull(cardIndex)?.pkg

    /** ¿La isla debe estar oculta ahora? (bloqueo desactivado, juego al frente o app elegida) */
    private fun hiddenNow(): Boolean {
        if (!Prefs.lockScreen(this) &&
            (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked) return true
        val apps = Prefs.hideApps(this)
        if (!Prefs.hideInGames(this) && apps.isEmpty()) return false
        val fg = Foreground.pkg(this) ?: return false
        if (fg == packageName) return false
        return fg in apps || (Prefs.hideInGames(this) && Foreground.isGame(this, fg))
    }

    /** App que estás usando ahora mismo (pantalla encendida y desbloqueada), o null si no se puede saber. */
    private fun usingNow(): String? = try {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!pm.isInteractive || km.isKeyguardLocked) null
        else RyxAccessibility.instance?.currentPackage() ?: Foreground.pkg(this)
    } catch (e: Exception) { null }

    /** ¿Estás dentro de esa app ahora? Entonces lo que llegue de ella ya lo estás leyendo ahí. */
    private fun readingNow(pkg: String, userId: Int): Boolean {
        if (!Prefs.autoRead(this) || pkg == packageName || usingNow() != pkg) return false
        val u = frontUserOf(pkg) ?: return false     // con clon y sin poder distinguir: se muestra como siempre
        return u == ANY_USER || u == norm(userId)
    }

    /** Cada pocos segundos: quita la isla si entró a un juego y la devuelve (modo permanente) al salir. */
    private val watch = object : Runnable {
        override fun run() {
            try {
                if (Prefs.islandOn(this@NotifListener)) {
                    val hide = hiddenNow()
                    if (hide && attached) removeNow()
                    else if (!hide && !attached && Prefs.permanent(this@NotifListener)) showIdle()
                    // Respaldo: mientras estás dentro de una app, sus avisos que esperaban en la isla ya se leyeron
                    if (cards.any { !it.ephemeral }) {
                        val fg = usingNow()
                        if (fg != null) readFront(fg, 1200)
                    }
                }
            } catch (e: Exception) {}
            main.postDelayed(this, 2500)
        }
    }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun px(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ---------- Ciclo de vida ----------

    override fun onListenerConnected() {
        instance = this
        Glass.applyTheme(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        main.post { if (Prefs.islandOn(this) && Prefs.permanent(this)) showIdle() }
        registerSystemReceivers()
        main.removeCallbacks(watch); main.postDelayed(watch, 2500)
        if (Prefs.hideHeadsUp(this)) Thread { Shell.setHeadsUp(this, false) }.start()
    }

    override fun onListenerDisconnected() {
        main.removeCallbacks(watch)
        try { unregisterReceiver(sysReceiver) } catch (e: Exception) {}
        main.post { removeNow() }
        restoreHeadsUp()
        instance = null
    }

    override fun onDestroy() {
        main.removeCallbacks(watch)
        try { unregisterReceiver(sysReceiver) } catch (e: Exception) {}
        anim?.cancel()
        removeNow()
        restoreHeadsUp()
        instance = null
        histExec.shutdown()
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

        // Ya estás dentro de esa app (pantalla encendida y desbloqueada): lo estás leyendo ahí, la isla no lo toma
        val uid = if (sbn.userId >= 0) sbn.userId else myUser
        if (isClone(uid)) cloneSeen[sbn.packageName] = uid
        val reading = readingNow(sbn.packageName, uid)

        // 0) SOLO LA ISLA: si ella va a mostrarla, el aviso del sistema se quita YA (antes de guardar o preparar nada)
        //    para que apenas llegue a verse. Llamadas, multimedia, navegación, alarmas y progresos se quedan en el sistema.
        val takeOver = Prefs.islandOn(this) && Prefs.blockSystem(this) && Settings.canDrawOverlays(this) &&
            sbn.packageName !in Prefs.blocked(this) && !sbn.isOngoing && sbn.isClearable && !keepInSystem(n) && !hiddenNow() && !reading
        if (takeOver) { try { cancelNotification(sbn.key) } catch (e: Exception) {} }

        // Palabras ocultas (privacidad): se reemplaza el contenido
        val words = Prefs.hiddenWords(this)
        if (words.isNotEmpty()) {
            val all = "${title ?: ""} ${text ?: ""}".lowercase()
            if (words.any { all.contains(it) }) { title = appLabel(sbn.packageName, sbn.user, uid); text = "Contenido oculto" }
        }

        // Si ya lo leíste y la app lo reenvía igual (pasa al abrir el chat), no vuelve a la isla ni al historial
        val nowMs = System.currentTimeMillis()
        readSigs.entries.removeAll { nowMs - it.value > 120_000L }
        if (readSigs.containsKey("${sbn.key}|$title|$text")) return

        val (mediaBmp, mediaKind) = extractMedia(n)

        // 1) La isla REGISTRA todo en el historial, por aplicación
        if (Prefs.keepHistory(this) && !sbn.isOngoing) {
            val hp = sbn.packageName; val ht = title?.toString() ?: ""; val hx = text?.toString() ?: ""
            histExec.execute {      // disco y copia de seguridad fuera del hilo principal: la isla sale antes
                try { Prefs.addHist(this, hp, ht, hx, saveBitmap(mediaBmp, mediaKind), mediaKind); Backup.maybeUpload(this) } catch (e: Exception) {}
            }
        }

        // 2) Filtros de la isla
        if (!Prefs.islandOn(this)) return
        if (sbn.packageName in Prefs.blocked(this)) return
        if (hiddenNow()) return          // la notificación del sistema se queda intacta
        if (reading) {                   // ya la estás leyendo dentro de la app: no entra a la isla ni deja esperando lo anterior de esa app
            val rp = sbn.packageName
            main.post { markRead(rp, user = uid) }
            return
        }
        if (sbn.isOngoing) return

        val large: Drawable? = try { n.getLargeIcon()?.loadDrawable(this) } catch (e: Exception) { null }
        val small: Drawable? = try {
            n.smallIcon?.loadDrawable(createPackageContext(sbn.packageName, 0))
        } catch (e: Exception) { null }
        // Texto completo para la vista de detalle: texto largo, o los últimos mensajes de la conversación
        var full: CharSequence? = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        if (full == null) {
            @Suppress("DEPRECATION")
            val lines = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.mapNotNull { it as? android.os.Bundle }?.takeLast(5)?.mapNotNull { m ->
                    val t = m.getCharSequence("text") ?: return@mapNotNull null
                    val who = m.getCharSequence("sender")
                    if (who != null) "$who: $t" else t.toString()
                }
            if (!lines.isNullOrEmpty()) full = lines.joinToString("\n")
        }
        if (words.isNotEmpty() && text == "Contenido oculto") full = text
        val ci = n.contentIntent
        val ik = appKey(sbn.packageName, uid)      // original y clon tienen su propia tarjeta y su propio destino
        if (ci != null && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0) intents[ik] = ci
        else if (ci != null && !intents.containsKey(ik)) intents[ik] = ci
        show(sbn.packageName, title, text, large, true, true, "app:$ik", false,
            fallback = small, media = mediaBmp, detailText = full ?: text,
            sig = "${sbn.key}|$title|$text", user = sbn.user, userId = uid)
    }

    /** La notificación se canceló (la leíste, la deslizaste o la app la retiró). Lo que cancela la propia isla no cuenta. */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?, reason: Int) {
        if (reason == NotificationListenerService.REASON_LISTENER_CANCEL || reason == NotificationListenerService.REASON_LISTENER_CANCEL_ALL) return
        if (sbn.packageName == packageName) return
        val pk = sbn.packageName; val key = sbn.key; val ru = norm(sbn.userId)
        main.post { markRead(pk, key, user = ru) }
    }

    /** Lo llama accesibilidad cuando una app abre una ventana: si tenía avisos esperando en la isla, ya los estás viendo. */
    fun onAppOpened(pkg: String) { main.post { readFront(pkg, 1200) } }

    /**
     * Ya lo leíste: la tarjeta sale de la isla y, si la app reenvía la misma, no vuelve.
     * [key] = solo la de esa notificación; [minAge] = no toca las recién llegadas.
     */
    private fun markRead(pkg: String, key: String? = null, minAge: Long = 0, user: Int? = null) {
        if (!Prefs.autoRead(this) || pkg == packageName) return
        val now = System.currentTimeMillis()
        val gone = cards.filter { !it.ephemeral && it.pkg == pkg && now - it.time >= minAge && (key == null || it.sig.startsWith("$key|")) &&
            (user == null || norm(it.userId) == norm(user)) }
        if (gone.isEmpty()) return
        gone.forEach { if (it.sig.isNotEmpty()) readSigs[it.sig] = now }
        val shown = cards.getOrNull(cardIndex)
        cards.removeAll(gone.toSet())
        gone.forEach { intents.remove(appKey(it.pkg, it.userId)) }
        if (!attached) return
        if (cards.isEmpty()) {
            cardIndex = 0
            if (expanded) collapse()
            else if (!Prefs.permanent(this)) removeNow()
            else { updateBadge(); updateCount() }
            return
        }
        val keep = if (shown != null && shown !in gone) cards.indexOf(shown) else -1
        cardIndex = if (keep >= 0) keep else 0
        if (keep < 0) { if (detail) resetDetail(); bindCard(cards[cardIndex]) }
        updateBadge(); updateCount()
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
        show(packageName, ev.title, ev.body, eventIcon(ev.symbol, ev.color), ev.short, shouldExpand && Prefs.eventExpand(this, ev.id), "ev:${ev.id}", ev.short,
            target = ev.target ?: "settings", label = ev.label, accentOverride = if (ev.color != 0) ev.color else null)
    }

    /** Icono vectorial del evento, blanco sobre un círculo del COLOR propio del evento (verde batería, azul Wi-Fi…). */
    private fun eventIcon(name: String, color: Int = 0): Drawable? {
        val ic = Glass.icon(this, name, Color.WHITE) ?: return null
        val base = if (color != 0) color else Glass.ACCENT
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(IslandLook.lighter(base, 0.28f), base, IslandLook.darker(base, 0.82f)))
            .apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(bg, ic)).apply { val i = px(8); setLayerInset(1, i, i, i, i) }
    }

    fun showTest() {
        show(packageName, "Ryx Island", "Funciona. Toca para abrir en ventana flotante.", null, true, true)
    }

    // ---------- Construccion de la isla ----------

    private val cornerTargets = ArrayList<GradientDrawable>()
    private var bgCard: Drawable? = null
    private var bgIdle: Drawable? = null
    private var blend = 0f

    private fun setCorners(r: Float) { cornerTargets.forEach { it.cornerRadius = r } }

    /** 0 = aspecto de reposo, 1 = tarjeta; se mezclan al abrir y cerrar. */
    private fun setBlend(b: Float) {
        blend = b
        bgCard?.setAlpha((255 * b).toInt().coerceIn(0, 255))
        bgIdle?.setAlpha((255 * (1f - b)).toInt().coerceIn(0, 255))
    }

    private fun lookDrawable(sp: IslandLook.Spec, r: Float, accent: Int?): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sp.top, sp.bottom)).apply {
            cornerRadius = r
            if (accent != null) setStroke(maxOf(1, px(1.6f)), (accent and 0x00FFFFFF) or 0xB0000000.toInt())
            else if (sp.rimDp > 0f) setStroke(maxOf(1, px(sp.rimDp)), sp.rim)
        }
        cornerTargets.add(base)
        if (sp.shine == 0) return base
        val glow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sp.shine, 0x00FFFFFF))
            .apply { cornerRadius = r }
        cornerTargets.add(glow)
        return LayerDrawable(arrayOf(base, glow)).apply {
            val i = px(1)
            setLayerInset(1, i, i, i, i)
        }
    }

    private fun buildBackground(s: IslandStyle, accent: Int? = null): Drawable {
        val cd = Prefs.cornerDp(this)
        val r = px(if (cd >= 0) cd.toFloat() else s.cornerDp).toFloat()
        cornerTargets.clear()
        val card = lookDrawable(IslandLook.card(this, s), r, accent)
        val idle = lookDrawable(IslandLook.idle(this), r, null)
        bgCard = card; bgIdle = idle
        val layers = LayerDrawable(arrayOf(card, idle))
        setBlend(if (expanded) 1f else 0f)
        return layers
    }

    // ---------- Zona táctil ampliada ----------
    // La ventana de la isla mide lo mismo que la pastilla: un toque unos píxeles fuera caía en la barra de estado y bajaba el panel.
    // Esta ventana transparente, más grande que la isla y siempre pegada a ella, atrapa esos toques y los gestos.
    private var hitView: View? = null
    private var hitLp: WindowManager.LayoutParams? = null
    private var hitAttached = false
    private val touchHandler by lazy { IslandTouch() }

    private fun addHit(params: WindowManager.LayoutParams) {
        if (hitAttached || !::wm.isInitialized) return
        val v = View(this).apply { setOnTouchListener(touchHandler) }
        val hp = WindowManager.LayoutParams(
            params.width, px(48), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (params.flags and WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        hitView = v; hitLp = hp
        syncHit(params)
        try { wm.addView(v, hp); hitAttached = true } catch (e: Exception) { hitAttached = false }
    }

    private fun removeHit() {
        val v = hitView
        if (hitAttached && v != null && ::wm.isInitialized) try { wm.removeView(v) } catch (e: Exception) {}
        hitAttached = false; hitView = null; hitLp = null
    }

    /** Coloca la zona táctil sobre la isla con margen extra (mínimo ~48 dp de alto y 96 dp de ancho). */
    private fun syncHit(params: WindowManager.LayoutParams) {
        val hp = hitLp ?: return
        val row = island
        val h = maxOf(row?.height ?: 0, row?.minimumHeight ?: 0, px(24))
        val padX = px(16); val padB = px(16)
        val padT = minOf(px(12), maxOf(params.y, 0))
        hp.width = maxOf(params.width + 2 * padX, px(96))
        hp.height = maxOf(h + padT + padB, px(48))
        hp.x = params.x
        hp.y = params.y - padT
        if (hitAttached) hitView?.let { try { wm.updateViewLayout(it, hp) } catch (e: Exception) {} }
    }

    /** Actualiza la ventana de la isla y mueve su zona táctil con ella. */
    private fun upd(v: View?, params: WindowManager.LayoutParams) {
        if (v == null) return
        try { wm.updateViewLayout(v, params) } catch (e: Exception) {}
        syncHit(params)
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
            setTextColor(Glass.ACCENT)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = if (s.showAppName) View.VISIBLE else View.GONE
        }
        val title = TextView(this).apply {
            setTextColor(s.textColor)
            textSize = s.titleSp
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, if (Prefs.fontBold(this@NotifListener)) Typeface.BOLD else Typeface.NORMAL)
        }
        val body = TextView(this).apply {
            setTextColor(s.subColor)
            textSize = s.bodySp
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val dImg = ImageView(this).apply {
            visibility = View.GONE; scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, px(14).toFloat()) }
            }
            clipToOutline = true
        }
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
            val chipLp = { l: Int, r: Int -> LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(l); marginEnd = px(r) } }
            addView(actionChip("Abrir", true, s) { openCurrent(false) }, chipLp(0, 3))
            addView(actionChip("Libre", false, s) { openCurrent(true, 0) }, chipLp(3, 3))
            addView(actionChip("Burbuja", false, s) { openCurrent(true, 1) }, chipLp(3, 3))
            addView(actionChip("Descartar", false, s) { dismissCurrent() }, chipLp(3, 0))
        }
        detailImg = dImg
        actionsRow = acts
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(app)
            addView(title)
            addView(body)
            addView(dImg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(130)).apply { topMargin = px(8) })
            addView(acts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        }
        val cnt = TextView(this).apply {
            visibility = View.GONE; textSize = 10.5f; gravity = Gravity.CENTER
            setTextColor(0xFF1B0B02.toInt()); setTypeface(typeface, Typeface.BOLD)
            minWidth = px(18); setPadding(px(5), 0, px(5), 0)
            background = GradientDrawable().apply { cornerRadius = px(9).toFloat(); setColor(Glass.ACCENT) }
        }
        countView = cnt
        val iconBox = FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false
            addView(icon, FrameLayout.LayoutParams(px(s.iconDp), px(s.iconDp)))
            addView(cnt, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(18), Gravity.TOP or Gravity.END)
                .apply { topMargin = -px(6); marginEnd = -px(6) })
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
            if (Prefs.shadow(this@NotifListener)) elevation = px(10).toFloat()
            setPadding(px(s.padH), px(s.padV), px(s.padH + 4), px(s.padV))
            addView(iconBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(10)
            })
            addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
            setOnTouchListener(touchHandler)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> lp?.let { syncHit(it) } }
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
            if (Prefs.lockScreen(this@NotifListener)) flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            if (Build.VERSION.SDK_INT >= 31 && Prefs.idleBlur(this@NotifListener)) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = px(22)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun actionChip(text: String, primary: Boolean, s: IslandStyle, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) 0xFF1B0B02.toInt() else s.textColor)
        setPadding(px(6), px(9), px(6), px(9))
        background = GradientDrawable().apply {
            cornerRadius = px(16).toFloat()
            setColor(if (primary) Glass.ACCENT else if (s.textColor == 0xFFFFFFFF.toInt()) 0x30FFFFFF else 0x22000000)
        }
        setOnClickListener { onClick() }
    }

    private fun idleWidth(): Int = px(Prefs.idleWidthDp(this))

    private fun expandedWidth(): Int =
        minOf(resources.displayMetrics.widthPixels - px(2 * Prefs.sideMarginDp(this)),
            px(Prefs.expandedWidthDp(this).let { if (it > 0) it else style.expandedWidthDp }))

    private fun setContentAlpha(a: Float) {
        iconView?.alpha = a
        textCol?.alpha = a
        countView?.alpha = a
    }

    /** La cantidad de notificaciones de la app aparece arriba, junto al icono. */
    private fun updateCount() {
        val v = countView ?: return
        val c = cards.getOrNull(cardIndex)
        if (expanded && c != null && c.count > 1) {
            v.text = if (c.count > 99) "99+" else c.count.toString(); v.visibility = View.VISIBLE
        } else v.visibility = View.GONE
    }

    private fun setIdleContent() {
        island?.minimumHeight = px(Prefs.heightDp(this))
        iconView?.scaleX = 1f; iconView?.scaleY = 1f; textCol?.translationX = 0f
        iconView?.visibility = View.GONE
        textCol?.visibility = View.GONE
        setContentAlpha(1f)
        island?.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setBlend(0f)
        setCorners(px(Prefs.heightDp(this)) / 2f)
        updateBadge()
        updateCount()
    }

    // ---------- Estados: oculta / reposo (permanente) / expandida ----------

    private fun showIdle() {
        if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) return
        if (hiddenNow()) return
        buildIsland()
        val v = island ?: return
        val params = lp ?: return
        if (!attached) {
            params.width = idleWidth()
            setIdleContent()
            addHit(params)
            try {
                wm.addView(v, params)
            } catch (e: Exception) {
                removeHit(); return
            }
            attached = true
        }
        expanded = false
    }

    private fun show(
        pkg: String, t: CharSequence?, b: CharSequence?, large: Drawable?, autoHide: Boolean = true,
        expand: Boolean = true, key: String = "$pkg|$t", ephemeral: Boolean = false,
        fallback: Drawable? = null, media: Bitmap? = null, detailText: CharSequence? = null,
        sig: String = "", target: String? = null, label: String? = null, accentOverride: Int? = null,
        user: android.os.UserHandle? = null, userId: Int = -1
    ) {
        main.post {
            if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) return@post
            if (hiddenNow()) return@post
            buildIsland()
            val v = island ?: return@post
            val params = lp ?: return@post
            val baseIcon: Drawable? = try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
            // El clon lleva la insignia que le pone la ROM, para distinguirlo del original a simple vista
            val sysIcon: Drawable? = if (baseIcon != null && isClone(userId) && user != null)
                (try { packageManager.getUserBadgedIcon(baseIcon, user) } catch (e: Exception) { baseIcon }) else baseIcon
            // Si el sistema no deja leer el icono de la app (HyperOS sin permiso), se usa el de la notificación
            val appIcon: Drawable? = sysIcon ?: large ?: fallback?.let { badgeOf(it) }
            val shown: Drawable? = when {
                pkg == packageName && large != null -> large                      // iconos de eventos
                Prefs.iconMode(this) == 1 -> large ?: appIcon                     // el de la notificación
                Prefs.iconMode(this) == 2 -> appIcon?.constantState?.newDrawable()?.mutate()?.also {
                    it.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) } // monocromo
                else -> appIcon                                                   // el del sistema
            }
            val accent = accentOverride ?: if (Prefs.smartColor(this) && pkg != packageName) dominantColor(appIcon) else null
            if (detail) resetDetail()

            // Acumulación: la nueva queda al frente y las anteriores esperan a la derecha
            // Misma app = una sola tarjeta: queda la última notificación y se cuenta cuántas llegaron
            val prev = cards.firstOrNull { it.key == key }
            val grouped = key.startsWith("app:")
            val sameAsPrev = prev != null && sig.isNotEmpty() && sig == prev.sig
            val newLine = listOf(t, b).mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString(": ")
            val count = when {
                !grouped || prev == null -> 1
                sameAsPrev -> prev.count
                else -> prev.count + 1
            }
            val lines: List<String> = when {
                !grouped || prev == null -> listOf(newLine)
                sameAsPrev -> prev.lines
                else -> (listOf(newLine) + prev.lines).take(6)
            }
            val fullText: CharSequence? = if (lines.size > 1) lines.filter { it.isNotEmpty() }.joinToString("\n") else detailText
            cards.removeAll { it.key == key }
            cards.add(0, Card(key, pkg, t, b, shown, accent, ephemeral, fullText, media, count, lines, sig, target = target, label = label, userId = userId, user = user))
            val limit = System.currentTimeMillis() - 15 * 60_000L
            cards.removeAll { it.time < limit }
            while (cards.size > 8) cards.removeAt(cards.size - 1)
            cardIndex = 0
            bindCard(cards[0])

            main.removeCallbacks(hideTask)
            if (!attached) {
                params.width = idleWidth()
                setIdleContent()
                addHit(params)
                try {
                    wm.addView(v, params)
                } catch (e: Exception) {
                    removeHit(); return@post
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
        updateCount()
        iconView?.visibility = View.VISIBLE
        textCol?.visibility = View.VISIBLE
        setContentAlpha(0f)
        updateBadge()
        morph(true, null)
    }

    /** Icono pequeño (monocromo) de la notificación sobre un disco de color. */
    private fun badgeOf(d: Drawable): Drawable {
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3A3F4A.toInt()) }
        val ic = d.constantState?.newDrawable()?.mutate() ?: d
        ic.setTint(Color.WHITE)
        return LayerDrawable(arrayOf(bg, ic)).apply { val i = px(8); setLayerInset(1, i, i, i, i) }
    }

    /** Pone el contenido de una tarjeta en la isla. */
    private fun bindCard(c: Card) {
        appView?.text = nameOf(c) + if (c.count > 1) " · ${c.count}" else ""
        titleView?.text = c.title
        bodyView?.text = c.body
        iconView?.setImageDrawable(c.icon?.constantState?.newDrawable()?.mutate() ?: c.icon
            ?: getDrawable(R.drawable.logo))
        (iconView as? ShapedImageView)?.shape = Prefs.iconShape(this)
        island?.background = buildBackground(style, c.accent)
        updateCount()
        if (detail) {
            bodyView?.text = c.detail ?: c.body
            detailImg?.let { if (c.media != null) { it.setImageBitmap(c.media); it.visibility = View.VISIBLE } else it.visibility = View.GONE }
        }
    }

    /** Acumulación a la derecha: iconos apilados al expandir, contador al reposo. */
    private fun updateBadge() {
        val b = badgeView ?: return
        b.removeAllViews()
        val others: List<Card> = if (expanded) cards.filterIndexed { i, _ -> i != cardIndex } else cards.filter { !it.ephemeral }
        if (others.isEmpty()) { b.visibility = View.GONE; return }
        b.visibility = View.VISIBLE
        val size = if (expanded) 20 else 18
        others.take(3).forEachIndexed { i, c ->
            b.addView(ShapedImageView(this).apply {
                shape = Prefs.iconShape(this@NotifListener); scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(c.icon?.constantState?.newDrawable()?.mutate() ?: c.icon ?: getDrawable(R.drawable.logo))
                alpha = 1f - 0.18f * i
            }, LinearLayout.LayoutParams(px(size), px(size)).apply { if (i > 0) marginStart = -px(8) })
        }
        if (others.size > 3) b.addView(TextView(this).apply {
            text = "+${others.size - 3}"; setTextColor(Color.WHITE); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = px(4) })
    }

    // ---------- Gestos ----------

    private inner class IslandTouch : View.OnTouchListener {
        private var x0 = 0f
        private var y0 = 0f
        private var t0 = 0L
        private var down = false
        private var longFired = false
        private var baseX = 0
        private var baseY = 0
        private val slop = ViewConfiguration.get(this@NotifListener).scaledTouchSlop
        private val longRun = Runnable {
            if (down) { longFired = true; island?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onGesture(5) }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val params = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; t0 = e.eventTime; down = true; longFired = false
                    baseX = px(Prefs.xOffsetDp(this@NotifListener)); baseY = px(Prefs.yOffsetDp(this@NotifListener))
                    main.removeCallbacks(hideTask)
                    main.postDelayed(longRun, 420)
                    // Respuesta inmediata al tocar: se hunde un poco al instante
                    island?.animate()?.cancel()
                    island?.animate()?.scaleX(0.96f)?.scaleY(0.96f)?.setDuration(60)?.start()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!down) return true
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (abs(dx) > slop * 2 || abs(dy) > slop * 2) main.removeCallbacks(longRun)
                    // la isla sigue al dedo con resistencia (se mueve la ventana, no la vista); un temblor pequeño no la mueve
                    if (abs(dx) > slop || abs(dy) > slop) {
                        params.x = baseX + (dx * 0.35f).toInt()
                        params.y = baseY + (dy * 0.5f).coerceIn(-px(24).toFloat(), px(60).toFloat()).toInt()
                        upd(island, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longRun)
                    val wasDown = down; down = false
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    val dist = kotlin.math.hypot(dx, dy)
                    val ms = e.eventTime - t0
                    // Umbral de deslizar más bajo (nunca más de 28 dp) y toque tolerante: un dedo no es un punto
                    val th = minOf(px(Prefs.swipeDp(this@NotifListener)), px(28)).toFloat()
                    val tapR = maxOf(slop * 3f, px(16).toFloat())
                    island?.animate()?.cancel()
                    island?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                    springBack(v)
                    if (expanded && !detail) main.postDelayed(hideTask, Prefs.durationMs(this@NotifListener))
                    if (!wasDown || longFired || e.actionMasked == MotionEvent.ACTION_CANCEL) return true
                    val kind = when {
                        dist <= tapR && ms < 700 -> 0
                        dy > th && dy > abs(dx) * 1.1f -> 1
                        dy < -th && -dy > abs(dx) * 1.1f -> 2
                        dx > th && dx > abs(dy) * 1.1f -> 4
                        dx < -th && -dx > abs(dy) * 1.1f -> 3
                        dist <= tapR * 2f && ms < 450 -> 0      // movimiento dudoso y rápido: era un toque
                        else -> -1
                    }
                    if (kind >= 0) {
                        island?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onGesture(kind)
                    }
                }
            }
            return true
        }
    }

    /** La isla vuelve a su sitio con un resorte después de arrastrarla. */
    private fun springBack(v: View) {
        val params = lp ?: return
        val bx = px(Prefs.xOffsetDp(this)); val by = px(Prefs.yOffsetDp(this))
        val fx = params.x; val fy = params.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (420 * Motion.factor(this@NotifListener)).toLong()
            interpolator = Motion.spring()
            addUpdateListener {
                val t = it.animatedValue as Float
                params.x = lerp(fx.toFloat(), bx.toFloat(), t).toInt()
                params.y = lerp(fy.toFloat(), by.toFloat(), t).toInt()
                if (attached) upd(island, params)
            }
            start()
        }
    }

    /** kind: 0 toque, 1 abajo, 2 arriba, 3 izquierda, 4 derecha, 5 mantener. */
    private fun onGesture(kind: Int) {
        if (!expanded) {
            // Contraída: tocar o jalar hacia abajo despliega las notificaciones acumuladas
            if (kind == 0 || kind == 1 || kind == 5) expandPending()
            return
        }
        if (detail) {
            when (kind) {
                0 -> openCurrent(false)
                1 -> doAction(Prefs.gDown(this))
                2 -> exitDetail()
                3 -> page(1)
                4 -> page(-1)
                else -> collapse()
            }
            return
        }
        doAction(when (kind) {
            0 -> Prefs.gTap(this)
            1 -> Prefs.gDown(this)
            2 -> Prefs.gUp(this)
            3 -> Prefs.gLeft(this)
            4 -> Prefs.gRight(this)
            else -> Prefs.gLong(this)
        })
    }

    private fun doAction(a: Int) {
        when (a) {
            1 -> openCurrent(false)
            2 -> openCurrent(true)          // ventana según tu ajuste (formato libre o burbuja)
            3 -> dismissCurrent()
            4 -> page(1)
            5 -> page(-1)
            6 -> collapse()
            7 -> enterDetail()
            8 -> openCurrent(true, 1)       // burbuja
            9 -> openCurrent(true, 0)       // formato libre
            10 -> copyCurrent()
            11 -> muteCurrent()
        }
    }

    /** Copia el texto de la tarjeta al portapapeles. */
    private fun copyCurrent() {
        val c = cards.getOrNull(cardIndex) ?: return
        val txt = (c.detail ?: c.body ?: c.title)?.toString() ?: return
        try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Ryx", txt))
            android.widget.Toast.makeText(this, "Texto copiado", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {}
        pulse()
    }

    /** La isla deja de mostrar esa app (queda en «Apps bloqueadas» de los ajustes). */
    private fun muteCurrent() {
        val c = cards.getOrNull(cardIndex) ?: return
        if (c.pkg == packageName) { dismissCurrent(); return }
        Prefs.setBlocked(this, Prefs.blocked(this) + c.pkg)
        android.widget.Toast.makeText(this, "${nameOf(c)}: silenciada en la isla", android.widget.Toast.LENGTH_SHORT).show()
        cards.removeAll { it.pkg == c.pkg }
        if (cards.isEmpty()) { collapse(); return }
        cardIndex = 0; bindCard(cards[0]); updateBadge()
    }

    // ---------- Vista de detalle ----------

    private fun resetDetail() {
        detail = false
        detailImg?.visibility = View.GONE
        actionsRow?.visibility = View.GONE
        bodyView?.maxLines = 1
        appView?.visibility = if (style.showAppName) View.VISIBLE else View.GONE
        cards.getOrNull(cardIndex)?.let {
            bodyView?.text = it.body
            appView?.text = nameOf(it) + if (it.count > 1) " · ${it.count}" else ""
        }
        island?.minimumHeight = px(maxOf(style.iconDp + 2 * style.padV, 64))
    }

    private fun enterDetail() {
        val c = cards.getOrNull(cardIndex) ?: return
        if (detail) return
        detail = true
        main.removeCallbacks(hideTask)
        bodyView?.maxLines = 8
        bodyView?.text = c.detail ?: c.body
        appView?.visibility = View.VISIBLE
        appView?.text = nameOf(c) + if (c.count > 1) " · ${c.count} notificaciones" else ""
        detailImg?.let { if (c.media != null) { it.setImageBitmap(c.media); it.visibility = View.VISIBLE } else it.visibility = View.GONE }
        actionsRow?.visibility = View.VISIBLE
        animateDetail(true)
    }

    private fun exitDetail() {
        if (!detail) return
        animateDetail(false)
        main.postDelayed(hideTask, Prefs.durationMs(this))
    }

    /** Crece o se encoge la tarjeta con resorte y aparece el contenido extra. */
    private fun animateDetail(open: Boolean) {
        val row = island ?: return
        val params = lp ?: return
        row.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val compact = px(maxOf(style.iconDp + 2 * style.padV, 64))
        val target = if (open) maxOf(row.measuredHeight, compact) else compact
        val from = if (row.height > 0) row.height else compact
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (open) Motion.expandMs(this) else Motion.collapseMs(this)
        a.interpolator = if (open) Motion.spring() else Motion.smooth()
        a.addUpdateListener { va ->
            val t = va.animatedValue as Float
            row.minimumHeight = lerp(from.toFloat(), target.toFloat(), t).toInt().coerceAtLeast(compact)
            val k = if (open) t.coerceIn(0f, 1f) else (1f - t).coerceIn(0f, 1f)
            detailImg?.alpha = k; actionsRow?.alpha = k
            if (attached) upd(row, params)
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!open) resetDetail()
            }
        })
        anim = a
        a.start()
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
        if (detail) animateDetail(true)
        textCol?.translationX = px(28).toFloat() * d
        textCol?.animate()?.translationX(0f)?.setDuration((380 * Motion.factor(this)).toLong())
            ?.setInterpolator(Motion.spring())?.start()
        main.removeCallbacks(hideTask)
        if (!detail) main.postDelayed(hideTask, Prefs.durationMs(this))
    }

    private fun dismissCurrent() {
        if (cardIndex in cards.indices) cards.removeAt(cardIndex)
        if (cards.isEmpty()) { collapse(); return }
        cardIndex = cardIndex.coerceAtMost(cards.size - 1)
        bindCard(cards[cardIndex]); updateBadge()
        if (detail) animateDetail(true) else pulse()
        main.removeCallbacks(hideTask)
        if (!detail) main.postDelayed(hideTask, Prefs.durationMs(this))
    }

    // ---------- Medios para el historial ----------

    /** Imagen o sticker de la notificación (si hay) y su tipo. */
    @Suppress("DEPRECATION")
    private fun extractMedia(n: Notification): Pair<Bitmap?, String> {
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
            return Pair(bmp?.let { scaleDown(it, 640) }, kind)
        } catch (e: Throwable) {
            return Pair(null, "")
        }
    }

    private fun saveBitmap(bmp: Bitmap?, kind: String): String {
        if (bmp == null) return ""
        return try {
            val name = "${System.currentTimeMillis()}_${(Math.random() * 99999).toInt()}.img"
            FileOutputStream(File(Prefs.histDir(this), name)).use {
                if (kind == "Sticker") bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                else bmp.compress(Bitmap.CompressFormat.JPEG, 82, it)
            }
            name
        } catch (e: Throwable) { "" }
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
        if (detail) resetDetail()
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
        removeHit()
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
        val fromR = cornerTargets.firstOrNull()?.cornerRadius ?: (idleH / 2f)
        val fromB = blend
        val toB = if (toExpanded) 1f else 0f
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
            setCorners(lerp(fromR, toR, t.coerceIn(0f, 1f)))
            setBlend(lerp(fromB, toB, t.coerceIn(0f, 1f)))
            val c = if (toExpanded) ((time - 0.08f) / 0.42f).coerceIn(0f, 1f) else (1f - time / 0.4f).coerceIn(0f, 1f)
            setContentAlpha(c)
            iconView?.scaleX = 0.6f + 0.4f * c; iconView?.scaleY = 0.6f + 0.4f * c
            textCol?.translationX = (1f - c) * px(14)
            if (attached) {
                upd(island, params)
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
    private fun appLabel(pkg: String, user: android.os.UserHandle? = null, userId: Int = -1): String {
        val base = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        if (!isClone(userId)) return base
        // El clon se nombra como lo nombra la ROM («… (clon)», «… (dual)»…); si no cambia el nombre, se le añade «clon»
        var badged: String? = null
        if (user != null) try { badged = packageManager.getUserBadgedLabel(base, user).toString() } catch (e: Exception) {}
        return if (badged != null && badged != base) badged else "$base · clon"
    }

    // ---------- Abrir app ----------

    /** [kind]: null = el modo elegido en Ventana; 0 = formato libre; 1 = burbuja. */
    private fun openCurrent(floating: Boolean, kind: Int? = null) {
        val card = cards.getOrNull(cardIndex) ?: return
        // Eventos del sistema (batería, Wi-Fi…): llevan a su pantalla de Ajustes
        if (card.target != null) { openTarget(card.target); dismissCurrent(); return }
        val pkg = card.pkg
        // Primero se lanza la app (con la isla aún visible) y luego se descarta la tarjeta
        val pi = intents[appKey(pkg, card.userId)]
        val user = card.user; val uid = card.userId
        Thread { openApp(pkg, floating, pi, kind, user, uid) }.start()
        dismissCurrent()
    }

    private fun openApp(pkg: String, freeform: Boolean, pi: android.app.PendingIntent?, kind: Int? = null,
                        user: android.os.UserHandle? = null, userId: Int = -1) {
        val cloneUser = if (isClone(userId)) user else null
        if (freeform) { FloatingWindow.open(this, pkg, pi, kind, cloneUser, norm(userId)); return }
        // Abre la conversación exacta de la notificación; si no hay destino, la pantalla principal de la app
        if (pi != null && FloatingWindow.sendPending(this, pi, false, null)) return
        // Es un clon y no hay destino guardado: se abre SU copia, nunca la original
        if (cloneUser != null) { openClone(pkg, cloneUser, userId); return }
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(launch) } catch (e: Exception) {}
    }

    /** Abre la copia (clon) de la app en su propio espacio. Si no se puede, avisa; jamás abre la original en su lugar. Fuera del hilo principal. */
    private fun openClone(pkg: String, user: android.os.UserHandle, userId: Int) {
        // 1) API pública del sistema para lanzar la app principal de otro espacio
        try {
            val la = getSystemService(LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            val comp = la.getActivityList(pkg, user).firstOrNull()?.componentName
            if (comp != null) { la.startMainActivity(comp, user, null, null); return }
        } catch (e: Exception) {}
        // 2) Con Shizuku/root: mismo lanzamiento indicando el espacio del clon
        try {
            if (Shell.privileged(this)) {
                val r = Shell.run(this, "am start --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg")
                if (r.first == 0 && !r.second.contains("Error", true)) return
            }
        } catch (e: Exception) {}
        main.post {
            android.widget.Toast.makeText(this, "No pude abrir el clon (esta ROM no deja lanzarlo sin Shizuku/root)", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Destino de los eventos del sistema ----------

    private fun targetIntents(k: String): List<Intent> = (when (k) {
        "wifi" -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS))
        "bluetooth" -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        "battery" -> listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        "saver" -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        "airplane" -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        "data" -> listOf(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        "vpn" -> listOf(Intent(Settings.ACTION_VPN_SETTINGS))
        "hotspot" -> listOf(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        "dnd" -> listOf(Intent("android.settings.ZEN_MODE_SETTINGS"), Intent(Settings.ACTION_SOUND_SETTINGS))
        "privacy" -> listOf(Intent(Settings.ACTION_PRIVACY_SETTINGS))
        "alarms" -> listOf(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
        "downloads" -> listOf(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
        "calllog" -> listOf(Intent(Intent.ACTION_VIEW).setType(android.provider.CallLog.Calls.CONTENT_TYPE))
        "dialer" -> listOf(Intent(Intent.ACTION_DIAL))
        "messages" -> listOf(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))
        else -> emptyList()
    }) + Intent(Settings.ACTION_SETTINGS)

    /** Abre la pantalla del sistema de ese evento; si la ROM no la tiene, los Ajustes generales. */
    private fun openTarget(k: String) {
        for (i in targetIntents(k)) {
            try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return } catch (e: Exception) {}
        }
    }
}
