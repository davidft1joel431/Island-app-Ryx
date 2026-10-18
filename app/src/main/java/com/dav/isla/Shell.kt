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

    /** null = aún no probado. true si «su» responde como uid 0 (Magisk/KernelSU ya concedió el permiso). */
    @Volatile private var rootOk: Boolean? = null

    /** Prueba root UNA vez por sesión (Magisk pregunta solo la primera vez). Llamar fuera del hilo principal. */
    fun probeRoot(): Boolean {
        val r = rootOk
        if (r != null) return r
        val ok = Root.run("id").second.contains("uid=0")
        rootOk = ok
        return ok
    }

    fun autoUsesRoot(): Boolean = !shizukuGranted() && rootOk == true

    /** 0 auto (Shizuku si está activo y, si no, root con Magisk si concediste el permiso), 1 Shizuku, 2 root, 3 sin privilegios. */
    fun method(c: Context): Int = Prefs.shellMethod(c)

    /** ¿Se pueden ejecutar comandos con privilegios ahora mismo? */
    fun privileged(c: Context): Boolean = when (method(c)) {
        2 -> true
        3 -> false
        1 -> shizukuGranted()
        else -> shizukuGranted() || rootOk == true
    }

    fun run(c: Context, cmd: String): Pair<Int, String> = when (method(c)) {
        2 -> Root.run(cmd)
        3 -> Pair(-1, "Modo sin privilegios")
        1 -> if (shizukuGranted()) runShizuku(cmd) else Pair(-1, "Shizuku sin permiso o sin iniciar")
        else -> when {
            shizukuGranted() -> runShizuku(cmd)
            rootOk == true -> Root.run(cmd)
            else -> Pair(-1, "Ni Shizuku ni root disponibles")
        }
    }

    /** Para ajustes puntuales del sistema: root si responde (Magisk) y, si no, Shizuku. Ignora el método elegido arriba. Fuera del hilo principal. */
    fun runAdmin(cmd: String): Pair<Int, String> = when {
        probeRoot() -> Root.run(cmd)
        shizukuGranted() -> runShizuku(cmd)
        else -> Pair(-1, "Ni root ni Shizuku disponibles")
    }

    /** Quién ejecuta los comandos, para el registro. */
    fun label(c: Context): String = when (method(c)) {
        2 -> "root"
        3 -> "sin privilegios"
        1 -> "Shizuku"
        else -> if (autoUsesRoot()) "root (Magisk)" else "Shizuku"
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
