package com.dav.isla

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator

/** Estilo "Liquid Glass" de HyperOS: fondo oliva con brillo, tarjetas translúcidas y bordes claros. */
object Glass {
    const val ACCENT = 0xFFC3D29A.toInt()      // verde claro (interruptores, seleccionados)
    const val ACCENT_DARK = 0xFF5F6B3E.toInt() // pastilla de la pestaña activa
    const val TEXT = 0xFFF4F5EE.toInt()
    const val MUTED = 0xFFB4B8A6.toInt()

    private fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

    fun page(c: Context): Drawable {
        val d = c.resources.displayMetrics
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF2D3124.toInt(), 0xFF1B1E15.toInt(), 0xFF12140E.toInt()))
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.92f, 0.42f)
            gradientRadius = d.widthPixels * 0.9f
            colors = intArrayOf(0x88808066.toInt(), 0x00808066)
        }
        val glow2 = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.05f, 0.02f)
            gradientRadius = d.widthPixels * 0.8f
            colors = intArrayOf(0x44707A55, 0x00707A55)
        }
        return LayerDrawable(arrayOf(base, glow, glow2))
    }

    fun card(c: Context, radiusDp: Float = 26f, strong: Boolean = false): Drawable {
        val r = dp(c, radiusDp)
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (strong) intArrayOf(0xB0444A38.toInt(), 0xCC1D2016.toInt())
            else intArrayOf(0x70434838, 0x99181A12.toInt())).apply { cornerRadius = r }
        val rim = GradientDrawable().apply {
            cornerRadius = r; setColor(0); setStroke(maxOf(1, dp(c, 1.1f).toInt()), 0x55E6EBD2)
        }
        val shine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x22FFFFFF, 0x00FFFFFF)).apply { cornerRadius = r }
        val l = LayerDrawable(arrayOf(body, shine, rim))
        val i = dp(c, 1f).toInt(); l.setLayerInset(1, i, i, i, i)
        return l
    }

    fun tile(c: Context, color: Int = 0xFF3A4030.toInt(), radiusDp: Float = 14f): Drawable =
        GradientDrawable().apply { cornerRadius = dp(c, radiusDp); setColor(color) }

    fun pill(c: Context, fill: Int, stroke: Int = 0, radiusDp: Float = 18f): Drawable =
        GradientDrawable().apply {
            cornerRadius = dp(c, radiusDp); setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, dp(c, 1f).toInt()), stroke)
        }

    fun icon(c: Context, name: String, color: Int): Drawable? {
        val id = c.resources.getIdentifier("ic_$name", "drawable", c.packageName)
        if (id == 0) return null
        return c.getDrawable(id)?.mutate()?.also { it.setTintList(ColorStateList.valueOf(color)) }
    }
}

/** Interruptor en píldora, como el de Smart Island: pista verde clara con perilla blanca. */
class GlassSwitch(c: Context) : View(c) {
    var checked = false
        set(v) { field = v; target(v, false) }
    var onChange: ((Boolean) -> Unit)? = null
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val d = c.resources.displayMetrics.density

    init {
        isClickable = true
        setOnClickListener { checked = !checked; onChange?.invoke(checked) }
    }

    private fun target(v: Boolean, anim: Boolean) {
        val to = if (v) 1f else 0f
        if (!isAttachedToWindow) { t = to; invalidate(); return }
        if (!anim) { ValueAnimator.ofFloat(t, to).apply { duration = 160; interpolator = DecelerateInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }; start() } }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((54 * d).toInt(), (32 * d).toInt())

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        p.color = blend(0xFF4A4F40.toInt(), Glass.ACCENT, t); p.style = Paint.Style.FILL
        cv.drawRoundRect(rect, h / 2, h / 2, p)
        val r = h / 2 - 4 * d
        val cx = h / 2 + (w - h) * t
        p.color = blend(0xFFB9BCAE.toInt(), 0xFFFFFFFF.toInt(), t)
        cv.drawCircle(cx, h / 2, r + (2 * d * t), p)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * f).toInt()
        return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
