package com.dav.isla

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.provider.Settings

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

    /** ¿Esta ROM acepta ventanas libres? Se lee con APIs públicas: no hace falta Shizuku ni root. */
    fun support(c: Context): String {
        val sb = StringBuilder("ROM: ${Build.DISPLAY}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val has = c.packageManager.hasSystemFeature("android.software.freeform_window_management")
        fun g(k: String): String = try { Settings.Global.getString(c.contentResolver, k) ?: "sin definir" } catch (e: Exception) { "?" }
        val f1 = g("enable_freeform_support")
        sb.append("\nLa ROM declara ventanas libres: ${if (has) "SÍ" else "NO"}\n")
        sb.append("enable_freeform_support = $f1\n")
        sb.append("force_resizable_activities = ${g("force_resizable_activities")}\n")
        sb.append("freeform_window_management = ${g("freeform_window_management")}\n")
        sb.append("\nMétodo actual: ${Shell.label(c)}\n")
        if (!has && f1 != "1")
            sb.append("\nActiva «Permitir ventanas de forma libre» en Opciones de desarrollador y reinicia.")
        else if (!has)
            sb.append("\nLa ROM no declara la función, pero enable_freeform_support = 1 la habilita. " +
                "Lo que cuenta es la prueba real de apertura.")
        else sb.append("\nSi acabas de cambiar estos valores, el sistema necesita un reinicio para aplicarlos.")
        return sb.toString()
    }

    /** Devuelve el registro de lo ocurrido (también se guarda para Opciones de desarrollador). */
    fun open(c: Context, pkg: String): String {
        val log = StringBuilder("Aplicación: $pkg\nMétodo: ${Shell.label(c)}\n")
        val launch = c.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { log.append("No se encontró la actividad principal."); return save(c, log) }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = bounds(c)
        log.append("Ventana: ${b.left},${b.top} → ${b.right},${b.bottom}\n")

        // 1) Con Shizuku (o root, solo si el usuario lo eligió): modo libre + tamaño exacto, verificado con dumpsys
        val comp = launch.component?.flattenToShortString()
        if (comp != null && Shell.privileged(c)) {
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
            if (started) {
                Thread.sleep(700)
                var dump = taskDump(c, pkg)
                val id = Regex("Task\\{[^}]*#(\\d+)|taskId=(\\d+)").find(dump)
                    ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                var free = isFreeTask(dump, id)
                log.append("\nTarea: ${id ?: "no encontrada"} · flotante al abrir: ${if (free) "SÍ" else "NO"}\n")
                if (id != null) {
                    if (!free) log.append("Forzar modo libre: " + Shell.run(c, "cmd activity task set-windowing-mode $id 5").second.trim() + "\n")
                    val rz = Shell.run(c, "cmd activity task resize $id ${b.left} ${b.top} ${b.right} ${b.bottom}; " +
                        "am task resize $id ${b.left} ${b.top} ${b.right} ${b.bottom}")
                    log.append("Tamaño: código ${rz.first} ${rz.second.trim().take(160)}\n")
                    Thread.sleep(300)
                    dump = taskDump(c, pkg); free = isFreeTask(dump, id)
                }
                log.append("\nVerificación final (¿flotante?): ${if (free) "SÍ" else "NO"}\n")
                if (!free) log.append("\nSi no aparece: en Opciones de desarrollador activa «Permitir ventanas de forma libre» " +
                    "y «Forzar que las actividades sean redimensionables», deja apagado «Forzar modo escritorio» y reinicia una vez.")
                return save(c, log)       // ya se lanzó con privilegios; no se abre una segunda vez
            }
            log.append("\nLos comandos fallaron; se prueba sin privilegios.\n")
        } else if (Shell.method(c) != 3) {
            log.append("Sin Shizuku activo; se abre sin privilegios (nunca se usa root si no lo eliges).\n")
        }

        // 2) Sin privilegios: solo opciones de Android para cualquier app (depende de la ROM)
        try {
            unlockHiddenApi(log)
            val (inn, out) = anim(c)
            val opts = ActivityOptions.makeCustomAnimation(c, inn, out)
            try { opts.setLaunchDisplayId(0) } catch (e: Throwable) {}
            var modeAsked = false
            try {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 5)
                modeAsked = true
            } catch (e: Throwable) { log.append("Modo libre: no accesible (${e.javaClass.simpleName}).\n") }
            opts.setLaunchBounds(b)
            if (Prefs.forceNew(c)) launch.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            c.startActivity(launch, opts.toBundle())
            log.append("\nLanzada sin privilegios · modo libre pedido: ${if (modeAsked) "sí" else "no"}\n" +
                "Ryx no puede comprobar el resultado sin permisos: mira si la ventana quedó flotante.")
            return save(c, log)
        } catch (e: Exception) { log.append("\nFalló la apertura sin privilegios: ${e.message}") }

        // 3) Apertura normal
        try { c.startActivity(launch); log.append("\nSe abrió en pantalla completa.") } catch (e: Exception) {}
        return save(c, log)
    }

    /** Quita la restricción de APIs internas solo para poder pedir el modo ventana libre. */
    private fun unlockHiddenApi(log: StringBuilder) {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            log.append("APIs internas: desbloqueadas\n")
        } catch (e: Throwable) {
            log.append("APIs internas: no se pudo (${e.javaClass.simpleName})\n")
        }
    }

    private fun taskDump(c: Context, pkg: String): String =
        Shell.run(c, "dumpsys activity activities | grep -i -B2 -A10 \"$pkg\" | head -80").second

    /** Mira solo la línea de ESA tarea (Task con #id y mode=freeform), no todo el volcado. */
    private fun isFreeTask(dump: String, id: String?): Boolean {
        if (id == null) return isFree(dump)
        val lines = dump.lines().filter { it.contains("#$id ") }
        return if (lines.isEmpty()) isFree(dump) else lines.any { it.contains("freeform", ignoreCase = true) }
    }

    private fun isFree(dump: String) =
        dump.contains("freeform", ignoreCase = true) ||
            Regex("windowingMode=(5|\\w*freeform)", RegexOption.IGNORE_CASE).containsMatchIn(dump)

    private fun save(c: Context, log: StringBuilder): String {
        Prefs.setLastLog(c, log.toString()); return log.toString()
    }
}
