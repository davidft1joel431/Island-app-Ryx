package com.dav.isla

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.ImageView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Servicio de accesibilidad de Ryx. Solo hace dos cosas, las dos cuando tú abres una app desde la isla:
 *  - FORMATO LIBRE: lanza la app en ventana libre con las opciones oficiales de Android (sin salir de tu app).
 *  - BURBUJA (Android 17): toca la burbuja si ya existe y, si no, pulsa «Burbuja» en el menú del launcher (solo la primera vez).
 * No lee ni guarda nada por su cuenta.
 */
class RyxAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: RyxAccessibility? = null

        fun isEnabled(c: Context): Boolean {
            if (instance != null) return true
            val v = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val cn = ComponentName(c, RyxAccessibility::class.java)
            return v.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
        }

        /** Opción «Burbuja» del menú del launcher (Android 17). */
        private val BUBBLE = Regex("burbuja|bubble", RegexOption.IGNORE_CASE)

        /**
         * Variantes de apertura directa en formato libre. La última usa Shizuku/root (`am start --windowingMode 5`),
         * que en ROMs que ignoran las ActivityOptions es la que sí funciona; se redimensiona en el acto porque
         * `am start` no acepta límites.
         */
        val VARIANTS = listOf("estándar (clave windowingMode)", "con pantalla 0", "sin ventana nueva", "método interno",
            "con Shizuku/root (am start)")
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { hideCurtain(); instance = null; return super.onUnbind(intent) }
    /** Cambios de ventanas (mover, redimensionar, cerrar): la barra de Ryx se entera al instante y se pega a la ventana real. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> FloatFrame.onWindowsChanged()
            // Una app abrió una ventana: sus avisos pendientes en la isla ya se leyeron
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> event?.packageName?.toString()?.let { NotifListener.instance?.onAppOpened(it) }
        }
    }
    override fun onInterrupt() {}

    /** Paquete de la app que está al frente ahora (la ventana activa). */
    fun currentPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // FORMATO LIBRE DIRECTO: lo lanza este servicio (tiene ventana propia y exención de segundo plano) pidiendo el modo libre
    // con ActivityOptions + la clave «android.activity.windowingMode» = 5. Android no avisa si la ROM lo respetó, así que
    // se comprueba con las ventanas que ve accesibilidad y se prueban variantes; la que funciona se recuerda.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun fire(v: Int, launch: Intent, pi: PendingIntent?, b: Rect, reuse: Boolean = false,
                     pkg: String = "", user: android.os.UserHandle? = null, userId: Int = -1): Boolean = try {
        if (v == 4 && pi == null && pkg.isNotEmpty()) return fireShell(userId, pkg, b, reuse)
        val opts = ActivityOptions.makeBasic()
        try { opts.setLaunchBounds(b) } catch (e: Throwable) {}
        if (v == 1) try { opts.setLaunchDisplayId(0) } catch (e: Throwable) {}
        if (Build.VERSION.SDK_INT >= 34) try {
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        } catch (e: Throwable) {}
        if (v == 3) try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 5)
        } catch (e: Throwable) {}
        val bundle = opts.toBundle() ?: android.os.Bundle()
        if (v != 3) bundle.putInt("android.activity.windowingMode", 5)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or (if (v == 2 || reuse) 0 else Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (pi != null) {
            pi.send(this, 0, Intent().addFlags(flags), null, null, null, bundle)
        } else if (pkg.isNotEmpty() && (user != null || hasOtherSpace(pkg))) {
            // Clon (app dual): se lanza SU copia en su espacio; el intent normal abriría la original.
            // Y si la app TIENE clon, la ORIGINAL también se lanza indicando su espacio (el principal): con un startActivity normal
            // la ROM decide sola entre las dos copias y puede abrir el clon en lugar de la original.
            val target = user ?: android.os.Process.myUserHandle()
            val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val acts = try { la.getActivityList(pkg, target) } catch (e: Throwable) { null }
            val want = if (user == null) launch.component else null
            val comp = acts?.firstOrNull { want != null && it.componentName == want }?.componentName
                ?: acts?.firstOrNull()?.componentName
            // Espacio oculto (clon de MIUI/OPPO…): la API pública no lo ve → se lanza con Shizuku/root
            if (comp != null) la.startMainActivity(comp, target, null, bundle)
            else if (!fireShell(userId, pkg, b, reuse)) return false
        } else {
            launch.flags = flags
            startActivity(launch, bundle)
        }
        true
    } catch (e: Throwable) { false }

    // Tareas abiertas por el método con privilegios, para redimensionarlas sin volver a buscarlas.
    private val taskIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Apertura con Shizuku/root: `am start --windowingMode 5` (la ROM no puede ignorarlo) y, en el acto, un
     * `cmd activity task resize` porque `am start` no acepta límites. Llamar fuera del hilo principal.
     */
    private fun fireShell(userId: Int, pkg: String, b: Rect, reuse: Boolean): Boolean {
        if (pkg.isEmpty() || !Shell.privileged(this)) return false
        val u = if (userId >= 0) userId else Clones.myUser()
        val flags = if (reuse) "0x10000000" else "0x18000000"      // NEW_TASK (| MULTIPLE_TASK)
        val comp = shellComponent(u, pkg)
        val target = if (comp != null) "-n $comp"
            else "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
        val cmd = "am start --user $u -f $flags --windowingMode 5 $target"
        val r = try { Shell.run(this, cmd) } catch (e: Throwable) { return false }
        val out = r.second
        if (r.first != 0 || out.contains("Error", true) || out.contains("does not exist", true)) return false
        // `am start` no acepta límites: se coloca la ventana real en el acto (la tarea tarda un instante en existir)
        val key = "$pkg@$u"
        var id = taskIds[key] ?: ""
        if (id.isEmpty()) {
            pause(220)
            id = FloatingWindow.findTaskId(this, pkg, u)
            if (id.isNotEmpty()) taskIds[key] = id
        }
        if (id.isNotEmpty()) FloatingWindow.resizeTask(this, id, b)
        return true
    }

    /** Componente del launcher de [pkg] en el espacio [userId], en formato «paquete/clase» (lo que acepta `am -n`). */
    private fun shellComponent(userId: Int, pkg: String): String? = try {
        val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        la.getActivityList(pkg, Clones.handle(userId)).firstOrNull()?.componentName?.flattenToShortString()
    } catch (e: Throwable) { null }

    /** ¿[pkg] está también en otro espacio (clon / app dual)? Lo sabe [Clones] (copia vista, perfiles o usuarios de la ROM). */
    private fun hasOtherSpace(pkg: String): Boolean = Clones.hasClone(this, pkg)

    /**
     * Si la app tiene clon y se pidió la ORIGINAL, lo anota en el registro. «Acceso de uso» solo ve el espacio principal:
     * si ahí aparece [pkg] al frente, lo que se abrió es la original; si no, puede haberse abierto el clon.
     */
    private fun checkSpace(pkg: String, user: android.os.UserHandle?, pi: PendingIntent?, log: StringBuilder) {
        if (user != null || pi != null || !hasOtherSpace(pkg)) return
        if (!Foreground.hasAccess(this)) {
            log.append("Espacio: esta app tiene clon. Activa «Acceso de uso» para que Ryx confirme que abrió la ORIGINAL.\n"); return
        }
        pause(300)
        val fg = Foreground.pkg(this)
        log.append(if (fg == pkg) "Espacio: se abrió la ORIGINAL (confirmado con Acceso de uso).\n"
            else "Espacio: NO pude confirmar la original (al frente en tu espacio: ${fg ?: "nada"}); puede haberse abierto el clon.\n")
    }

    /** Medidas reales de las ventanas de [pkg] y de la pantalla, para el registro (así se ve si quedó flotante o completa). */
    fun describeWindows(pkg: String): String {
        val m = resources.displayMetrics
        val ws = try { windows } catch (e: Exception) { null }
        val parts = ArrayList<String>()
        if (ws != null) for (w in ws) {
            val name = try { w.root?.packageName?.toString() } catch (e: Exception) { null } ?: continue
            if (name != pkg) continue
            val r = Rect(); w.getBoundsInScreen(r)
            parts.add("${r.left},${r.top}→${r.right},${r.bottom} (${r.width()}×${r.height()})")
        }
        return "Pantalla ${m.widthPixels}×${m.heightPixels} · ventanas de $pkg: ${if (parts.isEmpty()) "ninguna visible" else parts.joinToString(" | ")}"
    }

    /**
     * Formato libre DIRECTO (función de Smart Island), sin salir de tu app ni pasar por el launcher:
     *  1) desbloquea las apps no redimensionables (force_resizable_activities) si hace falta,
     *  2) lanza desde este servicio con ActivityOptions + límites + «android.activity.windowingMode» = 5 (NEW_TASK | MULTIPLE_TASK),
     *  3) comprueba con las ventanas de accesibilidad si quedó flotante y, si el sistema la abrió a pantalla completa,
     *     prueba las variantes de lanzamiento (la que funciona se recuerda para las siguientes aperturas).
     * Con la variante recordada la apertura es de un solo intento y tarda lo que tarda el sistema (unos cientos de ms).
     * Llamar fuera del hilo principal.
     */
    fun launchSmartIsland(pkg: String, pi: PendingIntent?, b: Rect, log: StringBuilder,
                          user: android.os.UserHandle? = null, userId: Int = -1): Boolean {
        val t0 = System.currentTimeMillis()
        try { FreeformUnlock.ensure(this, log) } catch (e: Throwable) { log.append("Desbloqueo: ${e.javaClass.simpleName}\n") }
        return try {
            val m = resources.displayMetrics
            // Si pides una ventana casi del tamaño de la pantalla no se puede distinguir de «pantalla completa»: un solo intento
            val almostFull = b.width() >= m.widthPixels * 0.97f && b.height() >= m.heightPixels * 0.88f
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null && pi == null && user == null) { log.append("No se encontró la actividad principal.\n"); return false }
            val def = if (Shell.privileged(this)) 4 else 0
            val ok = if (almostFull) {
                val v = Prefs.fwVariant(this).let { if (it in VARIANTS.indices) it else def }
                log.append("Ventana casi a pantalla completa: un solo intento (variante ${v + 1}).\n")
                fire(v, Intent(launch ?: Intent()), pi, b, pkg = pkg, user = user, userId = userId)
            } else if (launch != null || user != null) {
                launchFreeformDirect(pkg, pi, b, log, user, userId)
            } else {
                fire(Prefs.fwVariant(this).let { if (it in VARIANTS.indices) it else def }, Intent(), pi, b, userId = userId)
            }
            log.append("Tiempo total: ${System.currentTimeMillis() - t0} ms. ${describeWindows(pkg)}\n")
            ok
        } catch (e: Throwable) {
            log.append("Error al lanzar: ${e.javaClass.simpleName} ${e.message ?: ""}\n"); false
        }
    }

    /** Límites reales de la ventana flotante de [pkg] (la de menor área si hay varias), o null si no se ve. */
    fun windowBounds(pkg: String): Rect? {
        val ws = try { windows } catch (e: Exception) { null } ?: return null
        var best: Rect? = null
        for (w in ws) {
            val name = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
            if (name != pkg) continue
            val r = Rect(); w.getBoundsInScreen(r)
            val b = best
            if (b == null || r.width() * r.height() < b.width() * b.height()) best = r
        }
        return best
    }

    /** ¿Sigue visible alguna ventana de [pkg]? (para quitar la barra de Ryx cuando la ventana se cierra) */
    fun windowAlive(pkg: String): Boolean = floatingState(pkg) != null

    /**
     * Mover/redimensionar sin privilegios: vuelve a pedir el modo libre con los nuevos límites SIN «ventana nueva»,
     * para que Android reutilice la misma tarea en vez de abrir otra. Llamar fuera del hilo principal.
     */
    fun relaunchBounds(pkg: String, b: Rect, user: android.os.UserHandle? = null, userId: Int = -1): Boolean {
        // El intent de la app se guarda: preguntarlo a Android en cada movimiento restaba fluidez
        val launch = if (user != null) Intent()
            else (launchCache[pkg] ?: packageManager.getLaunchIntentForPackage(pkg)?.also { launchCache[pkg] = it }) ?: return false
        val def = if (Shell.privileged(this)) 4 else 0
        val v = Prefs.fwVariant(this).let { if (it in VARIANTS.indices) it else def }   // la misma variante que abrió la ventana
        return fire(v, Intent(launch), null, b, reuse = true, pkg = pkg, user = user, userId = userId)
    }
    private val launchCache = java.util.concurrent.ConcurrentHashMap<String, Intent>()

    /** true = la ventana de [pkg] no ocupa toda la pantalla (flotante); false = pantalla completa; null = no se pudo saber. */
    private fun floatingState(pkg: String): Boolean? {
        val m = resources.displayMetrics
        val ws = try { windows } catch (e: Exception) { null } ?: return null
        var found = false
        for (w in ws) {
            val name = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
            if (name != pkg) continue
            found = true
            val r = Rect(); w.getBoundsInScreen(r)
            if (r.width() < m.widthPixels * 0.97f || r.height() < m.heightPixels * 0.88f) return true
        }
        return if (found) false else null
    }

    /** Espera a ver si la ventana quedó flotante. Sale en cuanto lo sabe: flotante → ya; pantalla completa estable 450 ms → ya. */
    private fun waitFloating(pkg: String, ms: Long): Boolean? {
        val end = System.currentTimeMillis() + ms
        var last: Boolean? = null
        var falseSince = 0L
        while (System.currentTimeMillis() < end) {
            last = floatingState(pkg)
            val now = System.currentTimeMillis()
            if (last == true) return true
            if (last == false) {
                if (falseSince == 0L) falseSince = now else if (now - falseSince > 450) return false
            } else falseSince = 0L
            pause(30)
        }
        return last
    }

    /** Llamar fuera del hilo principal. Devuelve true si la app quedó (o parece haber quedado) en ventana libre. */
    fun launchFreeformDirect(pkg: String, pi: PendingIntent?, b: Rect, log: StringBuilder,
                            user: android.os.UserHandle? = null, userId: Int = -1): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null && user == null) { log.append("No se encontró la actividad principal.\n"); return false }
        val cached = Prefs.fwVariant(this)
        val rest = VARIANTS.indices.filter { it != cached }
        // Con Shizuku/root se prueba PRIMERO el método con privilegios (el que la ROM no puede ignorar)
        val order = when {
            cached in VARIANTS.indices -> listOf(cached) + rest
            Shell.privileged(this) -> listOf(VARIANTS.indices.last()) + rest.filter { it != VARIANTS.indices.last() }
            else -> VARIANTS.indices.toList()
        }
        for (v in order) {
            val sent = fire(v, launch?.let { Intent(it) } ?: Intent(), pi, b, pkg = pkg, user = user, userId = userId)
            if (!sent) { log.append("Variante ${v + 1} · ${VARIANTS[v]}: no se pudo lanzar.\n"); continue }
            when (waitFloating(pkg, 1500)) {
                true -> {
                    log.append("Variante ${v + 1} · ${VARIANTS[v]}: ventana libre verificada. ${describeWindows(pkg)}\n"); Prefs.setFwVariant(this, v)
                    checkSpace(pkg, user, pi, log)
                    return true
                }
                null -> { log.append("Variante ${v + 1} · ${VARIANTS[v]}: lanzada (no pude verificar si es libre; mira la pantalla).\n"); return true }
                false -> {
                    log.append("Variante ${v + 1} · ${VARIANTS[v]}: la ROM la abrió a pantalla completa.\n")
                    if (pi != null) pause(250)
                }
            }
        }
        Prefs.setFwVariant(this, -1)
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // CORTINA: una captura de tu pantalla puesta a pantalla completa mientras Ryx hace por debajo lo que harías
    // con el dedo en el launcher (Recientes / inicio → «Formato libre»). Se ve tu app congelada, no el salto al
    // inicio; al abrirse la ventana real del sistema (con su barra, iconos y esquinas) la cortina se retira.
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var curtain: ImageView? = null
    private var curtainLp: WindowManager.LayoutParams? = null

    private fun onMain(block: () -> Unit) {
        val l = CountDownLatch(1)
        ui.post { try { block() } catch (e: Throwable) {} finally { l.countDown() } }
        try { l.await(1500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    }

    private fun grabScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        var out: Bitmap? = null
        val latch = CountDownLatch(1)
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        out = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle(); hb.close()
                    } catch (e: Throwable) {}
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
        } catch (e: Throwable) { return null }
        try { latch.await(900, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
        return out
    }

    private fun showCurtain(log: StringBuilder): Boolean {
        val bmp = grabScreen()
        if (bmp == null) { log.append("Cortina: no pude capturar la pantalla (se verá el cambio de pantalla).\n"); return false }
        var ok = false
        onMain {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.BLACK) }
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.OPAQUE)
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            wm.addView(iv, lp); curtain = iv; curtainLp = lp; ok = true
        }
        if (ok) ui.postDelayed({ hideCurtain() }, 8000)      // seguro: nunca se queda puesta
        log.append(if (ok) "Cortina: puesta (tu pantalla se ve congelada mientras se abre la ventana).\n" else "Cortina: no se pudo poner.\n")
        return ok
    }

    private fun hideCurtain() {
        val iv = curtain ?: return
        curtain = null
        onMain { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(iv) } catch (e: Throwable) {} }
    }

    /** La cortina deja pasar los toques solo mientras Ryx inyecta un gesto; el resto del tiempo los absorbe. */
    private fun curtainPassThrough(on: Boolean) {
        val iv = curtain ?: return
        val lp = curtainLp ?: return
        onMain {
            lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(iv, lp) } catch (e: Throwable) {}
        }
    }

    /**
     * Burbuja ya creada: se toca su icono (instantáneo, sin cortina ni menús). Las burbujas de Android 17 se quedan en pantalla,
     * así que después de crearla una vez, abrirla es un solo toque. Solo se aceptan vistas que sean de burbuja (clase del sistema)
     * y cuyo texto/descripción contenga el nombre de la app, para no tocar una notificación por error.
     */
    fun tapExistingBubble(label: String, log: StringBuilder): Boolean {
        val ws = try { windows } catch (e: Exception) { null } ?: return false
        for (w in ws) {
            val root = try { w.root } catch (e: Exception) { null } ?: continue
            val pk = root.packageName?.toString() ?: continue
            if (pk == packageName) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root); var g = 0
            while (stack.isNotEmpty() && g++ < 400) {
                val n = stack.removeLast()
                val cls = n.className?.toString() ?: ""
                if (n.isVisibleToUser && (cls.contains("Bubble", true) || cls.contains("BadgedImageView", true))) {
                    val t = nodeText(n)
                    if (t.contains(label, true) && t.length < 120) {
                        val ok = tapExact(n)
                        log.append(if (ok) "Toqué la burbuja existente de «$label» (${cls.substringAfterLast('.')}).\n" else "No pude tocar la burbuja de «$label».\n")
                        if (ok) return true
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // BURBUJA (Android 17). Android no ofrece ninguna API para crearla: solo el launcher del sistema puede (menú «Burbuja»
    // al mantener pulsado un icono). Por eso: 1) si la burbuja ya existe se toca su icono (instantáneo, sin salir de tu app);
    // 2) si no, se hace UNA vez por el menú del launcher, tapado con una captura, y se devuelve tu app al frente.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Llamar fuera del hilo principal. Devuelve true si la burbuja quedó abierta o creada. */
    fun launchBubble(pkg: String, label: String, keepApp: Boolean, log: StringBuilder): Boolean {
        val t0 = System.currentTimeMillis()
        if (tapExistingBubble(label, log)) { log.append("Abierta en ${System.currentTimeMillis() - t0} ms.\n"); return true }
        val prev = currentPackage()
        val covered = showCurtain(log)
        try {
            val ok = viaHome(label, log)
            log.append("Menú del launcher: ${System.currentTimeMillis() - t0} ms en total.\n")
            if (!ok) { dump(log); performGlobalAction(GLOBAL_ACTION_HOME); return false }
            pause(400)      // un instante para que el sistema cree la burbuja antes de volver a tu app
            val skip = setOf(pkg, homePackage(), "com.android.systemui")
            if (keepApp && prev != null && prev !in skip) {
                try {
                    val a = packageManager.getLaunchIntentForPackage(prev)
                    if (a != null) { a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(a); waitForeground(prev, 900) }
                    log.append("Devolví «$prev» al frente; la burbuja queda encima.\n")
                } catch (e: Exception) { log.append("No pude devolver tu app: ${e.javaClass.simpleName}\n") }
            }
            log.append("Burbuja creada en ${System.currentTimeMillis() - t0} ms.\n")
            return true
        } finally {
            if (covered) hideCurtain()
        }
    }

    private fun pause(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) {} }

    private fun homePackage(): String? = try {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (e: Exception) { null }

    /** Ventanas visibles de otras apps (launcher y su menú emergente). */
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { windows.forEach { w -> w.root?.let { out.add(it) } } } catch (e: Exception) {}
        if (out.isEmpty()) rootInActiveWindow?.let { out.add(it) }
        return out.filter { it.packageName?.toString() != packageName }
    }

    private fun nodeText(n: AccessibilityNodeInfo): String = (n.text ?: n.contentDescription ?: "").toString().trim()

    private val CLONEISH = Regex("dual|clon|clone|gemel|twin|duplic|segundo espacio|second space", RegexOption.IGNORE_CASE)

    /** El icono de [label]. Si el launcher tiene dos (original y clon), gana el de nombre exacto y sin pinta de clon. */
    private fun findLabel(label: String): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        for (r in roots()) {
            val list = try { r.findAccessibilityNodeInfosByText(label) } catch (e: Exception) { null } ?: continue
            for (n in list) {
                if (!n.isVisibleToUser) continue
                val t = nodeText(n)
                if (!t.startsWith(label, true)) continue
                val score = (if (t.equals(label, true)) 2 else 0) + (if (CLONEISH.containsMatchIn(t)) 0 else 1)
                if (score > bestScore) { best = n; bestScore = score }
            }
        }
        return best
    }

    /** La opción «Burbuja» del menú del launcher, si está visible. */
    private fun findBubbleItem(): AccessibilityNodeInfo? {
        for (r in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(r)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 800) {
                val n = stack.removeLast()
                if (n.isVisibleToUser && BUBBLE.containsMatchIn(nodeText(n))) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun waitBubbleItem(ms: Long): AccessibilityNodeInfo? {
        var r: AccessibilityNodeInfo? = null
        waitUntil(ms, 60) { r = findBubbleItem(); r != null }
        return r
    }

    private fun gesture(p: Path, dur: Long): Boolean {
        curtainPassThrough(true)
        try { return gestureRaw(p, dur) } finally { curtainPassThrough(false) }
    }

    private fun gestureRaw(p: Path, dur: Long): Boolean {
        var ok = false
        val latch = CountDownLatch(1)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(dur + 2500, TimeUnit.MILLISECONDS)
        return ok
    }

    private fun pointOf(n: AccessibilityNodeInfo): Path {
        val r = Rect(); n.getBoundsInScreen(r)
        return Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
    }

    private fun swipeUp(from: Float, to: Float): Boolean {
        val m = resources.displayMetrics
        return gesture(Path().apply { moveTo(m.widthPixels / 2f, m.heightPixels * from); lineTo(m.widthPixels / 2f, m.heightPixels * to) }, 350)
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var i = 0
        while (cur != null && i < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent; i++
        }
        return gesture(pointOf(n), 60)
    }

    /** Toca el nodo en sí (sin subir a un padre clicable). */
    private fun tapExact(n: AccessibilityNodeInfo): Boolean =
        (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) || gesture(pointOf(n), 50)

    /** Espera a que la app indicada esté al frente (hasta [ms] milisegundos). */
    private fun waitForeground(pkg: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (rootInActiveWindow?.packageName?.toString() == pkg) return true
            pause(80)
        }
        return false
    }

    private fun waitUntil(ms: Long, step: Long = 100, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; pause(step) }
        return cond()
    }

    /**
     * Va al inicio, busca el icono de la app (en la pantalla o en el cajón), lo mantiene pulsado y toca «Burbuja».
     * Bloquea el hilo: llamar fuera del hilo principal. Devuelve true si llegó a pulsar la opción.
     */
    private fun viaHome(label: String, log: StringBuilder): Boolean {
        log.append("Launcher: ${homePackage() ?: "?"} · busco el icono de «$label»\n")
        performGlobalAction(GLOBAL_ACTION_HOME)
        var icon: AccessibilityNodeInfo? = null
        waitUntil(1400, 60) { icon = findLabel(label); icon != null }       // sondeo: sigue en cuanto aparece el icono
        if (icon == null) {
            log.append("No está en la pantalla de inicio; abro el cajón de apps.\n")
            swipeUp(0.88f, 0.35f)
            waitUntil(900, 60) { icon = findLabel(label); icon != null }
            var tries = 0
            while (icon == null && tries < 12) {
                swipeUp(0.78f, 0.28f)
                waitUntil(450, 60) { icon = findLabel(label); icon != null }
                tries++
            }
        }
        val ic = icon
        if (ic == null) {
            log.append("No encontré el icono de «$label».\n"); performGlobalAction(GLOBAL_ACTION_HOME); return false
        }
        // Primero con la acción de accesibilidad «mantener pulsado»; si no sale el menú, con un gesto real
        var cur: AccessibilityNodeInfo? = ic
        var k = 0; var done = false
        while (cur != null && k < 4 && !done) {
            if (cur.isLongClickable) done = cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            cur = cur.parent; k++
        }
        var item = waitBubbleItem(800)
        if (item == null) {
            log.append("El menú no salió; pruebo con un gesto de mantener pulsado.\n")
            gesture(pointOf(ic), 600)
            item = waitBubbleItem(1000)
        }
        if (item == null) {
            log.append("No apareció «Burbuja» en el menú del launcher (¿tu launcher no la trae?).\n")
            performGlobalAction(GLOBAL_ACTION_BACK); return false
        }
        val ok = clickNode(item)
        log.append(if (ok) "Pulsé «${nodeText(item)}» en el menú del launcher.\n" else "No pude pulsar la opción del menú.\n")
        return ok
    }

    /** Textos visibles de otras apps, para el registro cuando algo falla (así se ve qué ve Ryx en tu launcher). */
    private fun dump(log: StringBuilder) {
        val seen = LinkedHashSet<String>()
        for (r in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(r)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 500 && seen.size < 30) {
                val n = stack.removeLast()
                val t = nodeText(n)
                if (n.isVisibleToUser && t.isNotEmpty()) seen.add(t.take(30))
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        log.append("Textos que ve Ryx ahora: ${if (seen.isEmpty()) "ninguno (¿el launcher oculta su contenido?)" else seen.joinToString(" | ")}\n")
    }
}
