package com.dav.isla

import android.content.Context

/** Colores y bordes de la isla (reposo y tarjeta), compartidos por la isla real y la vista previa. */
object IslandLook {
    class Spec(val top: Int, val bottom: Int, val rim: Int, val rimDp: Float, val shine: Int)

    private fun withAlpha(c: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (c and 0x00FFFFFF)
    private fun mulAlpha(c: Int, f: Float): Int = withAlpha(c, (c ushr 24) / 255f * f)

    fun darker(c: Int, f: Float): Int {
        val r = (((c shr 16) and 255) * f).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 255) * f).toInt().coerceIn(0, 255)
        val b = ((c and 255) * f).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    fun lighter(c: Int, f: Float): Int {
        fun up(x: Int) = (x + (255 - x) * f).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (up((c shr 16) and 255) shl 16) or (up((c shr 8) and 255) shl 8) or up(c and 255)
    }

    /** Isla contraída. */
    fun idle(ctx: Context): Spec {
        val mode = Prefs.idleMode(ctx)
        val op = Prefs.idleOpacity(ctx) / 100f
        val tint = Prefs.idleColor(ctx)
        val bd = Prefs.idleBorder(ctx).let { if (it >= 0) it.toFloat() else if (mode == 1) 1.2f else 0f }
        val rim = if (mode == 1) 0x99FFFFFF.toInt() else withAlpha(Glass.ACCENT, 0.7f)
        return when (mode) {
            1 -> {                                   // cristal líquido: translúcido, con brillo y borde de luz
                val t = if (tint == 0) 0xFFFFFFFF.toInt() else tint
                val a = 0.10f + 0.62f * op
                Spec(withAlpha(t, a), withAlpha(t, a * 0.55f), rim, bd, 0x55FFFFFF)
            }
            2 -> {                                   // color liso
                val t = if (tint == 0) 0xFF1C1C1E.toInt() else tint
                Spec(withAlpha(t, op), withAlpha(darker(t, 0.8f), op), rim, bd, 0)
            }
            3 -> {                                   // degradado con el acento
                val t = if (tint == 0) Glass.ACCENT else tint
                Spec(withAlpha(lighter(t, 0.15f), op), withAlpha(darker(t, 0.72f), op), rim, bd, 0x33FFFFFF)
            }
            else -> Spec(withAlpha(0xFF000000.toInt(), op), withAlpha(0xFF000000.toInt(), op), rim, bd, 0)
        }
    }

    /** Tarjeta expandida. */
    fun card(ctx: Context, s: IslandStyle): Spec {
        val op = Prefs.opacity(ctx) / 100f
        val custom = Prefs.cardColor(ctx)
        var top = if (custom != 0) custom else s.bgTop
        var bottom = if (custom != 0) darker(custom, 0.78f) else s.bgBottom
        var rim = s.strokeColor
        var rimDp = s.strokeDp
        var shine = s.highlight
        if (Prefs.cardGlass(ctx)) {                  // cristal: más transparente, con brillo y borde de luz
            top = mulAlpha(top, 0.55f); bottom = mulAlpha(bottom, 0.40f)
            rim = 0x99FFFFFF.toInt(); rimDp = maxOf(rimDp, 1.2f); shine = 0x55FFFFFF
        }
        return Spec(mulAlpha(top, op), mulAlpha(bottom, op), rim, rimDp, shine)
    }
}
