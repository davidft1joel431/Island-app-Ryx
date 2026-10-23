package com.dav.isla

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.ImageView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Servicio de accesibilidad de Ryx. Solo hace dos cosas, las dos cuando tú abres una app desde la isla:
 *  - FORMATO LIBRE: lanza la app en ventana libre con las opciones oficiales de Android (sin salir de tu app).
 *  - BURBUJA (Android 17): toca la burbuja si ya existe y, si no, pulsa «Burbuja» en el menú del launcher (solo la primera vez).
 * No lee ni guarda nada por su cuenta.
 */
class RyxAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: RyxAccessibility? = null

        fun isEnabled(c: Context): Boolean {
            if (instance != null) return true
            val v = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val cn = ComponentName(c, RyxAccessibility::class.java)
            return v.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
        }

        /** Opción «Burbuja» del menú del launcher (Android 17). */
        private val BUBBLE = Regex("burbuja|bubble", RegexOption.IGNORE_CASE)

        /**
         * Variantes de apertura directa en formato libre. La última usa Shizuku/root (`am start --windowingMode 5`),
         * que en ROMs que ignoran las ActivityOptions es la que sí funciona; se redimensiona en el acto porque
         * `am start` no acepta límites.
         */
        val VARIANTS = listOf("estándar (clave windowingMode)", "con pantalla 0", "sin ventana nueva", "método interno",
            "con Shizuku/root (am start)")
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { hideCurtain(); instance = null; return super.onUnbind(intent) }

    /** Handler del hilo principal para lanzar actividades sin romper desde hilos de fondo. */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onDestroy() {
        instance = null
        // Sin esto, el hilo de lectura de chats seguía vivo tras desactivar el servicio.
        try { chatWork.shutdownNow() } catch (e: Throwable) {}
        hideCurtain()
        super.onDestroy()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            // Una app abrió una ventana: sus avisos pendientes en la isla ya se leyeron
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> event?.packageName?.toString()?.let {
                NotifListener.instance?.onAppOpened(it)
                onWindowChanged(it)
            }
            // El contenido de la pantalla cambió: en una app de chat, se lee el texto visible y se guarda
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val p = event?.packageName?.toString() ?: return
                if (chatWanted(p)) scheduleRead(p)
            }
        }
    }
    override fun onInterrupt() {}

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // LECTURA DE CHATS POR ACCESIBILIDAD
    // Cuando el usuario abre una app de chat (WhatsApp, Telegram, Instagram, TikTok…), se lee el texto VISIBLE
    // de la pantalla y se guarda en el Historial. Así se registran también los chats ARCHIVADOS o RESTRINGIDOS
    // que el usuario abre (esos no publican notificación). No inventa nada: solo guarda lo que ya está en pantalla.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private val chatWork = java.util.concurrent.Executors.newSingleThreadExecutor()
    // scheduleRead corre en el hilo principal y el trabajo termina en chatWork: el estado debe ser atómico.
    private val readPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastReadHash = 0
    private var lastReadAt = 0L
    private var lastReadTry = 0L
    // La captura de pantalla es cara: se limita a una cada pocos segundos y solo si hay un adjunto visible.
    private var lastShotAt = 0L

    /** Un adjunto visible: su tipo, dónde está en pantalla y la pista que lo delató. */
    private data class SeenMedia(val kind: String, val bounds: Rect, val cue: String)

    private fun dpx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /** ¿Esta app es de chat Y el usuario activó la lectura por pantalla (y, si eligió apps, es una de ellas)? */
    private fun chatWanted(pkg: String): Boolean {
        if (pkg == packageName) return false
        if (!Prefs.chatCapture(this) || !Prefs.keepHistory(this)) return false
        if (!Sys.isChatApp(pkg)) return false
        val pick = Prefs.chatPkgs(this)
        return pick.isEmpty() || pkg in pick
    }

    private fun onWindowChanged(pkg: String) {
        if (chatWanted(pkg)) scheduleRead(pkg)
    }

    /**
     * Se espera un pelín para que la pantalla termine de pintar y no leer a medias. La lectura corre en un hilo
     * APARTE: recorrer el árbol de vistas bloquea, y hacerlo en el hilo principal congelaba la isla. Además se
     * limita a una lectura cada 1,5 s como mucho: `typeWindowContentChanged` dispara sin parar en todas las apps.
     */
    private fun scheduleRead(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastReadTry < 1_500L) return
        if (!readPending.compareAndSet(false, true)) return
        lastReadTry = now
        chatWork.execute {
            try { readChat(pkg) } catch (e: Throwable) {} finally { readPending.set(false) }
        }
    }

    /** Lee el texto visible de la conversación y lo guarda en el Historial (si hay algo nuevo). */
    private fun readChat(pkg: String) {
        if (!chatWanted(pkg)) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val parts = ArrayList<String>()
        val media = ArrayList<String>()
        val seen = ArrayList<SeenMedia>()
        // Se lee una sola vez por recorrido: antes se consultaba SharedPreferences en CADA nodo del árbol.
        val wantMedia = Prefs.chatMedia(this)
        collectText(root, parts, media, seen, 0, wantMedia)
        // Une y limpia líneas repetidas seguidas
        val lines = ArrayList<String>()
        for (p in parts) {
            val t = p.trim()
            if (t.isEmpty()) continue
            if (lines.isNotEmpty() && lines.last() == t) continue
            lines.add(t)
        }
        if (lines.isEmpty() && media.isEmpty() && seen.isEmpty()) return
        // Se queda con lo más reciente (hasta 40 líneas): el final de la conversación es lo que interesa.
        val slice = if (lines.size > 40) lines.subList(lines.size - 40, lines.size) else lines
        val text = slice.joinToString("\n")
        // La clave incluye los adjuntos: si aparece un sticker o una foto nueva con el mismo texto,
        // también cuenta como cambio y se guarda.
        val mediaKey = seen.joinToString("|") { "${it.kind}@${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}" }
        val h = (text + "\n#" + mediaKey).hashCode()
        val now = System.currentTimeMillis()
        if (h == lastReadHash && now - lastReadAt < 60_000L) return
        lastReadHash = h; lastReadAt = now

        val app = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) { pkg }
        // El Historial guarda LO QUE SE VIO, no una marca de que Ryx revisó la pantalla. Por eso el
        // título es solo el nombre de la app, sin «Chat en pantalla», sin «(pantalla · app)».
        // En el árbol de accesibilidad los adjuntos no traen píxeles, pero sí su tipo y descripción
        // («Foto», «Sticker», «GIF»…). Con eso se registra qué se vio; además se copia el recorte
        // visible si el usuario activó «Copiar también la imagen visible».
        val kinds = ArrayList<String>()
        for (m in media) if (wantMedia && !kinds.contains(m)) kinds.add(m)
        val kind = kinds.joinToString(" · ")
        val body = buildString {
            if (text.isNotEmpty()) append(text)
            if (kinds.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(kinds.joinToString(" · ") { "📎 $it" })
            }
        }
        // Recortes de los adjuntos visibles. Fuera del hilo principal (ya estamos en chatWork) y
        // limitado a una captura cada pocos segundos para no castigar la batería.
        val shots: List<java.io.File> =
            if (wantMedia && Prefs.chatShots(this) && seen.isNotEmpty()) captureShots(seen) else emptyList()
        // Se reutiliza el mismo ejecutor: antes cada lectura creaba un Thread nuevo, y con árboles grandes
        // podían acumularse decenas. El guardado va detrás de la lectura, en orden y sin desbordar el sistema.
        try {
            chatWork.execute {
                try {
                    Prefs.addHist(this, pkg, app, body, shots.firstOrNull()?.name ?: "", kind, 1_500L,
                        Prefs.SRC_SCREEN, shots.drop(1).map { it.name })
                    Backup.maybeUpload(this)
                    Backup.autoLocal(this)
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
    }

    /**
     * Recoge el texto y los adjuntos de todos los nodos (en orden de aparición en pantalla).
     * Los adjuntos visuales guardan además sus límites exactos para poder recortarlos de la captura.
     */
    private fun collectText(node: AccessibilityNodeInfo?, out: ArrayList<String>,
                            media: ArrayList<String>, seen: ArrayList<SeenMedia>,
                            depth: Int, wantMedia: Boolean) {
        if (node == null || depth > 40) return
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        val cls = node.className?.toString() ?: ""
        // MEDIA: solo se miran los campos "caros" (viewId/hint/tooltip) si el nodo tiene algo de texto, y se
        // descarta al instante si la cadena no contiene ninguna pista de adjunto. Mirarlos en TODOS los nodos
        // (miles) era lo que ponía el sistema lento.
        val cheap = ((d ?: "") + " " + (t ?: "") + " " + cls).lowercase()
        var seenKind: String? = null
        if (wantMedia && cheap.isNotEmpty()) {
            if (cheap.contains("sticker") || cheap.contains("foto") || cheap.contains("photo") ||
                cheap.contains("image") || cheap.contains("imagen") || cheap.contains("video") ||
                cheap.contains("vídeo") || cheap.contains("audio") || cheap.contains("voz") ||
                cheap.contains("voice") || cheap.contains("gif") || cheap.contains("ver una vez") ||
                cheap.contains("view once") || cheap.contains("document") || cheap.contains("pdf") ||
                cheap.contains("ubicaci") || cheap.contains("location") || cheap.contains("figura")) {
                val hint = try { node.hintText?.toString() } catch (e: Throwable) { null }
                val tool = try { node.tooltipText?.toString() } catch (e: Throwable) { null }
                val vid = try { node.viewIdResourceName } catch (e: Throwable) { null }
                val kind = mediaKind(d, hint, tool, vid, cls)
                // Una foto de perfil o un avatar no son un adjunto del mensaje: se ignoran.
                val avatar = cheap.contains("foto de perfil") || cheap.contains("profile photo") ||
                    cheap.contains("avatar")
                if (kind != null && !avatar) {
                    seenKind = kind
                    if (!media.contains(kind)) media.add(kind)
                    // Solo se recortan los tipos que de verdad se ven. «Ver una vez», audio, documentos,
                    // ubicaciones y contactos se registran por tipo, sin guardar píxeles.
                    val visual = kind == "Foto" || kind == "Sticker" || kind == "GIF" || kind == "Vídeo"
                    if (visual && node.isVisibleToUser) {
                        val r = Rect()
                        try { node.getBoundsInScreen(r) } catch (e: Throwable) { r.setEmpty() }
                        val visualNode = node.isClickable || cls.contains("image", true) ||
                            cls.contains("video", true) || cls.contains("photo", true) ||
                            (vid?.contains("image", true) == true) || (vid?.contains("photo", true) == true)
                        if (visualNode && r.width() > 0 && r.height() > 0) {
                            seen.add(SeenMedia(kind, Rect(r), (d ?: t ?: "").take(80)))
                        }
                    }
                }
            }
        }

        val label = when {
            !t.isNullOrBlank() && t.length > 1 -> t
            !d.isNullOrBlank() && d.length > 1 && node.text.isNullOrBlank() &&
                (node.isClickable || node.isFocusable) -> d
            else -> null
        }
        val editable = try { node.isEditable } catch (e: Throwable) { false }
        // La descripción de una imagen («Foto», «Sticker»…) no es un mensaje: no se repite como línea
        // de texto si ya se guardó como adjunto. El tipo se muestra una sola vez con 📎.
        val mediaLabel = seenKind != null && d != null && t == null &&
            d.trim().equals(seenKind, ignoreCase = true)
        // Las palabras de la interfaz se descartan solo en controles (botones, pestañas, campos):
        // un mensaje real que diga «Llamadas» o «Estados» no debe perderse.
        val uiNode = node.isClickable || node.isFocusable || cls.contains("Button", true) ||
            cls.contains("Tab", true) || cls.contains("EditText", true)
        if (label != null && !editable && !mediaLabel && !(uiNode && chromeLine(label))) out.add(label)

        val n = node.childCount
        for (i in 0 until n) {
            val ch = try { node.getChild(i) } catch (e: Throwable) { null }
            collectText(ch, out, media, seen, depth + 1, wantMedia)
        }
    }

    /** Líneas de la interfaz (barras, botones de la app) que no son parte de la conversación. */
    private fun chromeLine(raw: String): Boolean {
        val s = raw.trim().lowercase()
        if (s.length > 60) return false
        val chrome = setOf(
            "buscar", "search", "chats", "canales", "canales de whatsapp", "comunidades",
            "llamadas", "estados", "ajustes", "settings", "nuevo chat", "nuevo mensaje",
            "escribe un mensaje", "type a message", "mensaje", "message", "volver", "back",
            "más opciones", "more options", "cámara", "camera", "adjuntar", "attach",
            "grabar", "record", "enviar", "send", "seleccionar", "select", "cerrar", "close",
            "sticker", "stickers", "emoji", "emojis", "gif", "gifs", "fotos", "photos",
            "documentos", "documents", "contactos", "contacts", "ubicación", "location",
            "audio", "videollamada", "llamada de voz", "videollamadas", "llamadas de voz"
        )
        if (s in chrome) return true
        return s.startsWith("toca para ") && s.length < 80
    }

    /**
     * Recorta de la pantalla cada adjunto visible y guarda un PNG. Si algo falla, devuelve lo que
     * haya podido guardar: la entrada del historial conserva igualmente el TIPO del adjunto.
     */
    private fun captureShots(seen: List<SeenMedia>): List<java.io.File> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val now = System.currentTimeMillis()
        // La captura completa es costosa: una vez cada 2,5 s como máximo.
        if (now - lastShotAt < 2_500L) return emptyList()
        lastShotAt = now
        val shot = grabScreen() ?: return emptyList()
        val out = ArrayList<java.io.File>()
        try {
            val minW = dpx(44)
            val minH = dpx(44)
            val chosen = ArrayList<SeenMedia>()
            val sorted = seen.filter { it.bounds.width() >= minW && it.bounds.height() >= minH }
                .sortedByDescending { it.bounds.width().toLong() * it.bounds.height() }
            for (m in sorted) {
                if (chosen.size >= 4) break
                val r = m.bounds
                // Un nodo padre y su hijo pueden describir el mismo sticker: se descarta el solapado.
                val dup = chosen.any { o ->
                    val x = maxOf(0, minOf(r.right, o.bounds.right) - maxOf(r.left, o.bounds.left))
                    val y = maxOf(0, minOf(r.bottom, o.bounds.bottom) - maxOf(r.top, o.bounds.top))
                    val inter = x.toLong() * y
                    inter >= 0.55f * minOf(r.width().toLong() * r.height(), o.bounds.width().toLong() * o.bounds.height())
                }
                if (!dup) chosen.add(m)
            }
            chosen.forEachIndexed { i, m ->
                saveCrop(shot, m, now, i)?.let { out.add(it) }
            }
        } catch (t: Throwable) {
            // Sin píxeles se guarda solo el tipo.
        } finally {
            try { shot.recycle() } catch (t: Throwable) {}
        }
        return out
    }

    /** Guarda un recorte de la captura como PNG, reducido si la pantalla es muy grande. */
    private fun saveCrop(shot: Bitmap, m: SeenMedia, stamp: Long, index: Int): java.io.File? {
        val l = m.bounds.left.coerceIn(0, shot.width - 1)
        val t = m.bounds.top.coerceIn(0, shot.height - 1)
        val r = m.bounds.right.coerceIn(l + 1, shot.width)
        val b = m.bounds.bottom.coerceIn(t + 1, shot.height)
        if (r - l < dpx(24) || b - t < dpx(24)) return null
        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(shot, l, t, r - l, b - t)
            val max = 1600
            if (maxOf(crop.width, crop.height) > max) {
                val f = max.toFloat() / maxOf(crop.width, crop.height)
                val scaled = Bitmap.createScaledBitmap(crop, (crop.width * f).toInt().coerceAtLeast(1),
                    (crop.height * f).toInt().coerceAtLeast(1), true)
                if (scaled !== crop) { crop.recycle(); crop = scaled }
            }
            val f = java.io.File(Prefs.histDir(this), "s_${stamp}_$index.png")
            java.io.FileOutputStream(f).use { fs -> crop!!.compress(Bitmap.CompressFormat.PNG, 100, fs) }
            f
        } catch (e: Throwable) {
            null
        } finally {
            try { crop?.recycle() } catch (e: Throwable) {}
        }
    }

    /**
     * Reconoce el TIPO de un adjunto a partir de las pistas que sí da la accesibilidad (sin píxeles).
     * Devuelve null si el nodo no es media. Multilenguaje (español/inglés) porque las apps se traducen.
     */
    private fun mediaKind(vararg cues: String?): String? {
        val s = cues.filterNotNull().joinToString(" ").lowercase()
        if (s.isBlank()) return null
        // Identificadores internos de las apps (funcionan aunque el idioma cambie)
        if (s.contains("view_once") || s.contains("viewonce")) return "Ver una vez"
        // Tipos (inglés + español)
        if (s.contains("sticker") || s.contains("figura") || s.contains("adhesivo")) return "Sticker"
        if (s.contains("gif")) return "GIF"
        if (s.contains("photo") || s.contains("imagen") || s.contains("image") ||
            s.contains("foto")) return "Foto"
        if (s.contains("video") || s.contains("vídeo")) return "Vídeo"
        if (s.contains("audio") || s.contains("voice") || s.contains("voz") ||
            s.contains("ptt")) return "Audio"
        if (s.contains("document") || s.contains("documento") || s.contains("pdf")) return "Documento"
        if (s.contains("location") || s.contains("ubicaci")) return "Ubicación"
        if (s.contains("contact card")) return "Contacto"
        return null
    }

    /** Paquete de la app que está al frente ahora (la ventana activa). */
    fun currentPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }

    /**
     * Busca una URI de imagen que la app exponga en su árbol de accesibilidad (extras, texto o descripción).
     * No hace capturas de pantalla ni lee píxeles: solo reutiliza la URI que la propia app publica. Se descartan
     * avatares/fotos de perfil y se elige el nodo con el área más grande, que suele ser el adjunto y no el icono
     * del contacto. Si la app está en segundo plano no hay árbol que leer y devuelve null.
     */
    fun findMediaUri(pkg: String): String? {
        if (pkg == packageName) return null
        var bestArea = 0
        var best: String? = null
        val seen = HashSet<String>()
        val uriRegex = Regex("(content|file)://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
        fun accept(uri: String?, node: AccessibilityNodeInfo) {
            if (uri.isNullOrBlank() || !seen.add(uri)) return
            try {
                val r = Rect(); node.getBoundsInScreen(r)
                val area = (r.width().coerceAtLeast(1)) * (r.height().coerceAtLeast(1))
                if (area >= bestArea) { bestArea = area; best = uri }
            } catch (t: Throwable) {
                if (best == null) best = uri
            }
        }
        fun scan(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 45) return
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val hint = try { node.hintText?.toString() } catch (t: Throwable) { null } ?: ""
            val tool = try { node.tooltipText?.toString() } catch (t: Throwable) { null } ?: ""
            val all = "$desc $text $hint $tool"
            val low = all.lowercase()
            // Avatares, fotos de perfil y adjuntos de contacto: no son la imagen del mensaje.
            val avatar = low.contains("avatar") || low.contains("foto de perfil") ||
                low.contains("profile photo") || low.contains("profile picture") ||
                low.contains("contact photo")
            try {
                val extras = node.extras
                if (extras != null) for (k in extras.keySet()) {
                    val kl = k.lowercase()
                    if (avatar || kl.contains("avatar") || kl.contains("profile") ||
                        kl.contains("sender") || kl.contains("person")) continue
                    if (!(kl.contains("uri") || kl.contains("image") || kl.contains("photo") ||
                            kl.contains("picture") || kl.contains("media") || kl.contains("thumbnail"))) continue
                    val v = try { extras.get(k) } catch (t: Throwable) { null }
                    val u = when (v) {
                        is android.net.Uri -> v.toString()
                        is String -> v
                        is CharSequence -> v.toString()
                        else -> null
                    }
                    if (u != null && (u.startsWith("content://") || u.startsWith("file://"))) accept(u, node)
                }
            } catch (t: Throwable) {}
            if (!avatar) for (m in uriRegex.findAll(all)) accept(m.value, node)
            val n = node.childCount
            for (i in 0 until n) {
                scan(try { node.getChild(i) } catch (t: Throwable) { null }, depth + 1)
            }
        }
        try {
            for (r in roots()) {
                if (r.packageName?.toString() == pkg) scan(r, 0)
            }
        } catch (t: Throwable) {}
        return best
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // FORMATO LIBRE DIRECTO: lo lanza este servicio (tiene ventana propia y exención de segundo plano) pidiendo el modo libre
    // con ActivityOptions + la clave «android.activity.windowingMode» = 5. Android no avisa si la ROM lo respetó, así que
    // se comprueba con las ventanas que ve accesibilidad y se prueban variantes; la que funciona se recuerda.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun fire(v: Int, launch: Intent, pi: PendingIntent?, b: Rect, reuse: Boolean = false,
                     pkg: String = "", user: android.os.UserHandle? = null, userId: Int = -1): Boolean {
        if (v == 4 && pi == null && pkg.isNotEmpty()) return fireShell(userId, pkg, b, reuse)
        return try {
            val opts = ActivityOptions.makeBasic()
            try { opts.setLaunchBounds(b) } catch (e: Throwable) {}
            if (v == 1) try { opts.setLaunchDisplayId(0) } catch (e: Throwable) {}
            if (Build.VERSION.SDK_INT >= 34) try {
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            } catch (e: Throwable) {}
            if (v == 3) try {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 5)
            } catch (e: Throwable) {}
            val bundle = opts.toBundle() ?: android.os.Bundle()
            if (v != 3) bundle.putInt("android.activity.windowingMode", 5)
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or (if (v == 2 || reuse) 0 else Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            if (pi != null) {
                mainHandler.post {
                    try { pi.send(this, 0, Intent().addFlags(flags), null, null, null, bundle) } catch (e: Throwable) {}
                }
            } else if (pkg.isNotEmpty() && (user != null || hasOtherSpace(pkg))) {
                // Clon (app dual): se lanza SU copia en su espacio; el intent normal abriría la original.
                // Y si la app TIENE clon, la ORIGINAL también se lanza indicando su espacio (el principal): con un startActivity normal
                // la ROM decide sola entre las dos copias y puede abrir el clon en lugar de la original.
                val target = user ?: android.os.Process.myUserHandle()
                val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val acts = try { la.getActivityList(pkg, target) } catch (e: Throwable) { null }
                val want = if (user == null) launch.component else null
                val comp = acts?.firstOrNull { want != null && it.componentName == want }?.componentName
                    ?: acts?.firstOrNull()?.componentName
                // Espacio oculto (clon de MIUI/OPPO…): la API pública no lo ve → se lanza con Shizuku/root
                if (comp != null) mainHandler.post {
                    try { la.startMainActivity(comp, target, null, bundle) } catch (e: Throwable) {}
                }
                else if (!fireShell(userId, pkg, b, reuse)) return false
            } else {
                launch.flags = flags
                mainHandler.post {
                    try { startActivity(launch, bundle) } catch (e: Throwable) {}
                }
            }
            true
        } catch (e: Throwable) { false }
    }

    // Tareas abiertas por el método con privilegios, para redimensionarlas sin volver a buscarlas.
    private val taskIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Apertura con Shizuku/root: `am start --windowingMode 5` (la ROM no puede ignorarlo) y, en el acto, un
     * `cmd activity task resize` porque `am start` no acepta límites. Llamar fuera del hilo principal.
     */
    private fun fireShell(userId: Int, pkg: String, b: Rect, reuse: Boolean): Boolean {
        if (pkg.isEmpty() || !Shell.privileged(this)) return false
        val u = if (userId >= 0) userId else Clones.myUser()
        val flags = if (reuse) "0x10000000" else "0x18000000"      // NEW_TASK (| MULTIPLE_TASK)
        val comp = shellComponent(u, pkg)
        val target = if (comp != null) "-n $comp"
            else "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
        val cmd = "am start --user $u -f $flags --windowingMode 5 $target"
        val r = try { Shell.run(this, cmd) } catch (e: Throwable) { return false }
        val out = r.second
        if (r.first != 0 || out.contains("Error", true) || out.contains("does not exist", true)) return false
        // `am start` no acepta límites: se coloca la ventana real en el acto (la tarea tarda un instante en existir)
        val key = "$pkg@$u"
        var id = taskIds[key] ?: ""
        if (id.isEmpty()) {
            pause(220)
            id = FloatingWindow.findTaskId(this, pkg, u)
            if (id.isNotEmpty()) taskIds[key] = id
        }
        if (id.isNotEmpty()) FloatingWindow.resizeTask(this, id, b)
        return true
    }

    /** Componente del launcher de [pkg] en el espacio [userId], en formato «paquete/clase» (lo que acepta `am -n`). */
    private fun shellComponent(userId: Int, pkg: String): String? = try {
        val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        la.getActivityList(pkg, Clones.handle(userId)).firstOrNull()?.componentName?.flattenToShortString()
    } catch (e: Throwable) { null }

    /** ¿[pkg] está también en otro espacio (clon / app dual)? Lo sabe [Clones] (copia vista, perfiles o usuarios de la ROM). */
    private fun hasOtherSpace(pkg: String): Boolean = Clones.hasClone(this, pkg)

    /**
     * Si la app tiene clon y se pidió la ORIGINAL, lo anota en el registro. «Acceso de uso» solo ve el espacio principal:
     * si ahí aparece [pkg] al frente, lo que se abrió es la original; si no, puede haberse abierto el clon.
     */
    private fun checkSpace(pkg: String, user: android.os.UserHandle?, pi: PendingIntent?, log: StringBuilder) {
        if (user != null || pi != null || !hasOtherSpace(pkg)) return
        if (!Foreground.hasAccess(this)) {
            log.append("Espacio: esta app tiene clon. Activa «Acceso de uso» para que Ryx confirme que abrió la ORIGINAL.\n"); return
        }
        pause(300)
        val fg = Foreground.pkg(this)
        log.append(if (fg == pkg) "Espacio: se abrió la ORIGINAL (confirmado con Acceso de uso).\n"
            else "Espacio: NO pude confirmar la original (al frente en tu espacio: ${fg ?: "nada"}); puede haberse abierto el clon.\n")
    }

    /** Medidas reales de las ventanas de [pkg] y de la pantalla, para el registro (así se ve si quedó flotante o completa). */
    fun describeWindows(pkg: String): String {
        val m = resources.displayMetrics
        val ws = try { windows } catch (e: Exception) { null }
        val parts = ArrayList<String>()
        if (ws != null) for (w in ws) {
            val name = try { w.root?.packageName?.toString() } catch (e: Exception) { null } ?: continue
            if (name != pkg) continue
            val r = Rect(); w.getBoundsInScreen(r)
            parts.add("${r.left},${r.top}→${r.right},${r.bottom} (${r.width()}×${r.height()})")
        }
        return "Pantalla ${m.widthPixels}×${m.heightPixels} · ventanas de $pkg: ${if (parts.isEmpty()) "ninguna visible" else parts.joinToString(" | ")}"
    }

    /**
     * Formato libre DIRECTO (función de Smart Island), sin salir de tu app ni pasar por el launcher:
     *  1) desbloquea las apps no redimensionables (force_resizable_activities) si hace falta,
     *  2) lanza desde este servicio con ActivityOptions + límites + «android.activity.windowingMode» = 5 (NEW_TASK | MULTIPLE_TASK),
     *  3) comprueba con las ventanas de accesibilidad si quedó flotante y, si el sistema la abrió a pantalla completa,
     *     prueba las variantes de lanzamiento (la que funciona se recuerda para las siguientes aperturas).
     * Con la variante recordada la apertura es de un solo intento y tarda lo que tarda el sistema (unos cientos de ms).
     * Llamar fuera del hilo principal.
     */
    fun launchSmartIsland(pkg: String, pi: PendingIntent?, b: Rect, log: StringBuilder,
                          user: android.os.UserHandle? = null, userId: Int = -1): Boolean {
        val t0 = System.currentTimeMillis()
        try { FreeformUnlock.ensure(this, log) } catch (e: Throwable) { log.append("Desbloqueo: ${e.javaClass.simpleName}\n") }
        return try {
            val m = resources.displayMetrics
            // Si pides una ventana casi del tamaño de la pantalla no se puede distinguir de «pantalla completa»: un solo intento
            val almostFull = b.width() >= m.widthPixels * 0.97f && b.height() >= m.heightPixels * 0.88f
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null && pi == null && user == null) { log.append("No se encontró la actividad principal.\n"); return false }
            val def = if (Shell.privileged(this)) 4 else 0
            val ok = if (almostFull) {
                val v = Prefs.fwVariant(this).let { if (it in VARIANTS.indices) it else def }
                log.append("Ventana casi a pantalla completa: un solo intento (variante ${v + 1}).\n")
                fire(v, Intent(launch ?: Intent()), pi, b, pkg = pkg, user = user, userId = userId)
            } else if (launch != null || user != null) {
                launchFreeformDirect(pkg, pi, b, log, user, userId)
            } else {
                fire(Prefs.fwVariant(this).let { if (it in VARIANTS.indices) it else def }, Intent(), pi, b, userId = userId)
            }
            log.append("Tiempo total: ${System.currentTimeMillis() - t0} ms. ${describeWindows(pkg)}\n")
            ok
        } catch (e: Throwable) {
            log.append("Error al lanzar: ${e.javaClass.simpleName} ${e.message ?: ""}\n"); false
        }
    }

    /** Límites reales de la ventana flotante de [pkg] (la de menor área si hay varias), o null si no se ve. */
    fun windowBounds(pkg: String): Rect? {
        val ws = try { windows } catch (e: Exception) { null } ?: return null
        var best: Rect? = null
        for (w in ws) {
            val name = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
            if (name != pkg) continue
            val r = Rect(); w.getBoundsInScreen(r)
            val b = best
            if (b == null || r.width() * r.height() < b.width() * b.height()) best = r
        }
        return best
    }

    /** ¿Sigue visible alguna ventana de [pkg]? (diagnóstico de ventanas) */
    fun windowAlive(pkg: String): Boolean = floatingState(pkg) != null

    /**
     * Mover/redimensionar sin privilegios: vuelve a pedir el modo libre con los nuevos límites SIN «ventana nueva»,
     * para que Android reutilice la misma tarea en vez de abrir otra. Llamar fuera del hilo principal.
     */
    fun relaunchBounds(pkg: String, b: Rect, user: android.os.UserHandle? = null, userId: Int = -1): Boolean {
        // El intent de la app se guarda: preguntarlo a Android en cada movimiento restaba fluidez
        val launch = if (user != null) Intent()
            else (launchCache[pkg] ?: packageManager.getLaunchIntentForPackage(pkg)?.also { launchCache[pkg] = it }) ?: return false
        val def = if (Shell.privileged(this)) 4 else 0
        val v = Prefs.fwVariant(this).let { if (it in VARIANTS.indices) it else def }   // la misma variante que abrió la ventana
        return fire(v, Intent(launch), null, b, reuse = true, pkg = pkg, user = user, userId = userId)
    }
    private val launchCache = java.util.concurrent.ConcurrentHashMap<String, Intent>()

    /** true = la ventana de [pkg] no ocupa toda la pantalla (flotante); false = pantalla completa; null = no se pudo saber. */
    private fun floatingState(pkg: String): Boolean? {
        val m = resources.displayMetrics
        val ws = try { windows } catch (e: Exception) { null } ?: return null
        var found = false
        for (w in ws) {
            val name = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
            if (name != pkg) continue
            found = true
            val r = Rect(); w.getBoundsInScreen(r)
            if (r.width() < m.widthPixels * 0.97f || r.height() < m.heightPixels * 0.88f) return true
        }
        return if (found) false else null
    }

    /** Espera a ver si la ventana quedó flotante. Sale en cuanto lo sabe: flotante → ya; pantalla completa estable 450 ms → ya. */
    private fun waitFloating(pkg: String, ms: Long): Boolean? {
        val end = System.currentTimeMillis() + ms
        var last: Boolean? = null
        var falseSince = 0L
        while (System.currentTimeMillis() < end) {
            last = floatingState(pkg)
            val now = System.currentTimeMillis()
            if (last == true) return true
            if (last == false) {
                if (falseSince == 0L) falseSince = now else if (now - falseSince > 450) return false
            } else falseSince = 0L
            pause(30)
        }
        return last
    }

    /** Llamar fuera del hilo principal. Devuelve true si la app quedó (o parece haber quedado) en ventana libre. */
    fun launchFreeformDirect(pkg: String, pi: PendingIntent?, b: Rect, log: StringBuilder,
                            user: android.os.UserHandle? = null, userId: Int = -1): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null && user == null) { log.append("No se encontró la actividad principal.\n"); return false }
        val cached = Prefs.fwVariant(this)
        val rest = VARIANTS.indices.filter { it != cached }
        // Con Shizuku/root se prueba PRIMERO el método con privilegios (el que la ROM no puede ignorar)
        val order = when {
            cached in VARIANTS.indices -> listOf(cached) + rest
            Shell.privileged(this) -> listOf(VARIANTS.indices.last()) + rest.filter { it != VARIANTS.indices.last() }
            else -> VARIANTS.indices.toList()
        }
        for (v in order) {
            val sent = fire(v, launch?.let { Intent(it) } ?: Intent(), pi, b, pkg = pkg, user = user, userId = userId)
            if (!sent) { log.append("Variante ${v + 1} · ${VARIANTS[v]}: no se pudo lanzar.\n"); continue }
            when (waitFloating(pkg, 1500)) {
                true -> {
                    log.append("Variante ${v + 1} · ${VARIANTS[v]}: ventana libre verificada. ${describeWindows(pkg)}\n"); Prefs.setFwVariant(this, v)
                    checkSpace(pkg, user, pi, log)
                    return true
                }
                null -> { log.append("Variante ${v + 1} · ${VARIANTS[v]}: lanzada (no pude verificar si es libre; mira la pantalla).\n"); return true }
                false -> {
                    log.append("Variante ${v + 1} · ${VARIANTS[v]}: la ROM la abrió a pantalla completa.\n")
                    if (pi != null) pause(250)
                }
            }
        }
        Prefs.setFwVariant(this, -1)
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // CORTINA: una captura de tu pantalla puesta a pantalla completa mientras Ryx hace por debajo lo que harías
    // con el dedo en el launcher (Recientes / inicio → «Formato libre»). Se ve tu app congelada, no el salto al
    // inicio; al abrirse la ventana real del sistema (con su barra, iconos y esquinas) la cortina se retira.
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var curtain: ImageView? = null
    private var curtainLp: WindowManager.LayoutParams? = null

    private fun onMain(block: () -> Unit) {
        val l = CountDownLatch(1)
        ui.post { try { block() } catch (e: Throwable) {} finally { l.countDown() } }
        try { l.await(1500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    }

    private fun grabScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        // Nunca bloquear el hilo principal esperando una captura: el callback también llega al main.
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        var out: Bitmap? = null
        val latch = CountDownLatch(1)
        // takeScreenshot es una API del servicio: se pide en el hilo principal y se espera fuera de él.
        ui.post {
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        try {
                            val hb = r.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                            out = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle(); hb.close()
                        } catch (e: Throwable) {}
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) { latch.countDown() }
                })
            } catch (e: Throwable) { latch.countDown() }
        }
        try { latch.await(900, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
        return out
    }

    private fun showCurtain(log: StringBuilder): Boolean {
        val bmp = grabScreen()
        if (bmp == null) { log.append("Cortina: no pude capturar la pantalla (se verá el cambio de pantalla).\n"); return false }
        var ok = false
        onMain {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.BLACK) }
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.OPAQUE)
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            wm.addView(iv, lp); curtain = iv; curtainLp = lp; ok = true
        }
        if (ok) ui.postDelayed({ hideCurtain() }, 8000)      // seguro: nunca se queda puesta
        log.append(if (ok) "Cortina: puesta (tu pantalla se ve congelada mientras se abre la ventana).\n" else "Cortina: no se pudo poner.\n")
        return ok
    }

    private fun hideCurtain() {
        val iv = curtain ?: return
        curtain = null
        onMain { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(iv) } catch (e: Throwable) {} }
    }

    /** La cortina deja pasar los toques solo mientras Ryx inyecta un gesto; el resto del tiempo los absorbe. */
    private fun curtainPassThrough(on: Boolean) {
        val iv = curtain ?: return
        val lp = curtainLp ?: return
        onMain {
            lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(iv, lp) } catch (e: Throwable) {}
        }
    }

    /**
     * Burbuja ya creada: se toca su icono (instantáneo, sin cortina ni menús). Las burbujas de Android 17 se quedan en pantalla,
     * así que después de crearla una vez, abrirla es un solo toque. Solo se aceptan vistas que sean de burbuja (clase del sistema)
     * y cuyo texto/descripción contenga el nombre de la app, para no tocar una notificación por error.
     */
    fun tapExistingBubble(label: String, log: StringBuilder): Boolean {
        val ws = try { windows } catch (e: Exception) { null } ?: return false
        for (w in ws) {
            val root = try { w.root } catch (e: Exception) { null } ?: continue
            val pk = root.packageName?.toString() ?: continue
            if (pk == packageName) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root); var g = 0
            while (stack.isNotEmpty() && g++ < 400) {
                val n = stack.removeLast()
                val cls = n.className?.toString() ?: ""
                if (n.isVisibleToUser && (cls.contains("Bubble", true) || cls.contains("BadgedImageView", true))) {
                    val t = nodeText(n)
                    if (t.contains(label, true) && t.length < 120) {
                        val ok = tapExact(n)
                        log.append(if (ok) "Toqué la burbuja existente de «$label» (${cls.substringAfterLast('.')}).\n" else "No pude tocar la burbuja de «$label».\n")
                        if (ok) return true
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // BURBUJA (Android 17). Android no ofrece ninguna API para crearla: solo el launcher del sistema puede (menú «Burbuja»
    // al mantener pulsado un icono). Por eso: 1) si la burbuja ya existe se toca su icono (instantáneo, sin salir de tu app);
    // 2) si no, se hace UNA vez por el menú del launcher, tapado con una captura, y se devuelve tu app al frente.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Llamar fuera del hilo principal. Devuelve true si la burbuja quedó abierta o creada. */
    fun launchBubble(pkg: String, label: String, keepApp: Boolean, log: StringBuilder): Boolean {
        val t0 = System.currentTimeMillis()
        if (tapExistingBubble(label, log)) { log.append("Abierta en ${System.currentTimeMillis() - t0} ms.\n"); return true }
        val prev = currentPackage()
        val covered = showCurtain(log)
        try {
            val ok = viaHome(label, log)
            log.append("Menú del launcher: ${System.currentTimeMillis() - t0} ms en total.\n")
            if (!ok) { dump(log); performGlobalAction(GLOBAL_ACTION_HOME); return false }
            pause(400)      // un instante para que el sistema cree la burbuja antes de volver a tu app
            val skip = setOf(pkg, homePackage(), "com.android.systemui")
            if (keepApp && prev != null && prev !in skip) {
                try {
                    val a = packageManager.getLaunchIntentForPackage(prev)
                    if (a != null) { a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(a); waitForeground(prev, 900) }
                    log.append("Devolví «$prev» al frente; la burbuja queda encima.\n")
                } catch (e: Exception) { log.append("No pude devolver tu app: ${e.javaClass.simpleName}\n") }
            }
            log.append("Burbuja creada en ${System.currentTimeMillis() - t0} ms.\n")
            return true
        } finally {
            if (covered) hideCurtain()
        }
    }

    private fun pause(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) {} }

    private fun homePackage(): String? = try {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (e: Exception) { null }

    /** Ventanas visibles de otras apps (launcher y su menú emergente). */
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { windows.forEach { w -> w.root?.let { out.add(it) } } } catch (e: Exception) {}
        if (out.isEmpty()) rootInActiveWindow?.let { out.add(it) }
        return out.filter { it.packageName?.toString() != packageName }
    }

    private fun nodeText(n: AccessibilityNodeInfo): String = (n.text ?: n.contentDescription ?: "").toString().trim()

    private val CLONEISH = Regex("dual|clon|clone|gemel|twin|duplic|segundo espacio|second space", RegexOption.IGNORE_CASE)

    /** El icono de [label]. Si el launcher tiene dos (original y clon), gana el de nombre exacto y sin pinta de clon. */
    private fun findLabel(label: String): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        for (r in roots()) {
            val list = try { r.findAccessibilityNodeInfosByText(label) } catch (e: Exception) { null } ?: continue
            for (n in list) {
                if (!n.isVisibleToUser) continue
                val t = nodeText(n)
                if (!t.startsWith(label, true)) continue
                val score = (if (t.equals(label, true)) 2 else 0) + (if (CLONEISH.containsMatchIn(t)) 0 else 1)
                if (score > bestScore) { best = n; bestScore = score }
            }
        }
        return best
    }

    /** La opción «Burbuja» del menú del launcher, si está visible. */
    private fun findBubbleItem(): AccessibilityNodeInfo? {
        for (r in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(r)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 800) {
                val n = stack.removeLast()
                if (n.isVisibleToUser && BUBBLE.containsMatchIn(nodeText(n))) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun waitBubbleItem(ms: Long): AccessibilityNodeInfo? {
        var r: AccessibilityNodeInfo? = null
        waitUntil(ms, 60) { r = findBubbleItem(); r != null }
        return r
    }

    private fun gesture(p: Path, dur: Long): Boolean {
        curtainPassThrough(true)
        try { return gestureRaw(p, dur) } finally { curtainPassThrough(false) }
    }

    private fun gestureRaw(p: Path, dur: Long): Boolean {
        var ok = false
        val latch = CountDownLatch(1)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(dur + 2500, TimeUnit.MILLISECONDS)
        return ok
    }

    private fun pointOf(n: AccessibilityNodeInfo): Path {
        val r = Rect(); n.getBoundsInScreen(r)
        return Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
    }

    private fun swipeUp(from: Float, to: Float): Boolean {
        val m = resources.displayMetrics
        return gesture(Path().apply { moveTo(m.widthPixels / 2f, m.heightPixels * from); lineTo(m.widthPixels / 2f, m.heightPixels * to) }, 350)
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var i = 0
        while (cur != null && i < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent; i++
        }
        return gesture(pointOf(n), 60)
    }

    /** Toca el nodo en sí (sin subir a un padre clicable). */
    private fun tapExact(n: AccessibilityNodeInfo): Boolean =
        (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) || gesture(pointOf(n), 50)

    /** Espera a que la app indicada esté al frente (hasta [ms] milisegundos). */
    private fun waitForeground(pkg: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (rootInActiveWindow?.packageName?.toString() == pkg) return true
            pause(80)
        }
        return false
    }

    private fun waitUntil(ms: Long, step: Long = 100, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; pause(step) }
        return cond()
    }

    /**
     * Va al inicio, busca el icono de la app (en la pantalla o en el cajón), lo mantiene pulsado y toca «Burbuja».
     * Bloquea el hilo: llamar fuera del hilo principal. Devuelve true si llegó a pulsar la opción.
     */
    private fun viaHome(label: String, log: StringBuilder): Boolean {
        log.append("Launcher: ${homePackage() ?: "?"} · busco el icono de «$label»\n")
        performGlobalAction(GLOBAL_ACTION_HOME)
        var icon: AccessibilityNodeInfo? = null
        waitUntil(1400, 60) { icon = findLabel(label); icon != null }       // sondeo: sigue en cuanto aparece el icono
        if (icon == null) {
            log.append("No está en la pantalla de inicio; abro el cajón de apps.\n")
            swipeUp(0.88f, 0.35f)
            waitUntil(900, 60) { icon = findLabel(label); icon != null }
            var tries = 0
            while (icon == null && tries < 12) {
                swipeUp(0.78f, 0.28f)
                waitUntil(450, 60) { icon = findLabel(label); icon != null }
                tries++
            }
        }
        val ic = icon
        if (ic == null) {
            log.append("No encontré el icono de «$label».\n"); performGlobalAction(GLOBAL_ACTION_HOME); return false
        }
        // Primero con la acción de accesibilidad «mantener pulsado»; si no sale el menú, con un gesto real
        var cur: AccessibilityNodeInfo? = ic
        var k = 0; var done = false
        while (cur != null && k < 4 && !done) {
            if (cur.isLongClickable) done = cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            cur = cur.parent; k++
        }
        var item = waitBubbleItem(800)
        if (item == null) {
            log.append("El menú no salió; pruebo con un gesto de mantener pulsado.\n")
            gesture(pointOf(ic), 600)
            item = waitBubbleItem(1000)
        }
        if (item == null) {
            log.append("No apareció «Burbuja» en el menú del launcher (¿tu launcher no la trae?).\n")
            performGlobalAction(GLOBAL_ACTION_BACK); return false
        }
        val ok = clickNode(item)
        log.append(if (ok) "Pulsé «${nodeText(item)}» en el menú del launcher.\n" else "No pude pulsar la opción del menú.\n")
        return ok
    }

    /** Textos visibles de otras apps, para el registro cuando algo falla (así se ve qué ve Ryx en tu launcher). */
    private fun dump(log: StringBuilder) {
        val seen = LinkedHashSet<String>()
        for (r in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(r)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 500 && seen.size < 30) {
                val n = stack.removeLast()
                val t = nodeText(n)
                if (n.isVisibleToUser && t.isNotEmpty()) seen.add(t.take(30))
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        log.append("Textos que ve Ryx ahora: ${if (seen.isEmpty()) "ninguno (¿el launcher oculta su contenido?)" else seen.joinToString(" | ")}\n")
    }
}
