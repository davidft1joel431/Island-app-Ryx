package com.dav.isla

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Notificaciones propias de Ryx (las que pone la app en el panel del sistema, no las que lee de otras apps).
 *
 * De momento solo se usa para una cosa: avisar de que HAY UNA VERSIÓN NUEVA. Hasta ahora el aviso solo salía si
 * abrías la app; ahora se pone en el panel y se ve aunque no la tengas delante.
 *
 * En Android 13+ hace falta el permiso POST_NOTIFICATIONS, que se pide desde Ajustes → Sistema → Actualizaciones
 * (o se concede con Shizuku/root o un comando ADB de una sola vez).
 */
object AppNotify {
    const val CH_APP = "ryx_app"
    const val ID_UPDATE = 1001

    /** ¿Se pueden poner notificaciones? (Android 13+ necesita el permiso; antes bastaba con tener la app). */
    fun allowed(c: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 33) true
        else c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    /** Canal de avisos de la propia app. */
    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_APP) != null) return
            val ch = NotificationChannel(CH_APP, "Ryx Island", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Actualizaciones de Ryx Island"
            }
            nm.createNotificationChannel(ch)
        } catch (e: Exception) {}
    }

    /**
     * Avisa de que hay una versión nueva. Se llama desde el hilo de fondo que comprueba las actualizaciones, así que
     * aquí no se toca la interfaz. [apk] es la descarga directa (si se conoce) y [page] la página del release; tocar
     * el aviso abre el enlace directo al APK, o la página si no hay descarga.
     */
    fun update(c: Context, version: String, fallbackName: String, apk: String, page: String, notes: String) {
        try {
            if (!allowed(c)) return
            ensureChannel(c)
            val url = if (apk.isNotEmpty()) apk else page
            val open = Intent(c, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("ryx_open", url)
                putExtra("ryx_manual_upd", true)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(c, 21, open, flags)
            // Un atajo aparte para abrir el enlace sin pasar por la app (si el navegador está disponible)
            val piUrl = try {
                PendingIntent.getActivity(c, 22, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            } catch (e: Exception) { pi }

            val title = "Hay una versión nueva de Ryx: v" + (if (version.isNotEmpty()) version else fallbackName)
            val text = notes.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(160)
                ?: "Toca para ver novedades y descargar la versión nueva."
            val b = if (Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(c, CH_APP)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(c)
            b.setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(piUrl)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
            if (piUrl !== pi) {
                b.addAction(android.app.Notification.Action.Builder(null, "Abrir Ryx", pi).build())
            }
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_UPDATE, b.build())
        } catch (e: Exception) {}
    }

    /** Quita el aviso de actualización (p. ej. cuando ya se ha instalado la nueva). */
    fun clearUpdate(c: Context) {
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_UPDATE) } catch (e: Exception) {}
    }
}
