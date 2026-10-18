package com.dav.isla

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

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

    private fun findFreeform(): AccessibilityNodeInfo? {
        for (r in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(r)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 800) {
                val n = stack.removeLast()
                if (n.isVisibleToUser && FREEFORM.containsMatchIn(nodeText(n))) return n
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

    /**
     * Camino 2 (mantiene tu app de fondo): abre la app de destino, entra en Recientes y toca su icono para sacar el menú
     * con «Formato libre». La app de destino pasa a ventana libre y tu app anterior queda detrás, sin ir a la pantalla de inicio.
     */
    private fun viaRecents(pkg: String, label: String, log: StringBuilder): Boolean {
        log.append("Camino Recientes: abro la app y toco su icono en Recientes (tu app anterior queda detrás).\n")
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(launch) } catch (e: Exception) { log.append("No pude abrir la app: ${e.javaClass.simpleName}\n"); return false }
        if (!waitForeground(pkg, 4000)) log.append("La app tardó en ponerse al frente; sigo igualmente.\n")
        pause(500)
        performGlobalAction(GLOBAL_ACTION_RECENTS); pause(1300)
        // Puede haber varios elementos con ese nombre (icono y miniatura): se prueba uno por uno hasta que salga el menú
        var item: AccessibilityNodeInfo? = null
        val cands = findAllLabel(label)
        log.append("Elementos «$label» en Recientes: ${cands.size}\n")
        for (cnd in cands.take(3)) {
            clickNode(cnd); pause(900)
            item = findFreeform()
            if (item != null) break
            if (findAllLabel(label).isEmpty()) break      // Recientes se cerró (se abrió la app): no seguir tocando
        }
        if (item == null) { log.append("Recientes no mostró «Formato libre».\n"); performGlobalAction(GLOBAL_ACTION_HOME); return false }
        val ok = clickNode(item)
        pause(500)
        log.append(if (ok) "Pulsé «${nodeText(item)}» en Recientes.\n" else "No pude pulsar la opción en Recientes.\n")
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

    /**
     * Prueba primero el camino elegido y, si falla, el otro. Con [keepApp] va primero por Recientes para no sacarte de tu app.
     * Llamar fuera del hilo principal.
     */
    fun launchViaLauncher(pkg: String, label: String, log: StringBuilder, keepApp: Boolean = true): Boolean {
        if (keepApp) {
            if (viaRecents(pkg, label, log)) return true
            performGlobalAction(GLOBAL_ACTION_HOME); pause(700)
            if (viaHome(label, log)) return true
        } else {
            if (viaHome(label, log)) return true
            performGlobalAction(GLOBAL_ACTION_HOME); pause(700)
            if (viaRecents(pkg, label, log)) return true
        }
        dump(log)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return false
    }
}
