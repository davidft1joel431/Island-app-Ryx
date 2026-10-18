package com.dav.isla

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Asa propia de Ryx para mover y cambiar el tamaño de la ventana flotante.
 * Con Shizuku (o root si lo elegiste) mueve la ventana real; sin privilegios la vuelve a abrir en el nuevo sitio (experimental).
 */
object FloatFrame {
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var wm: WindowManager? = null
    private var handle: View? = null
    private var grip: View? = null
    private var hp: WindowManager.LayoutParams? = null
    private var gp: WindowManager.LayoutParams? = null
    private val r = Rect()
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

    private const val OVL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun show(c: Context, id: String, bounds: Rect, pkg: String = "", label: String = "") {
        val app = c.applicationContext
        main.post {
            if (!Settings.canDrawOverlays(app)) return@post
            hide()
            ctx = app; taskId = id; pkgName = pkg; title = label; priv = id.isNotEmpty() && Shell.privileged(app); r.set(bounds)
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w
            val gen = generation

            // Barra de título de Ryx, del ancho de la ventana y pegada encima: arrastrar la mueve; la X quita la barra
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
            val hh = dp(app, 32)
            hp = WindowManager.LayoutParams(r.width(), hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OVL_FLAGS,
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
            val gs = dp(app, 28)
            gp = WindowManager.LayoutParams(r.width(), gs, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OVL_FLAGS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; cutoutAlways() }
            bar.setOnTouchListener(drag(true)); g.setOnTouchListener(drag(false))
            handle = bar; grip = g
            place()
            try { w.addView(bar, hp); w.addView(g, gp) } catch (e: Exception) { hide(); return@post }
            if (priv) watchTask(app, id, gen)
            else if (pkg.isNotEmpty() && RyxAccessibility.instance != null) watchWindow(pkg, gen)
            else main.postDelayed({ if (generation == gen) hide() }, 120_000L)   // sin verificación: se quitan solas a los 2 min
        }
    }

    fun hide() {
        generation++
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

    private fun drag(move: Boolean) = View.OnTouchListener { _, e ->
        val c = ctx ?: return@OnTouchListener false
        val m = c.resources.displayMetrics
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; start.set(r); dragging = true; true }
            MotionEvent.ACTION_MOVE -> {
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
                place(); if (priv) push(c); true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; lockUntil = System.currentTimeMillis() + 1800
                if (priv) push(c)
                else if (pkgName.isNotEmpty() && (r.left != start.left || r.top != start.top || r.right != start.right || r.bottom != start.bottom)) {
                    val rr = Rect(r); val pk = pkgName
                    Thread { FloatingWindow.reposition(c, pk, rr) }.start()
                }
                true
            }
            else -> false
        }
    }

    /** Manda el tamaño a la ventana real; si llegan varios movimientos seguidos, solo se envía el último. */
    private fun push(c: Context) {
        pending = Rect(r)
        if (worker) return
        worker = true
        val id = taskId
        Thread {
            try {
                while (true) {
                    val p = pending ?: break
                    pending = null
                    FloatingWindow.resizeTask(c, id, p)
                }
            } finally { worker = false }
        }.start()
    }

    /** Sin privilegios: sigue por accesibilidad la posición REAL de la ventana (la barra se pega a ella) y se quita cuando la ventana desaparece. */
    private fun watchWindow(pkg: String, gen: Int) {
        Thread {
            var misses = 0
            try { Thread.sleep(1200) } catch (e: InterruptedException) { return@Thread }
            while (generation == gen) {
                val svc = RyxAccessibility.instance ?: break
                val real = svc.windowBounds(pkg)
                if (real == null) misses++ else {
                    misses = 0
                    val c = ctx
                    val full = c != null && real.width() >= c.resources.displayMetrics.widthPixels * 0.97f &&
                        real.height() >= c.resources.displayMetrics.heightPixels * 0.88f
                    if (!dragging && System.currentTimeMillis() > lockUntil && !full &&
                        (Math.abs(real.left - r.left) > 3 || Math.abs(real.top - r.top) > 3 ||
                            Math.abs(real.right - r.right) > 3 || Math.abs(real.bottom - r.bottom) > 3)) {
                        main.post { if (generation == gen) { r.set(real); place() } }
                    }
                }
                if (misses >= 4) { main.post { if (generation == gen) hide() }; return@Thread }
                try { Thread.sleep(350) } catch (e: InterruptedException) { return@Thread }
            }
        }.start()
    }

    /** Quita las asas cuando la ventana ya no existe. Solo se activa si se pudo ver la tarea al inicio. */
    private fun watchTask(c: Context, id: String, gen: Int) {
        Thread {
            fun count() = Shell.run(c, "dumpsys activity activities | grep -c \"#$id \"").second.trim().toIntOrNull() ?: -1
            if (count() <= 0) return@Thread
            var zeros = 0
            while (generation == gen) {
                try { Thread.sleep(3000) } catch (e: InterruptedException) { return@Thread }
                zeros = if (count() == 0) zeros + 1 else 0
                if (zeros >= 2) { main.post { if (generation == gen) hide() }; return@Thread }
            }
        }.start()
    }
}
