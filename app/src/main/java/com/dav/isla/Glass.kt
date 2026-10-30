package com.dav.isla

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
    /** Temas oscuros con acentos personalizables; el tema amarillo es el predeterminado de Ryx. */
    val THEMES = listOf(
        AppTheme("yellow", "Miel dorada", 0xFFFFC857.toInt(), 0xFFF5A623.toInt(), 0xFFFF7B32.toInt()),
        AppTheme("onyx", "Ónix", 0xFF6366F1.toInt(), 0x1A6366F1, 0x0F4C86FF),   // índigo profundo
        AppTheme("slate", "Pizarra", 0xFF94A3B8.toInt(), 0x1594A3B8, 0x0E3B535C), // gris azulado
        AppTheme("emerald", "Esmeralda", 0xFF10B981.toInt(), 0x1510B981, 0x0B0F3B33), // verde esmeralda
        AppTheme("amber", "Ámbar tenue", 0xFFC0A060.toInt(), 0x12C0A060, 0x0A1A2A3A), // ámbar suave
        AppTheme("ruby", "Rubí", 0xFFEF4444.toInt(), 0x18EF4444, 0x0E2A2A3A), // rojo suave
        AppTheme("neon", "Neón azul", 0xFF06B6D4.toInt(), 0x1A06B6D4, 0x0B1A2A4A), // cian neón
        AppTheme("void", "Vacío", 0xFF8B5CF6.toInt(), 0x188B5CF6, 0x0C1A2A4A), // violeta profundo
        AppTheme("graphite", "Grafito", 0xFF6B7280.toInt(), 0x106B7280, 0x08151D25) // gris neutro
    )
    var theme: AppTheme = THEMES[0]
        private set
    var ACCENT: Int = THEMES[0].accent
        private set

    // Texto crema sobre superficies oscuras cálidas.
    const val TEXT = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFF9CA3AF.toInt()
    const val FAINT = 0xFF6B7280.toInt()
    // Superficies grafito con un matiz cálido que deja respirar el acento dorado.
    const val SURFACE = 0xFF0D0C09.toInt()      // fondo principal casi negro
    const val SURFACE_HI = 0xFF15130E.toInt()   // superficie elevada
    const val SURFACE_TOP = 0xFF1E1A11.toInt()  // superficie aún más elevada
    /** Borde fino tenue (8 % de blanco) para tarjetas y controles minimalistas. */
    const val HAIR = 0x14FFFFFF
    /** Borde algo más visible para elementos elevados. */
    const val EDGE = 0x2AFFFFFF
    /** Verde de estado (concedido / activo). */
    const val GREEN = 0xFF34D399.toInt()
    /** Rojo de estado (apagado / error). */
    const val RED = 0xFFF87171.toInt()
    /** Ámbar de advertencia suave. */
    const val AMBER = 0xFFFBBF24.toInt()

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

    /** Fondo de la app: negro profundo con resplandores sutiles del color del tema. */
    fun page(c: Context): Drawable {
        val d = c.resources.displayMetrics
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(SURFACE_TOP, SURFACE, 0xFF080704.toInt()))
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.9f, 0.0f)
            gradientRadius = d.widthPixels * 1.05f
            colors = intArrayOf(withA(theme.glow1, 0x2E), withA(theme.glow1, 0x00))
        }
        val glow2 = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.0f, 0.92f)
            gradientRadius = d.widthPixels * 0.95f
            colors = intArrayOf(withA(theme.glow2, 0x1C), withA(theme.glow2, 0x00))
        }
        return LayerDrawable(arrayOf(base, glow, glow2))
    }

    /**
     * Tarjeta: superficie con un degradado vertical de tres pasos (luz arriba, sombra abajo) y borde fino.
     * [strong] la eleva un tono para separarla del fondo (barras y hojas).
     */
    fun card(c: Context, radiusDp: Float = 24f, strong: Boolean = false): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (strong) intArrayOf(SURFACE_TOP, SURFACE_HI, SURFACE)
            else intArrayOf(SURFACE_HI, SURFACE, 0xFF0A0906.toInt())).apply {
            cornerRadius = dp(c, radiusDp); setStroke(maxOf(1, dp(c, 1f).toInt()), HAIR)
        }

    /** Tarjeta anidada: un tono por encima de [card], para filas dentro de una tarjeta. */
    fun innerCard(c: Context, radiusDp: Float = 18f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(SURFACE_TOP, SURFACE_HI)).apply {
            cornerRadius = dp(c, radiusDp); setStroke(maxOf(1, dp(c, 1f).toInt()), HAIR)
        }

    /** Cabecera destacada: la tarjeta con un velo del color [tint] que se apaga hacia el fondo. */
    fun hero(c: Context, tint: Int = ACCENT, radiusDp: Float = 28f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(withA(tint, 0x30), withA(tint, 0x0F), SURFACE_TOP, SURFACE)).apply {
            cornerRadius = dp(c, radiusDp)
            setStroke(maxOf(1, dp(c, 1f).toInt()), withA(tint, 0x3C))
        }

    /** Fondo opaco para diálogos: el contenido de atrás no se mezcla con el texto. */
    fun solid(c: Context, radiusDp: Float = 24f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(SURFACE_TOP, SURFACE_HI, SURFACE)).apply {
            cornerRadius = dp(c, radiusDp); setStroke(maxOf(1, dp(c, 1f).toInt()), EDGE)
        }

    /** Hoja inferior: solo las esquinas de arriba son redondas. */
    fun sheet(c: Context, radiusDp: Float = 28f): Drawable {
        val r = dp(c, radiusDp)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(SURFACE_TOP, SURFACE_HI, SURFACE)).apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(maxOf(1, dp(c, 1f).toInt()), EDGE)
        }
    }

    fun tile(c: Context, color: Int = SURFACE_TOP, radiusDp: Float = 13f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(color, 0.06f), color, darken(color, 0.04f))).apply {
            cornerRadius = dp(c, radiusDp)
            setStroke(maxOf(1, dp(c, 1f).toInt()), 0x16FFFFFF)
        }

    fun pill(c: Context, fill: Int, stroke: Int = 0, radiusDp: Float = 16f): Drawable =
        GradientDrawable().apply {
            cornerRadius = dp(c, radiusDp); setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, dp(c, 1f).toInt()), stroke)
        }

    /**
     * Superficie de control tipo pastilla: degradado vertical corto (luz arriba, sombra abajo)
     * y borde fino. Da volumen sin sombras ni desenfoques del sistema.
     */
    fun control(c: Context, fill: Int, stroke: Int = HAIR, radiusDp: Float = 16f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(fill, 0.07f), fill, darken(fill, 0.05f))).apply {
            cornerRadius = dp(c, radiusDp)
            if (stroke != 0) setStroke(maxOf(1, dp(c, 1f).toInt()), stroke)
        }

    /** Fila de ajuste: una pastilla casi transparente, con apenas un velo y un borde de 1 dp. */
    fun row(c: Context, radiusDp: Float = 16f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(HAIR, withA(0xFFFFFFFF, 0x08), withA(0x000000, 0x10))).apply {
            cornerRadius = dp(c, radiusDp)
            setStroke(maxOf(1, dp(c, 1f).toInt()), HAIR)
        }

    /** Botón lleno: degradado del acento (más claro arriba) y sin borde. Da volumen sin sombra. */
    fun accentFill(c: Context, radiusDp: Float = 14f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(ACCENT, 0.12f), ACCENT, darken(ACCENT, 0.10f))).apply {
            cornerRadius = dp(c, radiusDp)
        }

    /** Aclara [color] mezclándolo con blanco ([amount] 0-1). Conserva el alfa. */
    fun lighten(color: Int, amount: Float): Int = blendColor(color, 0xFFFFFFFF.toInt(), amount)

    /** Oscurece [color] mezclándolo con negro ([amount] 0-1). Conserva el alfa. */
    fun darken(color: Int, amount: Float): Int = blendColor(color, 0xFF000000.toInt(), amount)

    private fun blendColor(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s and 255) * (1f - t)) + ((b shr s and 255) * t)).toInt().coerceIn(0, 255)
        return (a ushr 24 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Animación de pulsación discreta para tarjetas y botones: se hunde un poco al tocar.
     * Devuelve false para no consumir el evento; el clic normal sigue funcionando.
     */
    fun press(v: View, scale: Float = 0.975f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale)
                    .setDuration(90).setInterpolator(DecelerateInterpolator(1.5f)).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140)
                        .setInterpolator(DecelerateInterpolator(1.5f)).start()
            }
            false
        }
    }

    // ---- Caché de dibujos ----
    // Cada fila decorada creaba su propio «pill» y descodificaba su icono: en listas largas eso se nota al abrir.
    // Se guardan por (relleno, borde, radio) y por (nombre, color). Se comparte el dibujo, cuya matriz sigue siendo
    // la misma para todas las filas (nada se deforma), y se devuelve una copia solo cuando la vista necesita una.
    private val pillCache = HashMap<String, Drawable>()
    private val controlCache = HashMap<String, Drawable>()
    private val iconCache = HashMap<String, Drawable?>()

    /**
     * Igual que [pill] pero reutilizando el «molde»: se guarda por (relleno, borde, radio) y se devuelve una
     * COPIA nueva (un dibujo solo puede servir a una vista a la vez). Evita recalcular el degradado en cada fila.
     */
    fun pillCached(c: Context, fill: Int, stroke: Int = 0, radiusDp: Float = 16f): Drawable {
        val key = "$fill|$stroke|$radiusDp"
        val base = pillCache.getOrPut(key) { pill(c, fill, stroke, radiusDp) }
        return base.constantState?.newDrawable() ?: pill(c, fill, stroke, radiusDp)
    }

    /** Igual que [control] pero reutilizando el molde; para listas largas de pastillas. */
    fun controlCached(c: Context, fill: Int, stroke: Int = HAIR, radiusDp: Float = 16f): Drawable {
        val key = "$fill|$stroke|$radiusDp"
        val base = controlCache.getOrPut(key) { control(c, fill, stroke, radiusDp) }
        return base.constantState?.newDrawable() ?: control(c, fill, stroke, radiusDp)
    }

    /** Igual que [chip] pero reutilizando el molde: úsalo en listas largas (eventos, temas, formas…). */
    fun chipCached(c: Context, on: Boolean, radiusDp: Float = 16f): Drawable =
        controlCached(c, if (on) accentA(0x2A) else SURFACE_HI, if (on) accentA(0xAA) else HAIR, radiusDp)

    /** Casilla seleccionable (estilos, temas, formas…): con el acento si está elegida y neutra si no. */
    fun chip(c: Context, on: Boolean, radiusDp: Float = 16f): Drawable =
        control(c, if (on) accentA(0x2A) else SURFACE_HI, if (on) accentA(0xAA) else HAIR, radiusDp)

    /** Añade el destello al tocar (ondulación) sobre [base]. */
    fun ripple(c: Context, base: Drawable, radiusDp: Float): Drawable {
        val mask = GradientDrawable().apply { cornerRadius = dp(c, radiusDp); setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), base, mask)
    }

    fun icon(c: Context, name: String, color: Int): Drawable? {
        val key = "$name|$color"
        val base = iconCache.getOrPut(key) {
            val id = c.resources.getIdentifier("ic_$name", "drawable", c.packageName)
            if (id == 0) null
            else c.getDrawable(id)?.mutate()?.also { it.setTintList(ColorStateList.valueOf(color)) }
        } ?: return null
        // Copia nueva por vista: un dibujo no puede servir a dos vistas a la vez (el tinte y las ondulaciones chocan).
        return base.constantState?.newDrawable()?.mutate()?.also { it.setTintList(ColorStateList.valueOf(color)) } ?: base
    }

    /** Otra instancia del mismo icono (para una vista que pueda teñirlo o animarlo aparte). */
    fun iconCopy(c: Context, name: String, color: Int): Drawable? = icon(c, name, color)
}

