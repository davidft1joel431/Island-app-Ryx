package com.dav.isla

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Servicio de accesibilidad de Ryx. No lee nada por su cuenta: solo cuando abres una ventana flotante,
 * hace por ti lo que harías con el dedo en tu launcher (mantener pulsado el icono y tocar «Formato libre»),
 * para que sea el propio sistema quien abra la ventana, con su barra para mover y cambiar el tamaño.
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

        private val FREEFORM = Regex("formato libre|forma libre|ventana libre|free-?form", RegexOption.IGNORE_CASE)
        /** Nombres alternativos que algunas ROM (Android 16/17) usan en el menú de Recientes. */
        private val FREEFORM_ALT = Regex("escritorio|desktop|flotante|floating|pop-?up", RegexOption.IGNORE_CASE)
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Paquete de la app que está al frente ahora (la ventana activa). */
    fun currentPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }

    /**
     * Tras abrir la ventana libre: devuelve al frente la app en la que estabas y luego sube la ventana libre,
     * para que quede flotando encima de tu app y no encima del inicio.
     */
    fun keepBehind(prev: String?, target: String, log: StringBuilder) {
        val skip = setOf(packageName, target, homePackage(), "com.android.systemui")
        if (prev == null || prev in skip) return
        pause(600)
        val a = packageManager.getLaunchIntentForPackage(prev) ?: return
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val t = packageManager.getLaunchIntentForPackage(target) ?: return
        t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(a); pause(500)       // tu app vuelve al frente
            startActivity(t); pause(500)         // la ventana libre se sube encima
            log.append("Devolví «$prev» al fondo y subí la ventana libre encima.\n")
        } catch (e: Exception) { log.append("No pude devolver tu app al fondo: ${e.javaClass.simpleName}\n") }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Apertura directa en ventana libre, sin tocar tu pantalla ni salir de tu app.
    // El servicio de accesibilidad es quien lanza (tiene ventana propia y exención de segundo plano) y
    // pide el modo libre con ActivityOptions + la clave «android.activity.windowingMode» = 5.
    // Como Android no avisa si la ROM lo respetó, se comprueba con las ventanas que ve accesibilidad
    // y se prueban variantes; la que funciona se recuerda para las siguientes aperturas.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private val VARIANTS = listOf("estándar (clave windowingMode)", "con pantalla 0", "sin ventana nueva", "método interno")

    private fun fire(v: Int, launch: Intent, pi: PendingIntent?, b: Rect): Boolean = try {
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
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or (if (v == 2) 0 else Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (pi != null) {
            pi.send(this, 0, Intent().addFlags(flags), null, null, null, bundle)
        } else {
            launch.flags = flags
            startActivity(launch, bundle)
        }
        true
    } catch (e: Throwable) { false }

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
     * Método EXACTO de Smart Island, sin variantes, sin comprobaciones y sin caminos alternativos:
     * ActivityOptions.makeBasic() + límites + clave «android.activity.windowingMode» = 5, lanzado desde este servicio de accesibilidad
     * con NEW_TASK | MULTIPLE_TASK (con el PendingIntent de la notificación si lo hay). Llamar fuera del hilo principal.
     */
    fun launchSmartIsland(pkg: String, pi: PendingIntent?, b: Rect, log: StringBuilder): Boolean {
        return try {
            val options = ActivityOptions.makeBasic()
            try { options.setLaunchBounds(b) } catch (e: Throwable) { log.append("Límites: ${e.javaClass.simpleName}\n") }
            if (Build.VERSION.SDK_INT >= 34) try {
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            } catch (e: Throwable) {}
            val bundle = options.toBundle() ?: android.os.Bundle()
            bundle.putInt("android.activity.windowingMode", 5)
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            if (pi != null) {
                pi.send(this, 0, Intent().addFlags(flags), null, null, null, bundle)
                log.append("Lanzado con el destino exacto de la notificación.\n")
            } else {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch == null) { log.append("No se encontró la actividad principal.\n"); return false }
                launch.addFlags(flags)
                startActivity(launch, bundle)
                log.append("Lanzado desde el servicio de accesibilidad con windowingMode=5.\n")
            }
            pause(1200)
            log.append(describeWindows(pkg)).append("\n")
            true
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
    fun relaunchBounds(pkg: String, b: Rect): Boolean = try {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(b)
        val bundle = options.toBundle() ?: android.os.Bundle()
        bundle.putInt("android.activity.windowingMode", 5)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch, bundle)
        true
    } catch (e: Throwable) { false }

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

    private fun waitFloating(pkg: String, ms: Long): Boolean? {
        val end = System.currentTimeMillis() + ms
        var last: Boolean? = null
        while (System.currentTimeMillis() < end) {
            last = floatingState(pkg)
            if (last == true) return true
            pause(150)
        }
        return last
    }

    /** Llamar fuera del hilo principal. Devuelve true si la app quedó (o parece haber quedado) en ventana libre. */
    fun launchFreeformDirect(pkg: String, pi: PendingIntent?, b: Rect, log: StringBuilder): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: run { log.append("No se encontró la actividad principal.\n"); return false }
        val cached = Prefs.fwVariant(this)
        val order = if (cached in VARIANTS.indices) listOf(cached) + VARIANTS.indices.filter { it != cached } else VARIANTS.indices.toList()
        for (v in order) {
            val sent = fire(v, Intent(launch), pi, b)
            if (!sent) { log.append("Variante ${v + 1} · ${VARIANTS[v]}: no se pudo lanzar.\n"); continue }
            when (waitFloating(pkg, 1800)) {
                true -> { log.append("Variante ${v + 1} · ${VARIANTS[v]}: ventana libre verificada. ${describeWindows(pkg)}\n"); Prefs.setFwVariant(this, v); return true }
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

    private fun findLabel(label: String): AccessibilityNodeInfo? {
        for (r in roots()) {
            val list = try { r.findAccessibilityNodeInfosByText(label) } catch (e: Exception) { null } ?: continue
            val hit = list.firstOrNull { n -> n.isVisibleToUser && nodeText(n).startsWith(label, true) }
            if (hit != null) return hit
        }
        return null
    }

    private fun findFreeform(extended: Boolean = false): AccessibilityNodeInfo? {
        val rx = FREEFORM
        for (r in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(r)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 800) {
                val n = stack.removeLast()
                val t = nodeText(n)
                if (n.isVisibleToUser && (rx.containsMatchIn(t) || (extended && t.length < 40 && FREEFORM_ALT.containsMatchIn(t)))) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun gesture(p: Path, dur: Long): Boolean {
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

    /**
     * Va al inicio, busca el icono de la app (en la pantalla o en el cajón), lo mantiene pulsado y toca «Formato libre».
     * Bloquea el hilo: llamar fuera del hilo principal. Devuelve true si llegó a pulsar la opción.
     */
    private fun viaHome(label: String, log: StringBuilder): Boolean {
        log.append("Camino 1 · pantalla de inicio. Launcher: ${homePackage() ?: "?"} · busco el icono de «$label»\n")
        performGlobalAction(GLOBAL_ACTION_HOME); pause(900)
        var icon = findLabel(label)
        if (icon == null) {
            log.append("No está en la pantalla de inicio; abro el cajón de apps.\n")
            swipeUp(0.88f, 0.35f); pause(1000)
            icon = findLabel(label)
            var tries = 0
            while (icon == null && tries < 12) { swipeUp(0.78f, 0.28f); pause(600); icon = findLabel(label); tries++ }
        }
        if (icon == null) {
            log.append("No encontré el icono de «$label».\n"); performGlobalAction(GLOBAL_ACTION_HOME); return false
        }
        // Primero con la acción de accesibilidad «mantener pulsado»; si no sale el menú, con un gesto real
        var cur: AccessibilityNodeInfo? = icon
        var k = 0; var done = false
        while (cur != null && k < 4 && !done) {
            if (cur.isLongClickable) done = cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            cur = cur.parent; k++
        }
        pause(800)
        var item = findFreeform()
        if (item == null) {
            log.append("El menú no salió; pruebo con un gesto de mantener pulsado.\n")
            gesture(pointOf(icon), 800); pause(900)
            item = findFreeform()
        }
        if (item == null) {
            log.append("No apareció «Formato libre» en el menú del launcher.\n")
            performGlobalAction(GLOBAL_ACTION_BACK); return false
        }
        val ok = clickNode(item)
        pause(500)
        log.append(if (ok) "Pulsé «${nodeText(item)}» en el menú del launcher.\n" else "No pude pulsar la opción del menú.\n")
        return ok
    }

    private fun findAllLabel(label: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (r in roots()) {
            val list = try { r.findAccessibilityNodeInfosByText(label) } catch (e: Exception) { null } ?: continue
            list.filter { n -> n.isVisibleToUser && nodeText(n).startsWith(label, true) }.forEach { out.add(it) }
        }
        return out
    }

    /** Espera a que la app indicada esté al frente (hasta [ms] milisegundos). */
    private fun waitForeground(pkg: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (rootInActiveWindow?.packageName?.toString() == pkg) return true
            pause(150)
        }
        return false
    }

    /** true si en el último intento hubo que abrir la app a pantalla completa antes (entonces tu app anterior hay que restaurarla). */
    @Volatile var launchedFirst = false

    private fun waitUntil(ms: Long, step: Long = 100, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; pause(step) }
        return cond()
    }

    private fun waitFreeform(ms: Long, extended: Boolean = true): AccessibilityNodeInfo? {
        var r: AccessibilityNodeInfo? = null
        waitUntil(ms) { r = findFreeform(extended); r != null }
        return r
    }

    /** ¿Seguimos en Recientes (launcher o SystemUI al frente)? */
    private fun inRecents(): Boolean {
        val p = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        return p != null && (p == homePackage() || p == "com.android.systemui")
    }

    private fun area(n: AccessibilityNodeInfo): Int { val r = Rect(); n.getBoundsInScreen(r); return r.width() * r.height() }

    /** Elementos de Recientes con el nombre de la app, del más pequeño (el icono) al más grande (la tarjeta). */
    private fun recentsCandidates(label: String): List<AccessibilityNodeInfo> {
        val seen = HashSet<String>()
        return findAllLabel(label).filter { n -> val r = Rect(); n.getBoundsInScreen(r); seen.add(r.flattenToString()) }
            .sortedBy { area(it) }
    }

    /** Toca el nodo en sí (sin subir a un padre clicable, que sería la tarjeta y abriría la app). */
    private fun tapExact(n: AccessibilityNodeInfo): Boolean =
        (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) || gesture(pointOf(n), 50)

    /** Pequeños elementos (iconos) dentro o junto a una tarjeta de Recientes, de arriba a abajo y de izquierda a derecha. */
    private fun iconGuesses(card: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val m = resources.displayMetrics
        val limit = (m.widthPixels * m.heightPixels * 0.04).toInt()
        val out = ArrayList<AccessibilityNodeInfo>()
        val cr = Rect(); card.getBoundsInScreen(cr)
        // Solo lo que cae dentro de ESTA tarjeta o justo encima (cabecera con el icono): nunca iconos de otras apps
        val zone = Rect(cr.left, cr.top - (m.heightPixels * 0.12f).toInt(), cr.right, cr.bottom)
        val scopes = listOfNotNull(card.parent ?: card)
        val seen = HashSet<String>()
        for (sc in scopes) {
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(sc); var g = 0
            while (stack.isNotEmpty() && g++ < 300) {
                val n = stack.removeLast()
                val r = Rect(); n.getBoundsInScreen(r)
                if (n.isVisibleToUser && r.width() > 20 && r.height() > 20 && r.width() * r.height() < limit &&
                    zone.contains(r.centerX(), r.centerY()) && seen.add(r.flattenToString())) out.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return out.sortedWith(compareBy({ val r = Rect(); it.getBoundsInScreen(r); r.top }, { val r = Rect(); it.getBoundsInScreen(r); r.left })).take(4)
    }

    /** Árbol visible del launcher, para el registro cuando falla (clase · texto · clicable · posición). */
    private fun treeDump(log: StringBuilder) {
        val lines = ArrayList<String>()
        for (r in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(r); var g = 0
            while (stack.isNotEmpty() && g++ < 400 && lines.size < 40) {
                val n = stack.removeLast()
                if (n.isVisibleToUser) {
                    val b = Rect(); n.getBoundsInScreen(b)
                    val t = nodeText(n)
                    if (t.isNotEmpty() || n.isClickable)
                        lines.add("${n.className?.toString()?.substringAfterLast('.') ?: "?"}|${t.take(24)}|${if (n.isClickable) "clic" else "-"}|${b.left},${b.top},${b.right},${b.bottom}")
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        log.append("Árbol de Recientes: ${lines.joinToString(" ; ")}\n")
    }

    /** Cierra el menú/Recientes sin sacarte de tu app si ya estás en ella. */
    private fun closeRecents() {
        var k = 0
        while (inRecents() && k++ < 3) { performGlobalAction(GLOBAL_ACTION_BACK); pause(250) }
    }

    /**
     * Camino Recientes (mantiene tu app de fondo y NO pasa por la pantalla de inicio):
     * abre Recientes directamente desde la app en la que estás; si la app destino ya está ahí, toca su icono y «Formato libre».
     * Solo si no está en Recientes la abre antes. Rápido: espera por sondeo, no con pausas fijas.
     */
    private fun viaRecents(pkg: String, label: String, log: StringBuilder): Boolean {
        launchedFirst = false
        log.append("Camino Recientes: abro Recientes desde tu app (sin ir al inicio).\n")
        var opened = false
        for (round in 0..1) {
            if (!inRecents()) { performGlobalAction(GLOBAL_ACTION_RECENTS) }
            waitUntil(1600) { inRecents() }
            // El carrusel puede tener la app más a un lado: se busca con hasta 3 deslizamientos
            var cands = recentsCandidates(label)
            var sw = 0
            while (cands.isEmpty() && sw < 3) {
                val m = resources.displayMetrics
                gesture(Path().apply { moveTo(m.widthPixels * 0.2f, m.heightPixels * 0.5f); lineTo(m.widthPixels * 0.85f, m.heightPixels * 0.5f) }, 250)
                pause(350); cands = recentsCandidates(label); sw++
            }
            if (cands.isNotEmpty()) { opened = true; break }
            if (round == 0) {
                log.append("«$label» no está en Recientes; la abro primero.\n")
                closeRecents()
                val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(launch) } catch (e: Exception) { log.append("No pude abrir la app: ${e.javaClass.simpleName}\n"); return false }
                launchedFirst = true
                if (!waitForeground(pkg, 4000)) log.append("La app tardó en ponerse al frente; sigo igualmente.\n")
                pause(350)
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                waitUntil(1600) { inRecents() }
            }
        }
        if (!opened) { log.append("No encontré «$label» en Recientes.\n"); closeRecents(); return false }

        // Se tocan primero los iconos pequeños de la tarjeta; la tarjeta grande abriría la app.
        val screenArea = resources.displayMetrics.let { it.widthPixels * it.heightPixels }
        var taps = 0
        var queue: List<AccessibilityNodeInfo> = emptyList()
        var qi = 0
        while (taps < 5) {
            if (!inRecents()) {      // Recientes se cerró (se abrió la app): se reabre
                performGlobalAction(GLOBAL_ACTION_RECENTS); waitUntil(1500) { inRecents() }
                queue = emptyList(); qi = 0
            }
            if (qi >= queue.size) {
                val cands = recentsCandidates(label)
                if (taps == 0) log.append("Elementos «$label» en Recientes: ${cands.size}\n")
                if (cands.isEmpty()) break
                val first = cands.first()
                queue = if (area(first) > screenArea * 0.04) iconGuesses(first) + cands else cands
                if (taps == 0) log.append("Candidatos a icono: ${queue.size}\n")
                qi = 0
                if (queue.isEmpty()) break
            }
            val target = queue[qi++]
            tapExact(target)
            taps++
            var item = waitFreeform(1100)
            if (item == null) {      // algunos launchers sacan el menú con mantener pulsado
                var cur: AccessibilityNodeInfo? = target; var k = 0
                while (cur != null && k < 3) { if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) break; cur = cur.parent; k++ }
                item = waitFreeform(800)
            }
            if (item != null) {
                val ok = clickNode(item)
                log.append(if (ok) "Pulsé «${nodeText(item)}» en Recientes.\n" else "No pude pulsar la opción en Recientes.\n")
                return ok
            }
            if (inRecents()) { treeDump(log); performGlobalAction(GLOBAL_ACTION_BACK); pause(250) }   // cierra un menú sin la opción
        }
        log.append("Recientes no mostró «Formato libre».\n")
        closeRecents()
        return false
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

    /**
     * Prueba primero el camino elegido y, si falla, el otro. Con [keepApp] va primero por Recientes para no sacarte de tu app.
     * Llamar fuera del hilo principal.
     */
    fun launchViaLauncher(pkg: String, label: String, log: StringBuilder, keepApp: Boolean = true): Boolean {
        launchedFirst = false
        if (keepApp) {
            // Primero Recientes (no te saca de tu app). Si esa ROM no muestra la opción ahí, se usa el camino del inicio,
            // que sí funciona, para que la ventana libre se abra siempre.
            if (viaRecents(pkg, label, log)) return true
            log.append("Recientes no sirvió en esta ROM; uso el camino del inicio.\n")
            performGlobalAction(GLOBAL_ACTION_HOME); pause(500)
            if (viaHome(label, log)) return true
            dump(log)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return false
        }
        if (viaHome(label, log)) return true
        performGlobalAction(GLOBAL_ACTION_HOME); pause(500)
        if (viaRecents(pkg, label, log)) return true
        dump(log)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return false
    }
}
