package com.dav.isla

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator

/**
 * Sistema de diseño de la app: superficies tonales oscuras con bordes finos, un solo acento y jerarquía clara.
 * Solo afecta a la interfaz de la app; la isla usa únicamente [ACCENT] e iconos.
 */
data class AppTheme(val id: String, val label: String, val accent: Int, val glow1: Int, val glow2: Int)

object Glass {
    val THEMES = listOf(
        AppTheme("yellow", "Amarillo", 0xFFFFC857.toInt(), 0x30FFC857, 0x26FF8A4C),
        AppTheme("ryx", "Naranja Ryx", 0xFFFF8A4C.toInt(), 0x30FF7A3D, 0x264F7CFF),
        AppTheme("ocean", "Océano", 0xFF4DA3FF.toInt(), 0x304DA3FF, 0x2600D4C8),
        AppTheme("forest", "Bosque", 0xFF4ADE80.toInt(), 0x3040D87A, 0x26F2C94C),
        AppTheme("violet", "Violeta", 0xFFB388FF.toInt(), 0x30B388FF, 0x264DA3FF),
        AppTheme("rose", "Rosa", 0xFFFF6B9A.toInt(), 0x30FF6B9A, 0x26FFB84D),
        AppTheme("mint", "Menta", 0xFF2EE6C5.toInt(), 0x302EE6C5, 0x264DA3FF),
        AppTheme("sunset", "Atardecer", 0xFFFF7A59.toInt(), 0x30FF7A59, 0x30B388FF),
        AppTheme("midnight", "Medianoche", 0xFF7C89FF.toInt(), 0x307C89FF, 0x2600D4C8)
    )
    var theme: AppTheme = THEMES[0]
        private set
    var ACCENT: Int = THEMES[0].accent
        private set

    // Texto
    const val TEXT = 0xFFF3F5F9.toInt()
    const val MUTED = 0xFF98A1B3.toInt()
    const val FAINT = 0xFF646D7F.toInt()
    // Superficies: de la tarjeta a lo que sobresale dentro de ella
    const val SURFACE = 0xFF13161C.toInt()
    const val SURFACE_HI = 0xFF1B1F27.toInt()
    const val SURFACE_TOP = 0xFF262B36.toInt()
    /** Borde fino (10 % de blanco) de tarjetas y controles. */
    const val HAIR = 0x1AFFFFFF

    fun applyTheme(c: Context) {
        theme = THEMES.firstOrNull { it.id == Prefs.appTheme(c) } ?: THEMES[0]
        ACCENT = theme.accent
    }

