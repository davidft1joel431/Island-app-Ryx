package com.dav.isla

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Desbloqueo de apps «no redimensionables» y del formato libre en Android 16 y 17.
 *
 * En A16 el formato libre de teléfono convive con el modo escritorio (`override_desktop_mode_features`).
 * En A17 se suma el modo escritorio por pantalla (`override_desktop_experience_features`) y las apps
 * con target 37 ya no pueden negarse a redimensionar en pantallas grandes; en un teléfono igual hace
 * falta `force_resizable_activities` para las que se declaran fijas.
 *
 * Android aplica estos ajustes en caliente (WindowManager los observa), sin reiniciar.
 * Escribirlos desde Ryx necesita WRITE_SECURE_SETTINGS, concedido UNA vez:
 *   - solo, si Shizuku o root están disponibles (Ryx se lo da a sí mismo con `pm grant`), o
 *   - con un comando de ADB (ver [adbCommand]).
 */
object FreeformUnlock {
    const val PERM = "android.permission.WRITE_SECURE_SETTINGS"

    /** Los dos ajustes imprescindibles: ventanas libres + forzar redimensionable. */
    private val CORE = listOf("enable_freeform_support", "force_resizable_activities")

    /**
     * A16/A17. El modo escritorio se apoya en freeform; activar el override de desarrollador
     * hace que la ROM acepte ventanas libres aunque la pantalla sea menor de 600 dp.
     * `force_desktop_mode_on_external_displays` cubre el escritorio al conectar un monitor.
     */
    private val DESKTOP = listOf(
        "enable_non_resizable_multi_window",
        "override_desktop_mode_features",
        "override_desktop_experience_features",
        "force_desktop_mode_on_external_displays"
    )

    private val ALL = (CORE + DESKTOP).distinct()

    fun hasPerm(c: Context): Boolean = c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun read(c: Context, k: String): String? =
        try { Settings.Global.getString(c.contentResolver, k) } catch (e: Throwable) { null }

    /** true si ya están activos los dos ajustes imprescindibles. */
    fun unlocked(c: Context): Boolean = CORE.all { read(c, it) == "1" }

    fun adbCommand(c: Context): String = "adb shell pm grant ${c.packageName} $PERM"

    /** Estado legible para la pantalla y el registro. */
    fun status(c: Context): String {
        val sb = StringBuilder()
        sb.append("API ${Build.VERSION.SDK_INT} · ${if (Build.VERSION.SDK_INT >= 37) "Android 17" else if (Build.VERSION.SDK_INT >= 36) "Android 16" else "anterior"}\n")
        ALL.forEach { sb.append("$it = ${read(c, it) ?: "sin definir"}\n") }
        sb.append("Permiso WRITE_SECURE_SETTINGS: ${if (hasPerm(c)) "concedido" else "no concedido"}")
        return sb.toString()
    }

    private fun writeAll(c: Context) {
        for (k in ALL) {
            try { Settings.Global.putInt(c.contentResolver, k, 1) } catch (e: Throwable) {}
            try { Settings.Global.putString(c.contentResolver, k, "1") } catch (e: Throwable) {}
        }
    }

    /**
     * Activa el desbloqueo si hace falta. Rápido cuando ya está activo (dos lecturas).
     * [force] = true ignora el interruptor de Ryx (botón manual).
     * Llamar fuera del hilo principal. Devuelve true si al final los ajustes están activos.
     */
    fun ensure(c: Context, log: StringBuilder, force: Boolean = false): Boolean {
        if (!force && !Prefs.fwUnlock(c)) {
            log.append("Desbloqueo de apps no redimensionables: apagado en Ryx.\n")
            return unlocked(c)
        }
        if (unlocked(c) && !force) {
            log.append("Apps no redimensionables: desbloqueadas (force_resizable_activities = 1).\n")
            return true
        }
        if (hasPerm(c)) writeAll(c)
        if (!unlocked(c) && Shell.privileged(c)) {
            val cmd = "pm grant ${c.packageName} $PERM; " + ALL.joinToString("; ") { "settings put global $it 1" }
            val r = Shell.run(c, cmd)
            log.append("Desbloqueo con ${Shell.label(c)}: código ${r.first}\n")
            if (hasPerm(c)) writeAll(c)
        }
        val ok = unlocked(c)
        log.append(
            if (ok) "Formato libre listo (freeform + redimensionable"
                + (if (Build.VERSION.SDK_INT >= 36) " + modo escritorio A16/A17" else "")
                + "). Se aplica sin reiniciar.\n"
            else "No pude desbloquear las apps no redimensionables: falta el permiso. Concédelo una vez con «${adbCommand(c)}» " +
                "(o inicia Shizuku) o activa «Forzar que las actividades sean redimensionables» en Opciones de desarrollador.\n"
        )
        return ok
    }
}
