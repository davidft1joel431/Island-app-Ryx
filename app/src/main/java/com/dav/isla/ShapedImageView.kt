package com.dav.isla

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.widget.ImageView
import kotlin.math.cos
import kotlin.math.sin

/** Icono recortado en: 0 círculo, 1 squircle, 2 flor, 3 hexágono. */
class ShapedImageView(c: Context) : ImageView(c) {
    var shape = 1
        set(v) { field = v; rebuild(); invalidate() }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); rebuild() }

    private fun rebuild() {
        path.reset()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f; val cy = h / 2f; val r = minOf(w, h) / 2f
        when (shape) {
            0 -> path.addCircle(cx, cy, r, Path.Direction.CW)
            2 -> {
                path.addCircle(cx, cy, r * 0.62f, Path.Direction.CW)
                for (i in 0 until 8) {
                    val a = Math.PI * 2 * i / 8
                    path.addCircle(cx + (r * 0.58f * cos(a)).toFloat(), cy + (r * 0.58f * sin(a)).toFloat(), r * 0.42f, Path.Direction.CW)
                }
            }
            3 -> {
                for (i in 0 until 6) {
                    val a = Math.PI / 3 * i - Math.PI / 2
                    val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            else -> path.addRoundRect(0f, 0f, w, h, r * 0.72f, r * 0.72f, Path.Direction.CW)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = canvas.save()
        canvas.clipPath(path)
        super.onDraw(canvas)
        canvas.restoreToCount(n)
    }
}
