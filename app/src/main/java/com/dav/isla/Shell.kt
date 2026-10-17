package com.dav.isla

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Ejecuta comandos con privilegios. Orden en modo Auto: Shizuku primero, luego root.
 * Llamar SIEMPRE fuera del hilo principal.
 */
object Shell {
    const val PERM_CODE = 7001
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    fun shizukuInstalled(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (e: Exception) { false }

    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun shizukuGranted(): Boolean = try {
        shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    /** Estado legible para la pantalla. */
    fun shizukuStatus(c: Context): String = when {
        shizukuGranted() -> "Listo"
        shizukuRunning() -> "En ejecución · falta el permiso"
        shizukuInstalled(c) -> "Instalado · sin iniciar"
        else -> "No instalado"
    }

    fun requestShizuku() {
        try {
            if (shizukuRunning() && !shizukuGranted()) Shizuku.requestPermission(PERM_CODE)
        } catch (e: Throwable) {}
    }

    fun method(c: Context): Int = Prefs.shellMethod(c)   // 0 auto, 1 Shizuku, 2 root

    fun run(c: Context, cmd: String): Pair<Int, String> = when (method(c)) {
        1 -> if (shizukuGranted()) runShizuku(cmd) else Pair(-1, "Shizuku sin permiso o sin iniciar")
        2 -> Root.run(cmd)
        else -> if (shizukuGranted()) {
            val r = runShizuku(cmd)
            if (r.first == -1) Root.run(cmd) else r
        } else Root.run(cmd)
    }

    /** Quién ejecutó el último comando, para el registro. */
    fun label(c: Context): String = when (method(c)) {
        1 -> "Shizuku"
        2 -> "root"
        else -> if (shizukuGranted()) "Shizuku" else "root"
    }

    /** Shizuku.newProcess es privado desde la API 13, por eso se llama por reflexión. */
    private fun runShizuku(cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "($cmd) 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().use { it.readText() }
        Pair(p.waitFor(), out.trim())
    } catch (e: Throwable) {
        Pair(-1, "Shizuku: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
    }

    fun setHeadsUp(c: Context, enabled: Boolean): Pair<Int, String> =
        run(c, "settings put global heads_up_notifications_enabled ${if (enabled) 1 else 0}")
}
