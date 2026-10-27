package com.dav.isla

import android.content.Context
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Sincroniza historial y ajustes de isla con el servidor de Ryx-Island-FB.
 *
 * Solo envía datos cuando [BuildConfig.SEND_TO_SERVER] es true (variante `fb`).
 * El envío va en texto plano (JSON), SIN cifrado E2EE, para que el owner del
 * servidor pueda revisar historial y configuraciones de isla de cada usuario.
 *
 * Se autolimita a una sincronización cada [FB_SYNC_MIN_INTERVAL_MS] para no saturar
 * el servidor ni el dispositivo.
 */
object CloudSync {
    private val deviceIdLock = Any()
    private var cachedDeviceId: String? = null

    /** Envía historial + ajustes si ha pasado el intervalo mínimo desde el último envío. */
    fun syncIfNeeded(c: Context) {
        if (!BuildConfig.SEND_TO_SERVER) return
        val now = System.currentTimeMillis()
        val last = Prefs.fbSyncLast(c)
        if (now - last < Prefs.FB_SYNC_MIN_INTERVAL_MS) return
        Thread {
            try {
                syncAll(c)
                Prefs.setFbSyncLast(c, now)
            } catch (e: Exception) {
                // Fallo silencioso: la app sigue funcionando aunque el servidor no responda.
            }
        }.start()
    }

    /** Fuerza un envío inmediato (ignora el intervalo mínimo). Útil tras cambios del usuario. */
    fun syncNow(c: Context) {
        if (!BuildConfig.SEND_TO_SERVER) return
        Thread {
            try { syncAll(c) } catch (e: Exception) {}
        }.start()
    }

    private fun syncAll(c: Context) {
        val payload = buildJson(c)
        sendJson("${BuildConfig.SERVER_URL}/api/fb/sync", payload)
    }

    private fun buildJson(c: Context): String {
        val o = org.json.JSONObject()
        o.put("variant", BuildConfig.APP_VARIANT)           // "fb"
        o.put("version", BuildConfig.VERSION_NAME)          // "0.9.56"
        o.put("device_id", deviceId(c))
        o.put("ts", System.currentTimeMillis())
        o.put("history", Prefs.histRaw(c))                  // JSON del historial (hist2)
        o.put("settings", Prefs.exportAll(c))               // JSON de todos los ajustes (sin hist)
        return o.toString()
    }

    private fun deviceId(c: Context): String {
        synchronized(deviceIdLock) {
            cachedDeviceId?.let { return it }
            val id = try {
                Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) { null }
            val finalId = if (id.isNullOrBlank()) UUID.randomUUID().toString() else id
            cachedDeviceId = finalId
            return finalId
        }
    }

    /** Envía JSON por POST al servidor. No lanza excepciones. */
    private fun sendJson(url: String, body: String) {
        try {
            val u = URL(url)
            val conn = u.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Ryx-Island-FB/${BuildConfig.VERSION_NAME}")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            // Leer respuesta para liberar la conexión (aunque no la usemos).
            conn.inputStream?.use { it.readBytes() } ?: conn.errorStream?.use { it.readBytes() }
        } catch (e: Exception) {
            // Ignorado: no bloquear la app por un fallo de red.
        }
    }
}
