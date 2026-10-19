package com.dav.isla

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import android.os.UserHandle

/**
 * Copias clonadas de una app (app dual, «segundo espacio», «gemelo»…).
 *
 * El clon y el original comparten paquete: lo único que los distingue es el ESPACIO de usuario.
 * Por eso aquí todo se identifica con una CLAVE: el paquete a secas para la original
 * («com.whatsapp») y «paquete@espacio» para su clon («com.whatsapp@999»).
 *
 * Un clon se conoce por tres fuentes, sin adivinar:
 *  1) una notificación suya (se guarda como «clonado conocido», y así aparece aunque su espacio esté oculto),
 *  2) los perfiles que Android declara (perfil de trabajo, segundo espacio…),
 *  3) los usuarios que la ROM tenga, consultados con Shizuku/root (los clonados de MIUI/OPPO suelen ser usuarios ocultos).
 * NUNCA se listan todos los paquetes de un espacio desconocido: daría falsos positivos con las apps del sistema;
 * para un paquete concreto sí se pregunta (`pm list packages --user N paquete`), que es exacto.
 */
object Clones {

    /** Espacio de usuario de Ryx (el principal). */
    fun myUser(): Int = Process.myUid() / 100000

    /** ¿[userId] es un espacio distinto del principal? */
    fun isClone(userId: Int): Boolean = userId >= 0 && userId != myUser()

    /** Clave de una copia: el paquete para la original; «paquete@espacio» para un clon. */
    fun key(pkg: String, userId: Int): String = if (isClone(userId)) "$pkg@$userId" else pkg

    /** Separa una clave en (paquete, espacio). Una clave sin «@» es la original. */
    fun splitKey(key: String): Pair<String, Int> {
        val at = key.lastIndexOf('@')
        if (at <= 0) return key to myUser()
        val u = key.substring(at + 1).toIntOrNull() ?: return key to myUser()
        return key.substring(0, at) to u
    }

    /** UserHandle de un espacio, con API pública (no hay «getIdentifier», pero sí esto). */
    fun handle(userId: Int): UserHandle = UserHandle.getUserHandleForUid(userId * 100000)

    /** Id del espacio a partir de su UserHandle. «getIdentifier» está oculto (@hide); hashCode() devuelve ese id. */
    fun idOf(h: UserHandle): Int = h.hashCode()

    private fun la(c: Context) = c.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    /**
     * Espacios que Android declara además del principal (perfil de trabajo, segundo espacio…).
     * Se usa LauncherApps.getProfiles() (API pública, 26+) en lugar de UserManager.getUserProfiles(),
     * porque su tipo UserInfo está oculto (@hide) y no existe en el SDK público.
     */
    fun profiles(c: Context): List<UserHandle> = try {
        la(c).profiles.filter { idOf(it) != myUser() }
    } catch (e: Exception) { emptyList() }

    // Espacios vistos con Shizuku/root («pm list users» puede mostrar los clonados ocultos). Se refresca cada 5 min.
    @Volatile private var usersCache: Pair<Long, List<Int>>? = null

    /** Todos los espacios conocidos (declarados por el sistema y, con privilegios, los ocultos de la ROM). */
    fun userIds(c: Context): List<Int> {
        val declared = profiles(c).map { idOf(it) }
        val extra = try {
            // Nunca se ejecuta shell en el hilo principal: se deja para la próxima consulta de fondo.
            if (onMain()) emptyList()
            else {
                val now = System.currentTimeMillis()
                val cached = usersCache?.takeIf { now - it.first < 300_000L }?.second
                if (cached != null) cached
                else {
                    val ids = if (Shell.privileged(c)) parseUsers(Shell.run(c, "pm list users").second) else emptyList()
                    usersCache = now to ids
                    ids
                }
            }
        } catch (e: Exception) { emptyList() }
        return (declared + extra).distinct().filter { it != myUser() && it >= 0 }
    }

    /** ¿Estamos en el hilo principal? (para no bloquearlo con comandos de shell) */
    private fun onMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun parseUsers(out: String): List<Int> =
        Regex("UserInfo\\{(\\d+):").findAll(out).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()

    // ¿La app está instalada en ese espacio? Con caché de 60 s: se pregunta en cada movimiento de ventana.
    private val installCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /** ¿[pkg] está instalada en el espacio [userId]? Primero con la API pública; con privilegios, por shell (espacios ocultos). */
    fun installedFor(c: Context, pkg: String, userId: Int): Boolean {
        val k = "$pkg@$userId"
        val now = System.currentTimeMillis()
        installCache[k]?.let { if (now - it.second < 60_000L) return it.first }
        var r = try { la(c).getActivityList(pkg, handle(userId)).isNotEmpty() } catch (e: Exception) { false }
        if (!r && !onMain()) try {
            if (Shell.privileged(c)) r = Shell.run(c, "pm list packages --user $userId $pkg").second.contains("package:$pkg")
        } catch (e: Exception) {}
        installCache[k] = r to now
        return r
    }

    /** Anota que [pkg] tiene una copia en el espacio [userId] (llegó una notificación suya). Queda guardado. */
    fun note(c: Context, pkg: String, userId: Int) {
        if (!isClone(userId)) return
        installCache["$pkg@$userId"] = true to System.currentTimeMillis()
        if (key(pkg, userId) !in Prefs.knownClones(c)) Prefs.addKnownClone(c, key(pkg, userId))
    }

    private val cloneCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /** ¿Esta app existe también en otro espacio (clon)? Se sabe por una copia vista, por los perfiles o por los usuarios de la ROM. */
    fun hasClone(c: Context, pkg: String): Boolean {
        if (Prefs.knownClones(c).any { splitKey(it).first == pkg }) return true
        val now = System.currentTimeMillis()
        cloneCache[pkg]?.let { if (now - it.second < 60_000L) return it.first }
        val r = userIds(c).any { installedFor(c, pkg, it) }
        cloneCache[pkg] = r to now
        return r
    }

    /** Nombre de la copia: el de la ROM si lo distingue («… (clon)»), o «… · clon» si no. */
    fun label(c: Context, pkg: String, userId: Int): String {
        val base = try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
        if (!isClone(userId)) return base
        var badged: String? = null
        try { badged = c.packageManager.getUserBadgedLabel(base, handle(userId)).toString() } catch (e: Exception) {}
        return if (badged != null && badged != base) badged else "$base · clon"
    }

    /** Una copia concreta de una app. */
    data class Entry(val pkg: String, val userId: Int, val label: String) {
        val key: String get() = Clones.key(pkg, userId)
        val clone: Boolean get() = isClone(userId)
    }

    private fun appExists(c: Context, pkg: String): Boolean =
        try { c.packageManager.getApplicationInfo(pkg, 0); true } catch (e: Exception) { false }

    /**
     * Copias (clones) conocidas, para la lista de «Aplicaciones»: las vistas en notificaciones (aunque su espacio
     * esté oculto) más las que el sistema declara tener instaladas en otro perfil.
     */
    fun cloneEntries(c: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (k in Prefs.knownClones(c)) {
            val (p, u) = splitKey(k)
            if (!isClone(u) || !seen.add(k) || !appExists(c, p)) continue
            out.add(Entry(p, u, label(c, p, u)))
        }
        for (h in profiles(c)) {
            val u = idOf(h)
            val apps = try { la(c).getActivityList(null, h) } catch (e: Exception) { emptyList() }
            for (ai in apps) {
                val p = ai.componentName.packageName
                if (p == c.packageName) continue
                val k = key(p, u)
                if (!seen.add(k)) continue
                out.add(Entry(p, u, label(c, p, u)))
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }
}
