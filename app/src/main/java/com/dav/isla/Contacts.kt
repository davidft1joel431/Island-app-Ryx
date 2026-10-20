package com.dav.isla

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * Contactos «prioritarios» (favoritos): sirven para que una llamada de un contacto marcado como favorito
 * haga que la isla aparezca SIEMPRE, aunque el interruptor de «ocultar en juegos» esté activo o la isla
 * esté oculta en ese momento.
 *
 * Cómo se decide, en este orden (lo más fiable primero):
 *  1) Grupo «Favoritos» de la agenda (el que Android marca con la estrella). Necesita el permiso de contactos.
 *  2) Lista propia que el usuario puede añadir dentro de Ryx (nombres o números). No necesita permisos.
 *
 * Todo se cachea unos segundos para no consultar la agenda en cada notificación ni bloquear el hilo de la isla.
 */
object Contacts {

    /** ¿Tenemos permiso para leer los contactos? */
    fun canRead(c: Context): Boolean = try {
        c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    // Caché sencilla: se refresca cada 10 s como mucho.
    @Volatile private var cacheAt = 0L
    @Volatile private var starNames: Set<String> = emptySet()
    @Volatile private var starNumbers: Set<String> = emptySet()

    /** Nombres y números de los contactos marcados como favoritos (estrella). Vacío si no hay permiso. */
    private fun favorites(c: Context) {
        if (!canRead(c)) { starNames = emptySet(); starNumbers = emptySet(); return }
        val now = System.currentTimeMillis()
        if (now - cacheAt < 10_000L) return          // aún vale la caché
        val names = HashSet<String>()
        val numbers = HashSet<String>()
        try {
            // Los favoritos suelen estar en el grupo del sistema cuyo TITLE es «Favorites» (en español «Favoritos»).
            val groupIds = HashSet<String>()
            c.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
                null, null, null
            )?.use { cur ->
                val idIx = cur.getColumnIndex(ContactsContract.Groups._ID)
                val tIx = cur.getColumnIndex(ContactsContract.Groups.TITLE)
                while (cur.moveToNext()) {
                    val t = (cur.getString(tIx) ?: "").lowercase()
                    // Coincide con «favoritos», «favorites», «starred»… o el grupo «System Group: Favorites»
                    if (t.contains("favorit") || t.contains("starred") || t.contains("estrella"))
                        groupIds.add(cur.getString(idIx))
                }
            }
            if (groupIds.isNotEmpty()) {
                val sel = ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID +
                    " IN (" + groupIds.joinToString(",") { "'" + it.replace("'", "") + "'" } + ")"
                c.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME),
                    ContactsContract.Data.MIMETYPE + "=? AND " + sel,
                    arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                    null
                )?.use { cur ->
                    val nIx = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
                    while (cur.moveToNext()) cur.getString(nIx)?.trim()?.let { if (it.isNotEmpty()) names.add(it.lowercase()) }
                }
            }
            // Contactos con la estrella (STARRED) del propio contacto
            c.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.STARRED),
                ContactsContract.Contacts.STARRED + "=1", null, null
            )?.use { cur ->
                val nIx = cur.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (cur.moveToNext()) cur.getString(nIx)?.trim()?.let { if (it.isNotEmpty()) names.add(it.lowercase()) }
            }
            // Números de los favoritos (para comparar por teléfono)
            if (names.isNotEmpty()) {
                c.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null
                )?.use { cur ->
                    val nIx = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val pIx = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cur.moveToNext()) {
                        val nm = cur.getString(nIx)?.trim()?.lowercase() ?: continue
                        if (nm in names) cur.getString(pIx)?.let { numbers.add(digits(it)) }
                    }
                }
            }
        } catch (e: Throwable) {}
        starNames = names
        starNumbers = numbers
        cacheAt = now
    }

    private fun digits(s: String): String = s.filter { it.isDigit() }.takeLast(9)

    /**
     * ¿El nombre (o el número) que trae la llamada es de un contacto prioritario?
     * Se mira primero la lista propia de Ryx (sin permisos) y luego el grupo «Favoritos».
     */
    fun isPriority(c: Context, name: String?, number: String? = null): Boolean {
        val own = Prefs.priorityNames(c)
        val n = (name ?: "").trim().lowercase()
        val num = digits(number ?: "")
        if (own.isNotEmpty()) {
            for (o in own) {
                val k = o.trim().lowercase()
                if (k.isEmpty()) continue
                if (k == n && n.isNotEmpty()) return true
                val kd = digits(k)
                if (kd.length >= 6 && num.isNotEmpty() && kd == num) return true
            }
        }
        // El grupo «Favoritos» de la agenda solo se usa si el usuario lo dejó activado.
        if (!Prefs.callUseFavorites(c)) return false
        favorites(c)
        if (!canRead(c)) return false
        if (n.isNotEmpty() && n in starNames) return true
        if (num.length >= 6 && num in starNumbers) return true
        return false
    }

    /**
     * Si la notificación de llamada trae un nombre pero no un número, intenta sacar el número resolviendo el
     * contacto por el nombre. Sirve para comparar por teléfono cuando el nombre viene abreviado.
     */
    fun numberFor(c: Context, name: String?): String? {
        val n = (name ?: "").trim()
        if (n.isEmpty() || !canRead(c)) return null
        return try {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=?",
                arrayOf(n), null
            )?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
        } catch (e: Exception) { null }
    }

    /** Cuántos contactos favoritos hay (para mostrarlo en Ajustes → Permisos). */
    fun favoriteCount(c: Context): Int {
        favorites(c)
        return starNames.size
    }
}