    /** [color] con una transparencia dada (0-255). */
    fun withA(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** El acento con una transparencia dada (0-255). */
    fun accentA(alpha: Int): Int = withA(ACCENT, alpha)

    /** Color de texto legible sobre [color] (oscuro o blanco, el de más contraste). */
    fun onAccent(color: Int = ACCENT): Int {
        fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * lin(color shr 16 and 255) + 0.7152 * lin(color shr 8 and 255) + 0.0722 * lin(color and 255)
        val vsWhite = 1.05 / (l + 0.05)
        val vsDark = (l + 0.05) / 0.054
        return if (vsDark >= vsWhite) 0xFF0B0D12.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

    /** Fondo de la app: casi negro con dos resplandores muy suaves del color del tema. */
    fun page(c: Context): Drawable {
        val d = c.resources.displayMetrics
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF0E1015.toInt(), 0xFF08090C.toInt()))
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.92f, 0.0f)
            gradientRadius = d.widthPixels * 1.0f
            colors = intArrayOf(withA(theme.glow1, 0x26), withA(theme.glow1, 0x00))
        }
        val glow2 = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.0f, 1.0f)
            gradientRadius = d.widthPixels * 0.9f
            colors = intArrayOf(withA(theme.glow2, 0x16), withA(theme.glow2, 0x00))
        }
        return LayerDrawable(arrayOf(base, glow, glow2))
    }

    /** Tarjeta: superficie sólida con un degradado casi imperceptible y un borde fino. [strong] = un tono más claro. */
    fun card(c: Context, radiusDp: Float = 24f, strong: Boolean = false): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (strong) intArrayOf(0xFF1E222B.toInt(), 0xFF181B22.toInt()) else intArrayOf(0xFF161920.toInt(), 0xFF12151B.toInt())).apply {
            cornerRadius = dp(c, radiusDp); setStroke(maxOf(1, dp(c, 1f).toInt()), HAIR)
        }

    /** Cabecera destacada: la tarjeta con un velo del color [tint] que se apaga hacia el fondo. */
    fun hero(c: Context, tint: Int = ACCENT, radiusDp: Float = 28f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(withA(tint, 0x3A), 0xFF14171D.toInt(), 0xFF12151B.toInt())).apply {
            cornerRadius = dp(c, radiusDp); setStroke(maxOf(1, dp(c, 1f).toInt()), withA(tint, 0x40))
        }

    /** Fondo opaco para diálogos: el contenido de atrás no se mezcla con el texto. */
    fun solid(c: Context, radiusDp: Float = 24f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1A1E26.toInt(), 0xFF14171D.toInt())).apply {
            cornerRadius = dp(c, radiusDp); setStroke(maxOf(1, dp(c, 1f).toInt()), 0x24FFFFFF)
        }

    /** Hoja inferior: solo las esquinas de arriba son redondas. */
    fun sheet(c: Context, radiusDp: Float = 28f): Drawable {
        val r = dp(c, radiusDp)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1B1F27.toInt(), 0xFF14171D.toInt())).apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(maxOf(1, dp(c, 1f).toInt()), 0x24FFFFFF)
        }
    }

    fun tile(c: Context, color: Int = SURFACE_TOP, radiusDp: Float = 13f): Drawable =
        GradientDrawable().apply { cornerRadius = dp(c, radiusDp); setColor(color) }

    fun pill(c: Context, fill: Int, stroke: Int = 0, radiusDp: Float = 16f): Drawable =
        GradientDrawable().apply {
            cornerRadius = dp(c, radiusDp); setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, dp(c, 1f).toInt()), stroke)
        }

    /** Casilla seleccionable (estilos, temas, formas…): con el acento si está elegida y neutra si no. */
    fun chip(c: Context, on: Boolean, radiusDp: Float = 16f): Drawable =
        pill(c, if (on) accentA(0x24) else SURFACE_HI, if (on) accentA(0x99) else HAIR, radiusDp)

    /** Añade el destello al tocar (ondulación) sobre [base]. */
    fun ripple(c: Context, base: Drawable, radiusDp: Float): Drawable {
        val mask = GradientDrawable().apply { cornerRadius = dp(c, radiusDp); setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), base, mask)
    }

    fun icon(c: Context, name: String, color: Int): Drawable? {
        val id = c.resources.getIdentifier("ic_$name", "drawable", c.packageName)
        if (id == 0) return null
        return c.getDrawable(id)?.mutate()?.also { it.setTintList(ColorStateList.valueOf(color)) }
    }
}

/** Interruptor moderno: pista del acento con perilla blanca grande al encender; apagado, pista oscura con borde y perilla pequeña. */
class GlassSwitch(c: Context) : View(c) {
    var checked = false
        set(v) { field = v; animateTo(v) }
    var onChange: ((Boolean) -> Unit)? = null
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val d = c.resources.displayMetrics.density

    init {
        isClickable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            checked = !checked; onChange?.invoke(checked)
        }
    }

    private fun animateTo(v: Boolean) {
        val to = if (v) 1f else 0f
        anim?.cancel()
        if (!isAttachedToWindow || !isLaidOut) { t = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(t, to).apply {
            duration = 200; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((52 * d).toInt(), (32 * d).toInt())

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Pista
        rect.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.color = blend(0xFF2A2F3A.toInt(), Glass.ACCENT, t)
        cv.drawRoundRect(rect, h / 2, h / 2, p)
        // Borde fino, solo apagado
        if (t < 1f) {
            val sw = maxOf(1f, d)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw
            p.color = (((1f - t) * 0x3A).toInt() shl 24) or 0x00FFFFFF
            rect.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
            cv.drawRoundRect(rect, h / 2, h / 2, p)
            p.style = Paint.Style.FILL
        }
        // Perilla: pequeña y gris apagada; grande y blanca encendida
        val rOff = 8 * d; val rOn = 12 * d
        val r = rOff + (rOn - rOff) * t
        val cx = h / 2 + (w - h) * t
        p.color = blend(0xFF98A1B3.toInt(), 0xFFFFFFFF.toInt(), t)
        cv.drawCircle(cx, h / 2, r, p)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * f).toInt()
        return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** ScrollView con altura máxima: la hoja inferior se ajusta a su contenido y, si es largo, se desplaza. */
class MaxHeightScroll(c: Context, private val maxH: Int) : android.widget.ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}
