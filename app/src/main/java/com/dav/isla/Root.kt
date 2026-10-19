package com.dav.isla

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Ejecuta comandos como root.
 *
 * Para que MOVER/REDIMENSIONAR la ventana libre sea fluido no se arranca un proceso «su» por comando (eso costaba más
 * que un fotograma y era la causa de que la ventana fuera a tirones). En su lugar se mantiene UN shell root vivo y se
 * le escriben las órdenes por su entrada estándar. Si el shell no responde a tiempo o muere, se cae sola al método de
 * siempre (un proceso por comando), que funciona igual aunque sea más lento.
 *
 * Llamar SIEMPRE fuera del hilo principal.
 */
object Root {
    private val lock = Any()
    private var shell: Process? = null
    private var writer: BufferedWriter? = null
    private val lines = LinkedBlockingQueue<String>()
    private var seq = 0

    /** Comodidad: activa/oculta los avisos emergentes del sistema. */
    fun setHeadsUp(enabled: Boolean): Pair<Int, String> =
        run("settings put global heads_up_notifications_enabled ${if (enabled) 1 else 0}")

    /** Ejecuta un comando como root (una entrada, una salida). */
    fun run(cmd: String): Pair<Int, String> {
        synchronized(lock) {
            if (ensure()) {
                try {
                    lines.clear()
                    val marker = "@@RYX" + (seq++) + "@@"
                    val w = writer
                    if (w != null) {
                        w.write(cmd); w.write("\n")
                        w.write("echo \"$marker:\$?\"\n")
                        w.flush()
                        val sb = StringBuilder()
                        val deadline = System.currentTimeMillis() + 8000
                        while (System.currentTimeMillis() < deadline && shell?.isAlive == true) {
                            val l = lines.poll(250, TimeUnit.MILLISECONDS) ?: continue
                            val i = l.indexOf(marker)
                            if (i >= 0) {
                                val code = l.substring(i + marker.length + 1).trim().toIntOrNull() ?: 0
                                return Pair(code, sb.toString().trim())
                            }
                            sb.append(l).append('\n')
                        }
                    }
                } catch (e: Throwable) {}
                close()                            // se colgó o murió: se tira el shell
            }
        }
        return oneShot(cmd)
    }

    /** Arranca (una vez) el shell root persistente y comprueba que responde. */
    private fun ensure(): Boolean {
        val s = shell
        if (s != null && s.isAlive) return true
        close()
        return try {
            val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
            val w = BufferedWriter(OutputStreamWriter(p.outputStream))
            shell = p; writer = w
            val r = BufferedReader(InputStreamReader(p.inputStream))
            Thread {
                try { while (true) { val l = r.readLine() ?: break; lines.put(l) } } catch (e: Throwable) {}
            }.apply { isDaemon = true }.start()
            // Prueba rápida: si el shell no contesta, no merece la pena usarlo
            w.write(":\n"); w.write("echo @@RYXPROBE:0@@\n"); w.flush()
            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline) {
                val l = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (l.contains("@@RYXPROBE:")) { lines.clear(); return true }
            }
            close(); false
        } catch (e: Throwable) { close(); false }
    }

    private fun close() {
        try { writer?.close() } catch (e: Throwable) {}
        try { shell?.destroy() } catch (e: Throwable) {}
        shell = null; writer = null
        lines.clear()
    }

    /** Un proceso por comando (lo de siempre): respaldo fiable si el shell vivo no sirve. */
    private fun oneShot(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        Pair(p.waitFor(), out.trim())
    } catch (e: Exception) {
        Pair(-1, e.message ?: "sin acceso root")
    }
}
