package com.dav.isla

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings

/**
 * Todo lo relacionado con las LLAMADAS: saber qué apps de llamada hay instaladas, cuál es la predeterminada del
 * sistema, abrir el marcador/registro y (si se puede) contestar o colgar desde la isla.
 *
 * Idea: en cualquier ROM hay SIEMPRE una app de llamadas (el marcador de Android, «Teléfono», etc.), pero puede
 * haber varias. Ryx respeta la PREDETERMINADA del sistema y, si el usuario elige otra en Ajustes, usa esa.
 *
 * Importante: los botones de contestar/colgar se reenvían tal cual vienen en la propia notificación de llamada
 * (los `Notification.Action` de la app de teléfono), así que funcionan sin ser la app de llamadas por defecto.
 * Los permisos (ANSWER_PHONE_CALLS, CALL_PHONE, READ_CONTACTS, READ_CALL_LOG) se usan para lo que sí los necesita:
 * resolver el contacto de la llamada, marcarlo como favorito/prioritario y abrir el registro.
 */
object Calls {

    /** Apps que pueden atender llamadas (marcador). */
    fun dialers(c: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val pm = c.packageManager
            val i = Intent(Intent.ACTION_DIAL).apply { addCategory(Intent.CATEGORY_DEFAULT) }
            val list: List<ResolveInfo> = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
            for (r in list) {
                val p = r.activityInfo?.packageName ?: continue
                if (out.none { it.first == p }) out.add(p to appLabel(c, p))
            }
            // Respaldo: si la ROM no responde a ACTION_DIAL con MATCH_DEFAULT_ONLY, se prueba sin filtro.
            if (out.isEmpty()) {
                val all = pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0)
                for (r in all) {
                    val p = r.activityInfo?.packageName ?: continue
                    if (out.none { it.first == p }) out.add(p to appLabel(c, p))
                }
            }
        } catch (e: Exception) {}
        return out.sortedBy { it.second.lowercase() }
    }

    private fun appLabel(c: Context, pkg: String): String = try {
        val ai = c.packageManager.getApplicationInfo(pkg, 0)
        c.packageManager.getApplicationLabel(ai).toString()
    } catch (e: Exception) { pkg }

    /** La app de llamadas PREDETERMINADA del sistema (la que Android abre con ACTION_DIAL). */
    fun defaultDialer(c: Context): String? = try {
        val i = Intent(Intent.ACTION_DIAL)
        val r = c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        r?.activityInfo?.packageName
    } catch (e: Exception) { null }

    /** La app que Ryx debe usar al abrir una llamada: la elegida en Ajustes o, si no hay, la predeterminada. */
    fun chosen(c: Context): String {
        val set = Prefs.callApp(c)
        if (set.isNotEmpty()) return set
        return defaultDialer(c) ?: dialers(c).firstOrNull()?.first ?: ""
    }

    /** Nombre bonito de la app de llamadas que se va a usar. */
    fun chosenLabel(c: Context): String {
        val p = chosen(c)
        if (p.isEmpty()) return "la app de llamadas"
        return appLabel(c, p)
    }

    /** ¿Hay alguna app de llamadas instalada? (en cualquier ROM debería haber). */
    fun hasDialer(c: Context): Boolean = dialers(c).isNotEmpty() || defaultDialer(c) != null

    /** Abre el marcador de la app elegida (o el del sistema si no se eligió ninguna). */
    fun openDialer(c: Context, number: String? = null) {
        try {
            val pkg = chosen(c)
            val i = if (number.isNullOrBlank()) Intent(Intent.ACTION_DIAL)
            else Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
            if (pkg.isNotEmpty()) i.setPackage(pkg)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Exception) {
            try { c.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) {}
        }
    }

    /** Abre el REGISTRO de llamadas (la pantalla de «Recientes»), intentando primero la app elegida. */
    fun openLog(c: Context) {
        try {
            val pkg = chosen(c)
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("content://call_log/calls"))
            if (pkg.isNotEmpty()) i.setPackage(pkg)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Exception) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://call_log/calls"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                openDialer(c)
            }
        }
    }

    // ---------- Permisos ----------

    fun canReadContacts(c: Context): Boolean = granted(c, android.Manifest.permission.READ_CONTACTS)
    fun canReadCallLog(c: Context): Boolean = granted(c, android.Manifest.permission.READ_CALL_LOG)
    fun canAnswer(c: Context): Boolean = granted(c, android.Manifest.permission.ANSWER_PHONE_CALLS)
    fun canCall(c: Context): Boolean = granted(c, android.Manifest.permission.CALL_PHONE)
    fun canReadPhoneState(c: Context): Boolean = granted(c, android.Manifest.permission.READ_PHONE_STATE)

    private fun granted(c: Context, p: String): Boolean = try {
        c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    /** Los cuatro permisos de llamadas que se pueden pedir con el diálogo del sistema (en una sola tanda). */
    fun runtimePerms(): Array<String> = arrayOf(
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.ANSWER_PHONE_CALLS,
        android.Manifest.permission.CALL_PHONE
    )

    /** Manda al usuario a la pantalla de permisos de Ryx (para conceder o quitar los de llamadas). */
    fun openAppPerms(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }
}
