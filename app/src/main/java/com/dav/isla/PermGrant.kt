package com.dav.isla

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Da a Ryx el MÁXIMO de permisos posibles para que funcionen bien el formato libre, las burbujas y los avisos
 * aunque NO haya Shizuku ni root («modo sin privilegios»).
 *
 * Idea: hay dos caminos, y se usa el primero que esté disponible.
 *  1) CON PRIVILEGIOS (Shizuku o root): Ryx se concede a sí mismo todo por shell (`pm grant`, `appops set` y
 *     `settings put global`), sin ADB ni ordenador. Lo hace con [grant].
 *  2) SIN PRIVILEGIOS: se copia UN solo comando de ADB (el que devuelve [adbCommand]) que concede lo mismo de una
 *     vez; se pega una única vez y queda para siempre (no hace falta repetirlo en cada arranque).
 *
 * Qué se concede y para qué:
 *  - WRITE_SECURE_SETTINGS → desbloquear apps no redimensionables y ocultar avisos emergentes sin Shizuku/root.
 *  - PACKAGE_USAGE_STATS  → saber qué app tienes delante (leer avisos ya vistos, detectar juegos, clones).
 *  - POST_NOTIFICATIONS   → que Ryx pueda avisar de una versión nueva en el panel (Android 13+).
 *  - BLUETOOTH_CONNECT    → el aviso de Bluetooth con el nombre real del dispositivo (Android 12+).
 *  - SYSTEM_ALERT_WINDOW  → la isla y las barras del formato libre (Android suele pedirlo con un diálogo aparte).
 *  - RECEIVE_BOOT_COMPLETED → el servicio y los ajustes vuelven solos tras reiniciar.
 */
object PermGrant {

    /** Permiso de uso (aparece como «acceso de uso» en Ajustes). */
    const val USAGE = "android.permission.PACKAGE_USAGE_STATS"
    const val BLUETOOTH = "android.permission.BLUETOOTH_CONNECT"
    const val POST_NOTIF = "android.permission.POST_NOTIFICATIONS"
    const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    // Permisos de LLAMADAS: contactos (favoritos prioritarios), registro de llamadas y contestar/marcar.
    const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    const val READ_CALL_LOG = "android.permission.READ_CALL_LOG"
    const val ANSWER_CALLS = "android.permission.ANSWER_PHONE_CALLS"
    const val CALL_PHONE = "android.permission.CALL_PHONE"
    const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"

    /** Permisos «normales» de Android, que se conceden con `pm grant`. */
    private val NORMAL = listOf(BLUETOOTH, POST_NOTIF, READ_CONTACTS, READ_CALL_LOG, ANSWER_CALLS, CALL_PHONE, READ_PHONE_STATE)
    /**
     * Permisos «de aplicación» (appops). No se conceden con `pm grant`, van por `appops set`.
     *   - SYSTEM_ALERT_WINDOW: dibujar encima (la isla y las barras del formato libre).
     *   - PACKAGE_USAGE_STATS: qué app tienes delante.
     *   - WRITE_SECURE_SETTINGS: escribir ajustes globales del sistema (desbloqueo del formato libre).
     */
    private val APPOPS = listOf("SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS", "WRITE_SECURE_SETTINGS")

    /** Ajustes globales que desbloquean el formato libre al completo (los mismos de [FreeformUnlock], más multiventana). */
    private val SETTINGS = listOf(
        "enable_freeform_support", "force_resizable_activities",
        "enable_non_resizable_multi_window", "freeform_window_management")

    /**
     * Comando de ADB (una sola vez, sin Shizuku ni root) que lo concede TODO.
     * Se puede pegar tal cual (todo en una línea, separado por «;»).
     */
    fun adbCommand(c: Context): String {
        val p = c.packageName
        val sb = StringBuilder("adb shell \"")
        for (perm in NORMAL) sb.append("pm grant $p $perm; ")
        for (op in APPOPS) sb.append("appops set $p $op allow; ")
        for (k in SETTINGS) sb.append("settings put global $k 1; ")
        sb.append("cmd deviceidle whitelist +$p\"")
        return sb.toString()
    }

    /** ¿Están ya los permisos importantes? */
    fun hasUsage(c: Context): Boolean = try {
        val ai = c.packageManager.getApplicationInfo(c.packageName, 0)
        val am = c.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        am.checkOpNoThrow("PACKAGE_USAGE_STATS", ai.uid, c.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    /** Concede todo lo posible CON PRIVILEGIOS (Shizuku o root). Llamar fuera del hilo principal. */
    fun grant(c: Context): String {
        if (!Shell.privileged(c)) return "Ahora mismo no hay Shizuku ni root. Usa el comando de ADB."
        val p = c.packageName
        val partes = ArrayList<String>()
        for (perm in NORMAL) {
            val r = Shell.run(c, "pm grant $p $perm")
            partes.add(if (r.first == 0) "$perm ✓" else "$perm ✗ (${r.second.take(60)})")
        }
        for (op in APPOPS) {
            val r = Shell.run(c, "appops set $p $op allow")
            partes.add(if (r.first == 0) "$op ✓" else "$op ✗")
        }
        Shell.run(c, SETTINGS.joinToString("; ") { "settings put global $it 1" })
        Shell.run(c, "cmd deviceidle whitelist +$p")     // que el sistema no mate a Ryx en segundo plano
        return partes.joinToString(" · ")
    }

    /** Estado legible de cada permiso, para la pantalla de Permisos. */
    fun status(c: Context): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        fun granted(perm: String) = try { c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }
        fun allowed(op: String): Boolean = try {
            val ai = c.packageManager.getApplicationInfo(c.packageName, 0)
            val am = c.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            @Suppress("DEPRECATION")
            (am.checkOpNoThrow(op, ai.uid, c.packageName)) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) { false }

        out.add("Dibujar encima (isla y barras)" to Settings.canDrawOverlays(c))
        out.add("Ajustes del sistema (formato libre)" to FreeformUnlock.hasPerm(c))
        out.add("Acceso de uso (qué app usas)" to allowed("PACKAGE_USAGE_STATS"))
        if (Build.VERSION.SDK_INT >= 33) out.add("Notificaciones (avisos de versión)" to granted(POST_NOTIF))
        if (Build.VERSION.SDK_INT >= 31) out.add("Bluetooth (nombre del dispositivo)" to granted(BLUETOOTH))
        out.add("Formato libre desbloqueado" to FreeformUnlock.unlocked(c))
        out.add("Shizuku o root disponibles" to Shell.privileged(c))
        return out
    }
}
