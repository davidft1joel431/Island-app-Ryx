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
                path.addCircle(cx, cy, r * 0.55f, Path.Direction.CW)
                for (i in 0 until 6) {
                    val a = Math.PI * 2 * i / 6 - Math.PI / 2
                    path.addCircle(cx + (r * 0.52f * cos(a)).toFloat(), cy + (r * 0.52f * sin(a)).toFloat(), r * 0.38f, Path.Direction.CW)
                }
            }
            3 -> {
                val inset = r * 0.18f
                val pts = Array(6) { i ->
                    val a = Math.PI / 3 * i - Math.PI / 2
                    (cx + r * cos(a).toFloat()) to (cy + r * sin(a).toFloat())
                }
                for (i in pts.indices) {
                    val cur = pts[i]; val nxt = pts[(i + 1) % pts.size]; val prv = pts[(i + 5) % pts.size]
                    val inX = cur.first + (prv.first - cur.first) * (inset / r)
                    val inY = cur.second + (prv.second - cur.second) * (inset / r)
                    val outX = cur.first + (nxt.first - cur.first) * (inset / r)
                    val outY = cur.second + (nxt.second - cur.second) * (inset / r)
                    if (i == 0) path.moveTo(inX, inY) else path.lineTo(inX, inY)
                    path.quadTo(cur.first, cur.second, outX, outY)
                }
                path.close()
            }
            else -> {
                // Superelipse (squircle continuo, el de los iconos de A16/A17), no un rectángulo con radio.
                val n = 4.8
                val steps = 64
                val a = w / 2f; val b = h / 2f
                for (i in 0..steps) {
                    val t = Math.PI * 2 * i / steps - Math.PI / 2
                    val ct = cos(t); val st = sin(t)
                    val x = cx + a * Math.signum(ct).toFloat() * Math.pow(kotlin.math.abs(ct), 2.0 / n).toFloat()
                    val y = cy + b * Math.signum(st).toFloat() * Math.pow(kotlin.math.abs(st), 2.0 / n).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = canvas.save()
        canvas.clipPath(path)
        super.onDraw(canvas)
        canvas.restoreToCount(n)
    }
}
