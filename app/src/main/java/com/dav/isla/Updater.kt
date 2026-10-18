package com.dav.isla

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Busca la última versión publicada en GitHub Releases del repositorio de Ryx.
 * La versión se lee del tag, del nombre del release y de la descripción: en cualquiera de ellos vale «vN.N.N».
 * Se compara la versión más alta de los últimos releases, no solo el más reciente.
 */
object Updater {
    const val REPO = "davidft1joel431/Island-app-Ryx"

    class Info(val tag: String, val name: String, val page: String, val apk: String, val notes: String,
               val newer: Boolean, val channel: String, val version: String = "")
    class Res(val info: Info?, val error: String = "")

    private val SEMVER = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)", RegexOption.IGNORE_CASE)
    private val SEMVER_V = Regex("(?<![\\w.])v(\\d+)\\.(\\d+)\\.(\\d+)", RegexOption.IGNORE_CASE)

    private fun semver(s: String, r: Regex): List<Long>? =
        r.find(s)?.groupValues?.drop(1)?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 3 }

    /** Versión de un release: tag, luego nombre (con o sin «v») y por último la descripción (solo si dice «vN.N.N»). */
    private fun versionOf(o: JSONObject): List<Long>? =
        semver(o.optString("tag_name"), SEMVER) ?: semver(o.optString("name"), SEMVER) ?: semver(o.optString("body"), SEMVER_V)

    private fun cmp(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }; val y = b.getOrElse(i) { 0L }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Llamar fuera del hilo principal. */
    fun check(c: Context): Res = try {
        val con = URL("https://api.github.com/repos/$REPO/releases?per_page=15").openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 8000
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.setRequestProperty("User-Agent", "RyxIsland")
        when (val code = con.responseCode) {
            200 -> {
                val arr = org.json.JSONArray(con.inputStream.bufferedReader().use { it.readText() })
                var first: JSONObject? = null
                var best: JSONObject? = null
                var bestV: List<Long>? = null
                for (k in 0 until arr.length()) {
                    val x = arr.getJSONObject(k)
                    if (x.optBoolean("draft")) continue
                    if (first == null) first = x
                    val v = versionOf(x)
                    val b = bestV
                    if (v != null && (b == null || cmp(v, b) > 0)) { best = x; bestV = v }
                }
                val o = best ?: first
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
                    val v = bestV
                    val newer = if (v != null) cmp(v, nums(BuildConfig.VERSION_NAME).let { n -> if (n.size >= 3) n.take(3) else n }) > 0
                                else isNewerByNumber(c, tag, o.optString("published_at"))
                    Res(Info(tag, name, o.optString("html_url"), apk, body, newer,
                        channelOf(tag, name, body, o.optBoolean("prerelease")), v?.joinToString(".") ?: ""))
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

    /** Releases antiguos sin «N.N.N» (tags tipo v57): se compara el número de ejecución o, si no hay, la fecha. */
    private fun isNewerByNumber(c: Context, tag: String, publishedAt: String): Boolean {
        val n = nums(tag)
        if (n.size != 1) return false
        if (BuildConfig.BUILD_NUMBER > 0) return n[0] > BuildConfig.BUILD_NUMBER
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val pub = f.parse(publishedAt)?.time ?: return false
            pub > c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime + 60_000L
        } catch (e: Exception) { false }
    }
}
