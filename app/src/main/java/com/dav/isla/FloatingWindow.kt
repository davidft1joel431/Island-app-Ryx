package com.dav.isla

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.lang.reflect.Method

/**
 * Sistema de apertura de apps: FORMATO LIBRE del sistema (ventana flotante) o BARRA LATERAL.
 *
 * El modo se elige en Ajustes → Barra lateral:
 *  - 0 = Formato libre: la app se abre en ventana libre encima de la app actual.
 *  - 1 = Barra lateral: la app se abre normal y la barra lateral queda como acceso rápido.
 *
 * Todo pasa por el servicio de accesibilidad de Ryx para formato libre; no hace falta Shizuku, root ni
 * el modo escritorio. Ryx no dibuja una barra flotante propia sobre las ventanas abiertas: los controles
 * de mover/cerrar son los de la ROM o la barra lateral del launcher.
 */
object FloatingWindow {
    private val main = Handler(Looper.getMainLooper())

    /** Modo de ventana libre de Android (API 24+ usa reflection, API 34+ método público). */
    private const val WINDOWING_MODE_FREEFORM = 5

    /** Método oculto de ActivityOptions para establecer el modo de ventana (API < 34). */
    private val setLaunchWindowingModeMethod: Method? = try {
        ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
    } catch (e: Exception) { null }

