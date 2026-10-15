package com.dav.isla

object Root {
    /** Ejecuta un comando como root. Llamar SIEMPRE fuera del hilo principal. */
    fun run(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        Pair(p.waitFor(), out.trim())
    } catch (e: Exception) {
        Pair(-1, e.message ?: "sin acceso root")
    }

    /** enabled = false oculta los avisos emergentes (heads-up) del sistema. */
    fun setHeadsUp(enabled: Boolean): Pair<Int, String> =
        run("settings put global heads_up_notifications_enabled ${if (enabled) 1 else 0}")
}
