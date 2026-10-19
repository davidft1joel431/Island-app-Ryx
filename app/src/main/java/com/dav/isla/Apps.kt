package com.dav.isla

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings

/**
 * Catálogo de apps del launcher, incluida cada copia clonada.
 *
 * En HyperOS, MIUI, ColorOS, OriginOS y varias ROM A16/A17 el clon (Dual Apps / App Twin)
 * es el MISMO paquete en otro espacio de usuario — casi siempre el 999, en Samsung a veces el 95.
 * `queryIntentActivities` solo ve el espacio actual y, si se agrupa por paquete, el clon desaparece.
 * Aquí cada copia es una entrada propia: la original conserva la clave `paquete` (compatible con
 * ajustes ya guardados) y el clon usa `paquete@espacio`.
 */
data class AppEntry(
    val pkg: String,
    val userId: Int,
    val label: String,
    val clone: Boolean,
    val user: UserHandle?
) {
    val key: String get() = "$pkg@$userId"
}

object Apps {
    private const val TTL = 60_000L
    /** Espacios que las ROM usan para Dual Apps / Dual Messenger / segundo espacio. */
    private val KNOWN = intArrayOf(999, 9999, 95, 96, 99, 10, 11, 12)

    private val lock = Any()
    @Volatile private var cached: List<AppEntry>? = null
    @Volatile private var cachedAt = 0L

    fun myId(): Int = Process.myUid() / 100000

    fun isCloneId(userId: Int, my: Int = myId()): Boolean = userId >= 0 && userId != my

    fun parse(key: String): Pair<String, Int> {
        val i = key.lastIndexOf('@')
        if (i <= 0) return key to -1
        val id = key.substring(i + 1).toIntOrNull() ?: return key to -1
        return key.substring(0, i) to id
    }

    fun keyOf(pkg: String, userId: Int): String {
        val id = if (userId < 0) myId() else userId
        return "$pkg@$id"
    }

    /**
     * ¿Esta copia está en el conjunto?
     * Clave nueva: `paquete@espacio` (la original también, con su propio espacio).
     * Clave vieja: el paquete a secas bloquea TODAS las copias, hasta que el usuario vuelva a guardar.
     */
    fun matches(set: Set<String>, pkg: String, userId: Int): Boolean {
        if (keyOf(pkg, userId) in set) return true
        return pkg in set && set.none { it.startsWith("$pkg@") }
    }

    /** El primer plano solo dice el paquete: cualquier copia marcada cuenta. */
    fun matchesFront(set: Set<String>, pkg: String): Boolean =
        pkg in set || set.any { it.startsWith("$pkg@") }

    /** Si el ajuste viejo era «todo el paquete», lo parte en una clave por copia para poder apagarlas por separado. */
    fun materialize(set: MutableSet<String>, all: List<AppEntry>) {
        val bare = set.filter { '@' !in it }
        for (b in bare) {
            val copies = all.filter { it.pkg == b }
            if (copies.isEmpty()) continue
            set.remove(b)
            copies.forEach { set.add(it.key) }
        }
    }

    fun invalidate() { cachedAt = 0L }

    /** Anota un clon visto en una notificación, para listarlo aunque la ROM esconda el espacio. */
    fun note(c: Context, pkg: String, userId: Int) {
        if (!isCloneId(userId) || pkg.isBlank()) return
        val sp = c.getSharedPreferences("isla", Context.MODE_PRIVATE)
        val cur = (sp.getStringSet("seen_clones", emptySet()) ?: emptySet()).toMutableSet()
        if (cur.add("$pkg@$userId")) {
            sp.edit().putStringSet("seen_clones", cur).apply()
            invalidate()
        }
    }

    fun list(c: Context): List<AppEntry> {
        val hit = cached
        if (hit != null && System.currentTimeMillis() - cachedAt < TTL) return hit
        synchronized(lock) {
            val again = cached
            if (again != null && System.currentTimeMillis() - cachedAt < TTL) return again
            val built = build(c)
            cached = built
            cachedAt = System.currentTimeMillis()
            return built
        }
    }

    fun hasClone(c: Context, pkg: String): Boolean = list(c).any { it.pkg == pkg && it.clone }

    /**
     * Vuelca los paquetes de otros espacios (Shizuku o root) a la lista recordada.
     * [probeRoot] solo en un toque del usuario: en el arranque no se llama a `su`.
     */
    fun scanDeep(c: Context, probeRoot: Boolean): List<AppEntry> {
        if (shellAllowed(c, probeRoot)) absorbShell(c)
        invalidate()
        return list(c)
    }

    fun icon(c: Context, e: AppEntry): Drawable? {
        val pm = c.packageManager
        val base = try { pm.getApplicationIcon(e.pkg) } catch (ex: Exception) { null } ?: return null
        if (!e.clone || e.user == null) return base
        return try { pm.getUserBadgedIcon(base, e.user) } catch (ex: Exception) { base }
    }

    private fun shellAllowed(c: Context, probeRoot: Boolean): Boolean = when (Prefs.shellMethod(c)) {
        3 -> false
        2 -> Shell.probeRoot()
        1 -> Shell.shizukuGranted()
        else -> Shell.shizukuGranted() || (probeRoot && Shell.probeRoot())
    }

    private fun seen(c: Context): Set<String> =
        c.getSharedPreferences("isla", Context.MODE_PRIVATE).getStringSet("seen_clones", emptySet()) ?: emptySet()

    private fun remember(c: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val sp = c.getSharedPreferences("isla", Context.MODE_PRIVATE)
        val cur = (sp.getStringSet("seen_clones", emptySet()) ?: emptySet()).toMutableSet()
        if (cur.addAll(keys)) sp.edit().putStringSet("seen_clones", cur).apply()
    }

