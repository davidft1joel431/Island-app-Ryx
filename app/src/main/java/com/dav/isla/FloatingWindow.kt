package com.dav.isla

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build

/** Abre una app en ventana flotante con el tamaño, la posición y la animación elegidos. */
object FloatingWindow {

    /** Rectángulo de la ventana en píxeles reales de la pantalla (vertical). */
    fun bounds(c: Context): Rect {
        val m = c.resources.displayMetrics
        val sw = minOf(m.widthPixels, m.heightPixels); val sh = maxOf(m.widthPixels, m.heightPixels)
        val w = sw * Prefs.fwWidthPct(c) / 100
        val h = sh * Prefs.fwHeightPct(c) / 100
        val left = (sw - w) / 2
        val top = when (Prefs.fwPos(c)) {
            1 -> (sh * 0.07f).toInt()
            2 -> sh - h - (sh * 0.08f).toInt()
            else -> (sh - h) / 2
        }
        return Rect(left, top, left + w, top + h)
    }

    private fun anim(c: Context): Pair<Int, Int> = when (Prefs.fwAnim(c)) {
        1 -> R.anim.ryx_open_fade_in to R.anim.ryx_open_fade_out
        2 -> R.anim.ryx_open_slide_in to R.anim.ryx_open_slide_out
        else -> R.anim.ryx_open_spring_in to R.anim.ryx_open_spring_out
    }

    private fun failed(r: Pair<Int, String>) =
        r.first != 0 || r.second.contains("Exception") || r.second.contains("Error:") || r.second.contains("Unknown option")

    const val PREP = "settings put global enable_freeform_support 1; " +
        "settings put global force_resizable_activities 1; settings put global freeform_window_management 1"

    /** ¿Esta ROM acepta ventanas libres? Se consulta con Shizuku o root. */
    fun support(c: Context): String {
        val sb = StringBuilder("ROM: ${Build.DISPLAY}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val r = Shell.run(c, "pm list features | grep -i freeform; echo ---; " +
            "settings get global enable_freeform_support; settings get global force_resizable_activities; " +
            "settings get global freeform_window_management")
        if (r.first == -1) { sb.append("\nNo hay Shizuku con permiso ni root para consultar."); return sb.toString() }
        val parts = r.second.split("---")
        val feat = parts.getOrElse(0) { "" }
        val vals = parts.getOrElse(1) { "" }.trim().lines().map { it.trim() }
        val has = feat.contains("freeform_window_management")
        sb.append("\nLa ROM declara ventanas libres: ${if (has) "SÍ" else "NO"}\n")
        sb.append("enable_freeform_support = ${vals.getOrElse(0) { "?" }}\n")
        sb.append("force_resizable_activities = ${vals.getOrElse(1) { "?" }}\n")
        sb.append("freeform_window_management = ${vals.getOrElse(2) { "?" }}\n")
        if (!has && vals.getOrElse(0) { "" } != "1")
            sb.append("\nActiva «Permitir ventanas de forma libre» en Opciones de desarrollador, o usa «Activar y reiniciar».")
        else sb.append("\nSi acabas de cambiar estos valores, el sistema necesita un reinicio para aplicarlos.")
        return sb.toString()
    }

    /** Devuelve el registro de lo ocurrido (también se guarda para Opciones de desarrollador). */
    fun open(c: Context, pkg: String): String {
        val log = StringBuilder("Aplicación: $pkg\n")
        val launch = c.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { log.append("No se encontró la actividad principal."); return save(c, log) }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = bounds(c)
        log.append("Ventana: ${b.left},${b.top} → ${b.right},${b.bottom}\n")

        // 1) Con Shizuku o root: modo ventana libre + tamaño exacto, verificado con dumpsys
        val comp = launch.component?.flattenToShortString()
        if (comp != null) {
            // NEW_TASK = 0x10000000, MULTIPLE_TASK = 0x08000000 (am start no acepta --activity-new-task)
            val flags = if (Prefs.forceNew(c)) "-f 0x18000000" else "-f 0x10000000"
            val prep = Shell.run(c, PREP)
            log.append("Preparación (${Shell.label(c)}): código ${prep.first}\n")
            log.append(support(c)).append("\n")
            val attempts = listOf(
                "am start --user 0 --display 0 --windowingMode 5 $flags -n $comp",
                "am start --user 0 --windowingMode 5 $flags -n $comp",
                "am start --user 0 $flags -n $comp"
            )
            var started = false
            for ((i, cmd) in attempts.withIndex()) {
                val r = Shell.run(c, cmd)
                log.append("\nIntento ${i + 1}: código ${r.first}\n${r.second.take(260)}\n")
                if (!failed(r)) { started = true; break }
            }
            if (!started && !Shell.shizukuGranted())
                log.append("\nNo hay Shizuku con permiso ni root que respondan. Abre Ventana → Forzar apertura.\n")
            if (started) {
                Thread.sleep(700)
                var dump = taskDump(c, pkg)
                val id = Regex("Task\\{[^}]*#(\\d+)|taskId=(\\d+)").find(dump)
                    ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                var free = isFree(dump)
                log.append("\nTarea: ${id ?: "no encontrada"} · flotante al abrir: ${if (free) "SÍ" else "NO"}\n")
                if (id != null) {
                    if (!free) log.append("Forzar modo libre: " + Shell.run(c, "cmd activity task set-windowing-mode $id 5").second.trim() + "\n")
                    val rz = Shell.run(c, "cmd activity task resize $id ${b.left} ${b.top} ${b.right} ${b.bottom}; " +
                        "am task resize $id ${b.left} ${b.top} ${b.right} ${b.bottom}")
                    log.append("Tamaño: código ${rz.first} ${rz.second.trim().take(160)}\n")
                    Thread.sleep(300)
                    dump = taskDump(c, pkg); free = isFree(dump)
                }
                log.append("\nVerificación final (¿flotante?): ${if (free) "SÍ" else "NO"}\n")
                if (!free) log.append("\nSi no aparece: en Opciones de desarrollador activa «Permitir ventanas de forma libre» " +
                    "y «Forzar que las actividades sean redimensionables», deja apagado «Forzar modo escritorio» y reinicia una vez.")
                return save(c, log)       // ya se lanzó con privilegios; no se abre una segunda vez
            }
        } else log.append("Sin componente de arranque.\n")

        // 2) Sin privilegios: opciones de actividad con tamaño y animación propia (depende de la ROM)
        try {
            val (inn, out) = anim(c)
            val opts = ActivityOptions.makeCustomAnimation(c, inn, out)
            try {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 5)
            } catch (e: Throwable) { log.append("Sin acceso al modo libre (API oculta bloqueada).\n") }
            opts.setLaunchBounds(b)
            c.startActivity(launch, opts.toBundle())
            log.append("\nLanzada con ActivityOptions (animación propia).")
            return save(c, log)
        } catch (e: Exception) { log.append("\nFalló ActivityOptions: ${e.message}") }

        // 3) Apertura normal
        try { c.startActivity(launch); log.append("\nSe abrió en pantalla completa.") } catch (e: Exception) {}
        return save(c, log)
    }

    private fun taskDump(c: Context, pkg: String): String =
        Shell.run(c, "dumpsys activity activities | grep -i -B2 -A10 \"$pkg\" | head -80").second

    private fun isFree(dump: String) =
        dump.contains("freeform", ignoreCase = true) ||
            Regex("windowingMode=(5|\\w*freeform)", RegexOption.IGNORE_CASE).containsMatchIn(dump)

    private fun save(c: Context, log: StringBuilder): String {
        Prefs.setLastLog(c, log.toString()); return log.toString()
    }
}