/** Interruptor moderno: pista del acento con perilla blanca grande al encender; apagado, pista oscura con borde y perilla pequeña. */
class GlassSwitch(c: Context) : View(c) {
    /**
     * Estado del interruptor. Al escribirlo se dibuja al momento (sin depender de que la vista ya esté
     * en pantalla): antes, si se asignaba antes de estar «laid out», el cambio no se veía hasta el
     * siguiente redibujado y el interruptor parecía quedarse quieto.
     */
    var checked = false
        set(v) {
            if (field != v) {
                field = v
                contentDescription = if (v) "Activado" else "Desactivado"
                animateTo(v)
            }
        }
    var onChange: ((Boolean) -> Unit)? = null
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val d = c.resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Desactivado"
        // Realce pulsable: deja claro que se puede tocar aunque esté apagado.
        foreground = null
        setOnClickListener { toggle() }
    }

    /** Cambia el valor como si el usuario hubiera tocado el interruptor. */
    fun toggle() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        checked = !checked
        onChange?.invoke(checked)
    }

    /** Pone el valor sin avisar ([onChange] no se dispara). Útil al abrir un panel para reflejar el ajuste guardado. */
    fun setCheckedSilently(v: Boolean) { checked = v }

    private fun animateTo(v: Boolean) {
        val to = if (v) 1f else 0f
        anim?.cancel()
        // Sin animación cuando la vista todavía no está en pantalla: el valor se aplica ya y se ve al primer dibujado.
        if (!isAttachedToWindow || !isLaidOut) { t = to; invalidate(); return }
        // Cambio inmediato del color base; la animación solo suaviza el recorrido de la perilla.
        invalidate()
        anim = ValueAnimator.ofFloat(t, to).apply {
            duration = 210; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((50 * d).toInt(), (30 * d).toInt())

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2
        // Pista con degradado: neutra apagada y del acento encendida. Nada de sombras externas:
        // el interruptor vive dentro de tarjetas y debe verse limpio en cualquier ROM.
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            blend(0xFF333B48.toInt(), Glass.lighten(Glass.ACCENT, 0.10f), t),
            blend(0xFF1C212A.toInt(), Glass.darken(Glass.ACCENT, 0.12f), t),
            Shader.TileMode.CLAMP
        )
        cv.drawRoundRect(0f, 0f, w, h, r, r, p)
        p.shader = null
        // Brillo interior en la mitad superior: el cristal se lee sin desenfocar nada.
        p.color = ((0x1E * (1f - 0.30f * t)).toInt() shl 24) or 0x00FFFFFF
        cv.drawRoundRect(1.2f * d, 1.2f * d, w - 1.2f * d, h * 0.52f, r, r, p)
        // Borde fino, algo más marcado al encender para separar la pista del fondo.
        val sw = maxOf(1f, d)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw
        p.color = (((0x33 + 0x33 * t).toInt()) shl 24) or 0x00FFFFFF
        rect.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        cv.drawRoundRect(rect, r, r, p)
        p.style = Paint.Style.FILL
        // Perilla: pequeña y gris apagada; grande y blanca encendida, con una sombra mínima debajo.
        val rOff = 8.5f * d; val rOn = 11.5f * d
        val kr = rOff + (rOn - rOff) * t
        val cx = r + (w - h) * t
        p.color = 0x50000000
        cv.drawCircle(cx, h / 2 + 1.1f * d, kr, p)
        p.color = blend(0xFFA7B0C0.toInt(), 0xFFFFFFFF.toInt(), t)
        cv.drawCircle(cx, h / 2, kr, p)
        // Punto de luz en la perilla encendida.
        if (t > 0.5f) {
            p.color = ((0x30 * (t - 0.5f) * 2f).toInt() shl 24) or 0x00FFFFFF
            cv.drawCircle(cx - kr * 0.28f, h / 2 - kr * 0.3f, kr * 0.55f, p)
        }
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

/**
 * Contenedor con «arrastra hacia abajo para actualizar». Cuando la lista ya está arriba del todo y el dedo
 * baja lo suficiente, dispara [onRefresh] y muestra una pastilla con «Actualizando…» hasta que se llama a
 * [done]. No depende de androidx: es todo Android de base, como el resto de la interfaz.
 *
 * Es un FrameLayout con un ScrollView dentro; las vistas que se le añadan van al ScrollView.
 */
class PullRefreshScroll(c: Context) : android.widget.FrameLayout(c) {
    /** Se llama al soltar, si el arrastre pasó del umbral. Debe terminar llamando a [done]. */
    var onRefresh: (() -> Unit)? = null
    /** Texto que se muestra mientras se recarga. */
    var refreshHint: String = "Actualizando…"

    private val density = resources.displayMetrics.density
    private val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop
    private val threshold = (72 * density).toInt()
    private var y0 = 0f
    private var pulling = false
    private var offset = 0f
    private var refreshing = false
    private lateinit var inner: android.widget.ScrollView
    private var indicator: android.widget.TextView? = null

    init {
        inner = android.widget.ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(inner)
    }

    /** Las vistas se añaden al ScrollView interno, igual que a un ScrollView normal. */
    override fun addView(child: View) {
        if (!this::inner.isInitialized || child === inner) super.addView(child) else inner.addView(child)
    }

    /** Devuelve la posición actual de scroll Y del ScrollView interno. */
    fun scrollY(): Int = inner.scrollY

    /** Hace scroll a una posición del ScrollView interno. */
    override fun scrollTo(x: Int, y: Int) = inner.scrollTo(x, y)

    /** Termina la recarga y esconde el indicador. */
    fun done() {
        refreshing = false
        post {
            indicator?.animate()?.alpha(0f)?.setDuration(140)?.withEndAction { indicator?.visibility = View.GONE }?.start()
            inner.smoothScrollTo(0, 0)
        }
    }

    private fun ensureIndicator() {
        if (indicator != null) return
        val tv = android.widget.TextView(context).apply {
            text = refreshHint
            textSize = 12.5f
            setTextColor(Glass.TEXT)
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setPadding((14 * density).toInt(), (7 * density).toInt(), (14 * density).toInt(), (7 * density).toInt())
            background = Glass.pill(context, Glass.SURFACE_HI, Glass.HAIR, 14f)
            alpha = 0f
            visibility = View.GONE
        }
        addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL; topMargin = (14 * density).toInt() })
        indicator = tv
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { y0 = ev.rawY; pulling = false; offset = 0f }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (inner.scrollY <= 0 && !refreshing && ev.rawY - y0 > slop) { pulling = true; return true }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                if (pulling && !refreshing) {
                    offset = (ev.rawY - y0).coerceAtMost(threshold * 2f)
                    ensureIndicator()
                    indicator?.visibility = View.VISIBLE
                    indicator?.alpha = (offset / threshold).coerceIn(0f, 1f)
                    indicator?.text = if (offset >= threshold) "Suelta para actualizar" else refreshHint
                    inner.translationY = offset * 0.4f
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    inner.translationY = 0f
                    if (offset >= threshold && !refreshing) {
                        refreshing = true
                        ensureIndicator()
                        indicator?.visibility = View.VISIBLE
                        indicator?.alpha = 1f
                        indicator?.text = refreshHint
                        onRefresh?.invoke() ?: done()
                    } else indicator?.visibility = View.GONE
                    pulling = false
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}
