package com.dav.isla

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Busca la última versión publicada en GitHub Releases del repositorio de Ryx. */
object Updater {
    const val REPO = "davidft1joel431/Island-app-Ryx"

    class Info(val tag: String, val name: String, val page: String, val apk: String, val notes: String,
               val newer: Boolean, val channel: String)
    class Res(val info: Info?, val error: String = "")

    /** Llamar fuera del hilo principal. */
    fun check(c: Context): Res = try {
        val con = URL("https://api.github.com/repos/$REPO/releases?per_page=10").openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 8000
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.setRequestProperty("User-Agent", "RyxIsland")
        when (val code = con.responseCode) {
            200 -> {
                val arr = org.json.JSONArray(con.inputStream.bufferedReader().use { it.readText() })
                var o: JSONObject? = null
                for (k in 0 until arr.length()) {
                    val x = arr.getJSONObject(k)
                    if (!x.optBoolean("draft")) { o = x; break }
                }
                if (o == null) Res(null, "El repositorio no tiene versiones publicadas todavía.")
                else {
                    val tag = o.optString("tag_name")
                    val name = o.optString("name").ifEmpty { tag }
                    val body = o.optString("body")
                    var apk = ""
                    val assets = o.optJSONArray("assets")
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                    }
                    Res(Info(tag, name, o.optString("html_url"), apk, body,
                        isNewer(c, tag, o.optString("published_at")), channelOf(tag, name, body, o.optBoolean("prerelease"))))
                }
            }
            404 -> Res(null, "No encontré versiones publicadas. Si el repositorio es privado, la app no puede consultarlo: " +
                "tiene que ser público y tener al menos un Release.")
            403 -> Res(null, "GitHub limitó las consultas por un rato. Prueba más tarde.")
            else -> Res(null, "GitHub respondió con el código $code.")
        }
    } catch (e: Exception) {
        Res(null, "Sin conexión o GitHub no respondió (${e.javaClass.simpleName}).")
    }

    /** "alpha", "beta" o "" según el tag, el nombre o la descripción del release (o si GitHub lo marca como pre-release). */
    private fun channelOf(tag: String, name: String, body: String, pre: Boolean): String {
        val t = "$tag $name $body".lowercase()
        return when {
            Regex("alpha|alfa").containsMatchIn(t) -> "alpha"
            t.contains("beta") -> "beta"
            pre -> "beta"
            else -> ""
        }
    }

    private fun nums(s: String) = Regex("\\d+").findAll(s).map { it.value.toLong() }.toList()

    private fun isNewer(c: Context, tag: String, publishedAt: String): Boolean {
        val n = nums(tag)
        if (n.isEmpty()) return false
        // La publicación automática etiqueta cada versión como v<número de ejecución>; la app lleva ese número dentro.
        if (n.size == 1) {
            if (BuildConfig.BUILD_NUMBER > 0) return n[0] > BuildConfig.BUILD_NUMBER
            // Compilación local sin número: se compara la fecha del release con la de instalación
            return try {
                val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val pub = f.parse(publishedAt)?.time ?: return false
                pub > c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime + 60_000L
            } catch (e: Exception) { false }
        }
        val mine = nums(BuildConfig.VERSION_NAME)
        for (i in 0 until maxOf(n.size, mine.size)) {
            val a = n.getOrElse(i) { 0L }; val b = mine.getOrElse(i) { 0L }
            if (a != b) return a > b
        }
        return false
    }
}
