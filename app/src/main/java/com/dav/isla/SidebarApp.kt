package com.dav.isla

import android.graphics.drawable.Drawable

/**
 * Una aplicación que puede aparecer en la barra lateral o en formato libre.
 * Guarda el paquete, la etiqueta (nombre mostrado) y el espacio de usuario (clon/app dual).
 */
data class SidebarApp(
    val pkg: String,
    val label: String,
    val userId: Int = -1,       // -1 = espacio principal
    val pinned: Boolean = false,
    val order: Int = 0,
    val icon: Drawable? = null
) {
    /** Clave única: "paquete" o "paquete@espacio" para clones. */
    fun key(): String = if (userId >= 0) "$pkg@$userId" else pkg
    /** Nombre para mostrar en la barra lateral. */
    fun displayName(): String = label.takeIf { it.isNotEmpty() } ?: pkg.substringAfterLast('.').capitalize()
}

/** Resultados de búsqueda de apps instaladas para elegir cuáles fijar en la barra lateral. */
object SidebarCatalog {
    /** Todas las apps instaladas con lanzador, excluyendo Ryx misma. */
    fun all(c: android.content.Context): List<SidebarApp> {
        val pm = c.packageManager
        val base = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { ai ->
                val pkg = ai.activityInfo.packageName
                if (pkg == c.packageName) null else
                    SidebarApp(pkg, ai.activityInfo.loadLabel(pm).toString(), icon = ai.activityInfo.loadIcon(pm))
            }
            .distinctBy { it.pkg }
        // Añadir copias clonadas
        val clones = Clones.cloneEntries(c).map { it.key to it.label }.filter { it.first != c.packageName }
        val cloneApps = clones.mapNotNull { (key, lbl) ->
            val (pkg, uid) = Clones.splitKey(key)
            if (pkg == c.packageName) null else SidebarApp(pkg, lbl, uid, pinned = false, order = 0)
        }
        return (base + cloneApps).sortedBy { it.displayName().lowercase() }
    }
}
