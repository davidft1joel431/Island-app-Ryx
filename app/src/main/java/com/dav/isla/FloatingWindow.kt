package com.dav.isla

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect

/** Abre una app en ventana flotante con el tamaño, la posición y la animación elegidos. */
object FloatingWindow {

    /** Rectángulo de la ventana en píxeles reales de la pantalla (vertical). */
    fun bounds(c: Context): Rect {
        val m = c.resources.displayMetrics
        val sw = m.widthPixels; val sh = m.heightPixels
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

    /** Devuelve el registro de lo ocurrido (también se guarda para Opciones de desarrollador). */
    fun open(c: Context, pkg: String): String {
        val log = StringBuilder("Aplicación: $pkg\n")
        val launch = c.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { log.append("No se encontró la actividad principal."); return save(c, log) }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = bounds(c)
        log.append("Ventana: ${b.left},${b.top} → ${b.right},${b.bottom}\n")

        // 1) Con Shizuku o root: modo ventana libre + tamaño exacto, y se verifica con dumpsys
        val comp = launch.component?.flattenToShortString()
        if (comp != null) {
            val extra = if (Prefs.forceNew(c)) "--activity-new-task --activity-multiple-task" else ""
            val prep = "settings put global enable_freeform_support 1; " +
                "settings put global force_resizable_activities 1; " +
                "settings put global freeform_window_management 1; "
            val res = Shell.run(c, prep + "am start --user 0 --display 0 --windowingMode 5 $extra -n $comp")
            log.append("\n${Shell.label(c)}: código ${res.first} ${res.second.trim()}\n")
            if (res.first != 0 && !Shell.shizukuGranted())
                log.append("\nNo hay Shizuku con permiso ni root. Abre la pestaña Ventana → Forzar apertura, " +
                    "inicia Shizuku y dale permiso a Ryx Island.\n")
            if (res.first == 0 && !res.second.contains("Error", ignoreCase = true)) {
                Thread.sleep(700)
                var dump = taskDump(c, pkg)
                val id = Regex("#(\\d+) type=|taskId=(\\d+)").find(dump)
                    ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                var free = isFree(dump)
                log.append("Tarea: ${id ?: "no encontrada"} · flotante al abrir: ${if (free) "SÍ" else "NO"}\n")
                if (id != null) {
                    if (!free) log.append("Forzar modo libre: " + Shell.run(c, "cmd activity task set-windowing-mode $id 5").second.trim() + "\n")
                    val rz = Shell.run(c, "cmd activity task resize $id ${b.left} ${b.top} ${b.right} ${b.bottom}; " +
                        "am task resize $id ${b.left} ${b.top} ${b.right} ${b.bottom}")
                    log.append("Tamaño: código ${rz.first} ${rz.second.trim()}\n")
                    Thread.sleep(300)
                    dump = taskDump(c, pkg); free = isFree(dump)
                }
                log.append("\nVerificación final (¿flotante?): ${if (free) "SÍ" else "NO"}\n")
                if (!free) log.append("\nSi no aparece: en Opciones de desarrollador desactiva «Forzar modo escritorio» y " +
                    "«Forzar actividades en cualquier app», deja solo «Permitir ventanas de forma libre» y reinicia.")
                if (free) return save(c, log)
            }
        } else log.append("Sin componente de arranque.\n")

        // 2) Sin root: opciones de actividad con tamaño y animación propia (depende de la ROM)
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