    /** ¿La ROM soporta ventanas libres? Verifica feature flag y configuración del sistema. */
    fun isFreeformSupported(c: Context): Boolean {
        return try {
            val hasFeature = c.packageManager.hasSystemFeature("android.software.freeform_window_management")
            if (!hasFeature) false else true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Rectángulo de la ventana en píxeles reales de la pantalla.
     *
     * Se usan las medidas actuales de la pantalla (no min/max), así también funciona en horizontal y en
     * pantallas plegables. Se deja un margen para que la ventana no quede pegada a la barra lateral, al
     * borde ni a la barra de navegación; los controles (mover/cerrar) los pone el sistema o el launcher.
     */
    fun bounds(c: Context): Rect {
        val m = c.resources.displayMetrics
        val screen = if (Build.VERSION.SDK_INT >= 30) {
            try {
                (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            } catch (t: Throwable) { Rect(0, 0, m.widthPixels, m.heightPixels) }
        } else Rect(0, 0, m.widthPixels, m.heightPixels)
        val sw = screen.width().coerceAtLeast(1)
        val sh = screen.height().coerceAtLeast(1)
        val density = c.resources.displayMetrics.density
        val margin = (minOf(sw, sh) * 0.045f).toInt().coerceAtLeast((10 * density).toInt())
        val minW = (220 * density).toInt().coerceAtMost(sw - margin * 2)
        val minH = (240 * density).toInt().coerceAtMost(sh - margin * 2)
        val maxW = (sw - margin * 2).coerceAtLeast(1)
        val maxH = (sh - margin * 2).coerceAtLeast(1)
        val w = (sw * Prefs.fwWidthPct(c) / 100).coerceIn(minW.coerceAtMost(maxW), maxW)
        val h = (sh * Prefs.fwHeightPct(c) / 100).coerceIn(minH.coerceAtMost(maxH), maxH)
        val left = ((sw - w) / 2).coerceIn(margin, (sw - w - margin).coerceAtLeast(margin))
        val top = when (Prefs.fwPos(c)) {
            1 -> margin + (sh * 0.04f).toInt()
            2 -> sh - h - margin - (sh * 0.05f).toInt()
            else -> (sh - h) / 2
        }.coerceIn(margin, (sh - h - margin).coerceAtLeast(margin))
        return Rect(left, top, left + w, top + h)
    }

    /** ¿Esta ROM acepta ventanas libres? Se lee con APIs públicas. */
    fun support(c: Context): String {
        val sb = StringBuilder("ROM: ${Build.DISPLAY}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val hasFeature = c.packageManager.hasSystemFeature("android.software.freeform_window_management")
        val isSupported = isFreeformSupported(c)
        sb.append("\nLa ROM declara ventanas libres: ${if (hasFeature) "SÍ" else "NO"}\n")
        sb.append("Soporte detectado: ${if (isSupported) "SÍ" else "NO (puede seguir funcionando con desbloqueo)"}\n")
        sb.append("\n${FreeformUnlock.status(c)}\n")
        sb.append("\nServicio de accesibilidad: ${if (RyxAccessibility.isEnabled(c)) "activo" else "apagado"}\n")
        sb.append("Método con privilegios (Shizuku/root): ${if (Shell.privileged(c)) "disponible · ${Shell.label(c)}" else "no disponible"}\n")
        val v = Prefs.fwVariant(c)
        sb.append("Método de apertura recordado: ${if (v in RyxAccessibility.VARIANTS.indices) "${v + 1} · ${RyxAccessibility.VARIANTS[v]}" else "sin calibrar (se prueba al abrir)"}\n")
        if (Build.VERSION.SDK_INT >= 36)
            sb.append("\nAndroid 16+: esta ROM puede traer ventanas de escritorio por pantalla; si el formato libre falla, revisa «Escritorio» en Opciones de desarrollador.\n")
        if (!FreeformUnlock.unlocked(c))
            sb.append("\nActiva «Permitir ventanas de forma libre» y «Forzar que las actividades sean redimensionables» en Opciones de desarrollador " +
                "(o usa el botón «Desbloquear apps no redimensionables»).")
        else sb.append("\nTodo listo para el formato libre directo.")
        return sb.toString()
    }

    /**
     * Abre [pkg] según el modo elegido en [Prefs.openMode].
     *  - Modo 0 (formato libre): ventana libre por accesibilidad.
     *  - Modo 1 (barra lateral): apertura normal; la barra lateral se encarga del acceso rápido.
     * Devuelve el registro de lo ocurrido (también se guarda). Llamar fuera del hilo principal.
     */
    fun open(c: Context, pkg: String, pi: PendingIntent? = null, kind: Int? = null,
             user: android.os.UserHandle? = null, userId: Int = -1): String {
        val mode = Prefs.openMode(c)
        if (mode == 1) {
            // Barra lateral: apertura normal, la barra lateral ya está disponible como acceso rápido
            return openNormal(c, pkg, pi, user, userId)
        }
        // Formato libre del sistema
        return openFreeform(c, pkg, pi, kind, user, userId)
    }

    /** Apertura normal (sin formato libre): se lanza la actividad principal. */
    private fun openNormal(c: Context, pkg: String, pi: PendingIntent?,
                           user: android.os.UserHandle?, userId: Int): String {
        val log = StringBuilder("Hora: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n" +
            "Aplicación: $pkg${if (user != null) " (CLON, espacio $userId)" else ""}\nMétodo: Barra lateral / Normal\n")
        try {
            if (pi != null) {
                // PendingIntent: abrir el destino exacto de la notificación
                try {
                    pi.send(c, 0, null)
                } catch (e: Exception) {}
                log.append("Destino de notificación abierto.\n")
                return save(c, log)
            }
            val intent = c.packageManager.getLaunchIntentForPackage(pkg) ?: run {
                log.append("No se encontró la actividad principal de $pkg.\n")
                return save(c, log)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (userId >= 0) {
                // Para clones, lanzar en el espacio de usuario correcto
                try {
                    val la = c.getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
                    la.startMainActivity(intent.component, null, null, null)
                } catch (e: Exception) {
                    c.startActivity(intent)
                }
            } else {
                c.startActivity(intent)
            }
            log.append("App abierta normalmente. La barra lateral permanece disponible.\n")
        } catch (e: Exception) {
            log.append("Error al abrir: ${e.javaClass.simpleName} ${e.message ?: ""}\n")
        }
        return save(c, log)
    }

    /** Apertura en formato libre del sistema (ventana flotante). */
    private fun openFreeform(c: Context, pkg: String, pi: PendingIntent?, kind: Int?,
                             user: android.os.UserHandle?, userId: Int): String {
        // [user] solo viene si la app es un CLON (app dual): se abre esa copia y no la original.
        // La burbuja se retiró del menú: solo se usa si un flujo antiguo la pide explícitamente.
        val wantBubble = kind == 1
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
        val ok = svc.launchSmartIsland(pkg, pi, b, log, user, userId)
        if (ok) log.append("\nLa ventana la controla el sistema: mueve, redimensiona o cierra con los botones de la ROM " +
            "o con tu barra lateral. Ryx no añade ninguna barra encima.\n")
        if (!ok) log.append("\nLa ROM abrió la app a pantalla completa con todas las variantes. Revisa que «Apps no redimensionables» " +
            "esté desbloqueado y que «Permitir ventanas de forma libre» esté activo en Opciones de desarrollador. " +
            "Si tu launcher tiene barra lateral, abre la app desde ahí para colocarla en formato libre.\n")
        return save(c, log)
    }

    /** Abre [pkg] directamente desde la barra lateral (un solo toque). */
    fun openFromSidebar(c: Context, pkg: String, userId: Int) {
        val mode = Prefs.openMode(c)
        if (mode == 0) {
            val svc = RyxAccessibility.instance
            if (svc != null) {
                val b = bounds(c)
                svc.launchSmartIsland(pkg, null, b, StringBuilder(), null, userId)
            } else {
                val intent = c.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } ?: return
                c.startActivity(intent)
            }
        } else {
            val intent = c.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return
            c.startActivity(intent)
        }
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
            // Intentar establecer windowing mode libre por reflection (funciona en todas las APIs)
            if (setLaunchWindowingModeMethod != null) {
                try { setLaunchWindowingModeMethod!!.invoke(opts, WINDOWING_MODE_FREEFORM) } catch (e: Exception) {}
            } else {
                // Fallback para API 34+: intentar método público por reflection
                try {
                    val m = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    m.invoke(opts, WINDOWING_MODE_FREEFORM)
                } catch (e: Exception) {}
            }
            // También poner la clave bundle directa (fallback para algunas ROMs)
            val bundle = opts.toBundle() ?: android.os.Bundle()
            bundle.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM)
            // FLAG_ACTIVITY_LAUNCH_ADJACENT: abrir al lado de la app actual (como Taskbar)
            try {
                val adjField = Intent::class.java.getField("FLAG_ACTIVITY_LAUNCH_ADJACENT")
                val adjValue = adjField.getInt(null)
                // Se aplicará al intent al enviar el pending
            } catch (e: Exception) {}
            if (bounds != null) opts.setLaunchBounds(bounds)
        }
        main.post { pi.send(c, 0, null, null, null, null, opts.toBundle()) }
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
    fun reposition(c: Context, pkg: String, r: Rect, user: android.os.UserHandle? = null, userId: Int = -1) {
        RyxAccessibility.instance?.relaunchBounds(pkg, r, user, userId)
    }

    /** Mueve/redimensiona una tarea ya abierta (solo con Shizuku o root). Devuelve true si el comando respondió sin error. */
    fun resizeTask(c: Context, id: String, r: Rect): Boolean {
        // `am` arranca una VM entera (lento): solo se usa si `cmd` falla, no siempre
        val out = Shell.run(c, "cmd activity task resize $id ${r.left} ${r.top} ${r.right} ${r.bottom} || " +
            "am task resize $id ${r.left} ${r.top} ${r.right} ${r.bottom}")
        return out.first == 0 && !out.second.contains("Error", true)
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
