package com.dav.isla

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    private var priv = false
    @Volatile private var generation = 0
    @Volatile private var pending: Rect? = null
    @Volatile private var worker = false

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun show(c: Context, id: String, bounds: Rect, pkg: String = "") {
        val app = c.applicationContext
        main.post {
            if (!Settings.canDrawOverlays(app)) return@post
            hide()
            ctx = app; taskId = id; pkgName = pkg; priv = id.isNotEmpty() && Shell.privileged(app); r.set(bounds)
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w
            val gen = generation

            // Asa superior: arrastrar mueve la ventana; la X quita las asas
            val bar = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(app, 14).toFloat(); setColor(0xCC15171C.toInt()) }
                setPadding(dp(app, 14), dp(app, 2), dp(app, 4), dp(app, 2))
            }
            bar.addView(TextView(app).apply { text = "━━━  mover"; setTextColor(Color.WHITE); textSize = 11f })
            bar.addView(TextView(app).apply {
                text = "✕"; setTextColor(Color.WHITE); textSize = 13f
                setPadding(dp(app, 10), dp(app, 4), dp(app, 10), dp(app, 4))
                setOnClickListener { hide() }
            })
            val hw = dp(app, 130); val hh = dp(app, 30)
            hp = WindowManager.LayoutParams(hw, hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            // Esquina inferior derecha: arrastrar cambia el tamaño
            val g = TextView(app).apply {
                text = "⌟"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 20f
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC15171C.toInt()) }
            }
            val gs = dp(app, 34)
            gp = WindowManager.LayoutParams(gs, gs, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            bar.setOnTouchListener(drag(true)); g.setOnTouchListener(drag(false))
            handle = bar; grip = g
            place()
            try { w.addView(bar, hp); w.addView(g, gp) } catch (e: Exception) { hide(); return@post }
            if (priv) watchTask(app, id, gen)
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
        hp?.let { it.x = r.left + (r.width() - it.width) / 2; it.y = r.top + dp(c, 2) }
        gp?.let { it.x = r.right - it.width; it.y = r.bottom - it.height }
        try { handle?.let { wm?.updateViewLayout(it, hp) } } catch (e: Exception) {}
        try { grip?.let { wm?.updateViewLayout(it, gp) } } catch (e: Exception) {}
    }

    private fun drag(move: Boolean) = View.OnTouchListener { _, e ->
        val c = ctx ?: return@OnTouchListener false
        val m = c.resources.displayMetrics
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; start.set(r); true }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                if (move) {
                    val l = (start.left + dx).coerceIn(-start.width() + dp(c, 80), m.widthPixels - dp(c, 80))
                    val t = (start.top + dy).coerceIn(0, m.heightPixels - dp(c, 80))
                    r.set(l, t, l + start.width(), t + start.height())
                } else {
                    val rr = maxOf(start.right + dx, start.left + dp(c, 180))
                    val bb = maxOf(start.bottom + dy, start.top + dp(c, 220))
                    r.set(start.left, start.top, rr, bb)
                }
                place(); if (priv) push(c); true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
