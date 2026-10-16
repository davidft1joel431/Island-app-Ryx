package com.dav.isla

import android.content.Context
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.exp

/** Movimiento tipo resorte (como la isla de HyperOS 3): sube un poco de más y se asienta. */
object Motion {
    class Spring(private val decay: Float = 6f, private val cycles: Float = 1.1f) : Interpolator {
        override fun getInterpolation(t: Float): Float =
            if (t >= 1f) 1f else (1f - exp(-decay * t) * cos(2f * Math.PI.toFloat() * cycles * t))
    }

    fun spring(): Interpolator = Spring()
    fun smooth(): Interpolator = DecelerateInterpolator(1.7f)

    /** Relajada más lenta, ágil más rápida. */
    fun factor(c: Context): Float = when (Prefs.speed(c)) { 0 -> 1.4f; 2 -> 0.65f; else -> 1f }
    fun expandMs(c: Context): Long = (520 * factor(c)).toLong()
    fun collapseMs(c: Context): Long = (300 * factor(c)).toLong()
}
