package com.dav.isla

import android.content.Context

/**
 * Medidas base de la isla.
 *
 * Contraída: 160 × 32 dp, con esquinas de pastilla como la HyperOS Island.
 * Expandida: 350 × 74 dp, con esquinas de cuadrado redondeado (no una píldora completa).
 *
 * El tamaño abierto es fijo: 350 × 74 dp. No cambia con el texto ni con la imagen;
 * el contenido se ajusta y se desplaza dentro de esa caja. La vista previa usa los
 * mismos valores para que lo que se ve sea exactamente lo que se dibuja.
 */
object IslandGeometry {
    const val COLLAPSED_WIDTH_DP = 160
    const val COLLAPSED_HEIGHT_DP = 32
    const val EXPANDED_WIDTH_DP = 350
    const val EXPANDED_HEIGHT_DP = 74

    /** Radio máximo por defecto de la tarjeta abierta: cuadrado redondeado, no pastilla. */
    const val EXPANDED_CORNER_DP = 24f

    /** Ancho abierto efectivo: siempre 350 dp (el ajuste viejo se ignora). */
    fun expandedWidthDp(@Suppress("UNUSED_PARAMETER") c: Context): Int = EXPANDED_WIDTH_DP

    /** La isla cerrada es una pastilla: radio = mitad del alto, salvo que el usuario fije otro. */
    fun collapsedCornerDp(c: Context): Float {
        val custom = Prefs.idleCornerDp(c)
        return if (custom >= 0) custom.toFloat() else Prefs.heightDp(c) / 2f
    }

    /**
     * La isla abierta usa un cuadrado redondeado. El estilo puede pedir una esquina
     * mayor (iOS, Clásico…), pero de fábrica se limita a 24 dp para que no vuelva a
     * parecer una cápsula. Un valor manual del usuario nunca se recorta.
     */
    fun expandedCornerDp(c: Context): Float {
        val custom = Prefs.cornerDp(c)
        if (custom >= 0) return custom.toFloat()
        return minOf(Styles.byId(Prefs.style(c)).cornerDp, EXPANDED_CORNER_DP)
    }
}
