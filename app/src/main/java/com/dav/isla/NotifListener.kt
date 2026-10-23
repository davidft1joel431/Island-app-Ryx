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
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.MediaStore
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
    /** Hijos de un grupo mostrados hace poco. La isla quita el aviso del sistema, así que «activeNotifications»
     *  ya no los ve cuando llega el resumen; esto evita que el resumen se muestre como una tarjeta duplicada. */
    private val recentChildren = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
    /** Miniatura de la imagen/sticker. La isla abierta mide 74 dp: la foto grande vive en el Historial. */
    private var previewImg: ImageView? = null
    /** Adjuntos ya decodificados, por URI: la misma imagen no se vuelve a leer al cambiar de tarjeta. */
    private val mediaCache = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    /** Dibujos ya decodificados, incluidos los animados (AnimatedImageDrawable). */
    private val mediaArtCache = android.util.LruCache<String, Drawable>(32)
    private val mediaTried = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val mediaFailed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Avisos a los que se les está dando unos segundos para que la app adjunte la foto/sticker. */
    private val mediaRetry = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Huellas ya guardadas: si el aviso se borra después, no se duplica en el historial. */
    private val historySaved = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Evita repetir la búsqueda de último recurso para el mismo aviso y el mismo texto. */
    private val mediaFallbackTried = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Imágenes de la carpeta ya usadas por un aviso, para no asignar la misma foto a dos mensajes. */
    private val folderImagesUsed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())
    private var actionsRow: LinearLayout? = null
    private var detail = false
    /** Columna propia de las llamadas: contacto/estado arriba y botones ANCHOS abajo (sin ganar altura). */
    private var callCol: LinearLayout? = null
    private var callNameView: TextView? = null
    private var callStateView: TextView? = null
    private var callActionsRow: LinearLayout? = null

    /** Una tarjeta acumulada en la isla. */
    private data class Card(
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

    /** Última app que se vio al frente, para limpiar solo al ENTRAR en ella, no cada 2,5 s. */
    private var lastFrontPkg: String? = null

    /** Cada pocos segundos: quita la isla si entró a un juego y la devuelve (modo permanente) al salir. */
    private val watch = object : Runnable {
        override fun run() {
            try {
                if (Prefs.islandOn(this@NotifListener)) {
                    val hide = hiddenNow()
                    if (hide && attached) removeNow()
                    else if (!hide && !attached && Prefs.permanent(this@NotifListener)) showIdle()
                    // Al ENTRAR en una app se quitan sus avisos viejos («quitar lo que ya leí»). Antes se hacía
                    // cada 2,5 s, así que un mensaje nuevo que llegaba mientras estabas dentro se borraba solo
                    // antes de que lo vieras: por eso TikTok no mostraba nada o solo aparecía tras muchos avisos.
                    // Ahora solo se limpia cuando cambia la app al frente; lo recién llegado se queda en la isla.
                    val fg = usingNow()
                    if (!expanded && cards.any { !it.ephemeral }) {
                        if (fg != null && fg != lastFrontPkg) readFront(fg, 4_000)
                    }
                    lastFrontPkg = fg
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
        Prefs.clearLegacyBlur(this)   // el desenfoque de sistema se retiró: no debe quedar activo tras actualizar
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        main.post { if (Prefs.islandOn(this) && Prefs.permanent(this)) showIdle() }
        registerSystemReceivers()
        main.removeCallbacks(watch); main.postDelayed(watch, 2500)
        if (Prefs.hideHeadsUp(this)) Thread { Shell.setHeadsUp(this, false) }.start()
        checkUpdatesInBackground()          // aviso de versión nueva aunque la app no esté abierta
        main.removeCallbacks(updWatch); main.postDelayed(updWatch, Prefs.updEverySec(this).coerceIn(15, 3600) * 1000L)
        Backup.autoLocal(this)              // copia propia al arrancar el servicio
        histExec.execute { try { Prefs.migrateCodes(this) } catch (t: Throwable) {} }
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
                // La altura base también se refresca al mover los deslizadores. En el detalle no se toca,
                // porque la tarjeta puede haber crecido para mostrar el texto completo.
                if (!detail) island?.minimumHeight = px(if (expanded) IslandGeometry.EXPANDED_HEIGHT_DP else Prefs.heightDp(this))
                // La pastilla contraída depende del alto elegido: al mover el deslizador, sus esquinas se
                // recalculan en el acto para que siga siendo una cápsula y no un rectángulo a medio redondear.
                setIdleCorners(idleCornerPx())
                if (!expanded) setCorners(idleCornerPx())
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
        val isSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isSummary) {
            // Resumen de un grupo («3 mensajes nuevos» de WhatsApp…). Si sus mensajes individuales están
            // activos, se quita para no duplicar. Pero algunas apps (TikTok Lite, por ejemplo) publican
            // SOLO el resumen: antes se descartaba siempre y por eso no aparecía nada en la isla. Si no hay
            // ningún hijo activo, el resumen sigue el camino normal y se muestra como una tarjeta más.
            val su = if (sbn.userId >= 0) sbn.userId else myUser
            if (hasActiveChild(sbn) || hadRecentChild(sbn)) {
                val why = keepReason(sbn, n)
                if (why == null) cancelOwn(sbn.key)
                noteDecision(sbn, su, true, why)
                return
            }
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

        // Aunque estés dentro de una app, un aviso NUEVO debe poder entrar a la isla. Antes se descartaba
        // aquí mismo («ya lo estás leyendo dentro»), y por eso TikTok —y cualquier app abierta— no mostraba
        // sus mensajes hasta que salías o llegaban muchos de golpe. Lo que ya leíste se sigue quitando al
        // abrir la app (onAppOpened / watch), pero un aviso recién llegado ya no se pierde.
        val uid = if (sbn.userId >= 0) sbn.userId else myUser
        if (isClone(uid)) { cloneSeen[sbn.packageName] = uid; Clones.note(this, sbn.packageName, uid) }

        // 0) SOLO LA ISLA: si la isla va a MOSTRARLA, se quita el aviso del sistema en el mismo momento de mostrarlo
        //    (no antes). Así ninguna notificación se pierde: si la isla no la enseña, la del sistema se queda.
        //    Llamadas, multimedia, navegación, alarmas y progresos se quedan siempre en el sistema.
        //    LLAMADA DE CONTACTO PRIORITARIO (favorito): se salta el bloqueo de juego/app oculta y aparece siempre.
        val callPrio = isCall(n) && Prefs.callPriorityBypass(this) && callIsPriority(title, text)
        val why = keepReason(sbn, n, callPrio)
        noteDecision(sbn, uid, isSummary, why)

        // Palabras ocultas (privacidad): se reemplaza el contenido
        val words = Prefs.hiddenWords(this)
        if (words.isNotEmpty()) {
            val all = "${title ?: ""} ${text ?: ""}".lowercase()
            if (words.any { all.contains(it) }) { title = appLabel(sbn.packageName, sbn.user, uid); text = "Contenido oculto" }
        }

        // Texto COMPLETO para el historial y la vista de detalle. Ya no se acepta el primer EXTRA_BIG_TEXT
        // a ciegas: se comparan todas las fuentes y se guarda la versión MÁS LARGA. Si la app recortó el
        // texto normal, MessagingStyle o EXTRA_BIG_TEXT suelen conservar el mensaje entero.
        var full: CharSequence? = fullTextOf(n, text)
        if (words.isNotEmpty() && text == "Contenido oculto") full = text
        if (full == null) full = text

        // Si ya lo leíste y la app lo reenvía igual (pasa al abrir el chat), no vuelve a la isla. Pero el
        // HISTORIAL sí debe registrarlo: antes se descartaba aquí y los mensajes repetidos (p. ej. «3 mensajes
        // nuevos» en un grupo) no se guardaban. Por eso el descarte se hace después de registrar.
        val nowMs = System.currentTimeMillis()
        readSigs.entries.removeAll { nowMs - it.value > 120_000L }
        val alreadyRead = readSigs.containsKey("${sbn.key}|$title|$text")

        val (mediaBmp, mediaKind) = extractMedia(n, sbn.packageName)
        // La URI también se conserva cuando ya hay un bitmap de respaldo: el bitmap suele ser el primer
        // fotograma (o el avatar), mientras que la URI puede ser el GIF/WebP animado completo. La isla
        // muestra el bitmap al instante y lo cambia por el dibujo animado cuando termina de cargar.
        val candidateUri = try { mediaUri(n) } catch (t: Throwable) { null }
        val uriKind = candidateUri?.let { kindOfUri(it) } ?: ""
        val imageKind = mediaKind.isEmpty() ||
            mediaKind.contains("Imagen", ignoreCase = true) ||
            mediaKind.contains("Foto", ignoreCase = true) ||
            mediaKind.contains("Sticker", ignoreCase = true) ||
            mediaKind.contains("GIF", ignoreCase = true) ||
            mediaKind.contains("Animación", ignoreCase = true)
        val attachUri = candidateUri.takeIf { uriKind.isNotEmpty() || imageKind }
        val attachKind = when {
            mediaKind.contains("Sticker", ignoreCase = true) -> "Sticker"
            mediaKind.contains("GIF", ignoreCase = true) -> "GIF"
            // El tipo real de la URI manda sobre un "Imagen" genérico del aviso.
            uriKind == "GIF" -> "GIF"
            uriKind == "Sticker" -> "Sticker"
            mediaKind.isNotEmpty() -> mediaKind
            uriKind.isNotEmpty() -> uriKind
            attachUri != null -> "Imagen"
            else -> ""
        }
        // Si el aviso habla de una foto/sticker pero todavía no trae la imagen, se le da un margen antes de
        // quitarlo del sistema. WhatsApp y otras apps publican el texto primero y actualizan la imagen después;
        // si se cancela el aviso en ese instante, la actualización (y el permiso de la URI) se pierde.
        val wantsBitmap = attachKind.startsWith("Foto") || attachKind.startsWith("Sticker") ||
            attachKind.startsWith("Imagen") || attachKind.startsWith("GIF") ||
            attachKind.startsWith("Animación")
        // Si hay URI animada, aunque exista un bitmap de respaldo, se espera antes de quitar el aviso:
        // al cancelarlo algunas apps revocan el permiso de lectura de la URI.
        val mediaHold = if (wantsBitmap && (mediaBmp == null || attachUri != null)) 2_500L else 0L
        // Diagnóstico para Puente → Solo la isla: si el aviso habla de foto/sticker pero no se encontró
        // ninguna imagen, se anotan los extras que trae para poder afinar la detección.
        if (mediaBmp == null && attachUri == null && looksLikeMedia(n)) noteMediaMiss(sbn, n)
        else if (mediaBmp != null && looksLikeMedia(n)) noteMediaFound(sbn, mediaBmp, mediaKind)

        // 1) La isla REGISTRA todo en el historial, por aplicación, con el texto COMPLETO.
        //    Se guardan también los avisos «en curso» (música, descargas, navegación…): así el historial ve
        //    TODAS las apps y TODOS los avisos del sistema, aunque la isla no los muestre como tarjeta.
        if (Prefs.keepHistory(this)) {
            // La clave incluye el espacio (pkg@usuario) para que el clon tenga su propio grupo en el historial
            // y no se mezcle con la app original, que comparte el mismo paquete.
            val hp = appKey(sbn.packageName, uid); val ht = title?.toString() ?: ""; val hx = (full ?: text)?.toString() ?: ""
            // Los avisos del sistema se de-duplican durante más tiempo: no queremos cuatro avisos de batería seguidos.
            val win = if (Sys.isSystemPkg(hp)) 120_000L else if (hx.length > 320) 15_000L else 3_000L
            val hUri = attachUri
            val fingerprint = "$hp|$ht|$hx"
            historySaved[fingerprint] = nowMs
            if (historySaved.size > 320) historySaved.entries.removeAll { nowMs - it.value > 60 * 60_000L }
            histExec.execute {      // disco y copia de seguridad fuera del hilo principal: la isla sale antes
                try {
                    // La URI conserva los bytes originales (GIF/WebP animados, transparencia de stickers).
                    // Si no se puede leer, se usa el bitmap que trajo el aviso. Todo fuera del hilo principal.
                    val saved = hUri?.let { saveUriMedia(it, attachKind) }
                        ?: mediaBmp?.let {
                            val k = refineMediaKind(attachKind, null, false)
                            SavedMedia(saveBitmap(it, k), k)
                        }
                        ?: SavedMedia("", attachKind)
                    Prefs.addHist(this, hp, ht, hx, saved.name, saved.kind, win)
                    Backup.maybeUpload(this)     // copia a la carpeta elegida (Drive u otra), si hay
                    Backup.autoLocal(this)       // copia a la carpeta PROPIA de la app, sola y sin tocar nada
                } catch (e: Exception) {}
            }
            // Último recurso: si el aviso habla de foto/sticker pero no comparte la imagen, se intenta
            // leer una URI de accesibilidad y, para WhatsApp, la última foto reciente de su carpeta.
            if (mediaBmp == null && attachUri == null && wantsBitmap) {
                scheduleMediaFallback(sbn, hp, ht, hx, attachKind, uid, nowMs)
            }
        }

        // 2) Filtros de la isla
        // Una llamada nunca se descarta por «ya leída»: si sigue sonando o en curso, sus botones deben
        // poder pulsarse aunque la app reenvíe la misma notificación.
        if (alreadyRead && !isCall(n)) return        // ya la leíste: queda en el historial, no vuelve a la isla
        if (!Prefs.islandOn(this)) return
        if (!callPrio && appKey(sbn.packageName, uid) in Prefs.blocked(this)) return
        if (!callPrio && hiddenNow()) return          // la notificación del sistema se queda intacta
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
        if (ci != null && !isSummary) intents[ik] = ci
        else if (ci != null && !intents.containsKey(ik)) intents[ik] = ci
        // Apps de pedidos y de viajes: se reconocen por su paquete y la tarjeta sale con su color y su etiqueta
        val tag = when {
            Sys.isOrderApp(sbn.packageName) -> "Pedido" to 0xFFFF9F0A.toInt()
            Sys.isRideApp(sbn.packageName) -> "Viaje" to 0xFF40C8E0.toInt()
            else -> null
        }
        // Marca el grupo como «ya tiene un hijo mostrado»: así su resumen posterior no se duplica.
        // Un resumen que se muestra por su cuenta NO cuenta como hijo; si no, el siguiente resumen de la
        // misma app (TikTok Lite) quedaría bloqueado durante 20 s.
        if (!isSummary) recentChildren[childGroupKey(sbn)] = System.currentTimeMillis()
        show(sbn.packageName, title, text, large, true, true, "app:$ik", false,
            fallback = small, media = mediaBmp, detailText = full ?: text,
            sig = "${sbn.key}|$title|$text", user = sbn.user, userId = uid,
            label = tag?.first, accentOverride = tag?.second,
            mediaUri = attachUri, mediaKind = attachKind,
            systemKey = if (why == null) sbn.key else "", holdSystemMs = mediaHold)
    }

    /** La notificación se canceló (la leíste, la deslizaste o la app la retiró). Lo que cancela la propia isla no cuenta. */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?, reason: Int) {
        // Si el aviso lo quitó la PROPIA isla (blockSystem), su callback de borrado no significa que ya lo hayas
        // leído: en varias ROM (MIUI/HyperOS) llega con un motivo distinto de REASON_LISTENER_CANCEL y antes hacía
        // que la tarjeta se marcara como leída y desapareciera justo al expandirse. Se ignora por clave.
        if (wasSelfCanceled(sbn.key)) return
        if (reason == NotificationListenerService.REASON_LISTENER_CANCEL || reason == NotificationListenerService.REASON_LISTENER_CANCEL_ALL) return
        if (sbn.packageName == packageName) return
        // El aviso se borró antes de que el historial lo viera (teléfono inactivo, mensaje eliminado…).
        // Se archiva igualmente; si ya estaba guardado, la huella evita el duplicado.
        captureRemoved(sbn)
        // Se colgó o se rechazó la llamada: la tarjeta unificada se retira
        val cn = sbn.notification
        if (cn != null && isCall(cn)) main.post { dropKey("sys:call") }
        val pk = sbn.packageName; val key = sbn.key; val ru = norm(sbn.userId)
        // Margen de 1,5 s: algunas apps quitan y vuelven a publicar la misma clave al actualizar. Sin este
        // margen, ese borrado llegaba después del alta y la tarjeta recién mostrada se marcaba como leída.
        main.post { markRead(pk, key, minAge = 1_500, user = ru) }
    }

    /**
     * Revisa el aviso que sigue activo en el sistema por si ya trae la foto/sticker. Algunas apps no mandan
     * el adjunto en el primer aviso, sino en una actualización; si la isla lo quitaba antes, la imagen se
     * perdía. Solo se vuelve a procesar cuando de verdad hay imagen, para no duplicar tarjetas ni historial.
     */
    private fun retryMedia(key: String, attempt: Int) {
        try {
            val active = activeNotifications?.firstOrNull { it.key == key }
            if (active != null) {
                val (bmp, _) = try { extractMedia(active.notification, active.packageName) } catch (t: Throwable) { Pair(null, "") }
                val uri = if (bmp == null) try { mediaUri(active.notification) } catch (t: Throwable) { null } else null
                if (bmp != null || uri != null) {
                    onNotificationPosted(active)
                    return
                }
            }
        } catch (t: Throwable) {}
        if (attempt < 3) main.postDelayed({ retryMedia(key, attempt + 1) }, 600L)
        else mediaRetry.remove(key)
    }

    /** Quita un aviso del sistema recordando su clave: así su callback de borrado no se confunde con «ya leído». */
    private fun cancelOwn(key: String) {
        if (key.isEmpty()) return
        mediaRetry.remove(key)
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
            // Solo al ENTRAR de verdad en esa app. Algunas ROM avisan también de las ventanas emergentes de
            // la notificación; si no se filtra, la tarjeta recién llegada se marcaba como leída al instante.
            if (!expanded && usingNow() == pkg && lastFrontPkg != pkg) {
                readFront(pkg, 2_000)
                lastFrontPkg = pkg
            }
        }
    }

    /**
     * Ya lo leíste: la tarjeta sale de la isla y, si la app reenvía la misma, no vuelve.
     * [key] = solo la de esa notificación; [minAge] = no toca las recién llegadas.
     */
    private fun markRead(pkg: String, key: String? = null, minAge: Long = 0, user: Int? = null) {
        if (!Prefs.autoRead(this) || pkg == packageName) return
        val now = System.currentTimeMillis()
        val gone = cards.filter { !it.ephemeral && it.pkg == pkg && it.key != "sys:call" && now - it.time >= minAge && (key == null || it.sig.startsWith("$key|")) &&
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

    /**
     * ¿El resumen tiene mensajes individuales activos? En ese caso el resumen sobra (los hijos ya se muestran).
     * Se compara la clave completa para no contar el propio resumen.
     */
    private fun hasActiveChild(sbn: StatusBarNotification): Boolean = try {
        activeNotifications?.any { a ->
            a.packageName == sbn.packageName && a.userId == sbn.userId && a.key != sbn.key &&
                (a.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        } == true
    } catch (t: Throwable) { false }

    /** Clave por app y espacio para recordar hijos ya mostrados (la isla quitó su aviso del sistema). */
    private fun childGroupKey(sbn: StatusBarNotification): String =
        "${sbn.packageName}|${sbn.userId}"

    private fun hadRecentChild(sbn: StatusBarNotification): Boolean {
        val now = System.currentTimeMillis()
        recentChildren.entries.removeAll { now - it.value > 20_000L }
        val t = recentChildren[childGroupKey(sbn)] ?: return false
        return now - t < 20_000L
    }

    /** null = la isla toma el aviso y lo quita del sistema. Si no, el motivo por el que se queda en el sistema (también para el diagnóstico). */
    private fun keepReason(sbn: StatusBarNotification, n: Notification, prio: Boolean = false): String? = when {
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

    /** Deja constancia en Puente de que la imagen sí se extrajo (tipo y tamaño), para comparar con el fallo. */
    private fun noteMediaFound(sbn: StatusBarNotification, bmp: Bitmap, kind: String) {
        try {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val line = "$t ${sbn.packageName} → imagen $kind (${bmp.width}x${bmp.height})"
            synchronized(diagLines) {
                diagLines.add(line)
                while (diagLines.size > 6) diagLines.removeAt(0)
                Prefs.setNotifDiag(this, diagLines.joinToString("\n"))
            }
        } catch (t: Throwable) {}
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
    private fun isCall(n: Notification): Boolean {
        if (n.category == "call" || n.category == "missed_call") return true
        // Algunas apps no ponen categoría de llamada pero sí el tipo que añade Android.
        val ct = try { n.extras.getInt("android.callType", 0) } catch (t: Throwable) { 0 }
        if (ct != 0) return true
        // Último recurso: pantalla completa con botones de contestar o colgar. Es lo que delata a
        // las llamadas de apps que publican la suya sin categoría ni callType.
        if (n.fullScreenIntent != null && n.actions?.any { isAnswerAction(it) || isEndAction(it) } == true) return true
        return false
    }

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
        // Los cambios internos de batería solo se muestran en la isla. El Historial es para
        // notificaciones y mensajes largos, no para un registro de eventos de la app.
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
        // La firma incluye los títulos de los botones: al pasar de entrante a en curso (o a perdida)
        // cambian las acciones y la tarjeta debe actualizarse, no quedarse con los botones viejos.
        val actSig = n.actions?.joinToString(",") { it.title?.toString().orEmpty() } ?: ""
        val sig = "sys:call|$t|$state|$b|$prio|$actSig"
        // El sistema reenvía la llamada en curso muchas veces: si nada cambió, no se vuelve a animar
        if (cards.any { it.key == "sys:call" && it.sig == sig }) return
        // Una llamada activa se queda visible hasta contestar/colgar; una perdida sí se retira sola.
        show(packageName, if (t.isNotEmpty()) t else "Llamada", body, icon, missed, true, "sys:call", false,
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
        show(packageName, "Ryx Island", "Funciona. Toca para abrir en formato libre.", null, true, true)
    }

    // ---------- Construccion de la isla ----------

    private val cornerTargets = ArrayList<GradientDrawable>()
    private val idleTargets = ArrayList<GradientDrawable>()
    private var bgCard: Drawable? = null
    private var bgIdle: Drawable? = null
    private var blend = 0f

    private fun setCorners(r: Float) { cornerTargets.forEach { it.cornerRadius = r } }
    private fun setIdleCorners(r: Float) { idleTargets.forEach { it.cornerRadius = r } }

    /** Radio de la isla contraída: pastilla HyperOS por defecto (alto / 2). */
    private fun idleCornerPx(): Float = px(IslandGeometry.collapsedCornerDp(this)).toFloat()

    /** Radio de la isla expandida: cuadrado redondeado por defecto, nunca una píldora completa. */
    private fun expandedCornerPx(): Float = px(IslandGeometry.expandedCornerDp(this)).toFloat()

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
        val r = expandedCornerPx()
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
        actionsRow = acts
        // Llamadas: el contacto/estado va arriba y los botones de contestar/colgar ocupan TODO el ancho
        // disponible en una fila propia. Comparte los 74 dp de la isla abierta: no gana altura.
        val callName = TextView(this).apply {
            setTextColor(s.textColor)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        }
        val callState = TextView(this).apply {
            setTextColor(s.subColor)
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(px(8), 0, 0, 0)
            includeFontPadding = false
        }
        val callActs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val callLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(callName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(callState, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val call = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(callLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(callActs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        }
        callCol = call
        callNameView = callName
        callStateView = callState
        callActionsRow = callActs
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(app, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // El cuerpo ocupa una caja fija y se desplaza dentro. La isla NO crece con el mensaje.
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
            addView(call, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(10)
            })
            addView(pImg, LinearLayout.LayoutParams(px(46), px(46)).apply { marginStart = px(8) })
            addView(acts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
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
            // NUNCA usar FLAG_BLUR_BEHIND en esta ventana. En Android 12+ y ROMs HyperOS/MIUI el
            // desenfoque de una TYPE_APPLICATION_OVERLAY puede descontrolarse y desenfocar toda la
            // pantalla, dejando el teléfono bloqueado. El cristal se simula dentro de la isla.
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
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setTypeface(typeface, Typeface.BOLD)
        val base = if (color != 0) color else Glass.ACCENT
        setTextColor(if (filled) 0xFF10131A.toInt() else if (color != 0) base else s.textColor)
        setPadding(px(7), px(9), px(7), px(9))
        val fill = when {
            filled -> base
            color != 0 -> (base and 0x00FFFFFF) or 0x33000000
            s.textColor == 0xFFFFFFFF.toInt() -> 0x30FFFFFF
            else -> 0x22000000
        }
        val border = when {
            filled -> 0x2EFFFFFF
            color != 0 -> (base and 0x00FFFFFF) or 0x66000000
            s.textColor == 0xFFFFFFFF.toInt() -> 0x3AFFFFFF
            else -> 0x2A000000
        }
        // Pastilla con un degradado muy corto y borde de 1 dp: se ve intencionada, no un rectángulo plano.
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            IslandLook.lighter(fill, 0.08f), fill, IslandLook.darker(fill, 0.06f)
        )).apply {
            cornerRadius = px(15).toFloat()
            setStroke(maxOf(1, px(1f)), border)
        }
        setOnClickListener { onClick() }
    }

    /**
     * Botón de acción en forma de icono (en vez de texto): » para abrir, ⇣ para formato libre, ✕ para descartar.
     * Se usa el mismo fondo de píldora que los botones de texto para que la fila siga viéndose uniforme.
     */
    private fun actionIcon(res: Int, tint: Int, primary: Boolean, s: IslandStyle, onClick: () -> Unit) = FrameLayout(this).apply {
        setPadding(px(6), px(5), px(6), px(5))
        val fill = if (primary) Glass.ACCENT else if (s.textColor == 0xFFFFFFFF.toInt()) 0x30FFFFFF else 0x22000000
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            IslandLook.lighter(fill, 0.08f), fill, IslandLook.darker(fill, 0.06f)
        )).apply {
            cornerRadius = px(14).toFloat()
            setStroke(maxOf(1, px(1f)), if (primary) 0x2EFFFFFF else if (s.textColor == 0xFFFFFFFF.toInt()) 0x3AFFFFFF else 0x2A000000)
        }
        // getDrawable puede devolver null si el recurso no está en esta densidad/versión: nunca debe tumbar la isla.
        val d = getDrawable(res)?.mutate()
        d?.setTint(if (primary) 0xFF10131A.toInt() else tint)
        val iv = ImageView(this@NotifListener).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER }
        addView(iv, FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    /** Botones de la tarjeta: los propios (llamadas, Wi-Fi, punto de acceso) o los de siempre. */
    private fun rebuildActions(c: Card) {
        val acts = actionsRow ?: return
        acts.removeAllViews()
        val callActs = callActionsRow
        callActs?.removeAllViews()
        val s = style
        // Llamadas: botones anchos que se reparten la columna. Antes eran píldoras pequeñas a la
        // derecha y costaba acertar en contestar/colgar con el teléfono sonando.
        if (c.key == "sys:call") {
            c.actions.forEachIndexed { i, a ->
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) lp.marginStart = px(6)
                callActs?.addView(callChip(a.label, a.color, a.filled, s) {
                    a.run()
                    if (a.dismiss) dismissCurrent()
                }, lp)
            }
            callActs?.visibility = if (c.actions.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        // La fila va a la derecha de la tarjeta, no debajo: así el alto abierto sigue siendo 74 dp.
        val chipLp = { l: Int, r: Int -> LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = px(l); marginEnd = px(r) } }
        if (c.actions.isNotEmpty()) {
            c.actions.forEachIndexed { i, a ->
                acts.addView(chip(a.label, a.color, a.filled, s) {
                    a.run()
                    if (a.dismiss) dismissCurrent()
                }, chipLp(if (i == 0) 0 else 3, if (i == c.actions.size - 1) 0 else 3))
            }
        } else {
            // Iconos compactos: » abrir · ⇣ formato libre · ✕ descartar.
            val lpIcon = { l: Int -> LinearLayout.LayoutParams(px(30), px(30)).apply { marginStart = px(l) } }
            acts.addView(actionIcon(R.drawable.ic_arrow_right, s.textColor, true, s) { openCurrent(false) }, lpIcon(0))
            acts.addView(actionIcon(R.drawable.ic_arrow_down, s.textColor, false, s) { openCurrent(true, 0) }, lpIcon(4))
            acts.addView(actionIcon(R.drawable.ic_close, 0xFFFF5A5F.toInt(), false, s) { dismissCurrent() }, lpIcon(4))
        }
    }

    /** Píldora de llamada: misma estética que [chip] pero más baja, para que las dos filas quepan en 74 dp. */
    private fun callChip(text: String, color: Int, filled: Boolean, s: IslandStyle, onClick: () -> Unit) =
        chip(text, color, filled, s, onClick).apply {
            includeFontPadding = false
            setPadding(px(8), px(4), px(8), px(4))
        }

    /** ¿La tarjeta actual es la de llamada unificada? */
    private fun isCallCard(c: Card?): Boolean = c != null && c.key == "sys:call"

    /**
     * Decide qué contenido se ve según la tarjeta: la llamada usa su columna propia (contacto y
     * botones anchos) y oculta el texto normal; las demás, al revés.
     */
    private fun applyCardMode(c: Card?) {
        val call = isCallCard(c)
        // Si la isla está contraída (solo posible con el modo permanente), el contenido queda oculto:
        // al pasar de una tarjeta a otra no debe asomar texto en la píldora.
        textCol?.visibility = if (call || !expanded) View.GONE else View.VISIBLE
        callCol?.visibility = if (call && expanded) View.VISIBLE else View.GONE
        actionsRow?.visibility = if (!call && expanded && (detail || (c != null && c.actions.isNotEmpty()))) View.VISIBLE else View.GONE
        if (call) badgeView?.visibility = View.GONE
    }

    /** La fila que se anima al entrar y salir del detalle: botones normales o botones de llamada. */
    private fun actionHost(): View? = if (isCallCard(cards.getOrNull(cardIndex))) callCol else actionsRow

    private fun idleWidth(): Int = px(Prefs.idleWidthDp(this))

    /** Ancho abierto: 350 dp exactos; solo se recorta si la pantalla física es más estrecha. */
    private fun expandedWidth(): Int =
        minOf(resources.displayMetrics.widthPixels, px(IslandGeometry.EXPANDED_WIDTH_DP))

    /**
     * Caja de texto del detalle dentro de los 74 dp fijos. Se muestran dos o tres líneas y el resto se
     * desplaza dentro de esa misma caja: el mensaje largo nunca hace crecer la isla.
     */
    private fun detailBodyHeight(): Int = px(28)

    /**
     * Deja el ancho y el desplazamiento dentro de la pantalla. La ventana va centrada
     * (`CENTER_HORIZONTAL`), así que un ancho grande + un `x` grande se salían por el borde.
     * El ancho expandido no se reduce por el margen: 350 dp mandan, y el margen que quede se reparte.
     */
    private fun clampParams(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val maxW = dm.widthPixels.coerceAtLeast(px(40))
        if (p.width > maxW) p.width = maxW
        if (p.width < px(40)) p.width = px(40)
        val half = p.width / 2
        val maxX = (dm.widthPixels / 2 - half).coerceAtLeast(0)
        if (p.x > maxX) p.x = maxX
        if (p.x < -maxX) p.x = -maxX
    }

    private fun setContentAlpha(a: Float) {
        iconView?.alpha = a
        textCol?.alpha = a
        countView?.alpha = a
        // La columna de llamada también entra y sale con el fundido; si no, quedaría invisible al abrir.
        callCol?.alpha = a
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
        island?.minimumWidth = 0
        iconView?.scaleX = 1f; iconView?.scaleY = 1f; textCol?.translationX = 0f
        iconView?.visibility = View.GONE
        textCol?.visibility = View.GONE
        previewImg?.visibility = View.GONE
        actionsRow?.visibility = View.GONE
        callCol?.visibility = View.GONE
        setContentAlpha(1f)
        island?.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setBlend(0f)
        setCorners(px(Prefs.heightDp(this)) / 2f)
        setIdleCorners(idleCornerPx())
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
            // La ventana se reutiliza al cambiar de ajustes o al volver del modo detalle; hay que
            // devolver el alto a WRAP_CONTENT o la isla en reposo conservaría los 74 dp del detalle.
            params.width = idleWidth()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
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
        systemKey: String = "",    // clave del aviso del sistema que hay que quitar al mostrarlo ("" = ninguno)
        holdSystemMs: Long = 0L    // espera antes de quitar el aviso del sistema (para que llegue la imagen)
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
            if (systemKey.isNotEmpty()) {
                if (holdSystemMs > 0) {
                    // Se le da tiempo a la app a publicar la imagen y a que el historial abra su URI.
                    val k = systemKey
                    main.postDelayed({ cancelOwn(k) }, holdSystemMs)
                    if (mediaRetry.add(k)) main.postDelayed({ retryMedia(k, 0) }, 550L)
                } else {
                    cancelOwn(systemKey)
                }
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
        setContentAlpha(0f)
        updateBadge()
        cards.getOrNull(cardIndex)?.let { syncMedia(it) }
        applyCardMode(cards.getOrNull(cardIndex))
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
        callNameView?.text = c.title ?: ""
        callStateView?.text = c.body ?: ""
        // El estado de la llamada toma el color de la tarjeta (verde entrante/en curso, rojo perdida).
        callStateView?.setTextColor(c.accent ?: style.subColor)
        applyCardMode(c)
        if (detail) updateDetailText(c)
        syncMedia(c)
    }

    /** Acumulación a la derecha: iconos apilados al expandir, contador al reposo. */
    private fun updateBadge() {
        val b = badgeView ?: return
        b.removeAllViews()
        // La llamada ya tiene sus propios botones: la pila de iconos solo le robaría ancho.
        if (isCallCard(cards.getOrNull(cardIndex))) { b.visibility = View.GONE; return }
        // En el detalle manda el texto completo: la pila de iconos se retira para no dejar la
        // columna del mensaje en un par de letras.
        if (detail) { b.visibility = View.GONE; return }
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
            // 12, 13 y 15 se retiraron: Base64 y binario son recuperación interna, no acciones del usuario.
            14 -> copyCurrentImage()
        }
    }

    /**
     * Copia el texto COMPLETO de la tarjeta. Si la isla recibió el mensaje cortado, se busca la entrada
     * del Historial y se reconstruye desde su texto, su binario o su Base64.
     */
    private fun copyCurrent() {
        val c = cards.getOrNull(cardIndex) ?: return
        val hint = (c.detail ?: c.body ?: c.title)?.toString() ?: ""
        val txt = MsgBase.fullTextFor(this, c.pkg, c.title?.toString() ?: "", hint, c.userId)
        if (txt.isEmpty()) {
            // Una tarjeta solo de imagen/sticker: el mismo gesto copia la imagen.
            copyCurrentImage()
            return
        }
        if (Clip.text(this, "Ryx", txt)) toast("Mensaje completo copiado (${txt.length} caracteres)")
        else toast("No se pudo copiar el mensaje")
        pulse()
    }

    /** Copia la imagen o el sticker de la tarjeta como imagen normal en el portapapeles. */
    private fun copyCurrentImage() {
        val c = cards.getOrNull(cardIndex) ?: return
        val hint = (c.detail ?: c.body ?: c.title)?.toString() ?: ""
        val h = MsgBase.findHist(this, c.pkg, c.title?.toString() ?: "", hint, c.userId)
        val kind = c.mediaKind.ifEmpty { h?.kind ?: "" }
        val file = h?.let { MsgBase.mediaFile(this, it) }
        if (file != null && Clip.image(this, file, kind)) {
            toast("Imagen copiada: pégala en el chat")
            pulse()
            return
        }
        val bmp = c.media
        if (bmp != null && Clip.image(this, bmp, kind)) {
            toast("Imagen copiada: pégala en el chat")
            pulse()
            return
        }
        val uri = c.mediaUri
        if (uri != null) {
            histExec.execute {
                val loaded = loadUriMedia(uri)
                main.post {
                    if (loaded != null && Clip.image(this, loaded, kind)) {
                        toast("Imagen copiada: pégala en el chat")
                        pulse()
                    } else toast("No pude copiar la imagen")
                }
            }
            return
        }
        toast("Esta tarjeta no tiene imagen ni sticker")
    }

    private fun toast(t: String) =
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()

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
            syncMedia(cur)      // vuelve la miniatura al tamaño compacto
        }
        applyCardMode(cur)
        actionHost()?.alpha = 1f
        updateBadge()
        bodyView?.let { b ->
            b.maxLines = 1
            b.ellipsize = TextUtils.TruncateAt.END
            b.movementMethod = null
            b.isVerticalScrollBarEnabled = false
            b.scrollTo(0, 0)
            (b.layoutParams as? LinearLayout.LayoutParams)?.let { p ->
                p.height = LinearLayout.LayoutParams.WRAP_CONTENT
                b.layoutParams = p
            }
        }
        appView?.visibility = if (style.showAppName) View.VISIBLE else View.GONE
        cards.getOrNull(cardIndex)?.let {
            bodyView?.text = it.body
            appView?.text = nameOf(it) + if (it.count > 1) " · ${it.count}" else ""
        }
        island?.minimumHeight = px(IslandGeometry.EXPANDED_HEIGHT_DP)
    }

    /** Fondo tenue para stickers (se ve la transparencia) y mientras llega una imagen. */
    private fun mediaTile(radius: Float): Drawable = GradientDrawable().apply {
        cornerRadius = px(radius).toFloat()
        setColor(0x1FFFFFFF)
    }

    /** Pinta una imagen con un fundido corto; si ya estaba puesta, no se repite la animación. */
    private fun paintMedia(iv: ImageView, bmp: Bitmap, tag: String) {
        val same = iv.tag == tag
        MediaArt.stop(iv.drawable)
        iv.setImageBitmap(bmp)
        iv.tag = tag
        if (!same) {
            iv.alpha = 0f
            iv.scaleX = 0.97f
            iv.scaleY = 0.97f
            iv.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            iv.alpha = 1f
            iv.scaleX = 1f
            iv.scaleY = 1f
        }
    }

    /** Pinta un dibujo (puede ser GIF/WebP animado) y arranca la animación si existe. */
    private fun paintArt(iv: ImageView, art: Drawable, tag: String) {
        val same = iv.tag == tag
        val fresh = art.constantState?.newDrawable(iv.resources)?.mutate() ?: art
        if (iv.drawable !== fresh) MediaArt.stop(iv.drawable)
        iv.setImageDrawable(fresh)
        iv.tag = tag
        MediaArt.start(fresh)
        if (!same) {
            iv.alpha = 0f
            iv.scaleX = 0.97f
            iv.scaleY = 0.97f
            iv.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            iv.alpha = 1f
            iv.scaleX = 1f
            iv.scaleY = 1f
        }
    }

    private fun stopAnimated(iv: ImageView?) = MediaArt.stop(iv?.drawable)

    /**
     * Coloca el adjunto de la tarjeta: miniatura al desplegar la isla y en el detalle.
     * Los stickers y las animaciones se muestran enteros (FIT_CENTER) sobre un fondo tenue.
     * Si la imagen viene por URI (WhatsApp/Telegram) se carga en un HILO APARTE: abrirla en el hilo principal
     * congelaba toda la isla. La copia binaria del Historial es el respaldo interno.
     */
    private fun syncMedia(c: Card) {
        // La llamada no tiene adjunto: su columna manda y la miniatura no debe ocupar sitio.
        if (isCallCard(c)) { previewImg?.visibility = View.GONE; stopAnimated(previewImg); return }
        val uri = c.mediaUri
        val kind = c.mediaKind
        val fit = kind.contains("Sticker", ignoreCase = true) ||
            kind.contains("GIF", ignoreCase = true) ||
            kind.contains("Animación", ignoreCase = true)
        val canUseHistory = kind.contains("Imagen", ignoreCase = true) ||
            kind.contains("Foto", ignoreCase = true) ||
            kind.contains("Sticker", ignoreCase = true) ||
            kind.contains("GIF", ignoreCase = true) ||
            kind.contains("Animación", ignoreCase = true)
        val histKey = if (canUseHistory) mediaHistKey(c) else ""
        val art = uri?.let { mediaArtCache.get(it) }
            ?: histKey.takeIf { it.isNotEmpty() }?.let { mediaArtCache.get(it) }
        val bmp = c.media ?: uri?.let { mediaCache.get(it) }
            ?: histKey.takeIf { it.isNotEmpty() }?.let { mediaCache.get(it) }
        // Aunque ya haya un bitmap de respaldo, se intenta la URI: puede ser el GIF/WebP animado real
        // y el bitmap solo su primer fotograma. Se carga una sola vez por clave.
        if (uri != null && art == null && !mediaFailed.contains(uri)) loadMedia(uri)
        val uriPending = uri != null && art == null && !mediaFailed.contains(uri)
        if (art == null && histKey.isNotEmpty() && !uriPending) loadHistoryMedia(c, histKey)
        val histPending = histKey.isNotEmpty() && art == null && bmp == null &&
            !mediaFailed.contains(histKey)
        val has = art != null || bmp != null || uriPending || histPending
        val tag = uri ?: histKey.ifEmpty { c.key }

        previewImg?.let { p ->
            // La misma miniatura se ve al abrir la tarjeta y en el detalle. La foto grande queda en el
            // Historial, donde sí hay espacio para verla completa.
            val show = expanded && has
            p.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                p.scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                val pad = if (fit) px(4) else 0
                p.setPadding(pad, pad, pad, pad)
                p.background = if (fit || (art == null && bmp == null)) mediaTile(12f) else null
                when {
                    art != null -> paintArt(p, art, tag)
                    bmp != null -> paintMedia(p, bmp, tag)
                    else -> { p.setImageDrawable(null); uri?.let { loadMedia(it) } }
                }
            } else stopAnimated(p)
        }
    }

    /** Clave estable del respaldo de imagen del Historial para una tarjeta. */
    private fun mediaHistKey(c: Card): String {
        val hint = (c.detail ?: c.body ?: c.title)?.toString() ?: ""
        return "hist|${c.key}|${hint.length}:${hint.hashCode()}|${c.userId}"
    }

    /**
     * Recupera en segundo plano la imagen/sticker guardado en el Historial cuando el aviso en vivo no
     * trajo el adjunto. Se decodifica una sola vez y se refresca la tarjeta que lo esté mostrando.
     */
    private fun loadHistoryMedia(c: Card, key: String) {
        // Un bitmap en caché no impide buscar el dibujo animado: puede ser solo el primer fotograma.
        if (key.isEmpty() || mediaArtCache.get(key) != null || mediaFailed.contains(key)) return
        if (!mediaTried.add(key)) return
        histExec.execute {
            var foundArt: Drawable? = null
            var found: Bitmap? = null
            var foundKind = c.mediaKind
            try {
                val hint = (c.detail ?: c.body ?: c.title)?.toString() ?: ""
                val h = MsgBase.findHist(this, c.pkg, c.title?.toString() ?: "", hint, c.userId)
                if (h != null) {
                    if (h.kind.isNotEmpty()) foundKind = h.kind
                    val f = MsgBase.mediaFile(this, h)
                    if (f != null) {
                        foundArt = MediaArt.fromFile(this, f, 512)
                        if (foundArt == null) found = loadFileMedia(f.absolutePath)
                    }
                }
            } catch (t: Throwable) {}
            val art = foundArt
            val bmp = found
            if (art != null) mediaArtCache.put(key, art)
            if (bmp != null) mediaCache.put(key, bmp)
            if (art == null && bmp == null) mediaFailed.add(key)
            main.post {
                val i = cards.indexOfFirst { mediaHistKey(it) == key }
                if ((art != null || bmp != null) && i >= 0) {
                    val old = cards[i]
                    // El Historial sabe si el archivo guardado es un GIF/sticker animado; si el aviso
                    // solo decía "Imagen", se adopta ese tipo para mostrarlo entero y sin recortes.
                    val kindOut = if (old.mediaKind.isEmpty() ||
                        foundKind.contains("GIF", true) || foundKind.contains("Sticker", true) ||
                        foundKind.contains("Animación", true)) foundKind
                    else old.mediaKind
                    val updated = old.copy(media = bmp ?: old.media, mediaKind = kindOut)
                    cards[i] = updated
                    if (i == cardIndex) syncMedia(updated)
                } else {
                    val cur = cards.getOrNull(cardIndex)
                    if (cur != null && mediaHistKey(cur) == key) syncMedia(cur)
                }
            }
        }
    }

    /** Descarga una imagen por URI una sola vez y refresca la tarjeta que la esté mostrando. */
    private fun loadMedia(uri: String) {
        if (mediaArtCache.get(uri) != null || mediaFailed.contains(uri)) return
        if (!mediaTried.add(uri)) return
        Thread {
            val art = MediaArt.fromUri(this, uri, 512)
            val bm = if (art == null) loadUriMedia(uri)
            else (art as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (art != null) mediaArtCache.put(uri, art)
            if (bm != null) mediaCache.put(uri, bm)
            if (art == null && bm == null) mediaFailed.add(uri)
            main.post {
                val cur = cards.getOrNull(cardIndex)
                if (cur != null && cur.mediaUri == uri) syncMedia(cur)
            }
        }.start()
    }

    private fun enterDetail() {
        val c = cards.getOrNull(cardIndex) ?: return
        if (detail || !expanded) return
        detail = true
        main.removeCallbacks(hideTask)
        // El detalle se mide siempre con el ancho final de la tarjeta. Si se entra mientras la isla aún se
        // está abriendo, medir con el ancho de la píldora hacía que el texto se partiera en muchísimas líneas
        // y la tarjeta creciera como un cuadrado enorme.
        lp?.let { p ->
            val w = expandedWidth()
            val h = px(IslandGeometry.EXPANDED_HEIGHT_DP)
            if (p.width != w || p.height != h) {
                p.width = w
                p.height = h
                clampParams(p)
                upd(island, p)
            }
        }
        updateDetailText(c)
        syncMedia(c)
        rebuildActions(c)
        applyCardMode(c)
        actionHost()?.alpha = 0f
        badgeView?.visibility = View.GONE
        // Si se entra en el detalle mientras la isla aún se está abriendo, se cancela esa animación:
        // el contenido debe quedar visible al 100 %, no a medio fundir.
        setContentAlpha(1f)
        iconView?.scaleX = 1f; iconView?.scaleY = 1f
        textCol?.translationX = 0f
        animateDetail(true)
    }

    /**
     * Prepara el texto del detalle con el alto correcto para ESTA tarjeta. Se llama también al cambiar
     * de aviso dentro del detalle: si antes había un mensaje corto y luego llega uno largo, la caja debe
     * pasar a ser desplazable en vez de crecer sin límite.
     */
    private fun updateDetailText(c: Card) {
        val hint = (c.detail ?: c.body ?: c.title)?.toString() ?: ""
        // El Historial puede tener la versión completa aunque el aviso haya llegado recortado.
        // Se consulta la base interna solo cuando el detalle ya tiene algo de texto.
        val resolved = if (hint.isNotEmpty())
            MsgBase.fullTextFor(this, c.pkg, c.title?.toString() ?: "", hint, c.userId)
        else hint
        val fullText: CharSequence = if (resolved.length >= hint.length) resolved else hint
        bodyView?.let { b ->
            b.maxLines = Int.MAX_VALUE
            b.ellipsize = null
            b.movementMethod = android.text.method.ScrollingMovementMethod()
            b.isVerticalScrollBarEnabled = true
            b.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            b.scrollTo(0, 0)
            b.text = fullText
            // La caja es fija: el detalle no pasa de 74 dp. Los mensajes largos se desplazan dentro.
            (b.layoutParams as? LinearLayout.LayoutParams)?.let { p ->
                p.height = detailBodyHeight()
                b.layoutParams = p
            } ?: run {
                b.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, detailBodyHeight())
            }
        }
        // En el detalle manda el mensaje: el nombre de la app ya está en su icono. Sin esta fila el
        // título y el texto caben en los 74 dp y la tarjeta no crece.
        appView?.visibility = View.GONE
    }

    private fun exitDetail() {
        if (!detail) return
        animateDetail(false)
        main.postDelayed(hideTask, Prefs.durationMs(this))
    }

    /** Abre o cierra el detalle sin cambiar el tamaño: la tarjeta sigue siendo 350 × 74 dp. */
    private fun animateDetail(open: Boolean) {
        val row = island ?: return
        val params = lp ?: return
        val w = expandedWidth()
        val compact = px(IslandGeometry.EXPANDED_HEIGHT_DP)
        if (params.width != w || params.height != compact) {
            params.width = w
            params.height = compact
            clampParams(params)
            if (attached) upd(row, params)
        }
        row.minimumHeight = compact
        // El detalle no gana altura: solo aparece el texto desplazable y los botones. La ventana se
        // mantiene en 74 dp aunque el mensaje sea larguísimo o incluya una imagen.
        row.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(compact, View.MeasureSpec.EXACTLY))
        val host = actionHost()
        val from = if ((host?.alpha ?: 0f) > 0f) 1f else 0f
        anim?.cancel()
        val a = ValueAnimator.ofFloat(from, if (open) 1f else 0f)
        a.duration = if (open) Motion.expandMs(this) else Motion.collapseMs(this)
        a.interpolator = if (open) Motion.spring() else Motion.smooth()
        a.addUpdateListener { va ->
            host?.alpha = va.animatedValue as Float
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
        is android.graphics.drawable.Icon -> try { v.uri?.toString() } catch (t: Throwable) { null }
        is String -> {
            val s = v.trim()
            when {
                s.startsWith("content://") || s.startsWith("file://") ||
                    s.startsWith("http://") || s.startsWith("https://") -> s
                // Algunas ROM/apps entregan la ruta absoluta sin esquema.
                s.startsWith("/") && s.length > 3 -> "file://$s"
                else -> null
            }
        }
        else -> null
    }

    /**
     * URI de un mensaje de MessagingStyle: unas apps la guardan como texto y otras como Uri (Parcelable).
     * Además se revisan claves no estándar (media_uri, image_uri, thumbnail…) sin tocar las del remitente,
     * que normalmente apuntan a su avatar.
     */
    @Suppress("DEPRECATION")
    private fun bundleUri(m: android.os.Bundle): String? {
        uriValue(try { m.get("uri") } catch (t: Throwable) { null })?.let { return it }
        for (k in m.keySet()) {
            val kl = k.lowercase()
            if (kl.contains("sender") || kl.contains("person") || kl.contains("avatar") ||
                kl.contains("profile") || kl.contains("icon")) continue
            if (!(kl.contains("uri") || kl.contains("image") || kl.contains("picture") ||
                    kl.contains("photo") || kl.contains("sticker") || kl.contains("media") ||
                    kl.contains("thumbnail"))) continue
            uriValue(try { m.get(k) } catch (t: Throwable) { null })?.let { return it }
        }
        return null
    }

    /**
     * Mensajes de MessagingStyle. Android 11+ añade EXTRA_HISTORIC_MESSAGES con la conversación anterior;
     * algunas apps (WhatsApp y sus clones) publican ahí la foto que ya no está en EXTRA_MESSAGES. Se leen
     * ambos sin tocar el remitente, que es donde suele venir el avatar.
     */
    @Suppress("DEPRECATION")
    /**
     * Reúne todas las versiones del texto que trae el aviso y devuelve la más larga. Las apps suelen
     * recortar EXTRA_TEXT pero dejar el mensaje entero en EXTRA_BIG_TEXT o en MessagingStyle; elegir la
     * más larga evita perder justo los «testamentos».
     */
    private fun fullTextOf(n: Notification, fallback: CharSequence?): CharSequence? {
        val candidates = ArrayList<CharSequence>(8)
        fun add(v: CharSequence?) { if (v != null && v.isNotBlank()) candidates.add(v) }
        add(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(n.extras.getCharSequence(Notification.EXTRA_TEXT))
        val lines = messageBundles(n).takeLast(120).mapNotNull { m ->
            val t = m.getCharSequence("text") ?: return@mapNotNull null
            val who = m.getCharSequence("sender")
            if (who != null) "$who: $t" else t.toString()
        }
        if (lines.isNotEmpty()) add(lines.joinToString("\n"))
        add(n.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        add(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(fallback)
        return candidates.maxByOrNull { it.length } ?: fallback
    }

    private fun messageBundles(n: Notification): List<android.os.Bundle> {
        val out = ArrayList<android.os.Bundle>()
        fun add(key: String) {
            try {
                n.extras.getParcelableArray(key)?.forEach { b ->
                    (b as? android.os.Bundle)?.let { out.add(it) }
                }
            } catch (t: Throwable) {}
        }
        // Primero la conversación anterior y después los mensajes actuales: así el último elemento
        // de la lista es siempre el mensaje más reciente (y las fotos se buscan de atrás hacia delante).
        add(Notification.EXTRA_HISTORIC_MESSAGES)
        add(Notification.EXTRA_MESSAGES)
        return out
    }

    /**
     * Última imagen de una notificación de mensajes y su MIME. Se recorre hacia atrás TODA la lista: si el último
     * mensaje es texto pero el anterior traía foto/sticker, antes se perdía la imagen. El segundo valor es el MIME
     * de la imagen encontrada o, si no hay ninguna, el del último mensaje (para reconocer audio/vídeo).
     */
    @Suppress("DEPRECATION")
    private fun latestImage(n: Notification): Pair<String?, String> {
        val msgs = messageBundles(n)
        var lastMime = ""
        for (i in msgs.indices.reversed()) {
            val m = msgs[i]
            val mime = (m.getCharSequence("type") ?: m.getString("type"))?.toString() ?: ""
            if (lastMime.isEmpty()) lastMime = mime
            val uri = bundleUri(m)
            // Solo se detiene si además hay URI: un mensaje «image/*» sin URI no debe impedir que se
            // encuentre la imagen real de un mensaje anterior.
            if (mime.startsWith("image/") && uri != null) return Pair(uri, mime)
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
        add(ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        add(ex.getCharSequence(Notification.EXTRA_INFO_TEXT))
        try {
            messageBundles(n).forEach { b ->
                add(b.getCharSequence("text")); add(b.getCharSequence("sender"))
            }
        } catch (t: Throwable) {}
        return sb.toString().lowercase()
    }

    /** Tipo de adjunto que se deduce del texto («Envió una foto», «Envió un sticker»…). */
    private fun mediaHintKind(n: Notification): String {
        val raw = try { mediaText(n) } catch (t: Throwable) { return "" }
        // «Foto de perfil» no es un adjunto: se quita para no marcar como media un simple cambio de avatar.
        val s = raw.replace("foto de perfil", " ").replace("profile photo", " ").replace("profile picture", " ")
        return when {
            s.contains("sticker") || s.contains("pegatina") -> "Sticker"
            s.contains("gif") -> "GIF"
            s.contains("video") || s.contains("vídeo") || s.contains("🎥") -> "Video"
            s.contains("audio") || s.contains("nota de voz") || s.contains("voice message") -> "Audio"
            s.contains("documento") || s.contains("document") || s.contains("archivo adjunto") -> "Documento"
            s.contains("foto") || s.contains("photo") || s.contains("imagen") || s.contains("image") ||
                s.contains("fotografía") || s.contains("fotografia") || s.contains("multimedia") ||
                s.contains("📷") || s.contains("🖼") || s.contains("cámara") || s.contains("camara") -> "Foto"
            else -> ""
        }
    }

    private fun looksLikeMedia(n: Notification): Boolean = mediaHintKind(n).isNotEmpty()

    /** Busca un Bitmap en claves no estándar de los mensajes (media, thumbnail, picture…). */
    @Suppress("DEPRECATION")
    private fun mediaBitmapFromMessages(n: Notification): Bitmap? {
        val msgs = messageBundles(n)
        for (i in msgs.indices.reversed()) {
            val b = msgs[i]
            for (k in b.keySet()) {
                val kl = k.lowercase()
                if (kl.contains("sender") || kl.contains("person") || kl.contains("avatar") ||
                    kl.contains("profile") || kl == "icon") continue
                if (!(kl.contains("image") || kl.contains("picture") || kl.contains("photo") ||
                        kl.contains("sticker") || kl.contains("media") || kl.contains("thumbnail"))) continue
                bitmapExtra(b, k)?.let { return it }
            }
            // Algunas apps meten la imagen dentro de un Bundle «extras» del mensaje.
            try {
                b.getBundle("extras")?.let { nested -> mediaBitmapFromExtras(nested)?.let { return it } }
            } catch (t: Throwable) {}
        }
        return null
    }

    /** Busca un Bitmap en extras propios de la app/ROM (nunca en el icono grande, que suele ser el avatar). */
    @Suppress("DEPRECATION")
    private fun mediaBitmapFromExtras(ex: android.os.Bundle): Bitmap? {
        for (k in ex.keySet()) {
            val kl = k.lowercase()
            if (kl.contains("largeicon") || kl.contains("smallicon") || kl.contains("pictureicon") ||
                kl.contains("avatar") || kl.contains("profile")) continue
            if (!(kl.contains("picture") || kl.contains("image") || kl.contains("photo") ||
                    kl.contains("sticker") || kl.contains("media") || kl.contains("thumbnail"))) continue
            bitmapExtra(ex, k)?.let { return it }
        }
        return null
    }

    /** Algunas apps mandan la imagen solo en las páginas de Wear OS (android.wearable.EXTENSIONS). */
    @Suppress("DEPRECATION")
    private fun mediaBitmapFromWearable(n: Notification): Bitmap? {
        val ext = try { n.extras.getBundle("android.wearable.EXTENSIONS") } catch (t: Throwable) { null } ?: return null
        val pages = try { ext.getParcelableArrayList<Notification>("pages") } catch (t: Throwable) { null } ?: return null
        for (page in pages) {
            bitmapExtra(page.extras, Notification.EXTRA_PICTURE)?.let { return it }
            bitmapExtra(page.extras, Notification.EXTRA_PICTURE_ICON)?.let { return it }
            mediaBitmapFromExtras(page.extras)?.let { return it }
        }
        return null
    }

    /** URI de imagen en extras propios de la app/ROM (miui.extra, image_uri, thumbnail…). */
    @Suppress("DEPRECATION")
    private fun mediaUriFromExtras(ex: android.os.Bundle): String? {
        for (k in ex.keySet()) {
            val kl = k.lowercase()
            if (kl.contains("largeicon") || kl.contains("smallicon") || kl.contains("pictureicon") ||
                kl.contains("avatar") || kl.contains("profile")) continue
            if (!(kl.contains("picture") || kl.contains("image") || kl.contains("photo") ||
                    kl.contains("sticker") || kl.contains("media") || kl.contains("thumbnail"))) continue
            uriValue(try { ex.get(k) } catch (t: Throwable) { null })?.let { return it }
        }
        return null
    }

    /**
     * Avatar del remitente que trae el propio aviso (MessagingStyle → sender_person, o EXTRA_PEOPLE).
     * En WhatsApp/HyperOS la imagen real (foto o sticker) viene en largeIcon y el avatar en la Person; así
     * se pueden distinguir sin adivinar por la forma de la imagen. Si no hay Person, se devuelve null.
     */
    @Suppress("DEPRECATION")
    private fun senderAvatar(n: Notification): Bitmap? {
        fun fromIcon(icon: android.graphics.drawable.Icon?): Bitmap? = try {
            icon?.loadDrawable(this)?.let { toBitmap(it) }
        } catch (t: Throwable) { null }
        try {
            val msgs = messageBundles(n)
            if (msgs.isNotEmpty()) for (i in msgs.indices.reversed()) {
                val b = msgs[i]
                val p = b.getParcelable<android.app.Person>("sender_person") ?: continue
                fromIcon(p.icon)?.let { return it }
            }
            val people = n.extras.getParcelableArray(Notification.EXTRA_PEOPLE)
            if (people != null) for (v in people) {
                val p = v as? android.app.Person ?: continue
                fromIcon(p.icon)?.let { return it }
            }
        } catch (t: Throwable) {}
        return null
    }

    /** ¿Dos bitmaps son la misma imagen? Se muestrea en una rejilla para tolerar reescalados y compresión. */
    private fun sameImage(a: Bitmap, b: Bitmap): Boolean = try {
        if (a.width < 8 || b.width < 8) false
        else {
            var bad = 0
            val n = 7
            for (iy in 0 until n) {
                val ya = iy * (a.height - 1) / (n - 1)
                val yb = iy * (b.height - 1) / (n - 1)
                for (ix in 0 until n) {
                    val xa = ix * (a.width - 1) / (n - 1)
                    val xb = ix * (b.width - 1) / (n - 1)
                    val ca = a.getPixel(xa, ya)
                    val cb = b.getPixel(xb, yb)
                    val d = abs(Color.red(ca) - Color.red(cb)) +
                        abs(Color.green(ca) - Color.green(cb)) +
                        abs(Color.blue(ca) - Color.blue(cb)) +
                        abs(Color.alpha(ca) - Color.alpha(cb))
                    if (d > 48) bad++
                }
            }
            bad <= 4
        }
    } catch (t: Throwable) { false }

    /** Apps que suelen publicar la imagen/vídeo como icono grande ancho (no como avatar redondo). */
    private fun isVisualApp(pkg: String): Boolean = pkg in setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
        "com.pinterest", "com.instagram.android", "com.google.android.youtube",
        "com.twitter.android", "com.snapchat.android", "com.facebook.katana",
        "com.linkedin.android", "com.reddit.frontpage")

    /** Imagen o sticker de la notificación (si hay) y su tipo. NUNCA hace E/S: eso va en loadUriMedia(). */
    @Suppress("DEPRECATION")
    private fun extractMedia(n: Notification, pkg: String = ""): Pair<Bitmap?, String> {
        val ex = n.extras
        val (_, mime) = try { latestImage(n) } catch (t: Throwable) { Pair(null, "") }
        val gifMime = mime.contains("gif")
        val stickerMime = !gifMime && (mime.contains("webp") || mime.contains("png"))
        val hint = try { looksLikeMedia(n) } catch (t: Throwable) { false }
        val hintKind = try { mediaHintKind(n) } catch (t: Throwable) { "" }

        // 1) La imagen grande de verdad (BigPictureStyle / MessagingStyle con foto).
        var bmp = bitmapExtra(ex, Notification.EXTRA_PICTURE)
        if (bmp == null) bmp = bitmapExtra(ex, Notification.EXTRA_PICTURE_ICON)
        // 2) Extras con nombre de imagen/media que algunas apps o la propia ROM añaden.
        if (bmp == null) bmp = mediaBitmapFromMessages(n)
        if (bmp == null) bmp = mediaBitmapFromExtras(ex)
        if (bmp == null) bmp = mediaBitmapFromWearable(n)
        // 3) Respaldo del icono grande. WhatsApp/HyperOS ponen ahí la foto o el sticker real y el avatar
        //    del contacto en sender_person. Si el largeIcon coincide con el avatar, se descarta; si es
        //    distinto (o no hay avatar), se usa. TikTok/Pinterest suelen usar un icono grande ancho.
        var fromLarge = false
        if (bmp == null) {
            val large = bitmapExtra(ex, Notification.EXTRA_LARGE_ICON)
                ?: bitmapExtra(ex, Notification.EXTRA_LARGE_ICON_BIG)
            val visual = isVisualApp(pkg) || n.category in setOf(
                Notification.CATEGORY_SOCIAL, Notification.CATEGORY_RECOMMENDATION,
                Notification.CATEGORY_PROMO, "news")
            val wide = large != null && large.width >= large.height * 1.25f
            if (large != null && (stickerMime || hint || (visual && wide))) {
                val avatar = try { senderAvatar(n) } catch (t: Throwable) { null }
                if (avatar == null || !sameImage(large, avatar)) {
                    bmp = large
                    fromLarge = true
                }
            }
        }

        var kind = when {
            bmp == null -> ""
            gifMime -> "GIF"
            stickerMime -> "Sticker"
            else -> "Imagen"
        }
        // El texto ya decía «Foto»/«Sticker»: conservar ese tipo para que el historial y el recorte sean correctos.
        if (fromLarge && hintKind.isNotEmpty()) kind = hintKind
        // Tipo del adjunto por su MIME (SIN abrir la URI: abrirla bloquea el hilo y congelaba la isla).
        if (kind.isEmpty()) kind = when {
            mime.startsWith("image/") -> when {
                gifMime -> "GIF"
                stickerMime -> "Sticker"
                else -> "Imagen"
            }
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

    /** Tipo de imagen que sugiere una URI, sin abrir el contenido. */
    private fun kindOfUri(uri: String): String = try {
        val u = android.net.Uri.parse(uri)
        val mime = contentResolver.getType(u)?.lowercase().orEmpty()
        val path = (u.path ?: "").lowercase()
        when {
            mime.contains("gif") || path.endsWith(".gif") -> "GIF"
            mime.contains("webp") && path.contains("sticker") -> "Sticker"
            mime.startsWith("image/") -> "Imagen"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".webp") -> "Imagen"
            else -> ""
        }
    } catch (t: Throwable) { "" }

    private data class SavedMedia(val name: String, val kind: String)

    /** Ajusta el tipo según los bytes reales: un GIF/WebP animado no debe guardarse como foto estática. */
    private fun refineMediaKind(kind: String, mime: String?, animated: Boolean): String {
        val k = kind.trim()
        val m = mime?.lowercase().orEmpty()
        return when {
            k.contains("Sticker", ignoreCase = true) -> "Sticker"
            m.contains("gif") -> "GIF"
            animated && m.contains("webp") && (k.isEmpty() || k == "Imagen" || k == "Foto") -> "Sticker"
            k.isNotEmpty() -> k
            m.startsWith("image/") -> "Imagen"
            else -> k
        }
    }

    /**
     * Guarda el adjunto conservando los bytes originales cuando son pequeños o animados. Antes se
     * re-codificaba todo a JPEG/PNG y los GIF/WebP animados quedaban congelados y los stickers perdían
     * transparencia. Si el archivo es enorme, se reduce y se comprime como antes.
     */
    private fun saveUriMedia(uri: String, kind: String): SavedMedia? {
        if (uri.isBlank()) return null
        return try {
            val bytes = readMediaBytes(uri, 16 * 1024 * 1024)
            val mime = (bytes?.let { sniffImageMime(it) }
                ?: try { contentResolver.getType(android.net.Uri.parse(uri))?.lowercase() } catch (t: Throwable) { null })
                ?: return null
            if (!mime.startsWith("image/")) return null
            val animated = bytes != null && isAnimatedImage(bytes, mime)
            val finalKind = refineMediaKind(kind, mime, animated)
            if (bytes != null && (animated || bytes.size <= 3 * 1024 * 1024)) {
                val ext = when {
                    mime.contains("gif") -> "gif"
                    mime.contains("webp") -> "webp"
                    mime.contains("png") -> "png"
                    else -> "jpg"
                }
                val name = "${System.currentTimeMillis()}_${(Math.random() * 99999).toInt()}.$ext"
                FileOutputStream(File(Prefs.histDir(this), name)).use { it.write(bytes) }
                SavedMedia(name, finalKind)
            } else {
                val bmp = bytes?.let { decodeSampledBytes(it, 1600) } ?: loadUriMedia(uri)
                bmp?.let { SavedMedia(saveBitmap(it, finalKind), finalKind) }
            }
        } catch (t: Throwable) { null }
    }

    private fun readMediaBytes(uri: String, max: Int): ByteArray? = try {
        contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (t: Throwable) { null }

    private fun sniffImageMime(b: ByteArray): String? {
        if (b.size < 12) return null
        fun at(i: Int): Int = b[i].toInt() and 0xFF
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == '8'.code -> "image/gif"
            at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "image/webp"
            else -> null
        }
    }

    private fun isAnimatedImage(b: ByteArray, mime: String): Boolean {
        if (mime.contains("gif")) return b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()
        if (mime.contains("webp") && b.size > 20) {
            val vp8x = b[12] == 'V'.code.toByte() && b[13] == 'P'.code.toByte() &&
                b[14] == '8'.code.toByte() && b[15] == 'X'.code.toByte()
            // En VP8X, el bit 1 de las banderas indica animación.
            if (vp8x && (b[20].toInt() and 0x02) != 0) return true
        }
        return false
    }

    private fun decodeSampledBytes(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
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

    /**
     * Decodifica una imagen guardada en el Historial (PNG, JPEG, GIF o WebP) reduciéndola antes de
     * retenerla en memoria. Se usa solo como respaldo interno cuando el aviso no trajo el adjunto.
     */
    private fun loadFileMedia(path: String): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 960 && bounds.outHeight / (sample * 2) >= 960) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)?.let { scaleDown(it, 960) }
        }
    } catch (t: Throwable) { null }

    /** ¿La app puede leer la galería? En Android 13+ es READ_MEDIA_IMAGES; antes, READ_EXTERNAL_STORAGE. */
    private fun canReadGallery(): Boolean = try {
        val full = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES"
        else "android.permission.READ_EXTERNAL_STORAGE"
        checkSelfPermission(full) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)
    } catch (t: Throwable) { false }

    /**
     * Último recurso para WhatsApp: busca una imagen recién añadida por la app en la galería del sistema.
     * Solo se llama cuando el aviso dice Foto/Sticker pero no trae ningún bitmap ni URI, nunca para otros
     * usuarios/clones (el MediaStore del proceso no puede leer su carpeta) y solo con permiso concedido.
     */
    private fun latestGalleryImage(pkg: String, around: Long): Bitmap? {
        if (!Prefs.mediaFolder(this) || !canReadGallery()) return null
        val folder = when (pkg) {
            "com.whatsapp" -> "WhatsApp Images"
            "com.whatsapp.w4b" -> "WhatsApp Business Images"
            else -> return null
        }
        if (folderImagesUsed.size > 300) folderImagesUsed.clear()
        val resolver = contentResolver
        val since = ((around - 5 * 60_000L) / 1000L).coerceAtLeast(0L)
        val until = (around + 60_000L) / 1000L
        val time = "((${MediaStore.Images.Media.DATE_ADDED}>=? AND ${MediaStore.Images.Media.DATE_ADDED}<=?)" +
            " OR (${MediaStore.Images.Media.DATE_MODIFIED}>=? AND ${MediaStore.Images.Media.DATE_MODIFIED}<=?))"
        val timeArgs = arrayOf(since.toString(), until.toString(), since.toString(), until.toString())
        val projection = arrayOf(MediaStore.Images.Media._ID)
        fun query(selection: String, args: Array<String>): Bitmap? {
            val cursor = try {
                resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                    selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            } catch (t: Throwable) { null } ?: return null
            try {
                var checked = 0
                while (cursor.moveToNext() && checked < 12) {
                    checked++
                    val id = try { cursor.getLong(0) } catch (t: Throwable) { -1L }
                    if (id <= 0 || !folderImagesUsed.add(id)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val bmp = loadUriMedia(uri.toString())
                    if (bmp != null) return bmp
                }
            } catch (t: Throwable) {} finally {
                try { cursor.close() } catch (t: Throwable) {}
            }
            return null
        }
        // Primero por propietario (lo más fiable); si la ROM no lo rellena, por la ruta de la carpeta.
        return query("${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=? AND $time",
                arrayOf(pkg) + timeArgs)
            ?: query("${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND $time",
                arrayOf("%$folder%") + timeArgs)
    }

    /**
     * Intenta completar una foto que la app no compartió. Primero lee una URI expuesta por accesibilidad
     * (sin capturas de pantalla); si no, para WhatsApp usa la última imagen reciente de su carpeta.
     * Se ejecuta en el hilo del historial, nunca en el principal.
     */
    private fun scheduleMediaFallback(
        sbn: StatusBarNotification, hp: String, title: String, text: String,
        kind: String, uid: Int, around: Long
    ) {
        if (uid != myUser) return
        if (mediaFallbackTried.size > 500) mediaFallbackTried.clear()
        val marker = "${sbn.key}|${text.hashCode()}"
        if (!mediaFallbackTried.add(marker)) return
        val pkg = sbn.packageName
        val whatsapp = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"
        if (!whatsapp && RyxAccessibility.instance == null) return
        histExec.execute {
            try {
                var bmp: Bitmap? = null
                var foundUri: String? = null
                // 1) URI que la app expone en su árbol de accesibilidad (solo si esa app está al frente).
                try {
                    RyxAccessibility.instance?.findMediaUri(pkg)?.let {
                        foundUri = it
                        bmp = loadUriMedia(it)
                    }
                } catch (t: Throwable) {}
                // 2) Último recurso: última imagen reciente de la carpeta de WhatsApp.
                if (bmp == null && whatsapp) bmp = latestGalleryImage(pkg, around)
                val found = bmp ?: return@execute
                val saved = foundUri?.let { saveUriMedia(it, kind) }
                    ?: SavedMedia(saveBitmap(found, kind), refineMediaKind(kind, null, false))
                // La ventana es amplia para que actualice la entrada ya guardada en vez de crear otra.
                Prefs.addHist(this, hp, title, text, saved.name, saved.kind, 120_000L)
                main.post { applyMediaToCard(sbn.key, text, found, saved.kind) }
            } catch (t: Throwable) {}
        }
    }

    /** Pinta la imagen encontrada en la tarjeta viva si sigue siendo la misma notificación. */
    private fun applyMediaToCard(key: String, fullText: String, bmp: Bitmap, kind: String) {
        val i = cards.indexOfFirst {
            it.key == key && (it.detail?.toString() == fullText || it.body?.toString() == fullText)
        }
        if (i < 0) return
        val old = cards[i]
        val updated = old.copy(media = bmp, mediaKind = if (old.mediaKind.isNotEmpty()) old.mediaKind else kind)
        cards[i] = updated
        if (i == cardIndex) {
            bindCard(updated)
            if (detail) animateDetail(true)
        }
    }

    /**
     * Algunas apps quitan el aviso tan rápido que el historial no llega a verlo (o el teléfono estaba
     * inactivo). Si el aviso se borra y no estaba ya guardado, se archiva su texto completo igualmente:
     * el historial es un registro, y un mensaje eliminado en la app no debe desaparecer de él.
     */
    private fun captureRemoved(sbn: StatusBarNotification) {
        if (!Prefs.keepHistory(this)) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val uid = if (sbn.userId >= 0) sbn.userId else myUser
        val pkg = sbn.packageName
        var title = n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: appLabel(pkg, sbn.user, uid)
        var text = n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_INFO_TEXT) ?: ""
        var full: CharSequence? = fullTextOf(n, text)
        // Privacidad: un aviso borrado no debe saltarse las palabras ocultas configuradas.
        val words = Prefs.hiddenWords(this)
        if (words.isNotEmpty() && words.any { "${title ?: ""} ${text ?: ""}".lowercase().contains(it) }) {
            title = appLabel(pkg, sbn.user, uid)
            text = "Contenido oculto"
            full = text
        }
        if (full == null) full = text
        val hx = (full ?: text)?.toString() ?: ""
        if (hx.isBlank()) return
        val hp = appKey(pkg, uid)
        val ht = title?.toString() ?: ""
        val fp = "$hp|$ht|$hx"
        val now = System.currentTimeMillis()
        if (historySaved.containsKey(fp)) return
        historySaved[fp] = now
        histExec.execute {
            try {
                val (bmp, kind) = extractMedia(n, pkg)
                val uri = mediaUri(n)
                val saved = uri?.let { saveUriMedia(it, kind) }
                    ?: bmp?.let {
                        val k = refineMediaKind(kind, null, false)
                        SavedMedia(saveBitmap(it, k), k)
                    }
                if (saved != null) Prefs.addHist(this, hp, ht, hx, saved.name, saved.kind, 60_000L)
                else Prefs.addHist(this, hp, ht, hx, "", kind, 60_000L)
            } catch (t: Throwable) {}
        }
    }

    private fun saveBitmap(bmp: Bitmap?, kind: String): String {
        if (bmp == null) return ""
        return try {
            // Los stickers y cualquier imagen con transparencia se guardan en PNG: pasarlos a JPEG
            // convertía la transparencia en un fondo negro.
            val png = kind.contains("Sticker", ignoreCase = true) ||
                kind.contains("GIF", ignoreCase = true) ||
                kind.contains("Animación", ignoreCase = true) || bmp.hasAlpha()
            val ext = if (png) "png" else "jpg"
            val name = "${System.currentTimeMillis()}_${(Math.random() * 99999).toInt()}.$ext"
            FileOutputStream(File(Prefs.histDir(this), name)).use {
                if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                else bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
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
            // Sin modo permanente la isla se retira del todo: antes quedaba una píldora en reposo con
            // el contador de avisos pendientes y parecía que la isla se había quedado pegada.
            if (Prefs.permanent(this)) setIdleContent() else removeNow()
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
        // El alto abierto es SIEMPRE 74 dp, con cualquier estilo, texto o imagen.
        val expH = px(IslandGeometry.EXPANDED_HEIGHT_DP)
        if (!toExpanded) params.height = WindowManager.LayoutParams.WRAP_CONTENT
        val fromW = params.width; val toW = if (toExpanded) expandedWidth() else idleWidth()
        val fromH = (row?.minimumHeight ?: idleH); val toH = if (toExpanded) expH else idleH
        val cornerPx = expandedCornerPx()
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
            override fun onAnimationEnd(animation: Animator) {
                if (canceled) return
                if (toExpanded) {
                    // Fija la ventana al alto exacto. El contenido ya está medido para caber dentro.
                    params.height = expH
                    row?.minimumHeight = expH
                    clampParams(params)
                    if (attached) upd(row, params)
                } else {
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    if (attached) upd(row, params)
                }
                end?.invoke()
            }
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
