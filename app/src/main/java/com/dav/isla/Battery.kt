package com.dav.isla

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Registro de batería en tiempo real.
 *
 * Lee el valor del propio sistema (emisión «pegajosa» ACTION_BATTERY_CHANGED) cada vez que
 * hace falta, en lugar de guardar avisos repetidos. Así la isla y el historial muestran
 * siempre el nivel actual y no cuatro notificaciones mal registradas.
 */
object Battery {

    class State(val pct: Int, val charging: Boolean, val full: Boolean) {
        val label: String
            get() = when {
                full -> "Cargada"
                charging -> "Cargando"
                pct in 0..15 -> "Batería baja"
                else -> "En uso"
            }
    }

    @Suppress("DEPRECATION")
    fun now(c: Context): State = try {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        State(
            if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1,
            st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL,
            st == BatteryManager.BATTERY_STATUS_FULL
        )
    } catch (e: Exception) {
        State(-1, false, false)
    }
}
