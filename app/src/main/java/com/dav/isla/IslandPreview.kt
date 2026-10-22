package com.dav.isla

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * Vista previa en vivo, estilo editor de la isla de HyperOS: dibuja la parte superior
 * de ESTE teléfono (ancho real, barra de estado y recorte de cámara detectado) y la isla
 * con exactamente los valores guardados en Prefs.
 */
class IslandPreview(c: Context) : View(c) {

    var landscape = false
    var cutout: RectF? = null          // en píxeles reales de pantalla (puede ser null)
    var statusBarPx = 0f
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private val dens = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private val screenW: Float get() {
        val m = resources.displayMetrics
        return (if (landscape) maxOf(m.widthPixels, m.heightPixels) else minOf(m.widthPixels, m.heightPixels)).toFloat()
    }
    private val regionDp = 150f
    private val scale: Float get() = if (width > 0) width / screenW else 1f

    fun refresh() = invalidate()


    fun setExpanded(e: Boolean) = animateTo(if (e) 1f else 0f)

    private fun animateTo(t: Float, then: (() -> Unit)? = null) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, t).apply {
            duration = if (t >= progress) Motion.expandMs(context) else Motion.collapseMs(context)
            interpolator = if (t >= progress) Motion.spring() else Motion.smooth()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            if (then != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { then() }
            })
            start()
        }
    }

    /** Abre, espera y cierra: muestra la animación con la velocidad elegida. */
    fun play() { animateTo(1f) { postDelayed({ animateTo(0f) }, 900) } }

    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w)
        val hh = (regionDp * dens * (ww / screenW)).toInt()
        setMeasuredDimension(ww, hh)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Dibuja el fondo de la isla (degradado, brillo y borde) con una opacidad k. */
    private fun drawLook(cv: Canvas, sp: IslandLook.Spec, rad: Float, k: Float, s: Float) {
        if (k <= 0.01f) return
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, sp.top, sp.bottom, Shader.TileMode.CLAMP)
        p.alpha = (255 * k).toInt()
        cv.drawRoundRect(rect, rad, rad, p)
        if (sp.shine != 0) {
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, sp.shine, 0x00FFFFFF, Shader.TileMode.CLAMP)
            p.alpha = (255 * k).toInt()
            val i = dens * s
            cv.drawRoundRect(rect.left + i, rect.top + i, rect.right - i, rect.bottom - i, rad, rad, p)
        }
        p.shader = null
        if (sp.rimDp > 0f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1f, sp.rimDp * dens * s)
            p.color = sp.rim; p.alpha = ((sp.rim ushr 24) * k).toInt()
            cv.drawRoundRect(rect, rad, rad, p)
            p.style = Paint.Style.FILL
        }
        p.alpha = 255
    }

    override fun onDraw(cv: Canvas) {
        val s = scale
        val w = width.toFloat(); val h = height.toFloat()
        fun u(dp: Float) = dp * dens * s          // dp reales -> px de la vista previa
        val ctx = context

        // Pantalla con fondo de ejemplo (para que se note el cristal)
        val r = u(34f)
        path.reset(); rect.set(0f, 0f, w, h + r)
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        cv.save(); cv.clipPath(path)
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF46705A.toInt(), 0xFF22364A.toInt(), 0xFF3A2D52.toInt()), null, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL; cv.drawRect(0f, 0f, w, h + r, p)
        p.shader = RadialGradient(w * 0.8f, h * 0.9f, w * 0.6f, 0x66FFD6A0, 0x00FFD6A0, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h + r, p); p.shader = null

        // Barra de estado
        val sbH = if (statusBarPx > 0) statusBarPx * s else u(26f)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = u(13f); p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        cv.drawText("9:41", u(24f), sbH * 0.5f + p.textSize * 0.35f, p)
        p.color = 0xFFFFFFFF.toInt()
        rect.set(w - u(30f), sbH * 0.5f - u(4f), w - u(14f), sbH * 0.5f + u(4f)); cv.drawRoundRect(rect, u(2f), u(2f), p)
        cv.drawCircle(w - u(40f), sbH * 0.5f, u(4f), p)
        cv.drawCircle(w - u(54f), sbH * 0.5f, u(4f), p)

        // Recorte de cámara real del teléfono
        val cr = cutout
        p.color = 0xFF000000.toInt()
        if (cr != null) {
            rect.set(cr.left * s, cr.top * s, cr.right * s, cr.bottom * s)
            cv.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, p)
        } else cv.drawCircle(w / 2f, u(18f), u(7f), p)

        // Isla con los valores guardados
        val st = Styles.byId(Prefs.style(ctx))
        val screenWdp = screenW / dens
        // El mismo margen mínimo que usa la isla real: 12 dp aunque el ajuste esté en 0.
        val margin = maxOf(Prefs.sideMarginDp(ctx), 12)
        val expDp = minOf(screenWdp - 2 * margin, IslandGeometry.expandedWidthDp(ctx).toFloat())
        val wdp = lerp(Prefs.idleWidthDp(ctx).toFloat(), expDp, progress)
        val hdp = lerp(Prefs.heightDp(ctx).toFloat(), IslandGeometry.EXPANDED_HEIGHT_DP.toFloat(), progress)
        val cx = w / 2f + u(Prefs.xOffsetDp(ctx).toFloat())
        val top = u(Prefs.yOffsetDp(ctx).toFloat())
        rect.set(cx - u(wdp) / 2f, top, cx + u(wdp) / 2f, top + u(hdp))
        val cornerDp = IslandGeometry.expandedCornerDp(ctx)
        val idleCornerDp = IslandGeometry.collapsedCornerDp(ctx)
        // Cada capa usa su propio radio: la contraída puede ser cuadrada y la expandida circular, o al revés.
        val crIdle = u(minOf(idleCornerDp, hdp / 2f))
        val cr2 = u(minOf(cornerDp, hdp / 2f))
        val idleSpec = IslandLook.idle(ctx)
        val cardSpec = IslandLook.card(ctx, st)
        val k = progress.coerceIn(0f, 1f)

        if (Prefs.shadow(ctx)) { p.setShadowLayer(u(10f), 0f, u(4f), 0x99000000.toInt()) }
        drawLook(cv, idleSpec, crIdle, 1f - k, s)
        p.clearShadowLayer()
        drawLook(cv, cardSpec, cr2, k, s)

        // Contenido de ejemplo al abrir
        if (progress > 0.35f) {
            val k = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val ic = u(st.iconDp.toFloat() * 0.9f)
            val ix = rect.left + u(st.padH.toFloat() + 2f); val iy = rect.top + (rect.height() - ic) / 2f
            p.color = Glass.ACCENT; p.alpha = (255 * k).toInt()
            val ir = RectF(ix, iy, ix + ic, iy + ic); cv.drawRoundRect(ir, u(st.iconCornerDp), u(st.iconCornerDp), p)
            p.color = st.textColor; p.alpha = (255 * k).toInt(); p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            p.textSize = u(st.titleSp)
            cv.drawText("Ryx Island", ix + ic + u(10f), rect.centerY() - u(1f), p)
            p.color = st.subColor; p.typeface = Typeface.DEFAULT; p.textSize = u(st.bodySp); p.alpha = (200 * k).toInt()
            cv.drawText("Vista previa", ix + ic + u(10f), rect.centerY() + u(st.bodySp + 1f), p)
        }
        p.alpha = 255

        // Acumulación a la derecha: iconos apilados al abrir, contador al reposo
        if (progress > 0.35f) {
            val k = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val bx = rect.right - u(st.padH.toFloat() + 12f)
            for (i in 2 downTo 0) {
                p.color = intArrayOf(0xFF8AB4FF.toInt(), 0xFFFFC857.toInt(), Glass.ACCENT)[i]
                p.alpha = (255 * k).toInt()
                cv.drawCircle(bx - u(11f) * i, rect.centerY(), u(9f), p)
            }
        } else {
            p.color = Glass.ACCENT; p.alpha = 255
            cv.drawCircle(rect.right - u(14f), rect.centerY(), u(8f), p)
            p.color = 0xFF000000.toInt(); p.textSize = u(10f); p.textAlign = Paint.Align.CENTER
            cv.drawText("3", rect.right - u(14f), rect.centerY() + u(3.5f), p); p.textAlign = Paint.Align.LEFT
        }
        p.alpha = 255

        // Cotas
        p.color = Glass.ACCENT; p.textSize = u(11f); p.typeface = Typeface.DEFAULT; p.textAlign = Paint.Align.CENTER
        cv.drawText("${wdp.toInt()} × ${hdp.toInt()} dp", rect.centerX(), rect.bottom + u(16f), p)
        p.strokeWidth = maxOf(1f, u(1f)); p.style = Paint.Style.STROKE
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(u(3f), u(3f)), 0f)
        cv.drawLine(rect.left, rect.bottom + u(4f), rect.right, rect.bottom + u(4f), p)
        p.pathEffect = null; p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT
        cv.restore()

        // Borde del teléfono
        p.style = Paint.Style.STROKE; p.strokeWidth = u(2f); p.color = 0x66FFFFFF
        rect.set(0f, 0f, w, h + r); cv.drawRoundRect(rect, r, r, p); p.style = Paint.Style.FILL
    }
}
