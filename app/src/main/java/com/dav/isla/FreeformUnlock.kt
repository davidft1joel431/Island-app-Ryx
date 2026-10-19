package com.dav.isla

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Desbloqueo de apps «no redimensionables» para el formato libre.
 *
 * Android solo pone en ventana libre una app si es redimensionable. Las ROM traen en Opciones de desarrollador
 * «Forzar que las actividades sean redimensionables» (Settings.Global.force_resizable_activities): con eso activo,
 * el sistema trata TODAS las apps como redimensionables y las deja entrar en ventana libre.
 *
 * Android aplica estos ajustes en caliente (WindowManager los observa), sin reiniciar.
 * Escribirlos desde Ryx necesita el permiso WRITE_SECURE_SETTINGS, que se concede UNA vez:
 *   - solo, si Shizuku o root están disponibles (Ryx se lo da a sí mismo con `pm grant`), o
 *   - con un comando de ADB (ver [adbCommand]).
 * Sin ese permiso no se puede escribir, y queda activarlo a mano en Opciones de desarrollador.
 */
object FreeformUnlock {
    const val PERM = "android.permission.WRITE_SECURE_SETTINGS"

    /** Los dos ajustes imprescindibles: ventanas libres + forzar redimensionable. */
    private val CORE = listOf("enable_freeform_support", "force_resizable_activities")

    /** Todo lo que se activa (el resto ayuda en ROM recientes con multiventana de apps no redimensionables). */
    private val ALL = CORE + listOf("enable_non_resizable_multi_window", "freeform_window_management")

    fun hasPerm(c: Context): Boolean = c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun read(c: Context, k: String): String? =
        try { Settings.Global.getString(c.contentResolver, k) } catch (e: Throwable) { null }

    private fun readSecure(c: Context, k: String): String? =
        try { Settings.Secure.getString(c.contentResolver, k) } catch (e: Throwable) { null }

    /** true si ya están activos los dos ajustes imprescindibles. */
    fun unlocked(c: Context): Boolean = CORE.all { read(c, it) == "1" }

    fun adbCommand(c: Context): String = "adb shell pm grant ${c.packageName} $PERM"

    /** Estado legible para la pantalla y el registro. */
    fun status(c: Context): String {
        val sb = StringBuilder()
        ALL.forEach { sb.append("$it = ${read(c, it) ?: "sin definir"}\n") }
        // Android 16+ añade el modo escritorio por pantalla; se informa (solo lectura, no se escribe).
        if (Build.VERSION.SDK_INT >= 36) {
            sb.append("desktop_mode = ${readSecure(c, "desktop_mode") ?: "sin definir"}\n")
            sb.append("force_desktop_mode_on_external_displays = ${read(c, "force_desktop_mode_on_external_displays") ?: "sin definir"}\n")
        }
        sb.append("Permiso WRITE_SECURE_SETTINGS: ${if (hasPerm(c)) "concedido" else "no concedido"}")
        return sb.toString()
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
        if (unlocked(c)) {
            log.append("Apps no redimensionables: desbloqueadas (force_resizable_activities = 1).\n")
            return true
        }
        // 1) Con el permiso: se escribe directamente, sin Shizuku ni root
        if (hasPerm(c)) {
            for (k in ALL) try { Settings.Global.putString(c.contentResolver, k, "1") } catch (e: Throwable) {}
        }
        // 2) Sin permiso pero con Shizuku/root ya activos: se concede el permiso a sí mismo y se escribe por shell
        if (!unlocked(c) && Shell.privileged(c)) {
            val cmd = "pm grant ${c.packageName} $PERM; " + ALL.joinToString("; ") { "settings put global $it 1" }
            val r = Shell.run(c, cmd)
            log.append("Desbloqueo con ${Shell.label(c)}: código ${r.first}\n")
        }
        val ok = unlocked(c)
        log.append(
            if (ok) "Apps no redimensionables: desbloqueadas ahora (se aplica sin reiniciar).\n"
            else "No pude desbloquear las apps no redimensionables: falta el permiso. Concédelo una vez con «${adbCommand(c)}» " +
                "(o inicia Shizuku) o activa «Forzar que las actividades sean redimensionables» en Opciones de desarrollador.\n"
        )
        if (Build.VERSION.SDK_INT >= 36)
            log.append("Android 16+: si el formato libre se abre a pantalla completa, prueba el modo «Escritorio» de Opciones de desarrollador (ventanas por pantalla).\n")
        return ok
    }
}