    private fun absorbShell(c: Context) {
        try {
            val my = myId()
            val found = LinkedHashSet<String>()
            for (id in shellUsers(c)) {
                if (id == my) continue
                val out = Shell.run(c, "pm list packages --user $id -3").second
                Regex("package:(\\S+)").findAll(out).forEach { m ->
                    val pkg = m.groupValues[1]
                    if (pkg == c.packageName) return@forEach
                    found.add("$pkg@$id")
                }
            }
            remember(c, found)
        } catch (e: Throwable) {}
    }

    /** Paquetes con actividad de launcher en el espacio actual (para cruzar con el listado de otro usuario). */
    private fun launcherPkgs(c: Context): Set<String> = try {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (android.os.Build.VERSION.SDK_INT >= 33)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        list.map { it.activityInfo.packageName }.toSet()
    } catch (e: Throwable) { emptySet() }

    private fun shellUsers(c: Context): List<Int> {
        val out = Shell.run(c, "pm list users").second
        val ids = Regex("UserInfo\\{(\\d+)").findAll(out).map { it.groupValues[1].toInt() }.toMutableSet()
        if (ids.isEmpty()) Regex("\\{(\\d+):").findAll(out).forEach { ids.add(it.groupValues[1].toInt()) }
        return ids.toList()
    }

    private fun oemUsers(c: Context): List<Int> {
        val ids = LinkedHashSet<Int>()
        val cr = c.contentResolver
        for (k in listOf("dual_app_user_id", "xspace_user_id", "second_user_id", "parallel_user_id")) {
            try { val v = Settings.Secure.getInt(cr, k); if (v > 0) ids.add(v) } catch (e: Throwable) {}
            try { val v = Settings.Global.getInt(cr, k); if (v > 0) ids.add(v) } catch (e: Throwable) {}
            try { Settings.System.getString(cr, k)?.toIntOrNull()?.let { if (it > 0) ids.add(it) } } catch (e: Throwable) {}
        }
        return ids.toList()
    }

    private fun build(c: Context): List<AppEntry> {
        val map = LinkedHashMap<String, AppEntry>()
        val my = myId()
        val me = Process.myUserHandle()
        val la = try { c.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps } catch (e: Throwable) { null }
        val handles = LinkedHashMap<Int, UserHandle>()
        handles[my] = me
        try {
            val um = c.getSystemService(Context.USER_SERVICE) as UserManager
            um.userProfiles.forEach { h -> handles[h.identifier] = h }
        } catch (e: Throwable) {}
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            val um = c.getSystemService(Context.USER_SERVICE) as UserManager
            for (name in listOf("getProfiles", "getUsers", "getAliveUsers")) {
                try {
                    val m = UserManager::class.java.getMethod(name)
                    val list = m.invoke(um) as? List<*> ?: continue
                    for (u in list) {
                        val id = try { u?.javaClass?.getField("id")?.getInt(u) } catch (e: Throwable) { null } ?: continue
                        if (id >= 0) handles.putIfAbsent(id, UserHandle.of(id))
                    }
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
        for (id in oemUsers(c)) handles.putIfAbsent(id, UserHandle.of(id))
        for (id in KNOWN) if (id != my) handles.putIfAbsent(id, UserHandle.of(id))
        for (key in seen(c)) {
            val (pkg, id) = parse(key)
            if (id >= 0) handles.putIfAbsent(id, UserHandle.of(id))
        }

        if (la != null) {
            for ((id, h) in handles) {
                val acts = try { la.getActivityList(null, h) } catch (e: Throwable) { null } ?: continue
                for (info in acts) {
                    val pkg = info.componentName?.packageName ?: continue
                    if (pkg == c.packageName) continue
                    val clone = id != my
                    val label = niceLabel(c, pkg, info.label?.toString(), h, id, clone)
                    val e = AppEntry(pkg, id, label, clone, h)
                    map.putIfAbsent(e.key, e)
                }
            }
        }
        if (map.none { !it.value.clone }) {
            // LauncherApps no devolvió nada (permiso de lista en HyperOS): respaldo con PackageManager
            for (pkg in launcherPkgs(c)) {
                if (pkg == c.packageName) continue
                val label = niceLabel(c, pkg, null, me, my, false)
                val e = AppEntry(pkg, my, label, false, me)
                map.putIfAbsent(e.key, e)
            }
        }
        for (key in seen(c)) {
            val (pkg, id) = parse(key)
            if (pkg.isBlank() || id < 0 || map.containsKey(key)) continue
            val h = handles[id] ?: UserHandle.of(id)
            val e = AppEntry(pkg, id, niceLabel(c, pkg, null, h, id, true), true, h)
            map[e.key] = e
        }
        return map.values.sortedWith(
            compareBy({ it.label.substringBefore(" ·").lowercase() }, { if (it.clone) 1 else 0 }, { it.userId })
        )
    }

    private fun niceLabel(c: Context, pkg: String, fromLauncher: String?, user: UserHandle?, userId: Int, clone: Boolean): String {
        val pm = c.packageManager
        val base = fromLauncher?.takeIf { it.isNotBlank() } ?: try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        if (!clone) return base
        if (user != null) {
            try {
                val badged = pm.getUserBadgedLabel(base, user).toString()
                if (badged.isNotBlank() && badged != base) return badged
            } catch (e: Exception) {}
        }
        return if (userId == 999 || userId == 9999 || userId == 95 || userId == 96) "$base · clon" else "$base · espacio $userId"
    }
}
