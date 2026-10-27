package com.dav.isla

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Busca la última versión publicada en GitHub Releases del repositorio de Ryx.
 *
 * La versión se lee del TÍTULO del release y debe ir como «v» + número: «vN», «vN.N» o «vN.N.N» (o más niveles).
 * Ejemplos: «Ryx Island v1», «Ryx Island v0.9», «Ryx Island v0.9.6». Si el título no la trae, se mira el tag
 * y por último la descripción. Se compara la versión más alta de los últimos releases, no solo el más reciente.
 * Si la API de GitHub falla, se usa la página pública de releases (feed) como respaldo.
 */
object Updater {
    const val REPO = "davidft1joel431/Island-app-Ryx"

    class Info(val tag: String, val name: String, val page: String, val apk: String, val notes: String,
               val newer: Boolean, val channel: String, val version: String = "")
    class Res(val info: Info?, val error: String = "")

    /** «vN», «vN.N», «vN.N.N»… con la «v» pegada y no dentro de otra palabra. */
    private val V_TITLE = Regex("(?<![\\p{L}\\d.])v(\\d+(?:\\.\\d+)*)(?![\\d])", RegexOption.IGNORE_CASE)
    /** Tag: «v0.9.6», «0.9.6», «v1». */
    private val V_TAG = Regex("^\\s*v?(\\d+(?:\\.\\d+)*)\\s*$", RegexOption.IGNORE_CASE)

    private fun parts(s: String): List<Long> = s.split('.').mapNotNull { it.toLongOrNull() }

    private fun versionOf(title: String, tag: String, body: String): List<Long>? =
        V_TITLE.find(title)?.groupValues?.get(1)?.let(::parts)
            ?: V_TAG.find(tag)?.groupValues?.get(1)?.let(::parts)
            ?: V_TITLE.find(body)?.groupValues?.get(1)?.let(::parts)

    private fun cmp(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }; val y = b.getOrElse(i) { 0L }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private class Rel(val tag: String, val name: String, val page: String, val apk: String, val body: String,
                      val pre: Boolean, val published: String, val ver: List<Long>?)

    private fun get(url: String, accept: String): Pair<Int, String> {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 10000; con.readTimeout = 10000
        con.instanceFollowRedirects = true
        con.setRequestProperty("Accept", accept)
        con.setRequestProperty("User-Agent", "RyxIsland")
        val code = con.responseCode
        val text = try { (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
        return code to text
    }

    private fun fromApi(): List<Rel> {
        val (code, text) = get("https://api.github.com/repos/$REPO/releases?per_page=20", "application/vnd.github+json")
        if (code != 200) throw IllegalStateException("HTTP $code")
        val arr = JSONArray(text)
        val out = ArrayList<Rel>()
        for (k in 0 until arr.length()) {
            val x: JSONObject = arr.getJSONObject(k)
            if (x.optBoolean("draft")) continue
            val tag = x.optString("tag_name"); val name = x.optString("name").ifEmpty { tag }; val body = x.optString("body")
            var apk = ""
            x.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                }
            }
            out.add(Rel(tag, name, x.optString("html_url"), apk, body, x.optBoolean("prerelease"),
                x.optString("published_at"), versionOf(name, tag, body)))
        }
        return out
    }

    /** Respaldo sin API (sin límite de consultas): feed público de releases. */
    private fun fromFeed(): List<Rel> {
        val (code, text) = get("https://github.com/$REPO/releases.atom", "application/atom+xml")
        if (code != 200) throw IllegalStateException("feed HTTP $code")
        val out = ArrayList<Rel>()
        for (m in Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val e = m.groupValues[1]
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1)?.trim() ?: ""
            val href = Regex("<link[^>]*href=\"([^\"]+)\"").find(e)?.groupValues?.get(1) ?: ""
            val tag = href.substringAfter("/releases/tag/", "").substringBefore('"')
            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1)
                ?.replace(Regex("&lt;.*?&gt;"), " ")?.replace("&amp;", "&")?.trim() ?: ""
            val apk = if (tag.isNotEmpty()) "https://github.com/$REPO/releases/download/$tag/RyxIsland.apk" else ""
            out.add(Rel(tag, title.ifEmpty { tag }, href, apk, content, false,
                Regex("<updated>(.*?)</updated>").find(e)?.groupValues?.get(1) ?: "", versionOf(title, tag, content)))
        }
        return out
    }

    /** Llamar fuera del hilo principal. */
    fun check(c: Context): Res {
        var rels: List<Rel>? = null
        val errs = ArrayList<String>()
        try { rels = fromApi() } catch (e: Exception) { errs.add("API: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()) }
        if (rels == null || rels.isEmpty()) {
            try { rels = fromFeed() } catch (e: Exception) { errs.add("Feed: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()) }
        }
        if (rels == null) return Res(null, "No pude consultar las versiones.\n\n${errs.joinToString("\n")}\n\n" +
            "Revisa tu conexión. Si el repositorio es privado la app no puede verlo: tiene que ser público y tener al menos un Release.")
        if (rels.isEmpty()) return Res(null, "El repositorio no tiene versiones publicadas todavía.")

        // Versiones con punto (N.N…) mandan sobre las de un solo número (tags antiguos de número de compilación como v57)
        val dotted = rels.filter { (it.ver?.size ?: 0) >= 2 }
        val pool = (if (dotted.isNotEmpty()) dotted else rels).filter { it.ver != null }
        if (pool.isEmpty()) return Res(null, "No se pudo determinar la versión de ningún release.")
        var best: Rel? = null
        for (r in pool) {
            val b = best
            if (b == null || cmp(r.ver ?: emptyList(), b.ver ?: emptyList()) > 0) best = r
        }
        val o = best ?: rels.first()
        val cur = parts(BuildConfig.VERSION_NAME)
        val v = o.ver
        val newer = if (v != null) cmp(v, cur) > 0 else isNewerByNumber(c, o.tag, o.published)
        return Res(Info(o.tag, o.name, o.page, o.apk, o.body, newer, channelOf(o.tag, o.name, o.body, o.pre),
            v?.joinToString(".") ?: ""))
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

    /** Releases antiguos sin versión (tags tipo v57): se compara el número de ejecución o, si no hay, la fecha. */
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
