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
    private val readSigs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Claves que quitó la propia isla del panel (blockSystem), para no confundirlas con «ya leído». */
    private val selfCanceled = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
    /** Miniatura que se ve al desplegar la isla (la imagen grande solo cabe en el detalle y en el historial). */
    private var previewImg: ImageView? = null
    /** Adjuntos ya decodificados, por URI: la misma imagen no se vuelve a leer al cambiar de tarjeta. */
    private val mediaCache = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val mediaTried = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val mediaFailed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
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
        val user: android.os.UserHandle? = null,
        val mediaUri: String? = null,    // imagen del adjunto por URI: se carga en 2º plano (abrirla bloquea)
        val mediaKind: String = "",      // "Imagen", "Sticker"… para decidir recorte y transparencia
        val actions: List<CardAction> = emptyList()   // botones propios: contestar/colgar, cambiar de Wi-Fi, apagar el punto de acceso…
    )

    /** Botón propio de una tarjeta (contestar/colgar, cambiar de Wi-Fi, apagar el punto de acceso…). */
    private class CardAction(
        val label: String,
        val color: Int = 0,          // 0 = color de acento
        val filled: Boolean = false,
        val dismiss: Boolean = false, // al pulsarlo, ¿se quita la tarjeta de la isla?
        val run: () -> Unit
    )

    private fun nameOf(c: Card): String = c.label ?: appLabel(c.pkg, c.user, c.userId)
    private val cards = ArrayList<Card>()
    /** Último destino (chat) de cada app, para abrirlo exactamente al tocar la tarjeta. */
    private val intents = HashMap<String, android.app.PendingIntent>()

    // ---------- Apps clonadas (apps duales / segundo espacio de la ROM) ----------
    // El clon y el original comparten paquete: lo que los distingue es el espacio de usuario de la notificación.
    private val myUser: Int by lazy { Clones.myUser() }
    private val ANY_USER = -2
    private fun norm(u: Int) = if (u < 0) myUser else u
    private fun isClone(u: Int) = Clones.isClone(u)
    /** Clave de la app: el paquete a secas para la original; «paquete@espacio» para su clon. */
    private fun appKey(pkg: String, u: Int) = Clones.key(pkg, u)
    /** Paquete → espacio donde ya se vio una notificación de su clon (para distinguir la copia al frente). */
    private val cloneSeen = HashMap<String, Int>()

    /** ¿Esta app existe también en otro espacio (clon)? Lo sabe [Clones]: copia vista, perfiles o usuarios de la ROM. */
    private fun hasClone(pkg: String): Boolean = Clones.hasClone(this, pkg)

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

    /** ¿La isla debe estar oculta ahora? (bloqueo desactivado, juego al frente o app elegida)
     *  Si [force] es true (llamada de un contacto prioritario), NO se oculta: la isla aparece igual. */
    private fun hiddenNow(force: Boolean = false): Boolean {
        if (force) return false
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
                    // Respaldo: mientras estás dentro de una app, sus avisos que esperaban en la isla ya se leyeron.
                    // Nunca con la isla desplegada: así una tarjeta recién llegada no se cierra sola al expandirse.
                    if (!expanded && cards.any { !it.ephemeral }) {
                        val fg = usingNow()
                        if (fg != null) readFront(fg, 2500)
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
        checkUpdatesInBackground()          // aviso de versión nueva aunque la app no esté abierta
        main.removeCallbacks(updWatch); main.postDelayed(updWatch, Prefs.updEverySec(this).coerceIn(15, 3600) * 1000L)
        Backup.autoLocal(this)              // copia propia al arrancar el servicio
        main.removeCallbacks(backWatch); main.postDelayed(backWatch, 30_000L)   // respaldo propio cada 30 s
    }

    /**
     * Respaldo propio: cada 30 segundos revisa si el historial o los ajustes cambiaron y, si es así, guarda una
     * copia en la carpeta «ryx respaldo» (aunque la app esté cerrada y solo viva el servicio). El intervalo de
     * guardado lo controla [Prefs.backupEverySec]; este vigilante solo «empuja» con frecuencia.
     */
    private val backWatch = object : Runnable {
        override fun run() {
            Backup.autoLocal(this@NotifListener)
            main.postDelayed(this, 30_000L)
        }
    }

    /** Revisa GitHub cada poco (por defecto 30 s) mientras el servicio está vivo. */
    private val updWatch = object : Runnable {
        override fun run() {
            checkUpdatesInBackground()
            main.postDelayed(this, Prefs.updEverySec(this@NotifListener).coerceIn(15, 3600) * 1000L)
        }
    }

    /**
     * Mira si hay versión nueva cada [Prefs.updEverySec] segundos (por defecto 30) y lo avisa. El aviso sale
     * EN LA ISLA (no en el panel del sistema, salvo que se pida). Corre en el hilo de historial para no molestar
     * al servicio. Si no hay nada nuevo, no se hace nada.
     */
    private fun checkUpdatesInBackground() {
        val gap = Prefs.updEverySec(this).coerceIn(15, 3600) * 1000L
        if (System.currentTimeMillis() - Prefs.updLast(this) < gap) return
        histExec.execute {
            try {
                Prefs.setUpdLast(this, System.currentTimeMillis())
                val r = Updater.check(this)
                val i = r.info
                if (i != null && i.newer) {
                    val key = if (i.version.isNotEmpty()) i.version else i.name
                    if (key != Prefs.updSeen(this)) {
                        Prefs.setUpdSeen(this, key)
                        if (Prefs.updViaIsland(this)) {
                            // El aviso va a la ISLA: título, versión y novedades; al tocarlo se abre la app para actualizar.
                            val canIsland = Settings.canDrawOverlays(this) && Prefs.islandOn(this)
                            if (canIsland) main.post { showUpdateCard(i) }
                            else AppNotify.update(this, i.version, i.name, i.apk, i.page, i.notes)   // sin isla posible: al panel
                        }
                        if (!Prefs.updViaIsland(this) || Prefs.updAlsoPanel(this)) {
                            AppNotify.update(this, i.version, i.name, i.apk, i.page, i.notes)
                        }
                    }
                } else if (i != null && Prefs.updSeen(this).isNotEmpty()) {
                    Prefs.setUpdSeen(this, ""); AppNotify.clearUpdate(this)
                }
            } catch (e: Throwable) {}
        }
    }

    /** Aviso de versión nueva dentro de la isla. Tocar la tarjeta abre Ryx para actualizar. */
    private fun showUpdateCard(i: Updater.Info) {
        val v = if (i.version.isNotEmpty()) "v" + i.version else i.name
        val line = i.notes.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(160)
            ?: "Toca para ver novedades y descargar."
        show(packageName, "Actualización de Ryx", "$v · $line", eventIcon("download", Glass.ACCENT),
            autoHide = true, expand = true, key = "sys:update", ephemeral = false,
            sig = "ev:update", target = "update", label = "Ryx Island",
            accentOverride = Glass.ACCENT)
    }


    override fun onListenerDisconnected() {
        main.removeCallbacks(watch)
        main.removeCallbacks(updWatch)
        main.removeCallbacks(backWatch)
        try { unregisterReceiver(sysReceiver) } catch (e: Exception) {}
        main.post { removeNow() }
        restoreHeadsUp()
        instance = null
    }

    override fun onDestroy() {
        main.removeCallbacks(watch)
        main.removeCallbacks(updWatch)
        main.removeCallbacks(backWatch)
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

    /**
     * Refresco LIGERO: aplica el tamaño y la posición nuevos a la ventana que ya está puesta, sin quitarla ni
     * volver a crearla. Se usa al mover los deslizadores de Diseño, donde solo cambia la geometría: antes cada
     * paso destruía y recreaba la vista (parpadeo y tirones).
     */
    fun refreshLive() {
        main.post {
            val v = island ?: return@post
            val p = lp ?: return@post
            if (!attached || !::wm.isInitialized) return@post
            try {
                p.width = if (expanded) expandedWidth() else idleWidth()
                // x es el desplazamiento desde el centro (la ventana usa CENTER_HORIZONTAL). Antes se le sumaba
                // el margen lateral, así que al mover el deslizador la isla se iba hacia un lado y podía salirse.
                p.x = px(Prefs.xOffsetDp(this))
                p.y = px(Prefs.yOffsetDp(this))
                clampParams(p)
                wm.updateViewLayout(v, p)
                syncHit(p)
            } catch (e: Exception) { applySettings() }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            // El resumen de un grupo («3 mensajes nuevos» de WhatsApp…) no entra a la isla, pero tampoco debe quedarse en el panel:
            // era la notificación de la ROM que seguía apareciendo aunque el mensaje ya lo mostrara la isla.
            val su = if (sbn.userId >= 0) sbn.userId else myUser
            val why = keepReason(sbn, n, readingNow(sbn.packageName, su))
            if (why == null) cancelOwn(sbn.key)
            noteDecision(sbn, su, true, why)
            return
        }
        var title: CharSequence? = n.extras.getCharSequence(Notification.EXTRA_TITLE)
        var text: CharSequence? = n.extras.getCharSequence(Notification.EXTRA_TEXT)
        // Ninguna app ni aviso del sistema debe escaparse: si no trae título ni texto, se rellena con el nombre de
        // la app y el del canal (pasa con Wi‑Fi, Bluetooth, USB, batería… que a veces solo traen icono).
        val uid0 = if (sbn.userId >= 0) sbn.userId else myUser
        if (title == null) title = appLabel(sbn.packageName, sbn.user, uid0)
        if (text == null) {
            val ch = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                ?: n.extras.getCharSequence(Notification.EXTRA_INFO_TEXT)
                ?: channelName(n)
            text = ch ?: ""
        }

        // Ya estás dentro de esa app (pantalla encendida y desbloqueada): lo estás leyendo ahí, la isla no lo toma
        val uid = if (sbn.userId >= 0) sbn.userId else myUser
        if (isClone(uid)) { cloneSeen[sbn.packageName] = uid; Clones.note(this, sbn.packageName, uid) }
        val reading = readingNow(sbn.packageName, uid)

        // 0) SOLO LA ISLA: si la isla va a MOSTRARLA, se quita el aviso del sistema en el mismo momento de mostrarlo
        //    (no antes). Así ninguna notificación se pierde: si la isla no la enseña, la del sistema se queda.
        //    Llamadas, multimedia, navegación, alarmas y progresos se quedan siempre en el sistema.
        //    LLAMADA DE CONTACTO PRIORITARIO (favorito): se salta el bloqueo de juego/app oculta y aparece siempre.
        val callPrio = isCall(n) && Prefs.callPriorityBypass(this) && callIsPriority(title, text)
        val why = keepReason(sbn, n, reading, callPrio)
        noteDecision(sbn, uid, false, why)

        // Palabras ocultas (privacidad): se reemplaza el contenido
        val words = Prefs.hiddenWords(this)
        if (words.isNotEmpty()) {
            val all = "${title ?: ""} ${text ?: ""}".lowercase()
            if (words.any { all.contains(it) }) { title = appLabel(sbn.packageName, sbn.user, uid); text = "Contenido oculto" }
        }

        // Texto COMPLETO para el historial y la vista de detalle: texto largo, o los últimos mensajes de la conversación.
        // La app lo recorta al mostrarlo, pero se guarda entero para que el .html descargado salga con todo.
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
        if (full == null) full = text

        // Si ya lo leíste y la app lo reenvía igual (pasa al abrir el chat), no vuelve a la isla. Pero el
        // HISTORIAL sí debe registrarlo: antes se descartaba aquí y los mensajes repetidos (p. ej. «3 mensajes
        // nuevos» en un grupo) no se guardaban. Por eso el descarte se hace después de registrar.
        val nowMs = System.currentTimeMillis()
        readSigs.entries.removeAll { nowMs - it.value > 120_000L }
        val alreadyRead = readSigs.containsKey("${sbn.key}|$title|$text")

        val (mediaBmp, mediaKind) = extractMedia(n)
        // Si la imagen viene por URI (WhatsApp/Telegram la mandan así), se carga en SEGUNDO PLANO: abrir el
        // stream en el hilo principal congelaba la isla. El tipo ya se conoce ahora; la imagen llega al instante.
        val attachUri = if (mediaBmp == null) mediaUri(n) else null
        // Si la imagen solo viene por URI y la app no dijo el tipo, se trata como imagen normal.
        val attachKind = when {
            mediaKind.isNotEmpty() -> mediaKind
            attachUri != null -> "Imagen"
            else -> ""
        }
        // Diagnóstico para Puente → Solo la isla: si el aviso habla de foto/sticker pero no se encontró
        // ninguna imagen, se anotan los extras que trae para poder afinar la detección.
        if (mediaBmp == null && attachUri == null && looksLikeMedia(n)) noteMediaMiss(sbn, n)

        // 1) La isla REGISTRA todo en el historial, por aplicación, con el texto COMPLETO.
        //    Se guardan también los avisos «en curso» (música, descargas, navegación…): así el historial ve
        //    TODAS las apps y TODOS los avisos del sistema, aunque la isla no los muestre como tarjeta.
        if (Prefs.keepHistory(this)) {
            val hp = sbn.packageName; val ht = title?.toString() ?: ""; val hx = (full ?: text)?.toString() ?: ""
            // Los avisos del sistema se de-duplican durante más tiempo: no queremos cuatro avisos de batería seguidos.
            val win = if (Sys.isSystemPkg(hp)) 120_000L else 3_000L
            val hUri = attachUri
            histExec.execute {      // disco y copia de seguridad fuera del hilo principal: la isla sale antes
                try {
                    // Si la imagen venía por URI, se abre AQUÍ (hilo aparte). Nunca en el hilo principal.
                    val bmp = mediaBmp ?: hUri?.let { loadUriMedia(it) }
                    Prefs.addHist(this, hp, ht, hx, saveBitmap(bmp, attachKind), attachKind, win)
                    Backup.maybeUpload(this)     // copia a la carpeta elegida (Drive u otra), si hay
                    Backup.autoLocal(this)       // copia a la carpeta PROPIA de la app, sola y sin tocar nada
                } catch (e: Exception) {}
            }
        }

        // 2) Filtros de la isla
        if (alreadyRead) return                       // ya la leíste: queda en el historial, no vuelve a la isla
        if (!Prefs.islandOn(this)) return
        if (!callPrio && appKey(sbn.packageName, uid) in Prefs.blocked(this)) return
        if (!callPrio && hiddenNow()) return          // la notificación del sistema se queda intacta
        if (reading && !callPrio) {                   // ya la estás leyendo dentro de la app: no entra a la isla ni deja esperando lo anterior de esa app
            val rp = sbn.packageName
            main.post { markRead(rp, user = uid) }
            return
        }
        if (sbn.isOngoing && !isCall(n)) return

        // Batería: todos los avisos del sistema se unen en UNA sola tarjeta con el nivel en tiempo real
        if (Sys.isBatteryNotif(sbn.packageName, title, text)) {
            if (why == null) {
                val bat = Battery.now(this)
                val body = (text?.toString() ?: "").let { if (bat.pct >= 0) "$it · ${bat.pct}%" else it }
                show(sbn.packageName, title, body, eventIcon("battery", 0xFF30D158.toInt()), true, true,
                    "sys:battery", false, sig = "sys:battery|$title|$text", target = "battery", label = "Batería",
                    systemKey = sbn.key)
            }
            return
        }

        val large: Drawable? = try { n.getLargeIcon()?.loadDrawable(this) } catch (e: Exception) { null }
        val small: Drawable? = try {
            n.smallIcon?.loadDrawable(createPackageContext(sbn.packageName, 0))
        } catch (e: Exception) { null }

        // Llamadas: entrante, en curso, en espera y perdida se unen en UNA sola tarjeta con sus botones reales
        if (isCall(n)) { showCall(sbn, n, title, text, large, callPrio); return }
        // Texto completo para la vista de detalle ya calculado arriba (se guarda entero en el historial)
        val ci = n.contentIntent
        val ik = appKey(sbn.packageName, uid)      // original y clon tienen su propia tarjeta y su propio destino
        if (ci != null && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0) intents[ik] = ci
        else if (ci != null && !intents.containsKey(ik)) intents[ik] = ci
        // Apps de pedidos y de viajes: se reconocen por su paquete y la tarjeta sale con su color y su etiqueta
        val tag = when {
            Sys.isOrderApp(sbn.packageName) -> "Pedido" to 0xFFFF9F0A.toInt()
            Sys.isRideApp(sbn.packageName) -> "Viaje" to 0xFF40C8E0.toInt()
            else -> null
        }
        show(sbn.packageName, title, text, large, true, true, "app:$ik", false,
            fallback = small, media = mediaBmp, detailText = full ?: text,
            sig = "${sbn.key}|$title|$text", user = sbn.user, userId = uid,
            label = tag?.first, accentOverride = tag?.second,
            mediaUri = attachUri, mediaKind = attachKind,
            systemKey = if (why == null) sbn.key else "")
    }

    /** La notificación se canceló (la leíste, la deslizaste o la app la retiró). Lo que cancela la propia isla no cuenta. */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?, reason: Int) {
        // Si el aviso lo quitó la PROPIA isla (blockSystem), su callback de borrado no significa que ya lo hayas
        // leído: en varias ROM (MIUI/HyperOS) llega con un motivo distinto de REASON_LISTENER_CANCEL y antes hacía
        // que la tarjeta se marcara como leída y desapareciera justo al expandirse. Se ignora por clave.
        if (wasSelfCanceled(sbn.key)) return
        if (reason == NotificationListenerService.REASON_LISTENER_CANCEL || reason == NotificationListenerService.REASON_LISTENER_CANCEL_ALL) return
        if (sbn.packageName == packageName) return
        // Se colgó o se rechazó la llamada: la tarjeta unificada se retira
        val cn = sbn.notification
        if (cn != null && (cn.category == "call" || cn.category == "missed_call")) main.post { dropKey("sys:call") }
        val pk = sbn.packageName; val key = sbn.key; val ru = norm(sbn.userId)
        // Margen de 1,5 s: algunas apps quitan y vuelven a publicar la misma clave al actualizar. Sin este
        // margen, ese borrado llegaba después del alta y la tarjeta recién mostrada se marcaba como leída.
        main.post { markRead(pk, key, minAge = 1_500, user = ru) }
    }

    /** Quita un aviso del sistema recordando su clave: así su callback de borrado no se confunde con «ya leído». */
    private fun cancelOwn(key: String) {
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        selfCanceled[key] = now
        if (selfCanceled.size > 40) selfCanceled.entries.removeAll { now - it.value > 30_000L }
        try { cancelNotification(key) } catch (e: Exception) {}
    }

    /** ¿Esa clave la quitó la isla? Se consume para que el mapa no crezca si la ROM no devuelve el callback. */
    private fun wasSelfCanceled(key: String): Boolean {
        val t = selfCanceled.remove(key) ?: return false
        // El callback de nuestro propio cancel llega en milisegundos; pasado ese margen, un borrado es real.
        return System.currentTimeMillis() - t < 5_000L
    }

    /** Lo llama accesibilidad cuando una app abre una ventana: si tenía avisos esperando en la isla, ya los estás viendo. */
    fun onAppOpened(pkg: String) {
        main.post {
            // Solo si de verdad estás dentro de esa app y la isla no está mostrando algo: algunas ROM avisan
            // también de las ventanas emergentes de la notificación, y eso marcaba la tarjeta como leída al instante.
            if (!expanded && usingNow() == pkg) readFront(pkg, 2000)
        }
    }

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

    /** null = la isla toma el aviso y lo quita del sistema. Si no, el motivo por el que se queda en el sistema (también para el diagnóstico). */
    private fun keepReason(sbn: StatusBarNotification, n: Notification, reading: Boolean, prio: Boolean = false): String? = when {
        !Prefs.islandOn(this) -> "la isla está apagada"
        !Prefs.blockSystem(this) -> "«Bloquear notificaciones del sistema» está apagado"
        !Settings.canDrawOverlays(this) -> "falta el permiso de superposición"
        Clones.key(sbn.packageName, if (sbn.userId >= 0) sbn.userId else myUser) in Prefs.blocked(this) -> "app excluida de la isla"
        sbn.isOngoing && !isCall(n) -> "aviso en curso"
        !sbn.isClearable -> "Android no deja quitarlo"
        isCall(n) -> "llamada (la isla la muestra sin quitar la del sistema)"
        keepInSystem(n) -> "categoría ${n.category} (se queda en el sistema)"
        // Llamada de un contacto prioritario: se salta el bloqueo de juego/app oculta.
        hiddenNow(prio) -> "isla oculta (bloqueo, juego o app elegida)"
        reading -> "estás dentro de esa app"
        else -> null
    }

    private val diagLines = ArrayList<String>()

    /** Guarda las últimas decisiones sobre avisos del sistema; se ven en Puente → Solo la isla. */
    private fun noteDecision(sbn: StatusBarNotification, uid: Int, summary: Boolean, why: String?) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val who = sbn.packageName + (if (isClone(uid)) " (clon)" else "") + (if (summary) " [resumen]" else "")
        val line = "$t $who → " + (if (why == null) "isla; quité el aviso del sistema" else "se queda en el sistema: $why")
        synchronized(diagLines) {
            diagLines.add(line)
            while (diagLines.size > 6) diagLines.removeAt(0)
            Prefs.setNotifDiag(this, diagLines.joinToString("\n"))
        }
    }

    /** Describe claves y tipos de un Bundle (sin volcar bitmaps) para el diagnóstico de medios. */
    @Suppress("DEPRECATION")
    private fun describeBundle(b: android.os.Bundle, depth: Int = 0): String =
        b.keySet().sorted().joinToString("; ") { k ->
            try {
                val v = b.get(k)
                val d = when (v) {
                    null -> "null"
                    is Bitmap -> "Bitmap(${v.width}x${v.height})"
                    is android.graphics.drawable.Icon -> "Icon"
                    is android.net.Uri -> "Uri($v)"
                    is android.os.Bundle -> if (depth < 1) "{" + describeBundle(v, depth + 1) + "}" else "Bundle"
                    is Array<*> -> {
                        val first = v.firstOrNull() as? android.os.Bundle
                        if (first != null && depth < 1) "Array(${v.size})[" + describeBundle(first, depth + 1) + "]"
                        else "Array(${v.size})"
                    }
                    is CharSequence -> "${v.javaClass.simpleName}(\"${v.toString().replace('\n', ' ').take(50)}\")"
                    else -> v.javaClass.simpleName
                }
                "$k=$d"
            } catch (t: Throwable) { "$k=?" }
        }

    /** Anota en el diagnóstico qué extras trae un aviso con foto/sticker del que no se pudo sacar la imagen. */
    private fun noteMediaMiss(sbn: StatusBarNotification, n: Notification) {
        try {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val line = "$t ${sbn.packageName} → sin imagen (${mediaHintKind(n)}); " +
                describeBundle(n.extras).take(1600)
            synchronized(diagLines) {
                diagLines.add(line)
                while (diagLines.size > 6) diagLines.removeAt(0)
                Prefs.setNotifDiag(this, diagLines.joinToString("\n"))
            }
        } catch (t: Throwable) {}
    }

    /** Multimedia, navegación, alarmas y progresos se quedan en el sistema para no romper sus controles.
     *  Las llamadas NO: entrante, en curso y perdida se unifican en la isla con sus botones. */
    private fun keepInSystem(n: Notification): Boolean =
        n.category in setOf("transport", "navigation", "alarm", "progress")

    /** ¿Es una llamada (entrante, en curso, en espera o perdida)? */
    private fun isCall(n: Notification): Boolean =
        n.category == "call" || n.category == "missed_call"

    /** Nombre del canal del aviso (respaldo cuando la notificación no trae texto). */
    private fun channelName(n: Notification): CharSequence? =
        try { n.channelId?.let { channelId ->
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            @Suppress("DEPRECATION") nm.getNotificationChannel(channelId)?.name
        } } catch (e: Exception) { null }

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
                // Wi-Fi apagado: aviso en rojo (la conexión, con el nombre de la red, llega por la red)
                android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val st = i.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE,
                        android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN)
                    if (st == android.net.wifi.WifiManager.WIFI_STATE_DISABLED) showNoNet("Wi-Fi desactivado")
                }
                // Punto de acceso (compartir datos móviles): se enciende/apaga y se cuentan los dispositivos
                WIFI_AP_ACTION -> {
                    val st = i.getIntExtra("wifi_state", 11)
                    if (st == 12 || st == 13) startHotspot() else stopHotspotCard()
                }
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
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WIFI_AP_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(sysReceiver, f, RECEIVER_EXPORTED)
            else registerReceiver(sysReceiver, f)
        } catch (e: Exception) {}
        // Wi-Fi / datos móviles / VPN: la red que se conecta sola se anuncia con su nombre
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
                    if (t.isEmpty() || t == last) return
                    last = t
                    main.removeCallbacks(offlineTask)
                    // Wi-Fi se decide más abajo (allí se conoce el nombre de la red); de datos y VPN solo se avisa
                    // si cambia de verdad o si ha pasado un buen rato desde la última vez.
                    if (t == "wifi") showWifi() else if (netOnce(t)) showEvent(t)
                }
                override fun onLost(n: android.net.Network) {
                    last = ""
                    // Puede ser un cambio Wi-Fi → datos: se espera un momento antes de darla por caída
                    main.removeCallbacks(offlineTask)
                    main.postDelayed(offlineTask, 1500)
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

    /** Eventos de energía que se funden en una sola tarjeta de batería con el nivel real. */
    private val BATTERY_EVENTS = setOf("plugged", "unplugged", "full", "low", "saver")

    private fun showCard(ev: IslandEvent, force: Boolean) {
        val shouldExpand = !force || Prefs.demoExpanded(this)
        val bat = ev.id in BATTERY_EVENTS
        // Los cambios de batería se registran UNA vez (con el nivel de ese momento), nunca como cuatro avisos sueltos.
        if (bat && !force) {
            val line = batteryLine(ev)
            histExec.execute { try { Prefs.addHist(this, "android", "Batería", line, "", "", 5 * 60_000L) } catch (e: Exception) {} }
        }
        show(packageName,
            if (bat) "Batería" else ev.title,
            if (bat) batteryLine(ev) else ev.body,
            eventIcon(if (bat) "battery" else ev.symbol, if (bat) 0xFF30D158.toInt() else ev.color),
            ev.short, shouldExpand && Prefs.eventExpand(this, ev.id),
            if (bat) "sys:battery" else "ev:${ev.id}", ev.short,
            target = if (bat) "battery" else (ev.target ?: "settings"),
            label = if (bat) "Batería" else ev.label,
            accentOverride = if (bat) 0xFF30D158.toInt() else if (ev.color != 0) ev.color else null)
    }

    /** Texto de la tarjeta de batería con el nivel leído ahora mismo (registro a tiempo real). */
    private fun batteryLine(ev: IslandEvent): String {
        val b = Battery.now(this)
        val pct = if (b.pct >= 0) " · ${b.pct}%" else ""
        return when (ev.id) {
            "plugged" -> "Cargador conectado$pct"
            "unplugged" -> "Cargador retirado$pct"
            "full" -> "100% cargada"
            "low" -> "Queda poca batería$pct"
            "saver" -> "Ahorro de batería activado$pct"
            else -> ev.body
        }
    }

    // ---------- Wi-Fi, red y punto de acceso ----------

    private val WIFI_AP_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private val offlineTask = Runnable { showNoNet("Red desactivada") }

    /**
     * ¿Toca anunciar la conexión en la isla otra vez?
     *
     * El sistema manda `onCapabilitiesChanged` muchas veces por una misma conexión (revalidaciones, cambio de señal,
     * portal cautivo, ida y vuelta entre datos y Wi‑Fi…) y el aviso de conectividad reaparecía una y otra vez: era lo
     * más molesto de la isla. Aquí solo se anuncia si cambia la red/transporte de verdad (clave distinta) o si ha
     * pasado un buen rato desde la última vez. Con el modo 2 no se anuncia nunca.
     *
     * 0 = avisar siempre · 1 = solo al cambiar de verdad (por defecto) · 2 = sin avisos de red
     */
    private fun netOnce(key: String): Boolean {
        val mode = Prefs.netMode(this)
        if (mode == 2) return false
        val now = System.currentTimeMillis()
        val same = key == Prefs.netKey(this)
        val fresh = now - Prefs.netAt(this) < if (mode == 0) 45_000L else 15 * 60_000L
        if (same && fresh) return false
        Prefs.setNetKey(this, key)
        Prefs.setNetAt(this, now)
        return true
    }

    /** Nombre de la red Wi-Fi conectada. Primero por el propio sistema y, si la ROM lo oculta, con Shizuku/root. */
    private fun currentSsid(): String? {
        try {
            val wm = getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val s = wm?.connectionInfo?.ssid?.trim('"')
            if (!s.isNullOrEmpty() && s != "<unknown ssid>" && s != "0x") return s
        } catch (e: Exception) {}
        // Sin permiso de ubicación la ROM oculta el nombre: con privilegios se lee de «cmd wifi status»
        if (Shell.privileged(this)) {
            try {
                val out = Shell.run(this, "cmd wifi status").second
                val m = Regex("SSID:\\s*\"?([^\"\\n,]+)\"?").find(out)
                val s = m?.groupValues?.getOrNull(1)?.trim()
                if (!s.isNullOrEmpty() && s != "<unknown ssid>") return s
            } catch (e: Exception) {}
        }
        return null
    }

    /** Wi-Fi conectado: «Conectado a la red [nombre]» + botón para cambiar de red. */
    private fun showWifi() {
        if (!Prefs.islandOn(this) || !Prefs.eventOn(this, "wifi")) return
        // El nombre de la red puede costar (consulta con privilegios): se resuelve fuera del hilo principal
        histExec.execute {
            val ssid = currentSsid()
            main.post { renderWifi(ssid) }
        }
    }

    private fun renderWifi(ssid: String?) {
        if (!Prefs.islandOn(this) || !Prefs.eventOn(this, "wifi")) return
        // Mismo nombre de red poco antes → no se vuelve a anunciar (la ROM lo pide decenas de veces por sesión)
        if (!netOnce("wifi|" + (ssid ?: ""))) return
        val blue = 0xFF0A84FF.toInt()
        val body = if (ssid != null) "Conectado a la red «$ssid»" else "Conectado a una red Wi-Fi"
        // Se muestra COMPACTA (sin desplegar): al tocarla se abren sus botones, así no interrumpe cada vez que aparece
        show(packageName, "Wi-Fi", body, eventIcon("wifi", blue), true, false, "sys:wifi", false,
            sig = "sys:wifi|$ssid", target = "wifi", label = "Wi-Fi", accentOverride = blue,
            actions = listOf(
                CardAction("Cambiar red", blue, true, dismiss = true) { openTarget("wifi") },
                CardAction("Ajustes", 0, false, dismiss = true) { openTarget("wifi") }))
    }

    /** Red caída o Wi-Fi apagado: aviso en ROJO. */
    private fun showNoNet(title: String) {
        if (!Prefs.islandOn(this) || !Prefs.eventOn(this, "offline")) return
        if (!netOnce("offline")) return
        val red = 0xFFFF453A.toInt()
        show(packageName, title, "Sin conexión a Internet", eventIcon("wifi_off", red), true, false, "sys:offline", false,
            sig = "sys:offline|$title", target = "wifi", label = "Red", accentOverride = red,
            actions = listOf(
                CardAction("Wi-Fi", 0xFF0A84FF.toInt(), false, dismiss = true) { openTarget("wifi") },
                CardAction("Datos", 0xFF30D158.toInt(), false, dismiss = true) { openTarget("data") }))
    }

    // ---------- Punto de acceso (compartir datos móviles) ----------

    private var hotspotOn = false
    private val hotspotTask = object : Runnable {
        override fun run() {
            if (!hotspotOn) return
            refreshHotspot()
            main.postDelayed(this, 8000)
        }
    }

    private fun startHotspot() {
        hotspotOn = true
        refreshHotspot()
        main.removeCallbacks(hotspotTask)
        main.postDelayed(hotspotTask, 8000)
    }

    private fun stopHotspotCard() {
        if (!hotspotOn) return
        hotspotOn = false
        main.removeCallbacks(hotspotTask)
        main.post { dropKey("sys:hotspot") }
    }

    /** Recuenta los dispositivos conectados y refresca la tarjeta del punto de acceso. */
    private fun refreshHotspot() {
        if (!Prefs.islandOn(this) || !Prefs.eventOn(this, "hotspot")) return
        val first = cards.none { it.key == "sys:hotspot" }
        histExec.execute {
            val clients = hotspotClients()
            main.post { if (hotspotOn) showHotspot(clients, first) }
        }
    }

    /** Dispositivos conectados al punto de acceso: se cuentan por la tabla ARP (solo con privilegios). */
    private fun hotspotClients(): Int? {
        if (!Shell.privileged(this)) return null
        return try {
            val out = Shell.run(this, "cat /proc/net/arp").second
            out.lineSequence().drop(1).count { l ->
                val p = l.trim().split(Regex("\\s+"))
                p.size >= 4 && p[3].equals("0x2", true) && p[1] != "00:00:00:00:00:00"
            }
        } catch (e: Exception) { null }
    }

    private fun showHotspot(clients: Int?, first: Boolean) {
        if (!hotspotOn) return
        val cyan = 0xFF40C8E0.toInt()
        val body = when {
            clients == null -> "Compartiendo internet"
            clients <= 0 -> "Compartiendo internet · sin dispositivos"
            clients == 1 -> "Compartiendo internet · 1 dispositivo"
            else -> "Compartiendo internet · $clients dispositivos"
        }
        val sig = "sys:hotspot|$body"
        if (cards.any { it.key == "sys:hotspot" && it.sig == sig }) return
        show(packageName, "Punto de acceso", body, eventIcon("hotspot", cyan), false, first, "sys:hotspot", false,
            sig = sig, target = "hotspot", label = "Punto de acceso", accentOverride = cyan,
            actions = listOf(
                CardAction("Desactivar", 0xFFFF453A.toInt(), true) { offHotspot() },
                CardAction("Ajustes", 0, false, dismiss = true) { openTarget("hotspot") }))
    }

    /** Apaga el punto de acceso con Shizuku/root y, si no se puede, deja abiertos los ajustes. */
    private fun offHotspot() {
        android.widget.Toast.makeText(this, "Apagando punto de acceso…", android.widget.Toast.LENGTH_SHORT).show()
        main.post { dropKey("sys:hotspot") }
        Thread {
            var ok = false
            try {
                if (Shell.privileged(this)) ok = Shell.run(this, "cmd wifi stop-softap").first == 0
            } catch (e: Exception) {}
            if (!ok) main.post { openTarget("hotspot") }
        }.start()
    }

    /** Quita todas las tarjetas con esa clave, sin tocar el resto. */
    private fun dropKey(key: String) {
        val gone = cards.filter { it.key == key }
        if (gone.isEmpty()) return
        val shown = cards.getOrNull(cardIndex)
        cards.removeAll(gone.toSet())
        if (!attached) return
        if (cards.isEmpty()) {
            cardIndex = 0
            if (expanded) collapse() else if (!Prefs.permanent(this)) removeNow() else { updateBadge(); updateCount() }
            return
        }
        cardIndex = if (shown != null && shown !in gone) cards.indexOf(shown) else 0
        bindCard(cards[cardIndex.coerceIn(0, cards.size - 1)])
        updateBadge(); updateCount()
    }

    /**
     * ¿La llamada viene de un contacto PRIORITARIO (favorito de la agenda o de la lista propia de Ryx)?
     * Se compara el nombre que trae la notificación y, si se puede, el número resuelto por contacto.
     */
    private fun callIsPriority(title: CharSequence?, text: CharSequence?): Boolean {
        if (!Prefs.callPriorityBypass(this)) return false
        val name = (title?.toString() ?: "").trim()
        if (name.isEmpty()) return false
        // Número visible en el texto de la notificación (algunas ROM lo ponen ahí si no hay contacto)
        val num = Regex("[+]?[0-9][0-9\\-\\s()]{5,}").find(text?.toString() ?: "")?.value
        return try { Contacts.isPriority(this, name, num) } catch (e: Exception) { false }
    }

    /** Nombre del contacto para una llamada: el título que trae o, si es un número, el nombre resuelto en la agenda. */
    private fun contactName(title: CharSequence?, text: CharSequence?): String {
        val t = (title?.toString() ?: "").trim()
        if (t.isNotEmpty() && !t.matches(Regex("[+]?[0-9][0-9\\-\\s()]{5,}"))) return t
        val num = t.ifEmpty { Regex("[+]?[0-9][0-9\\-\\s()]{5,}").find(text?.toString() ?: "")?.value ?: "" }
        if (num.isEmpty()) return t
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(num))
            contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else t } ?: t
        } catch (e: Exception) { t }
    }

    /** Llamadas: unifica entrante, en curso, en espera y perdida en UNA sola tarjeta con los botones del sistema. */
    private fun showCall(sbn: StatusBarNotification, n: Notification, title: CharSequence?, text: CharSequence?, large: Drawable?, prio: Boolean) {
        val green = 0xFF30D158.toInt(); val red = 0xFFFF453A.toInt()
        // Tipo de llamada que pone Android: 1 entrante, 2 en curso, 3 en espera; 0 = sin dato
        val ct = try { n.extras.getInt("android.callType", 0) } catch (e: Exception) { 0 }
        val t0 = (title?.toString() ?: "").trim()
        val b0 = (text?.toString() ?: "").trim()
        val low = "$t0 $b0".lowercase()
        // Nombre real del contacto (si el título es un número, se resuelve en la agenda)
        val t = contactName(title, text)
        val b = b0
        val missed = ct == 0 && (n.category == "missed_call" || low.contains("perdid") ||
            low.contains("missed") || low.contains("no contestad"))
        val state = when {
            ct == 1 -> "Llamada entrante"
            ct == 3 -> "Llamada en espera"
            ct == 2 -> "En llamada"
            missed -> "Llamada perdida"
            n.actions?.any { isAnswerAction(it) } == true -> "Llamada entrante"
            else -> "En llamada"
        }
        val color = if (missed) red else green
        // Botones REALES de la notificación (contestar, rechazar, colgar…): se reenvían tal cual, así funcionan
        // sin ser la app de llamadas por defecto.
        val acts = ArrayList<CardAction>()
        n.actions?.forEach { a ->
            val at = a.title?.toString()?.trim().orEmpty()
            val pi = a.actionIntent ?: return@forEach
            if (at.isEmpty()) return@forEach
            val ans = isAnswerAction(a)
            val end = !ans && isEndAction(a)
            acts.add(CardAction(at,
                color = if (ans) green else if (end) red else 0,
                filled = ans || end,
                dismiss = end,
                run = { try { pi.send(this@NotifListener, 0, null); buzz() } catch (e: Exception) {} }))
        }
        // Si no hay botones del sistema, se ponen los propios de Ryx: contestar/colgar no se puede inventar sin
        // los de la app de teléfono, así que se ofrece Abrir la app de llamada y, en perdidas, ver el registro.
        if (acts.isEmpty()) {
            if (missed) {
                acts.add(CardAction("Ver registro", color = Glass.ACCENT, filled = true, dismiss = true,
                    run = { Calls.openLog(this@NotifListener) }))
            } else {
                acts.add(CardAction("Abrir app", color = Glass.ACCENT, filled = true, dismiss = true,
                    run = { Calls.openDialer(this@NotifListener) }))
            }
        }
        // Etiqueta extra si es de un contacto prioritario
        val label = if (prio) "Llamada prioritaria" else "Llamada"
        val accent = if (prio) 0xFFFFB020.toInt() else color
        val icon = large ?: eventIcon("phone", accent)
        val extra = if (prio) "$state · ★ contacto prioritario" else state
        val body = if (b.isNotEmpty() && b != extra) "$extra · $b" else extra
        val sig = "sys:call|$t|$state|$b|$prio"
        // El sistema reenvía la llamada en curso muchas veces: si nada cambió, no se vuelve a animar
        if (cards.any { it.key == "sys:call" && it.sig == sig }) return
        show(packageName, if (t.isNotEmpty()) t else "Llamada", body, icon, false, true, "sys:call", false,
            sig = sig, target = if (missed) "calllog" else "dialer",
            label = label, accentOverride = accent, actions = acts)
    }

    /** ¿El botón de la notificación sirve para contestar? */
    private fun isAnswerAction(a: Notification.Action): Boolean {
        val s = (a.title?.toString() ?: "").lowercase()
        return s.contains("contest") || s.contains("respond") || s.contains("answer") ||
            s.contains("descolg") || s.contains("acept") || s.contains("atend") || s.contains("pick")
    }

    /** ¿El botón de la notificación sirve para colgar/rechazar? */
    private fun isEndAction(a: Notification.Action): Boolean {
        val s = (a.title?.toString() ?: "").lowercase()
        return s.contains("colg") || s.contains("rechaz") || s.contains("finaliz") ||
            s.contains("hang") || s.contains("decline") || s.contains("end") ||
            s.contains("ignor") || s.contains("deneg")
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
    private val idleTargets = ArrayList<GradientDrawable>()
    private var bgCard: Drawable? = null
    private var bgIdle: Drawable? = null
    private var blend = 0f

    private fun setCorners(r: Float) { cornerTargets.forEach { it.cornerRadius = r } }
    private fun setIdleCorners(r: Float) { idleTargets.forEach { it.cornerRadius = r } }

    /** Radio de la isla contraída: lo que elijas en Diseño → Isla contraída, o el del estilo. */
    private fun idleCornerPx(): Float {
        val cd = Prefs.idleCornerDp(this)
        return px(if (cd >= 0) cd.toFloat() else style.cornerDp).toFloat()
    }

    /** 0 = aspecto de reposo, 1 = tarjeta; se mezclan al abrir y cerrar. */
    private fun setBlend(b: Float) {
        blend = b
        bgCard?.setAlpha((255 * b).toInt().coerceIn(0, 255))
        bgIdle?.setAlpha((255 * (1f - b)).toInt().coerceIn(0, 255))
    }

    private fun lookDrawable(sp: IslandLook.Spec, r: Float, accent: Int?, into: ArrayList<GradientDrawable> = cornerTargets): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sp.top, sp.bottom)).apply {
            cornerRadius = r
            // El color de la app tiñe el borde, pero muy suave: antes un WhatsApp verde dejaba un aro enorme.
            if (accent != null) setStroke(maxOf(1, px(1f)), (accent and 0x00FFFFFF) or 0x4D000000)
            else if (sp.rimDp > 0f) setStroke(maxOf(1, px(sp.rimDp)), sp.rim)
        }
        into.add(base)
        if (sp.shine == 0) return base
        val glow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sp.shine, 0x00FFFFFF))
            .apply { cornerRadius = r }
        into.add(glow)
        return LayerDrawable(arrayOf(base, glow)).apply {
            val i = px(1)
            setLayerInset(1, i, i, i, i)
        }
    }

    private fun buildBackground(s: IslandStyle, accent: Int? = null): Drawable {
        val cd = Prefs.cornerDp(this)
        val r = px(if (cd >= 0) cd.toFloat() else s.cornerDp).toFloat()
        val rIdle = idleCornerPx()
        cornerTargets.clear()
        idleTargets.clear()
        val card = lookDrawable(IslandLook.card(this, s), r, accent, cornerTargets)
        val idle = lookDrawable(IslandLook.idle(this), rIdle, null, idleTargets)
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
        clampParams(params)
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
        val pImg = ImageView(this).apply {
            visibility = View.GONE; scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, px(12).toFloat()) }
            }
            clipToOutline = true
        }
        previewImg = pImg
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
            val chipLp = { l: Int, r: Int -> LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(l); marginEnd = px(r) } }
            addView(actionIcon(R.drawable.ic_arrow_right, s.textColor, true, s) { openCurrent(false) }, chipLp(0, 3))
            addView(actionIcon(R.drawable.ic_arrow_down, s.textColor, false, s) { openCurrent(true, 0) }, chipLp(3, 3))
            addView(actionIcon(R.drawable.ic_close, 0xFFFF5A5F.toInt(), false, s) { dismissCurrent() }, chipLp(3, 0))
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
            addView(pImg, LinearLayout.LayoutParams(px(46), px(46)).apply { marginStart = px(8) })
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

    /** Píldora de acción con color propio (verde contestar, rojo colgar, azul Wi-Fi…). */
    private fun chip(text: String, color: Int, filled: Boolean, s: IslandStyle, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        val base = if (color != 0) color else Glass.ACCENT
        setTextColor(if (filled) 0xFF10131A.toInt() else if (color != 0) base else s.textColor)
        setPadding(px(6), px(9), px(6), px(9))
        background = GradientDrawable().apply {
            cornerRadius = px(16).toFloat()
            setColor(when {
                filled -> base
                color != 0 -> (base and 0x00FFFFFF) or 0x33000000
                s.textColor == 0xFFFFFFFF.toInt() -> 0x30FFFFFF
                else -> 0x22000000
            })
        }
        setOnClickListener { onClick() }
    }

    /**
     * Botón de acción en forma de icono (en vez de texto): » para abrir, ⇣ para formato libre, ✕ para descartar.
     * Se usa el mismo fondo de píldora que los botones de texto para que la fila siga viéndose uniforme.
     */
    private fun actionIcon(res: Int, tint: Int, primary: Boolean, s: IslandStyle, onClick: () -> Unit) = FrameLayout(this).apply {
        setPadding(px(10), px(9), px(10), px(9))
        background = GradientDrawable().apply {
            cornerRadius = px(16).toFloat()
            setColor(if (primary) Glass.ACCENT else if (s.textColor == 0xFFFFFFFF.toInt()) 0x30FFFFFF else 0x22000000)
        }
        // getDrawable puede devolver null si el recurso no está en esta densidad/versión: nunca debe tumbar la isla.
        val d = getDrawable(res)?.mutate()
        d?.setTint(if (primary) 0xFF10131A.toInt() else tint)
        val iv = ImageView(this@NotifListener).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER }
        addView(iv, FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    /** Botones de la tarjeta: los propios (llamadas, Wi-Fi, punto de acceso) o los de siempre. */
    private fun rebuildActions(c: Card) {
        val acts = actionsRow ?: return
        acts.removeAllViews()
        val s = style
        val chipLp = { l: Int, r: Int -> LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(l); marginEnd = px(r) } }
        if (c.actions.isNotEmpty()) {
            c.actions.forEachIndexed { i, a ->
                acts.addView(chip(a.label, a.color, a.filled, s) {
                    a.run()
                    if (a.dismiss) dismissCurrent()
                }, chipLp(if (i == 0) 0 else 3, if (i == c.actions.size - 1) 0 else 3))
            }
        } else {
            // Iconos pequeños alineados a la derecha: » abrir · ⇣ formato libre · ✕ descartar.
            // Antes cada icono ocupaba un tercio del ancho y el botón amarillo salía como una barra enorme.
            val lpIcon = { l: Int -> LinearLayout.LayoutParams(px(38), px(38)).apply { marginStart = px(l) } }
            acts.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            acts.addView(actionIcon(R.drawable.ic_arrow_right, s.textColor, true, s) { openCurrent(false) }, lpIcon(0))
            acts.addView(actionIcon(R.drawable.ic_arrow_down, s.textColor, false, s) { openCurrent(true, 0) }, lpIcon(6))
            acts.addView(actionIcon(R.drawable.ic_close, 0xFFFF5A5F.toInt(), false, s) { dismissCurrent() }, lpIcon(6))
        }
    }

    private fun idleWidth(): Int = px(Prefs.idleWidthDp(this))

    private fun expandedWidth(): Int =
        minOf(resources.displayMetrics.widthPixels - px(2 * Prefs.sideMarginDp(this)),
            px(Prefs.expandedWidthDp(this).let { if (it > 0) it else style.expandedWidthDp }))

    /**
     * Deja el ancho y el desplazamiento dentro de los márgenes de la pantalla. La ventana va centrada
     * (`CENTER_HORIZONTAL`), así que un ancho grande + un `x` grande se salían por el borde (el mensaje largo
     * «se expandía de más»). Aquí se recorta el ancho y se limita el desplazamiento para que nunca sobrepase.
     */
    private fun clampParams(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val margin = px(Prefs.sideMarginDp(this)).coerceAtLeast(px(4))
        val maxW = (dm.widthPixels - 2 * margin).coerceAtLeast(px(80))
        if (p.width > maxW) p.width = maxW
        if (p.width < px(40)) p.width = px(40)
        // Con CENTER_HORIZONTAL, x es el desplazamiento desde el centro: no debe sacar la tarjeta de los bordes.
        val half = p.width / 2
        val maxX = (dm.widthPixels / 2 - margin - half).coerceAtLeast(0)
        if (p.x > maxX) p.x = maxX
        if (p.x < -maxX) p.x = -maxX
    }

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
        previewImg?.visibility = View.GONE
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
        user: android.os.UserHandle? = null, userId: Int = -1, actions: List<CardAction> = emptyList(),
        mediaUri: String? = null,  // imagen del adjunto por URI: se carga fuera del hilo principal
        mediaKind: String = "",    // tipo del adjunto (Imagen, Sticker…) para la vista previa
        systemKey: String = ""     // clave del aviso del sistema que hay que quitar al mostrarlo ("" = ninguno)
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
            cards.add(0, Card(key, pkg, t, b, shown, accent, ephemeral, fullText, media, count, lines, sig, target = target, label = label, userId = userId, user = user, mediaUri = mediaUri, mediaKind = mediaKind, actions = actions))
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
            // Solo se retira el aviso del sistema cuando la isla ya está en pantalla: si el addView falla,
            // la notificación original se queda y el usuario no pierde el aviso.
            if (systemKey.isNotEmpty()) cancelOwn(systemKey)
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
        cards.getOrNull(cardIndex)?.let { syncMedia(it) }
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
        rebuildActions(c)
        // Las tarjetas con botones propios (llamada, Wi-Fi, punto de acceso) los muestran ya al abrirse
        actionsRow?.visibility = if (detail || c.actions.isNotEmpty()) View.VISIBLE else View.GONE
        if (detail) bodyView?.text = c.detail ?: c.body
        syncMedia(c)
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
                    // Umbral de deslizar fijo: 50 dp. Un toque sigue siendo tolerante (un dedo no es un punto).
                    val th = px(50).toFloat()
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
            2 -> openCurrent(true, 0)       // ventana en formato libre
            3 -> dismissCurrent()
            4 -> page(1)
            5 -> page(-1)
            6 -> collapse()
            7 -> enterDetail()
            8 -> {}                          // «Burbuja» retirada: sin acción
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

    /** La isla deja de mostrar ESA copia de la app (queda en «Apps bloqueadas» de los ajustes). */
    private fun muteCurrent() {
        val c = cards.getOrNull(cardIndex) ?: return
        if (c.pkg == packageName) { dismissCurrent(); return }
        val k = appKey(c.pkg, c.userId)
        Prefs.setBlocked(this, Prefs.blocked(this) + k)
        android.widget.Toast.makeText(this,
            "${nameOf(c)}: ${if (isClone(c.userId)) "esta copia" else "esta app"} silenciada en la isla",
            android.widget.Toast.LENGTH_SHORT).show()
        cards.removeAll { appKey(it.pkg, it.userId) == k }
        if (cards.isEmpty()) { collapse(); return }
        cardIndex = 0; bindCard(cards[0]); updateBadge()
    }

    // ---------- Vista de detalle ----------

    private fun resetDetail() {
        detail = false
        val cur = cards.getOrNull(cardIndex)
        if (cur != null) {
            rebuildActions(cur)
            syncMedia(cur)      // vuelve la miniatura; la imagen grande se oculta
        } else detailImg?.visibility = View.GONE
        actionsRow?.visibility = if (cur != null && cur.actions.isNotEmpty()) View.VISIBLE else View.GONE
        bodyView?.maxLines = 1
        appView?.visibility = if (style.showAppName) View.VISIBLE else View.GONE
        cards.getOrNull(cardIndex)?.let {
            bodyView?.text = it.body
            appView?.text = nameOf(it) + if (it.count > 1) " · ${it.count}" else ""
        }
        island?.minimumHeight = px(maxOf(style.iconDp + 2 * style.padV, 64))
    }

    /** Fondo tenue para stickers (se ve la transparencia) y mientras llega una imagen. */
    private fun mediaTile(radius: Float): Drawable = GradientDrawable().apply {
        cornerRadius = px(radius).toFloat()
        setColor(0x1FFFFFFF)
    }

    /** Pinta una imagen con un fundido corto; si ya estaba puesta, no se repite la animación. */
    private fun paintMedia(iv: ImageView, bmp: Bitmap, tag: String) {
        val same = iv.tag == tag
        iv.setImageBitmap(bmp)
        iv.tag = tag
        if (!same) {
            iv.alpha = 0f
            iv.animate().alpha(1f).setDuration(180).start()
        } else iv.alpha = 1f
    }

    /**
     * Coloca el adjunto de la tarjeta: miniatura al desplegar la isla e imagen grande en el detalle.
     * Los stickers se muestran enteros (FIT_CENTER, sin recortar) sobre un fondo tenue; las fotos se recortan.
     * Si la imagen viene por URI (WhatsApp/Telegram) se carga en un HILO APARTE: abrirla en el hilo principal
     * congelaba toda la isla.
     */
    private fun syncMedia(c: Card) {
        val uri = c.mediaUri
        val sticker = c.mediaKind.contains("Sticker", ignoreCase = true)
        val bmp = c.media ?: uri?.let { mediaCache.get(it) }
        val has = bmp != null || (uri != null && !mediaFailed.contains(uri))

        previewImg?.let { p ->
            val show = expanded && !detail && has
            p.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                p.scaleType = if (sticker) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                val pad = if (sticker) px(4) else 0
                p.setPadding(pad, pad, pad, pad)
                p.background = if (sticker || bmp == null) mediaTile(12f) else null
                if (bmp != null) paintMedia(p, bmp, uri ?: c.key)
                else { p.setImageDrawable(null); uri?.let { loadMedia(it) } }
            }
        }

        detailImg?.let { iv ->
            val show = detail && has
            iv.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                iv.scaleType = if (sticker) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                val pad = if (sticker) px(6) else 0
                iv.setPadding(pad, pad, pad, pad)
                iv.background = if (sticker || bmp == null) mediaTile(14f) else null
                if (bmp != null) paintMedia(iv, bmp, uri ?: c.key)
                else { iv.setImageDrawable(null); uri?.let { loadMedia(it) } }
            }
        }
    }

    /** Descarga una imagen por URI una sola vez y refresca la tarjeta que la esté mostrando. */
    private fun loadMedia(uri: String) {
        if (mediaCache.get(uri) != null || mediaFailed.contains(uri)) return
        if (!mediaTried.add(uri)) return
        Thread {
            val bm = loadUriMedia(uri)
            if (bm != null) mediaCache.put(uri, bm) else mediaFailed.add(uri)
            main.post {
                val cur = cards.getOrNull(cardIndex)
                if (cur != null && cur.mediaUri == uri) syncMedia(cur)
            }
        }.start()
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
        syncMedia(c)
        rebuildActions(c)
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

    /** Convierte en URI solo valores que de verdad lo parezcan (evita coger textos cualquiera). */
    private fun uriValue(v: Any?): String? = when (v) {
        is android.net.Uri -> v.toString()
        is String -> if (v.startsWith("content://") || v.startsWith("file://") ||
            v.startsWith("http://") || v.startsWith("https://")) v else null
        else -> null
    }

    /**
     * URI de un mensaje de MessagingStyle: unas apps la guardan como texto y otras como Uri (Parcelable).
     * Además se revisan claves no estándar (media_uri, image_uri, thumbnail…) sin tocar las del remitente,
     * que normalmente apuntan a su avatar.
     */
    @Suppress("DEPRECATION")
    private fun bundleUri(m: android.os.Bundle): String? {
        m.getString("uri")?.let { return it }
        (m.getParcelable<android.os.Parcelable>("uri") as? android.net.Uri)?.let { return it.toString() }
        for (k in m.keySet()) {
            val kl = k.lowercase()
            if (kl.contains("sender") || kl.contains("person") || kl == "icon") continue
            if (!(kl.contains("uri") || kl.contains("image") || kl.contains("picture") ||
                    kl.contains("photo") || kl.contains("sticker") || kl.contains("media") ||
                    kl.contains("thumbnail"))) continue
            uriValue(try { m.get(k) } catch (t: Throwable) { null })?.let { return it }
        }
        return null
    }

    /**
     * Última imagen de una notificación de mensajes y su MIME. Se recorre hacia atrás TODA la lista: si el último
     * mensaje es texto pero el anterior traía foto/sticker, antes se perdía la imagen. El segundo valor es el MIME
     * de la imagen encontrada o, si no hay ninguna, el del último mensaje (para reconocer audio/vídeo).
     */
    @Suppress("DEPRECATION")
    private fun latestImage(n: Notification): Pair<String?, String> {
        val msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return Pair(null, "")
        var lastMime = ""
        for (i in msgs.indices.reversed()) {
            val m = msgs[i] as? android.os.Bundle ?: continue
            val mime = (m.getCharSequence("type") ?: m.getString("type"))?.toString() ?: ""
            if (lastMime.isEmpty()) lastMime = mime
            val uri = bundleUri(m)
            if (mime.startsWith("image/")) return Pair(uri, mime)
            if (uri != null && mime.isEmpty()) return Pair(uri, mime)
        }
        return Pair(null, lastMime)
    }

    /**
     * Lee un extra que puede ser un Bitmap o un Icon (Android 12+). Cada extra va por separado y con su propio
     * try: antes un solo extra raro (o un Icon donde se esperaba Bitmap) tumbaba TODA la extracción y la isla
     * se quedaba sin imagen aunque el aviso sí la trajera.
     */
    @Suppress("DEPRECATION")
    private fun bitmapExtra(b: android.os.Bundle, key: String): Bitmap? {
        val v = try { b.get(key) } catch (t: Throwable) { null } ?: return null
        return try {
            when (v) {
                is Bitmap -> v
                is android.graphics.drawable.Icon -> v.loadDrawable(this)?.let { toBitmap(it) }
                is Drawable -> toBitmap(v)
                else -> null
            }
        } catch (t: Throwable) { null }
    }

    /** Todo el texto visible del aviso, en minúsculas, para reconocer el tipo de adjunto. */
    @Suppress("DEPRECATION")
    private fun mediaText(n: Notification): String {
        val sb = StringBuilder()
        fun add(cs: CharSequence?) { if (cs != null) sb.append(' ').append(cs) }
        val ex = n.extras
        add(ex.getCharSequence(Notification.EXTRA_TITLE))
        add(ex.getCharSequence(Notification.EXTRA_TEXT))
        add(ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
        try {
            ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { m ->
                val b = m as? android.os.Bundle ?: return@forEach
                add(b.getCharSequence("text")); add(b.getCharSequence("sender"))
            }
        } catch (t: Throwable) {}
        return sb.toString().lowercase()
    }

    /** Tipo de adjunto que se deduce del texto («Envió una foto», «Envió un sticker»…). */
    private fun mediaHintKind(n: Notification): String {
        val s = try { mediaText(n) } catch (t: Throwable) { return "" }
        return when {
            s.contains("sticker") -> "Sticker"
            s.contains("gif") -> "GIF"
            s.contains("video") || s.contains("vídeo") || s.contains("🎥") -> "Video"
            s.contains("audio") || s.contains("nota de voz") || s.contains("voice message") -> "Audio"
            s.contains("documento") || s.contains("document") -> "Documento"
            s.contains("foto") || s.contains("photo") || s.contains("imagen") || s.contains("image") ||
                s.contains("📷") || s.contains("🖼") || s.contains("cámara") || s.contains("camara") -> "Foto"
            else -> ""
        }
    }

    private fun looksLikeMedia(n: Notification): Boolean = mediaHintKind(n).isNotEmpty()

    /** Busca un Bitmap en claves no estándar de los mensajes (media, thumbnail, picture…). */
    @Suppress("DEPRECATION")
    private fun mediaBitmapFromMessages(n: Notification): Bitmap? {
        val msgs = try { n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (t: Throwable) { null } ?: return null
        for (i in msgs.indices.reversed()) {
            val b = msgs[i] as? android.os.Bundle ?: continue
            for (k in b.keySet()) {
                val kl = k.lowercase()
                if (kl.contains("sender") || kl.contains("person") || kl == "icon") continue
                if (!(kl.contains("image") || kl.contains("picture") || kl.contains("photo") ||
                        kl.contains("sticker") || kl.contains("media") || kl.contains("thumbnail"))) continue
                bitmapExtra(b, k)?.let { return it }
            }
        }
        return null
    }

    /** Busca un Bitmap en extras propios de la app/ROM (nunca en el icono grande, que suele ser el avatar). */
    @Suppress("DEPRECATION")
    private fun mediaBitmapFromExtras(ex: android.os.Bundle): Bitmap? {
        for (k in ex.keySet()) {
            val kl = k.lowercase()
            if (kl.contains("largeicon") || kl.contains("smallicon") || kl.contains("pictureicon")) continue
            if (!(kl.contains("picture") || kl.contains("image") || kl.contains("photo") ||
                    kl.contains("sticker") || kl.contains("media") || kl.contains("thumbnail"))) continue
            bitmapExtra(ex, k)?.let { return it }
        }
        return null
    }

    /** URI de imagen en extras propios de la app/ROM (miui.extra, image_uri, thumbnail…). */
    @Suppress("DEPRECATION")
    private fun mediaUriFromExtras(ex: android.os.Bundle): String? {
        for (k in ex.keySet()) {
            val kl = k.lowercase()
            if (kl.contains("largeicon") || kl.contains("smallicon") || kl.contains("pictureicon")) continue
            if (!(kl.contains("picture") || kl.contains("image") || kl.contains("photo") ||
                    kl.contains("sticker") || kl.contains("media") || kl.contains("thumbnail") ||
                    kl.contains("uri"))) continue
            uriValue(try { ex.get(k) } catch (t: Throwable) { null })?.let { return it }
        }
        return null
    }

    /** Imagen o sticker de la notificación (si hay) y su tipo. NUNCA hace E/S: eso va en loadUriMedia(). */
    @Suppress("DEPRECATION")
    private fun extractMedia(n: Notification): Pair<Bitmap?, String> {
        val ex = n.extras
        val (lastUri, mime) = try { latestImage(n) } catch (t: Throwable) { Pair(null, "") }
        val imgUri = lastUri ?: try { mediaUriFromExtras(ex) } catch (t: Throwable) { null }
        val stickerMime = mime.contains("webp") || mime.contains("png")
        val hint = try { looksLikeMedia(n) } catch (t: Throwable) { false }
        val hintKind = try { mediaHintKind(n) } catch (t: Throwable) { "" }
        val messaging = try {
            ex.getParcelableArray(Notification.EXTRA_MESSAGES) != null || n.category == Notification.CATEGORY_MESSAGE
        } catch (t: Throwable) { true }

        // 1) La imagen grande de verdad (BigPictureStyle / MessagingStyle con foto).
        var bmp = bitmapExtra(ex, Notification.EXTRA_PICTURE)
        if (bmp == null) bmp = bitmapExtra(ex, Notification.EXTRA_PICTURE_ICON)
        // 2) Extras con nombre de imagen/media que algunas apps o la propia ROM añaden.
        if (bmp == null) bmp = mediaBitmapFromMessages(n)
        if (bmp == null) bmp = mediaBitmapFromExtras(ex)
        // 3) El icono grande SOLO se usa en avisos que no son de mensajería. En WhatsApp y demás chats
        //    ese icono es el avatar del contacto, no la foto/sticker enviado: usarlo llenaba el historial
        //    de fotos de perfil que no eran el mensaje.
        if (bmp == null && imgUri == null && !messaging) {
            val large = bitmapExtra(ex, Notification.EXTRA_LARGE_ICON)
                ?: bitmapExtra(ex, Notification.EXTRA_LARGE_ICON_BIG)
            if (large != null && (stickerMime || hint)) bmp = large
        }

        var kind = when {
            bmp == null -> ""
            stickerMime -> "Sticker"
            else -> "Imagen"
        }
        // Tipo del adjunto por su MIME (SIN abrir la URI: abrirla bloquea el hilo y congelaba la isla).
        if (kind.isEmpty()) kind = when {
            mime.startsWith("image/") -> if (stickerMime) "Sticker" else "Imagen"
            mime.startsWith("audio/") -> "Audio"
            mime.startsWith("video/") -> "Video"
            else -> ""
        }
        // Si la app NO comparte la imagen (WhatsApp solo manda «Envió una foto»), al menos se guarda el TIPO
        // para que el historial deje constancia del adjunto en vez de perderlo.
        if (kind.isEmpty()) kind = hintKind
        // «Ver una vez»: WhatsApp/Signal/Telegram marcan estos mensajes en el texto o el canal.
        val l = try { mediaText(n) } catch (t: Throwable) { "" }
        if (l.contains("ver una vez") || l.contains("view once") || l.contains("solo una vez") ||
            l.contains("una sola vez") || l.contains("one-time") || l.contains("temporal"))
            kind = if (kind.isEmpty()) "Ver una vez" else "$kind · Ver una vez"
        return Pair(bmp?.let { scaleDown(it, 1024) }, kind)
    }

    /**
     * URI del adjunto de una notificación (solo si es una IMAGEN). Se devuelve la URI sin abrirla: quien la use
     * la leerá en un hilo aparte. Abrir el stream en el hilo principal congelaba la isla (vídeos/audios grandes).
     */
    private fun mediaUri(n: Notification): String? {
        val fromMsg = try { latestImage(n).first } catch (e: Throwable) { null }
        return fromMsg ?: try { mediaUriFromExtras(n.extras) } catch (e: Throwable) { null }
    }

    /**
     * Abre la URI de una imagen y la convierte en bitmap. SIEMPRE en un hilo aparte (hace E/S y decodifica).
     * Primero se leen las dimensiones y se reduce al decodificar: una foto de cámara ya no puede agotar la memoria.
     */
    private fun loadUriMedia(uri: String): Bitmap? {
        return try {
            val u = android.net.Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 960 && bounds.outHeight / (sample * 2) >= 960) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val b = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
                b?.let { scaleDown(it, 960) }
            } else null
        } catch (e: Throwable) { null }
    }

    private fun saveBitmap(bmp: Bitmap?, kind: String): String {
        if (bmp == null) return ""
        return try {
            val name = "${System.currentTimeMillis()}_${(Math.random() * 99999).toInt()}.img"
            FileOutputStream(File(Prefs.histDir(this), name)).use {
                if (kind.startsWith("Sticker")) bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
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
        val cornerIdle = idleCornerPx()
        val fromR = cornerTargets.firstOrNull()?.cornerRadius ?: (idleH / 2f)
        val fromB = blend
        val toB = if (toExpanded) 1f else 0f
        // Al abrir: del radio de reposo al de la tarjeta. Al cerrar: al revés. Cada capa mantiene su propio radio.
        val toR = if (toExpanded) minOf(cornerPx, toH / 2f + px(4)) else cornerIdle
        val fromRIdle = idleTargets.firstOrNull()?.cornerRadius ?: cornerIdle
        val toRIdle = if (toExpanded) cornerPx else cornerIdle
        val maxW = resources.displayMetrics.widthPixels
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (toExpanded) Motion.expandMs(this) else Motion.collapseMs(this)
        a.interpolator = if (toExpanded) Motion.spring() else Motion.smooth()
        a.addUpdateListener { va ->
            val t = va.animatedValue as Float
            val time = va.currentPlayTime.toFloat() / va.duration.toFloat()
            params.width = lerp(fromW.toFloat(), toW.toFloat(), t).toInt().coerceIn(px(40), maxW)
            clampParams(params)
            row?.minimumHeight = lerp(fromH.toFloat(), toH.toFloat(), t).toInt().coerceAtLeast(idleH)
            setCorners(lerp(fromR, toR, t.coerceIn(0f, 1f)))
            setIdleCorners(lerp(fromRIdle, toRIdle, t.coerceIn(0f, 1f)))
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
        // Aviso de versión nueva: abre Ryx directamente en «Buscar actualizaciones».
        if (card.target == "update") {
            try {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("ryx_manual_upd", true)
                })
            } catch (e: Exception) {}
            dismissCurrent(); return
        }
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
        // Es la original de una app que tiene clon: se lanza indicando SU espacio para que la ROM no elija el clon
        if (hasClone(pkg) && openClone(pkg, android.os.Process.myUserHandle(), myUser, quiet = true)) return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(launch) } catch (e: Exception) {}
    }

    /** Abre la copia (clon) de la app en su propio espacio. Si no se puede, avisa; jamás abre la original en su lugar. Fuera del hilo principal. */
    private fun openClone(pkg: String, user: android.os.UserHandle, userId: Int, quiet: Boolean = false): Boolean {
        // 1) API pública del sistema para lanzar la app principal de otro espacio
        try {
            val la = getSystemService(LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            val comp = la.getActivityList(pkg, user).firstOrNull()?.componentName
            if (comp != null) { la.startMainActivity(comp, user, null, null); return true }
        } catch (e: Exception) {}
        // 2) Con Shizuku/root: mismo lanzamiento indicando el espacio del clon
        try {
            if (Shell.privileged(this)) {
                val r = Shell.run(this, "am start --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg")
                if (r.first == 0 && !r.second.contains("Error", true)) return true
            }
        } catch (e: Exception) {}
        if (!quiet) main.post {
            android.widget.Toast.makeText(this, "No pude abrir el clon (esta ROM no deja lanzarlo sin Shizuku/root)", android.widget.Toast.LENGTH_LONG).show()
        }
        return false
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
        // Llamadas: se usa la app de llamadas elegida o la predeterminada del sistema (ver [Calls]).
        if (k == "calllog") { Calls.openLog(this); return }
        if (k == "dialer") { Calls.openDialer(this); return }
        for (i in targetIntents(k)) {
            try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return } catch (e: Exception) {}
        }
    }
}
