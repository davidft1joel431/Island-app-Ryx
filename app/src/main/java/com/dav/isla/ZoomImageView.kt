package com.dav.isla

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs

/**
 * Visor de imágenes con zoom.
 *
 * - Pellizco para acercar/alejar (1× a 5×).
 * - Doble toque: 2,5× o volver a encajar.
 * - Un toque con la imagen encajada cierra el visor; con zoom, vuelve a encajar.
 * - Arrastrar con el dedo cuando hay zoom.
 *
 * Se usa en el Historial para ver fotos, stickers y GIF/WebP animados sin perder la animación.
 */
class ZoomImageView(context: Context) : ImageView(context) {
    private val matrix = Matrix()
    private val base = Matrix()
    private val drawableRect = RectF()
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0f) {
                    val now = currentScale().coerceAtLeast(0.01f)
                    val target = (now * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                    matrix.postScale(target / now, target / now, detector.focusX, detector.focusY)
                    fixBounds()
                    imageMatrix = matrix
                }
                return true
            }
        })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale() > 1.15f) resetZoom() else zoomTo(2.5f, e.x, e.y)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (currentScale() > 1.05f) resetZoom() else performClick()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    private fun fitToView() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        val s = minOf(vw / dw, vh / dh)
        base.reset()
        base.postScale(s, s)
        base.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f)
        matrix.set(base)
        imageMatrix = matrix
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val now = currentScale().coerceAtLeast(0.01f)
        val f = target.coerceIn(MIN_SCALE, MAX_SCALE) / now
        matrix.postScale(f, f, fx, fy)
        fixBounds()
        imageMatrix = matrix
    }

    private fun resetZoom() {
        matrix.set(base)
        imageMatrix = matrix
    }

    /** Mantiene la imagen centrada y sin huecos cuando hay zoom. */
    private fun fixBounds() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        drawableRect.set(0f, 0f, dw, dh)
        matrix.mapRect(drawableRect)
        var dx = 0f
        var dy = 0f
        if (drawableRect.width() <= vw) dx = (vw - drawableRect.width()) / 2f - drawableRect.left
        else if (drawableRect.left > 0f) dx = -drawableRect.left
        else if (drawableRect.right < vw) dx = vw - drawableRect.right
        if (drawableRect.height() <= vh) dy = (vh - drawableRect.height()) / 2f - drawableRect.top
        else if (drawableRect.top > 0f) dy = -drawableRect.top
        else if (drawableRect.bottom < vh) dy = vh - drawableRect.bottom
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && currentScale() > 1.02f) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(dx) > 1f || abs(dy) > 1f) {
                        matrix.postTranslate(dx, dy)
                        fixBounds()
                        imageMatrix = matrix
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastX = 0f
                lastY = 0f
            }
        }
        return true
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}
