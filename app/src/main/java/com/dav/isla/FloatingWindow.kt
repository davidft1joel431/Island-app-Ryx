package com.dav.isla

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.provider.Settings

/**
 * Abre una app en FORMATO LIBRE o en BURBUJA (Android 17), encima de la app en la que estás.
 * Todo pasa por el servicio de accesibilidad de Ryx; no hace falta Shizuku, root ni el modo escritorio.
 */
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

    /** ¿Esta ROM acepta ventanas libres? Se lee con APIs públicas. */
    fun support(c: Context): String {
        val sb = StringBuilder("ROM: ${Build.DISPLAY}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val has = c.packageManager.hasSystemFeature("android.software.freeform_window_management")
        sb.append("\nLa ROM declara ventanas libres: ${if (has) "SÍ" else "NO"}\n")
        sb.append("\n${FreeformUnlock.status(c)}\n")
        sb.append("\nServicio de accesibilidad: ${if (RyxAccessibility.isEnabled(c)) "activo" else "apagado"}\n")
        if (!FreeformUnlock.unlocked(c))
            sb.append("\nActiva «Permitir ventanas de forma libre» y «Forzar que las actividades sean redimensionables» en Opciones de desarrollador " +
                "(o usa el botón «Desbloquear apps no redimensionables»).")
        else sb.append("\nTodo listo para el formato libre directo.")
        return sb.toString()
    }

    /** Abre [pkg] según el modo elegido. Devuelve el registro de lo ocurrido (también se guarda). Llamar fuera del hilo principal. */
    fun open(c: Context, pkg: String, pi: PendingIntent? = null, kind: Int? = null,
             user: android.os.UserHandle? = null, userId: Int = -1): String {
        // [user] solo viene si la app es un CLON (app dual): se abre esa copia y no la original
        val wantBubble = (kind ?: Prefs.fwKind(c)) == 1
        // La burbuja se crea desde el menú del icono en el launcher, que no distingue entre original y clon: con un clon se usa formato libre
        val bubble = wantBubble && user == null
        val log = StringBuilder("Hora: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n" +
            "Aplicación: $pkg${if (user != null) " (CLON, espacio $userId)" else ""}\nMétodo: ${if (bubble) "Burbuja (Android 17)" else "Formato libre directo"}\n")
        if (wantBubble && !bubble) log.append("Es un clon: la burbuja podría abrir la app original, así que se usa formato libre.\n")
        val svc = RyxAccessibility.instance
        if (svc == null) {
            log.append("El servicio de accesibilidad de Ryx está apagado. Actívalo en Ajustes de Android → Accesibilidad → Ryx Island " +
                "(si sale en gris: Info de la app → ⋮ → «Permitir ajustes restringidos»).\n")
            return save(c, log)
        }
        if (bubble) {
            val ok = try { svc.launchBubble(pkg, appLabel(c, pkg), Prefs.fwKeepApp(c), log) } catch (e: Throwable) {
                log.append("Error: ${e.javaClass.simpleName} ${e.message ?: ""}\n"); false }
            log.append(if (ok) "\nLa burbuja se queda en pantalla: la próxima vez Ryx solo toca su icono (instantáneo).\n"
                       else "\nNo se pudo crear la burbuja; mira arriba qué vio Ryx (si tu launcher no tiene la opción «Burbuja», esta ROM no la trae).\n")
            return save(c, log)
        }
        val b = bounds(c)
        log.append("Ventana pedida: ${b.left},${b.top} → ${b.right},${b.bottom}\n")
        val ok = svc.launchSmartIsland(pkg, pi, b, log, user)
        if (ok && Prefs.fwFrame(c)) {
            FloatFrame.show(c, "", b, pkg, appLabel(c, pkg) + if (user != null) " · clon" else "", user, userId)
            log.append("Barra de Ryx: mostrada sobre la ventana (mover, cambiar tamaño y cerrar).\n")
        }
        if (!ok) log.append("\nLa ROM abrió la app a pantalla completa con todas las variantes. Revisa que «Apps no redimensionables» " +
            "esté desbloqueado y que «Permitir ventanas de forma libre» esté activo en Opciones de desarrollador; si aun así no, usa el modo Burbuja.\n")
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

    /** Con Shizuku/root ya activos: id de la tarea más reciente de [pkg] (para moverla de verdad). Vacío si no se pudo. */
    fun findTaskId(c: Context, pkg: String, userId: Int = -1): String {
        val out = Shell.run(c, "dumpsys activity activities | grep -E \"Task\\{.*" + pkg + "\"").second
        // Con clon hay dos tareas del mismo paquete: se toma la del espacio correcto (la línea trae «U=<espacio>»)
        return out.lines().mapNotNull { ln ->
            val id = Regex("#(\\d+)").find(ln)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val u = Regex("U=(\\d+)").find(ln)?.groupValues?.get(1)?.toIntOrNull()
            if (userId >= 0 && u != null && u != userId) null else id
        }.maxOrNull()?.toString() ?: ""
    }

    /** Vuelve a pedir la ventana libre en otra posición/tamaño sin ventana nueva (Android reutiliza la tarea). */
    fun reposition(c: Context, pkg: String, r: Rect, user: android.os.UserHandle? = null) {
        RyxAccessibility.instance?.relaunchBounds(pkg, r, user)
    }

    /** Mueve/redimensiona una tarea ya abierta (solo con Shizuku o root). */
    fun resizeTask(c: Context, id: String, r: Rect) {
        // `am` arranca una VM entera (lento): solo se usa si `cmd` falla, no siempre
        Shell.run(c, "cmd activity task resize $id ${r.left} ${r.top} ${r.right} ${r.bottom} || " +
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

    private fun save(c: Context, log: StringBuilder): String {
        Prefs.setLastLog(c, log.toString()); return log.toString()
    }
}
