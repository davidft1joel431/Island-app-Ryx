package com.dav.isla

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Muestra cómo se abre la ventana flotante: crece desde la isla (resorte), se desvanece o se desliza. */
class WindowPreview(c: Context) : View(c) {
    private var progress = 1f
    private var anim: ValueAnimator? = null
    private val dens = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val heightDp = 250f

    fun refresh() = invalidate()

    fun play() {
        anim?.cancel()
        progress = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (620 * Motion.factor(context)).toLong()
            interpolator = if (Prefs.fwAnim(context) == 0) Motion.spring() else DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (heightDp * dens).toInt())

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDraw(cv: Canvas) {
        val m = resources.displayMetrics
        val sw = minOf(m.widthPixels, m.heightPixels).toFloat(); val sh = maxOf(m.widthPixels, m.heightPixels).toFloat()
        val s = height / sh                     // escala: toda la pantalla cabe en la altura
        val pw = sw * s
        val x0 = (width - pw) / 2f
        fun u(dp: Float) = dp * dens * s

        // Pantalla
        r.set(x0, 0f, x0 + pw, height.toFloat())
        p.shader = LinearGradient(x0, 0f, x0 + pw, height.toFloat(),
            intArrayOf(0xFF3B5A7A.toInt(), 0xFF2A2F4A.toInt(), 0xFF4A2F3E.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(r, u(34f), u(34f), p)
        p.shader = RadialGradient(x0 + pw * 0.8f, height * 0.85f, pw, 0x55FF8A4C, 0x00FF8A4C, Shader.TileMode.CLAMP)
        cv.drawRoundRect(r, u(34f), u(34f), p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = u(2.5f); p.color = 0x66FFFFFF
        cv.drawRoundRect(r, u(34f), u(34f), p); p.style = Paint.Style.FILL

        // Isla en reposo
        val idleW = u(Prefs.idleWidthDp(context).toFloat()); val idleH = u(Prefs.heightDp(context).toFloat())
        val ix = x0 + pw / 2f + u(Prefs.xOffsetDp(context).toFloat()); val iy = u(Prefs.yOffsetDp(context).toFloat())
        val island = RectF(ix - idleW / 2f, iy, ix + idleW / 2f, iy + idleH)
        p.color = 0xFF000000.toInt(); cv.drawRoundRect(island, idleH / 2f, idleH / 2f, p)

        // Ventana
        val b = FloatingWindow.bounds(context)
        val target = RectF(x0 + b.left * s, b.top * s, x0 + b.right * s, b.bottom * s)
        val t = progress
        val win = RectF()
        var alpha = 1f
        when (Prefs.fwAnim(context)) {
            0 -> {
                win.set(lerp(island.left, target.left, t), lerp(island.top, target.top, t),
                    lerp(island.right, target.right, t), lerp(island.bottom, target.bottom, t))
                alpha = (t * 3f).coerceIn(0f, 1f)
            }
            1 -> { win.set(target); alpha = t.coerceIn(0f, 1f) }
            else -> {
                val dy = -(target.height() * 0.35f) * (1f - t)
                win.set(target.left, target.top + dy, target.right, target.bottom + dy); alpha = t.coerceIn(0f, 1f)
            }
        }
        val rad = u(26f) * (0.4f + 0.6f * t.coerceIn(0f, 1f))
        p.color = 0xFF1C1F27.toInt(); p.alpha = (255 * alpha).toInt()
        cv.drawRoundRect(win, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1f, u(1.2f)); p.color = 0x66FFFFFF; p.alpha = (255 * alpha).toInt()
        cv.drawRoundRect(win, rad, rad, p); p.style = Paint.Style.FILL
        // Barra de arrastre y contenido de ejemplo
        if (win.width() > u(80f)) {
            p.color = 0x88FFFFFF.toInt(); p.alpha = (140 * alpha).toInt()
            cv.drawRoundRect(win.centerX() - u(22f), win.top + u(8f), win.centerX() + u(22f), win.top + u(12f), u(2f), u(2f), p)
            p.color = Glass.ACCENT; p.alpha = (255 * alpha).toInt()
            cv.drawCircle(win.left + u(26f), win.top + u(40f), u(14f), p)
            p.color = 0x55FFFFFF; p.alpha = (140 * alpha).toInt()
            cv.drawRoundRect(win.left + u(48f), win.top + u(32f), win.right - u(24f), win.top + u(38f), u(3f), u(3f), p)
            cv.drawRoundRect(win.left + u(48f), win.top + u(44f), win.left + u(48f) + win.width() * 0.4f, win.top + u(50f), u(3f), u(3f), p)
            cv.drawRoundRect(win.left + u(16f), win.top + u(68f), win.right - u(16f), win.top + u(120f), u(14f), u(14f), p)
        }
        p.alpha = 255
    }
}
