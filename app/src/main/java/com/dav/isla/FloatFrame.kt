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
import android.provider.Settings
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
 * SEGUIMIENTO REAL: la fuente de verdad es la ventana real que ve accesibilidad. El servicio avisa en cada cambio de
 * ventanas (RyxAccessibility → [onWindowsChanged]) y, además, hay un sondeo de respaldo cada 400 ms. La barra se coloca
 * siempre sobre los límites reales (no sobre los pedidos), así que también sigue a la ventana si la mueve el sistema.
 *
 * MOVER / REDIMENSIONAR DE VERDAD: mientras arrastras, los nuevos límites se mandan a la ventana real cada ~60 ms
 * (solo se envía el último). Con Shizuku/root ya activos se usa `cmd activity task resize`; sin ellos, se vuelve a pedir
 * la ventana a Android con los nuevos límites (misma variante que la abrió, sin ventana nueva). Después de cada envío se
 * LEE la ventana real: si la ROM no la movió, tras 3 fallos seguidos se avisa y la barra deja de fingir que la mueve.
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
    private val r = Rect()          // lo que se dibuja: los límites reales (o los del dedo mientras arrastras)
    private val start = Rect()
    private var downX = 0f
    private var downY = 0f
    private var taskId = ""
    private var pkgName = ""
    private var title = ""
    private var priv = false

    @Volatile private var generation = 0
    @Volatile private var lockUntil = 0L
    @Volatile private var dragging = false
    @Volatile private var pending: Rect? = null
    @Volatile private var worker = false
    @Volatile private var syncQueued = false
    private var misses = 0
    private var fullCount = 0
    private var moverFails = 0
    private var moverBroken = false
    private var warned = false

    private const val OVL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun show(c: Context, id: String, bounds: Rect, pkg: String = "", label: String = "") {
        val app = c.applicationContext
        main.post {
            if (!Settings.canDrawOverlays(app)) return@post
            hide()
            ctx = app; taskId = id; pkgName = pkg; title = label; priv = false; r.set(bounds)
            misses = 0; fullCount = 0; moverFails = 0; moverBroken = false; warned = false; lockUntil = 0L
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
                maxLines = 1; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
            place()
            try { w.addView(bar, hp); w.addView(g, gp) } catch (e: Exception) { hide(); return@post }

            if (pkg.isNotEmpty() && RyxAccessibility.instance != null) {
                bg.postDelayed({ syncNow(gen) }, 250)            // primera lectura: se corrige a los límites reales
                bg.postDelayed(pollRun(gen), 650)                // respaldo si la ROM no manda el evento de cambio
            } else main.postDelayed({ if (generation == gen) hide() }, 120_000L)   // sin verificación: se quitan solas a los 2 min

            // Con Shizuku/root ya activos se busca la tarea para moverla de verdad (sin ellos se usa el relanzado)
            if (pkg.isNotEmpty()) Thread {
                try {
                    if (Shell.privileged(app)) {
                        val tid = if (id.isNotEmpty()) id else FloatingWindow.findTaskId(app, pkg)
                        if (tid.isNotEmpty() && generation == gen) { taskId = tid; priv = true }
                    }
                } catch (e: Throwable) {}
            }.start()
        }
    }

    fun hide() {
        generation++
        bg.removeCallbacksAndMessages(null)
        pending = null; syncQueued = false; dragging = false
        val w = wm
        try { handle?.let { w?.removeView(it) } } catch (e: Exception) {}
        try { grip?.let { w?.removeView(it) } } catch (e: Exception) {}
        handle = null; grip = null
    }

    private fun place() {
        val c = ctx ?: return
        val sh = c.resources.displayMetrics.heightPixels
        hp?.let { it.width = r.width(); it.x = r.left; it.y = if (r.top >= it.height) r.top - it.height else r.top }
        gp?.let { it.width = r.width(); it.x = r.left; it.y = if (r.bottom + it.height <= sh) r.bottom else r.bottom - it.height }
        try { handle?.let { wm?.updateViewLayout(it, hp) } } catch (e: Exception) {}
        try { grip?.let { wm?.updateViewLayout(it, gp) } } catch (e: Exception) {}
    }

    private fun WindowManager.LayoutParams.cutoutAlways() {
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun sameRect(a: Rect, b: Rect, tol: Int = 3) =
        abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol && abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol

    // ───────────────────────────── Seguimiento real ─────────────────────────────

    /** Lo llama el servicio de accesibilidad en cada cambio de ventanas. Se junta en una sola lectura cada ~12 ms. */
    fun onWindowsChanged() {
        if (handle == null || syncQueued) return
        syncQueued = true
        val gen = generation
        bg.postDelayed({ syncQueued = false; syncNow(gen) }, 12)
    }

    private fun pollRun(gen: Int): Runnable = object : Runnable {
        override fun run() {
            if (generation != gen || handle == null) return
            syncNow(gen)
            bg.postDelayed(this, 400)
        }
    }

    /** Lee la ventana real (hilo de fondo) y la aplica en el hilo principal. */
    private fun syncNow(gen: Int) {
        if (generation != gen) return
        val svc = RyxAccessibility.instance ?: return
        val pkg = pkgName
        if (pkg.isEmpty()) return
        val real = svc.windowBounds(pkg)
        main.post { applyReal(real, gen) }
    }

    private fun applyReal(real: Rect?, gen: Int) {
        if (generation != gen || handle == null) return
        val c = ctx ?: return
        if (real == null) { if (++misses >= 6) hide(); return }       // la ventana ya no existe → fuera las asas
        misses = 0
        val m = c.resources.displayMetrics
        val full = real.width() >= m.widthPixels * 0.97f && real.height() >= m.heightPixels * 0.88f
        if (full) { if (++fullCount >= 3) hide(); return }            // pasó a pantalla completa
        fullCount = 0
        if (dragging) return                                          // mientras arrastras manda tu dedo
        if (System.currentTimeMillis() < lockUntil) return            // se espera a que la ventana alcance lo pedido
        if (!sameRect(real, r)) { r.set(real); place() }
    }

    // ─────────────────────────── Mover y cambiar tamaño ───────────────────────────

    private fun drag(move: Boolean) = View.OnTouchListener { _, e ->
        val c = ctx ?: return@OnTouchListener false
        val m = c.resources.displayMetrics
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; start.set(r); dragging = true; true }
            MotionEvent.ACTION_MOVE -> {
                if (moverBroken) return@OnTouchListener true           // esta ROM no deja moverla: la barra se queda con la ventana
                val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                if (move) {
                    val l = (start.left + dx).coerceIn(-start.width() + dp(c, 80), m.widthPixels - dp(c, 80))
                    val t = (start.top + dy).coerceIn(dp(c, 34), m.heightPixels - dp(c, 80))
                    r.set(l, t, l + start.width(), t + start.height())
                } else {
                    val rr = maxOf(start.right + dx, start.left + dp(c, 180))
                    val bb = maxOf(start.bottom + dy, start.top + dp(c, 220))
                    r.set(start.left, start.top, rr, bb)
                }
                place(); push(); true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                lockUntil = System.currentTimeMillis() + 900
                if (!moverBroken && !sameRect(r, start)) push()
                true
            }
            else -> false
        }
    }

    /** Manda los límites a la ventana real. Si llegan varios movimientos seguidos, solo se envía el último. */
    private fun push() {
        pending = Rect(r)
        if (worker) return
        val c = ctx ?: return
        worker = true
        val gen = generation
        val usePriv = priv; val id = taskId; val pk = pkgName
        Thread {
            var last: Rect? = null
            try {
                while (generation == gen) {
                    val p = pending ?: break
                    pending = null
                    if (usePriv && id.isNotEmpty()) FloatingWindow.resizeTask(c, id, p) else FloatingWindow.reposition(c, pk, p)
                    last = p
                    try { Thread.sleep(if (usePriv) 20 else 60) } catch (e: InterruptedException) { break }
                }
            } finally { worker = false }
            last?.let { l -> bg.postDelayed({ verify(l, gen) }, 380) }
        }.start()
    }

    /** ¿La ventana real llegó a lo pedido? Tras 3 fallos seguidos se avisa y se deja de intentar mover. */
    private fun verify(target: Rect, gen: Int) {
        if (generation != gen || dragging) return
        val svc = RyxAccessibility.instance ?: return
        val real = svc.windowBounds(pkgName) ?: return
        val ok = sameRect(real, target, maxOf(24, target.width() / 20))
        main.post {
            if (generation != gen) return@post
            if (ok) { moverFails = 0; return@post }
            if (++moverFails >= 3 && !moverBroken) {
                moverBroken = true
                r.set(real); place()
                val c = ctx
                if (c != null && !warned) {
                    warned = true
                    Toast.makeText(c, "Esta ROM no mueve la ventana real sin Shizuku. La barra se queda pegada a la ventana; " +
                        "cambia su tamaño en Ajustes → Ventana.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
