package com.dav.isla

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Barra de Ryx pegada a la ventana libre: arriba para moverla, abajo para cambiar su tamaño.
 *
 * FLUIDEZ (como las burbujas del sistema): mientras arrastras NO se toca la ventana real. Se dibuja un MARCO FANTASMA
 * (translúcido) que sigue al dedo a la velocidad de la pantalla (60/120 Hz), junto con la barra, así el gesto es
 * instantáneo y nunca se traba. Al SOLTAR, la ventana real se coloca en segundo plano con un pequeño deslizamiento
 * (unos pocos pasos) para que «llegue» a su sitio en lugar de aparecer de golpe.
 *
 * SEGUIMIENTO REAL: en reposo la fuente de verdad es la ventana real que ve accesibilidad. El servicio avisa en cada cambio
 * de ventanas (RyxAccessibility → [onWindowsChanged]) y hay un sondeo de respaldo. La barra se coloca sobre los límites
 * reales, así que también sigue a la ventana si la mueve el sistema.
 *
 * MOVER DE VERDAD: con Shizuku/root se usa `cmd activity task resize`; sin ellos se vuelve a pedir la ventana a Android
 * con los nuevos límites. Después de cada envío se LEE la ventana real: si la ROM no la movió, tras 3 fallos seguidos
 * se avisa y la barra deja de fingir que la mueve.
 */
object FloatFrame {
    private val main = Handler(Looper.getMainLooper())
    private val bgThread by lazy { HandlerThread("ryx-frame").also { it.start() } }
    private val bg by lazy { Handler(bgThread.looper) }

    private var ctx: Context? = null
    private var wm: WindowManager? = null
    private var handle: View? = null
    private var grip: View? = null
    private var hp: WindowManager.LayoutParams? = null
    private var gp: WindowManager.LayoutParams? = null
    private var ghost: View? = null                              // marco fantasma: sigue al dedo al instante
    private var ghostP: WindowManager.LayoutParams? = null
    private var ghostShown = false
    private val r = Rect()          // lo que se dibuja: los límites reales (o los del dedo mientras arrastras)
    private val start = Rect()
    private var downX = 0f
    private var downY = 0f
    private var taskId = ""
    private var pkgName = ""
    private var title = ""
    private var priv = false
    private var cloneUser: android.os.UserHandle? = null      // si la ventana es de un CLON, su espacio
    private var userIdN = -1

    @Volatile private var generation = 0
    @Volatile private var lockUntil = 0L
    @Volatile private var dragging = false
    @Volatile private var syncQueued = false
    // Cola de envíos a la ventana real: un solo hueco con «gana el último». Se guarda dentro de un monitor para que
    // quien escribe y quien vacía lo hagan de forma atómica (antes un hilo podía borrar lo que otro acababa de
    // poner y la ventana «no obedecía»: parecía que no respondía).
    private val qLock = Any()
    private var qLast: Rect? = null
    private var qRunning = false
    private var misses = 0
    private var fullCount = 0
    private var moverFails = 0
    private var moverBroken = false
    private var warned = false
    private var nudge = false           // la ROM ignora un movimiento que no cambia el tamaño: se pide con 1 px de diferencia
    private var nudgeTick = 0

