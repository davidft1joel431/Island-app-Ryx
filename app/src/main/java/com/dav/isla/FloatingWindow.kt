package com.dav.isla

import android.app.ActivityOptions
import android.app.PendingIntent
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
        sb.append("force_desktop_mode_on_external_displays = ${g("force_desktop_mode_on_external_displays")}\n")
        // Recursos internos de la ROM que indican si hay modo escritorio (solo se muestran los que existen)
        val sys = android.content.res.Resources.getSystem()
        listOf("config_isDesktopModeSupported", "config_canInternalDisplayHostDesktops", "config_enableDesktopWindowing",
            "config_desktopModeSupported", "config_freeformWindowManagement").forEach { n ->
            val id = sys.getIdentifier(n, "bool", "android")
            if (id != 0) try { sb.append("$n = ${sys.getBoolean(id)}\n") } catch (e: Exception) {}
        }
        sb.append("\nMétodo actual: ${Shell.label(c)}\n")
        if (sysBool("config_canInternalDisplayHostDesktops") == false)
            sb.append("\nEsta ROM solo permite el modo escritorio en pantallas externas: en el teléfono el sistema no dibuja " +
                "la barra de las ventanas libres de apps de terceros. Para moverla usa las asas de Ryx con Shizuku.\n")
        if (!has && f1 != "1")
            sb.append("\nActiva «Permitir ventanas de forma libre» en Opciones de desarrollador y reinicia.")
        else if (!has)
            sb.append("\nLa ROM no declara la función, pero enable_freeform_support = 1 la habilita. " +
                "Lo que cuenta es la prueba real de apertura.")
        else sb.append("\nSi acabas de cambiar estos valores, el sistema necesita un reinicio para aplicarlos.")
        return sb.toString()
    }

    /**
     * «Ventana flotante · solo accesibilidad»: únicamente la función de Smart Island desde el servicio de accesibilidad.
     * No usa Shizuku, root, Recientes, inicio ni el menú del launcher.
     */
    fun openSolo(c: Context, pkg: String, pi: PendingIntent? = null): String {
        val log = StringBuilder("Hora: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n" +
            "Aplicación: $pkg\nMétodo: solo accesibilidad (función de Smart Island)\n")
        val svc = RyxAccessibility.instance
        if (svc == null) {
            log.append("El servicio de accesibilidad de Ryx está apagado. Actívalo en Ajustes de Android → Accesibilidad → Ryx Island " +
                "(si sale en gris: Info de la app → ⋮ → «Permitir ajustes restringidos»).\n")
            return save(c, log)
        }
        val b = bounds(c)
        log.append("Ventana pedida: ${b.left},${b.top} → ${b.right},${b.bottom}\n")
        svc.launchSmartIsland(pkg, pi, b, log)
        log.append("\nSi la ventana salió a pantalla completa, tu ROM no respetó el modo libre pedido por una app normal; " +
            "las medidas de arriba lo confirman.")
        return save(c, log)
    }

    /** Devuelve el registro de lo ocurrido (también se guarda para Opciones de desarrollador). */
    fun open(c: Context, pkg: String, pi: PendingIntent? = null): String {
        if (Prefs.fwSolo(c)) return openSolo(c, pkg, pi)
        // Auto: si no hay Shizuku, se prueba root (Magisk) una vez; así funciona en segundo plano sin tocar tu pantalla
        if (Shell.method(c) == 0 && !Shell.shizukuGranted()) Shell.probeRoot()
        val log = StringBuilder("Hora: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n" +
            "Aplicación: $pkg\nMétodo: ${Shell.label(c)}\n")
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
                if (id != null && Prefs.fwFrame(c)) { FloatFrame.show(c, id, b, pkg); log.append("Asas de Ryx: mostradas (mover y cambiar tamaño)\n") }
                if (!free) log.append("\nSi no aparece: en Opciones de desarrollador activa «Permitir ventanas de forma libre» " +
                    "y «Forzar que las actividades sean redimensionables», activa el modo escritorio de la ROM y reinicia una vez.")
                return save(c, log)       // ya se lanzó con privilegios; no se abre una segunda vez
            }
            log.append("\nLos comandos fallaron; se prueba sin privilegios.\n")
        } else if (Shell.method(c) != 3) {
            log.append("Sin Shizuku activo; se abre sin privilegios (nunca se usa root si no lo eliges).\n")
        }

        // 2·0) Apertura directa «a lo Smart Island»: la lanza el servicio de accesibilidad, sin tocar tu pantalla
        //      ni pasar por Recientes/inicio. Es el camino preferido: no te saca de tu app.
        if (Prefs.fwMode(c) != 1) {
            val svc0 = RyxAccessibility.instance
            if (svc0 != null) {
                log.append("Apertura directa desde accesibilidad (sin tocar tu pantalla):\n")
                val ok0 = try { svc0.launchFreeformDirect(pkg, pi, b, log) } catch (e: Throwable) {
                    log.append("Error: ${e.javaClass.simpleName} ${e.message ?: ""}\n"); false }
                if (ok0) return save(c, log)
                if (Prefs.fwKeepApp(c)) {
                    log.append("\nLa ROM no respetó el modo libre con ninguna variante. Para no sacarte de tu app no uso el menú del launcher " +
                        "(apaga «Mantener mi app de fondo» si prefieres que lo use).\n")
                    return save(c, log)
                }
            } else if (Prefs.fwMode(c) == 2) {
                log.append("Accesibilidad apagada: actívala para abrir sin sacarte de tu app.\n")
            }
        }

        // 2a) «Formato libre» del propio launcher, pulsado por el servicio de accesibilidad de Ryx
        if (Prefs.fwMode(c) == 2) {
            val svc = RyxAccessibility.instance
            if (svc == null) {
                log.append("Formato libre del launcher: el servicio de accesibilidad de Ryx está apagado " +
                    "(Ajustes de Android → Accesibilidad → Ryx Island). Se abre de forma directa.\n")
            } else {
                log.append("Formato libre del launcher (accesibilidad):\n")
                val prev = svc.currentPackage()          // la app en la que estabas, para devolverla al fondo
                val ok = try { svc.launchViaLauncher(pkg, appLabel(c, pkg), log, Prefs.fwKeepApp(c)) } catch (e: Throwable) {
                    log.append("Error: ${e.javaClass.simpleName} ${e.message ?: ""}\n"); false }
                if (ok) {
                    if (pi != null) {
                        Thread.sleep(500)
                        log.append(if (sendPending(c, pi, true, null)) "Abrí el destino exacto de la notificación (el chat).\n"
                                   else "No pude abrir el destino exacto de la notificación.\n")
                    }
                    if (Prefs.fwKeepApp(c) && svc.launchedFirst) try { svc.keepBehind(prev, pkg, log) } catch (e: Throwable) {}
                    log.append("\nLa ventana la abre el sistema desde el menú de tu launcher, con su barra y su tamaño habitual.\n")
                    return save(c, log)
                }
                log.append("No se pudo por ese camino; se abre de forma directa.\n")
            }
        }

        // 2b) Sin privilegios: solo opciones de Android para cualquier app (depende de la ROM)
        try {
            unlockHiddenApi(log)
            // Modo 0: igual que el menú «Formato libre» del lanzador (opciones básicas, sin animación propia).
            // Modo 1: con la animación de apertura de Ryx.
            val launcherLike = Prefs.fwMode(c) != 1
            val opts = if (launcherLike) ActivityOptions.makeBasic() else {
                val (inn, out) = anim(c); ActivityOptions.makeCustomAnimation(c, inn, out)
            }
            log.append("Apertura: ${if (launcherLike) "como el lanzador" else "con animación de Ryx"}\n")
            try { opts.setLaunchDisplayId(0) } catch (e: Throwable) {}
            var modeAsked = false
            try {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 5)
                modeAsked = true
            } catch (e: Throwable) { log.append("Modo libre: no accesible (${e.javaClass.simpleName}).\n") }
            if (Prefs.fwOwnBounds(c)) opts.setLaunchBounds(b) else log.append("Tamaño y posición: los elige Android.\n")
            if (Prefs.forceNew(c)) launch.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            if (pi != null && sendPending(c, pi, true, if (Prefs.fwOwnBounds(c)) b else null)) {
                log.append("Abrí el destino exacto de la notificación (el chat) en ventana libre.\n")
            } else c.startActivity(launch, opts.toBundle())
            if (Prefs.fwFrame(c)) { FloatFrame.show(c, "", b, pkg); log.append("Asas de Ryx: mostradas (modo sin privilegios, experimental)\n") }
            log.append("\nLanzada sin privilegios · modo libre pedido: ${if (modeAsked) "sí" else "no"}\n" +
                "Ryx no puede comprobar el resultado sin permisos: mira si la ventana quedó flotante.\n" + snapshot(c))
            return save(c, log)
        } catch (e: Exception) { log.append("\nFalló la apertura sin privilegios: ${e.message}") }

        // 3) Apertura normal
        try { c.startActivity(launch); log.append("\nSe abrió en pantalla completa.") } catch (e: Exception) {}
        return save(c, log)
    }

    @Suppress("DEPRECATION")
    private fun appLabel(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /**
     * Abre el destino exacto de una notificación (por ejemplo, el chat) con su PendingIntent.
     * Con [freeform] pide ventana libre; si la tarea ya es libre, se reutiliza y se queda como está.
     */
    fun sendPending(c: Context, pi: PendingIntent, freeform: Boolean, bounds: Rect?): Boolean = try {
        if (freeform) unlockHiddenApi(StringBuilder())
        val opts = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        if (freeform) {
            try { opts.setLaunchDisplayId(0) } catch (e: Throwable) {}
            try {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 5)
            } catch (e: Throwable) {}
            if (bounds != null) opts.setLaunchBounds(bounds)
        }
        pi.send(c, 0, null, null, null, null, opts.toBundle())
        true
    } catch (e: Exception) { false }

    private fun sysBool(name: String): Boolean? = try {
        val r = android.content.res.Resources.getSystem()
        val id = r.getIdentifier(name, "bool", "android")
        if (id == 0) null else r.getBoolean(id)
    } catch (e: Exception) { null }

    /** Estado del sistema en pocas líneas, para el registro. */
    private fun snapshot(c: Context): String {
        fun g(k: String): String = try { Settings.Global.getString(c.contentResolver, k) ?: "-" } catch (e: Exception) { "?" }
        val has = c.packageManager.hasSystemFeature("android.software.freeform_window_management")
        val canHost = sysBool("config_canInternalDisplayHostDesktops")
        val sb = StringBuilder("\n--- Estado del sistema ---\n")
        sb.append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}\n")
        sb.append("Ventanas libres declaradas: ${if (has) "sí" else "no"} · enable_freeform_support=${g("enable_freeform_support")} · " +
            "force_resizable_activities=${g("force_resizable_activities")}\n")
        sb.append("Escritorio soportado: ${sysBool("config_isDesktopModeSupported") ?: "?"} · " +
            "escritorio en pantalla del teléfono: ${canHost ?: "?"}\n")
        sb.append("Modo de apertura: ${when (Prefs.fwMode(c)) { 2 -> "Formato libre del launcher"; 1 -> "con animación Ryx"; else -> "directo" }} · accesibilidad: ${if (RyxAccessibility.isEnabled(c)) "activa" else "apagada"} · " +
            "tamaño propio: ${if (Prefs.fwOwnBounds(c)) "sí" else "no"} · ventana nueva: ${if (Prefs.forceNew(c)) "sí" else "no"}\n")
        sb.append("\nVeredicto: sin privilegios Ryx no puede saber si la ventana quedó flotante ni moverla.\n")
        if (canHost == false) sb.append("Si salió sin barra es porque esta ROM no dibuja la barra de ventana libre en el teléfono " +
            "(escritorio solo en pantallas externas). Opciones: asas de Ryx (experimental sin privilegios) o Shizuku (no es root), " +
            "con el que Ryx sí mueve y redimensiona la ventana real.\n")
        return sb.toString()
    }

    /** Sin privilegios: vuelve a pedir la ventana libre en otra posición/tamaño (experimental; depende de la ROM). */
    fun reposition(c: Context, pkg: String, r: Rect) {
        try {
            val launch = c.packageManager.getLaunchIntentForPackage(pkg) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            unlockHiddenApi(StringBuilder())
            val opts = ActivityOptions.makeBasic()
            try { opts.setLaunchDisplayId(0) } catch (e: Throwable) {}
            try {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 5)
            } catch (e: Throwable) {}
            opts.setLaunchBounds(r)
            c.startActivity(launch, opts.toBundle())
        } catch (e: Exception) {}
    }

    /** Mueve/redimensiona una tarea ya abierta (solo con Shizuku o root). */
    fun resizeTask(c: Context, id: String, r: Rect) {
        Shell.run(c, "cmd activity task resize $id ${r.left} ${r.top} ${r.right} ${r.bottom}; " +
            "am task resize $id ${r.left} ${r.top} ${r.right} ${r.bottom}")
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