    // ── Rendimiento del arrastre ────────────────────────────────────────────────────────────────
    // Cada updateViewLayout es un viaje a WindowManager. Con un toque de 120 Hz llegan más dedos que fotogramas, así
    // que solo se manda lo que ha cambiado de verdad y, mientras se MUEVE, el tirador inferior no se toca (la ventana
    // real sigue donde estaba, moverlo habría sido un viaje por fotograma sin motivo).
    private var attached = false                     // barra y tirador ya están en pantalla
    private var dragMove = true                      // el gesto actual es de MOVER (no de cambiar el tamaño)
    private val lb = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, -1, -1)     // barra dibujada: x, y, ancho, alto
    private val lg = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, -1, -1)     // tirador dibujado
    private val lgh = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, -1, -1)    // fantasma dibujado
    private var reserve = 0                          // ancho que la barra deja libre a la derecha (botones de la ROM)

    // La barra desaparece al quitar la ventana (minimizar/cerrar); aquí se apunta para volver a ponerla si vuelve.
    private var watch: Runnable? = null
    private var watchStop = 0L

    private const val OVL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun show(c: Context, id: String, bounds: Rect, pkg: String = "", label: String = "",
             user: android.os.UserHandle? = null, userId: Int = -1) {
        val app = c.applicationContext
        main.post {
            if (!Settings.canDrawOverlays(app)) return@post
            hide()
            ctx = app; taskId = id; pkgName = pkg; title = label; priv = false; r.set(bounds); cloneUser = user; userIdN = userId
            misses = 0; fullCount = 0; moverFails = 0; moverBroken = false; warned = false; lockUntil = 0L
            nudge = Prefs.fwMoveNudge(app)
            reserve = dp(app, 132)                  // hueco para los botones del sistema (minimizar/maximizar)
            attached = false
            lb[0] = Int.MIN_VALUE; lb[1] = Int.MIN_VALUE; lb[2] = -1; lb[3] = -1
            lg[0] = Int.MIN_VALUE; lg[1] = Int.MIN_VALUE; lg[2] = -1; lg[3] = -1
            lgh[0] = Int.MIN_VALUE; lgh[1] = Int.MIN_VALUE; lgh[2] = -1; lgh[3] = -1
            stopWatch()
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w
            val gen = generation

            // Barra superior, del ancho de la ventana y pegada encima: arrastrar la mueve; la X quita la barra
            val bar = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    val rad = dp(app, 14).toFloat(); cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f); setColor(0xE615171C.toInt())
                }
                setPadding(dp(app, 14), 0, dp(app, 4), 0)
            }
            bar.addView(TextView(app).apply {
                text = "━━  ${if (title.isNotEmpty()) title else "mover"}"; setTextColor(Color.WHITE); textSize = 12f
                maxLines = 1; ellipsize = TextView.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            bar.addView(TextView(app).apply {
                text = "✕"; setTextColor(Color.WHITE); textSize = 14f
                setPadding(dp(app, 12), dp(app, 6), dp(app, 12), dp(app, 6))
                setOnClickListener { hide() }
            })
            hp = WindowManager.LayoutParams(r.width(), dp(app, 32), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OVL_FLAGS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; cutoutAlways() }
            // Barra inferior, del ancho de la ventana: arrastrar cambia el tamaño
            val g = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    val rad = dp(app, 14).toFloat(); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, rad, rad, rad, rad); setColor(0xE615171C.toInt())
                }
            }
            g.addView(TextView(app).apply { text = "━━━━━━"; setTextColor(0xCCFFFFFF.toInt()); textSize = 12f })
            g.addView(TextView(app).apply { text = "  ⌟"; setTextColor(Color.WHITE); textSize = 16f })
            gp = WindowManager.LayoutParams(r.width(), dp(app, 28), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OVL_FLAGS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; cutoutAlways() }
            bar.setOnTouchListener(drag(true)); g.setOnTouchListener(drag(false))
            handle = bar; grip = g
            ensureGhost(app)
            place()
            try { w.addView(bar, hp); w.addView(g, gp) } catch (e: Exception) { hide(); return@post }
            attached = true

            if (pkg.isNotEmpty() && RyxAccessibility.instance != null) {
                bg.postDelayed({ syncNow(gen) }, 150)            // primera lectura: se corrige a los límites reales
                bg.postDelayed(pollRun(gen), 650)                // respaldo si la ROM no manda el evento de cambio
            } else main.postDelayed({ if (generation == gen) hide() }, 120_000L)   // sin verificación: se quitan solas a los 2 min

            // Con Shizuku/root ya activos se busca la tarea para moverla de verdad (sin ellos se usa el relanzado)
            if (pkg.isNotEmpty()) Thread {
                try {
                    if (Shell.privileged(app)) {
                        val tid = if (id.isNotEmpty()) id else FloatingWindow.findTaskId(app, pkg, userIdN)
                        if (tid.isNotEmpty() && generation == gen) { taskId = tid; priv = true }
                    }
                } catch (e: Throwable) {}
            }.start()
        }
    }

    fun hide() {
        generation++
        bg.removeCallbacksAndMessages(null)
        synchronized(qLock) { qLast = null; qRunning = false }
        syncQueued = false; dragging = false; attached = false
        hideGhost()
        val w = wm
        try { handle?.let { w?.removeView(it) } } catch (e: Exception) {}
        try { grip?.let { w?.removeView(it) } } catch (e: Exception) {}
        handle = null; grip = null; ghost = null; ghostP = null
    }

    /** Ancho útil de la barra y del tirador: se deja libre la esquina superior derecha, donde la ROM dibuja los
     *  botones de minimizar/maximizar de la ventana libre (viven en la decoración del sistema, justo encima de la app).
     *  Taparlos era el motivo de que «no respondieran» al tocarlos. */
    private fun barWidth(): Int {
        val c = ctx ?: return r.width()
        val w = r.width(); val free = if (reserve > 0) reserve else dp(c, 132)
        return when {
            w - free >= dp(c, 130) -> w - free          // sobra sitio: barra completa y esquina libre
            w >= dp(c, 220) -> w * 50 / 100             // ventana estrecha: la barra ocupa la mitad izquierda
            else -> w                                   // ventana diminuta: se deja entera (aquí no caben dos cosas)
        }
    }

    private fun place() {
        val c = ctx ?: return
        val sh = c.resources.displayMetrics.heightPixels
        val bw = barWidth()
        hp?.let {
            it.width = bw; it.x = r.left
            it.y = if (r.top >= it.height) r.top - it.height else 0
        }
        gp?.let { it.width = bw; it.x = r.left; it.y = if (r.bottom + it.height <= sh) r.bottom else r.bottom - it.height }
        ghostP?.let { it.width = r.width(); it.height = r.height(); it.x = r.left; it.y = r.top }
        if (!attached) return
        upd(handle, hp, lb)
        // Mientras se MUEVE, la ventana real no se ha tocado: el tirador no cambia, así que ni se le pregunta a WindowManager
        if (!(dragging && dragMove)) upd(grip, gp, lg)
        if (ghostShown) upd(ghost, ghostP, lgh)
    }

    /** Solo manda la vista si sus medidas o su posición han cambiado de verdad: cada updateViewLayout es un viaje
     *  a WindowManager y en un arrastre llegan más toques que fotogramas. */
    private fun upd(v: View?, p: WindowManager.LayoutParams?, last: IntArray) {
        if (v == null || p == null) return
        if (last[0] == p.x && last[1] == p.y && last[2] == p.width && last[3] == p.height) return
        last[0] = p.x; last[1] = p.y; last[2] = p.width; last[3] = p.height
        try { wm?.updateViewLayout(v, p) } catch (e: Exception) {
            last[0] = Int.MIN_VALUE; last[1] = Int.MIN_VALUE; last[2] = -1; last[3] = -1
        }
    }

    // ───────────────────────────── Marco fantasma ─────────────────────────────

    /** Crea (una vez) el marco fantasma que sigue al dedo mientras arrastras. */
    private fun ensureGhost(app: Context) {
        if (ghost != null) return
        ghost = View(app).apply {
            background = GradientDrawable().apply {
                setColor(0x227B5CE0)
                setStroke(dp(app, 2), 0xCC8C7BFF.toInt())
                cornerRadius = dp(app, 18).toFloat()
            }
        }
        ghostP = WindowManager.LayoutParams(r.width(), r.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            OVL_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; cutoutAlways()
        }
    }

    private fun showGhost() {
        val w = wm ?: return
        val g = ghost ?: return
        val gp2 = ghostP ?: return
        if (ghostShown) return
        try { w.addView(g, gp2); ghostShown = true } catch (e: Exception) {}
    }

    private fun hideGhost() {
        val w = wm; val g = ghost
        if (ghostShown && w != null && g != null) try { w.removeView(g) } catch (e: Exception) {}
        ghostShown = false
    }

    // Con el dedo llegan más movimientos que fotogramas (120 Hz de tacto vs 60 Hz de pantalla): se redibuja UNA vez por fotograma
    private var framePosted = false
    private val frameCb = Choreographer.FrameCallback { framePosted = false; place() }
    private fun placeOnFrame() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun WindowManager.LayoutParams.cutoutAlways() {
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun sameRect(a: Rect, b: Rect, tol: Int = 3) =
        abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol && abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol

    // ───────────────────────────── Seguimiento real ─────────────────────────────

    /** Lo llama el servicio de accesibilidad en cada cambio de ventanas. Se junta en una sola lectura cada ~50 ms
     *  (con 12 ms llegaban decenas de lecturas por segundo durante cada animación de minimizar/maximizar, y eso
     *  se notaba como tirones en toda la interfaz). */
    fun onWindowsChanged() {
        // Mientras arrastras no se consulta la ventana real: cada cambio de ventanas dispararía una lectura por binder y
        // eso es justo lo que hacía que el gesto fuera a tirones.
        if (handle == null || dragging || syncQueued) return
        if (System.currentTimeMillis() < lockUntil) return      // la ventana sigue moviéndose: se espera a que se quede
        syncQueued = true
        val gen = generation
        bg.postDelayed({ syncQueued = false; syncNow(gen) }, 50)
    }

    private fun pollRun(gen: Int): Runnable = object : Runnable {
        override fun run() {
            if (generation != gen || handle == null) return
            syncNow(gen)
            bg.postDelayed(this, 250)
        }
    }

    /** Lee la ventana real (hilo de fondo) y la aplica en el hilo principal. */
    private fun syncNow(gen: Int) {
        if (generation != gen || dragging) return
        val svc = RyxAccessibility.instance ?: return
        val pkg = pkgName
        if (pkg.isEmpty()) return
        val real = svc.windowBounds(pkg)
        main.post { applyReal(real, gen) }
    }

    private fun applyReal(real: Rect?, gen: Int) {
        if (generation != gen || handle == null) return
        val c = ctx ?: return
        if (real == null) { if (++misses >= 5) gone(); return }          // la ventana ya no existe → fuera las asas
        misses = 0
        val m = c.resources.displayMetrics
        val full = real.width() >= m.widthPixels * 0.97f && real.height() >= m.heightPixels * 0.88f
        if (full) { if (++fullCount >= 3) gone(); return }               // pasó a pantalla completa
        fullCount = 0
        if (dragging) return                                          // mientras arrastras manda tu dedo
        if (System.currentTimeMillis() < lockUntil) return            // se espera a que la ventana alcance lo pedido
        if (!sameRect(real, r)) { r.set(real); place() }
    }

    /**
     * La ventana se fue: la ROM la minimizó, la cerró o la llevó a pantalla completa. La barra se quita, pero se queda
     * mirando (una sola consulta cada 500 ms, 60 s): si el sistema la vuelve a poner en ventana libre, la barra regresa
     * sola. Antes desaparecía y ya no volvía, y parecía que los botones de minimizar/maximizar no hacían nada.
     */
    private fun gone() {
        val pk = pkgName
        hide()
        val gen = generation
        if (pk.isEmpty() || RyxAccessibility.instance == null) return
        val c = ctx ?: return
        val tid = taskId; val lbx = title; val cu = cloneUser; val un = userIdN
        stopWatch()
        watchStop = System.currentTimeMillis() + 60_000L
        val w = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() > watchStop) { watch = null; return }
                val svc = RyxAccessibility.instance
                if (svc == null) { watch = null; return }
                val b = svc.windowBounds(pk)
                val dm = c.resources.displayMetrics
                val stillFull = b != null && b.width() >= dm.widthPixels * 0.97f && b.height() >= dm.heightPixels * 0.88f
                if (b != null && !b.isEmpty && !stillFull && generation == gen) {
                    watch = null
                    main.post { if (handle == null) show(c, tid, Rect(b), pk, lbx, cu, un) }
                    return
                }
                bg.postDelayed(this, 500)
            }
        }
        watch = w
        bg.postDelayed(w, 500)
    }

    private fun stopWatch() { watch?.let { bg.removeCallbacks(it) }; watch = null; watchStop = 0L }

    // ─────────────────────────── Mover y cambiar tamaño ───────────────────────────

    private fun drag(move: Boolean) = View.OnTouchListener { _, e ->
        val c = ctx ?: return@OnTouchListener false
        val m = c.resources.displayMetrics
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; start.set(r); dragging = true; dragMove = move
                if (!moverBroken) { showGhost(); place() }                 // aparece el marco fantasma en su sitio
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (moverBroken) return@OnTouchListener true           // esta ROM no deja moverla: la barra se queda con la ventana
                val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                if (move) {
                    // Dentro de la pantalla: si el destino se sale, la ROM lo recorta y la ventana parece no obedecer
                    val l = (start.left + dx).coerceIn(0, maxOf(0, m.widthPixels - start.width()))
                    val t = (start.top + dy).coerceIn(dp(c, 34), maxOf(dp(c, 34), m.heightPixels - start.height()))
                    r.set(l, t, l + start.width(), t + start.height())
                } else {
                    val rr = maxOf(start.right + dx, start.left + dp(c, 180))
                    val bb = maxOf(start.bottom + dy, start.top + dp(c, 220))
                    r.set(start.left, start.top, rr, bb)
                }
                // Solo se mueve el marco fantasma (y la barra), a la velocidad de la pantalla. La ventana REAL no se toca
                // hasta soltar: así el gesto es instantáneo como el de las burbujas y no hay tirones por el binder.
                placeOnFrame(); true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                hideGhost()
                place()
                lockUntil = System.currentTimeMillis() + 900
                if (!moverBroken && !sameRect(r, start)) push()             // al soltar, la ventana real se pone en su sitio
                true
            }
            else -> false
        }
    }

    /**
     * Manda los límites a la ventana real, SIEMPRE en segundo plano. La cola tiene UN solo hueco con «gana el último»:
     * se escribe y se lee dentro del mismo monitor, así que un hilo nunca puede borrar lo que otro acababa de poner
     * (eso dejaba la ventana donde estaba y parecía que no respondía). Solo se envía el pedido final: el marco fantasma
     * ya ha dado la fluidez durante el arrastre, y esperar a un «deslizamiento» de varios pasos alargaba la respuesta.
     */
    private fun push() {
        val c = ctx ?: return
        synchronized(qLock) { qLast = Rect(r); if (qRunning) return; qRunning = true }
        val gen = generation
        val usePriv = priv; val id = taskId; val pk = pkgName; val cu = cloneUser; val uidN = userIdN
        // Ritmo mínimo entre comandos: ~45 ms con privilegios (van por el shell root vivo) o ~240 ms sin ellos
        // (mover sin privilegios implica volver a lanzar la ventana, que ya tarda más que eso).
        val gap = if (usePriv && id.isNotEmpty()) 45L else 240L
        val from = Rect(start)                      // ventana real antes de este movimiento (para saber si obedeció)
        Thread {
            var last: Rect? = null
            try {
                while (true) {
                    if (generation != gen) break
                    var slot: Rect? = null
                    synchronized(qLock) {
                        val q = qLast
                        if (q != null) { qLast = null; slot = q }
                    }
                    val p = slot
                    if (p == null) {
                        // Hueco vacío: se cierra el hilo, pero si entre medias alguien escribió, se vuelve a mirar
                        var quit = false
                        synchronized(qLock) { if (qLast == null) { qRunning = false; quit = true } }
                        if (quit) break
                        continue
                    }
                    val t0 = SystemClock.uptimeMillis()
                    if (usePriv && id.isNotEmpty()) FloatingWindow.resizeTask(c, id, p) else {
                        // Algunas ROM aceptan cambiar el tamaño pero ignoran un movimiento con el mismo tamaño: alternar 1 px lo evita
                        val q = if (nudge) Rect(p).also { it.right += (nudgeTick++ and 1) } else p
                        FloatingWindow.reposition(c, pk, q, cu, uidN)
                    }
                    last = p
                    // Se descuenta lo que ya tardó el propio envío, para no sumar esperas de más
                    val wait = gap - (SystemClock.uptimeMillis() - t0)
                    if (wait > 0) try { Thread.sleep(wait) } catch (e: InterruptedException) { /* no corta: sale el hueco vacío */ }
                }
            } finally {
                synchronized(qLock) { if (generation == gen && qLast == null) qRunning = false }
            }
            last?.let { l -> bg.postDelayed({ verify(l, from, gen) }, 380) }
        }.start()
    }

    /** Suma de las diferencias de los cuatro bordes. */
    private fun edgeDist(a: Rect, b: Rect) =
        abs(a.left - b.left) + abs(a.top - b.top) + abs(a.right - b.right) + abs(a.bottom - b.bottom)

    /** Anota en «Último diagnóstico» (Ajustes → Ventana) lo que pidió la barra y lo que hizo la ventana real. */
    private fun note(line: String) {
        val c = ctx ?: return
        try { Prefs.setLastLog(c, Prefs.lastLog(c).trimEnd() + "\n" + line) } catch (e: Throwable) {}
    }

    /**
     * ¿La ventana real obedeció? Se compara con el punto de partida: si fue hacia lo pedido y quedó más cerca, obedece aunque la ROM
     * haya recortado el destino (p. ej. para que quepa en pantalla). Solo cuenta como «no se mueve» si casi no se movió.
     * Primer fallo: se reintenta pidiéndola con 1 px de diferencia; tras 3 fallos seguidos se avisa y se deja de intentar.
     */
    private fun verify(target: Rect, from: Rect, gen: Int) {
        if (generation != gen || dragging) return
        val svc = RyxAccessibility.instance ?: return
        val real = svc.windowBounds(pkgName) ?: return
        val tol = maxOf(24, target.width() / 20)
        val need = edgeDist(from, target)
        if (need <= 4 * tol) return                                   // movimiento diminuto: no se puede juzgar
        val got = edgeDist(from, real)
        val miss = edgeDist(real, target)
        val obeys = miss <= 4 * tol || (got * 2 >= need && miss < need)
        val ignored = got * 4 < need
        main.post {
            if (generation != gen) return@post
            val c = ctx
            if (obeys) {
                if (moverFails > 0 || nudge) note("Mover: la ventana real obedeció (pedí $target · antes $from · quedó $real · retoque de 1 px: ${if (nudge) "sí" else "no"}).")
                if (nudge && moverFails > 0 && c != null) Prefs.setFwMoveNudge(c, true)
                moverFails = 0; return@post
            }
            if (!ignored) return@post                                  // se movió a medias (animación lenta): no es un fallo
            moverFails++
            note("Mover: la ventana real NO obedeció (fallo $moverFails). Pedí $target · antes $from · quedó $real · retoque de 1 px: ${if (nudge) "sí" else "no"}.")
            if (!nudge && moverFails == 1) {
                // Primer fallo: mismo destino, pero con 1 px de diferencia de tamaño
                nudge = true
                r.set(target); start.set(real); lockUntil = System.currentTimeMillis() + 900
                place(); push()
                return@post
            }
            if (moverFails >= 3 && !moverBroken) {
                moverBroken = true
                r.set(real); place()
                if (c != null && !warned) {
                    warned = true
                    Toast.makeText(c, "Esta ROM no mueve la ventana real sin Shizuku. La barra se queda pegada a la ventana; " +
                        "cambia su tamaño en Ajustes → Ventana. (El detalle quedó en «Último diagnóstico».)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
