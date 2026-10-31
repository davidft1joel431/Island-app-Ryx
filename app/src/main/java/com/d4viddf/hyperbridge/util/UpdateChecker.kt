package com.d4viddf.hyperbridge.util

import android.content.Context
import com.d4viddf.hyperbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Comprueba las versiones publicadas en el repositorio oficial de Ryx Isla.
 *
 * La comprobación siempre usa el repositorio propio de la app; nunca consulta
 * el proyecto de origen ni requiere HyperOS. GitHub exige un User-Agent y
 * limita las peticiones sin autenticar, por lo que se cachea la última
 * comprobación y se ignoran los errores de red de forma silenciosa.
 */
object UpdateChecker {

    const val REPOSITORY = "davidft1joel431/Island-app-Ryx"
    private const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=20"
    private const val LATEST_APK_URL =
        "https://github.com/$REPOSITORY/releases/latest/download/RyxIsla.apk"
    private const val LATEST_RELEASE_URL =
        "https://github.com/$REPOSITORY/releases/latest"

    private const val PREFS_NAME = "ryx_update_checker"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LAST_PROMPTED = "last_prompted_version"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    data class UpdateInfo(
        val versionName: String,
        val releaseName: String,
        val changelog: String,
        val apkUrl: String,
        val releaseUrl: String
    )

    sealed interface Result {
        data class Available(val info: UpdateInfo) : Result
        data object UpToDate : Result
        data object Skipped : Result
        data class Error(val message: String? = null) : Result
    }

    /**
     * @param force true para una comprobación manual (ignora el intervalo y la
     * caché). Las comprobaciones automáticas usan force = false.
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) {
                return@withContext Result.Skipped
            }

            try {
                val releases = fetchReleases()
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

                val latest = releases
                    .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                    .mapNotNull { release ->
                        val tag = release.optString("tag_name").ifBlank {
                            release.optString("name")
                        }
                        val version = normalizeVersion(tag)
                        if (version.isBlank()) null else release to version
                    }
                    .maxWithOrNull { a, b -> compareVersions(a.second, b.second) }
                    ?: return@withContext Result.Error()

                val (release, remoteVersion) = latest
                if (compareVersions(remoteVersion, normalizeVersion(BuildConfig.VERSION_NAME)) <= 0) {
                    return@withContext Result.UpToDate
                }

                val info = UpdateInfo(
                    versionName = remoteVersion,
                    releaseName = release.optString("name").ifBlank { "Ryx Isla $remoteVersion" },
                    changelog = release.optString("body").trim(),
                    apkUrl = findApkUrl(release),
                    releaseUrl = release.optString("html_url").ifBlank { LATEST_RELEASE_URL }
                )
                Result.Available(info)
            } catch (e: Exception) {
                Result.Error(e.message)
            }
        }

    /** Evita mostrar el mismo aviso automático más de una vez por versión. */
    fun shouldPrompt(context: Context, info: UpdateInfo): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_PROMPTED, null) != info.versionName
    }

    fun markPrompted(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PROMPTED, info.versionName)
            .apply()
    }

    private fun fetchReleases(): List<JSONObject> {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RyxIsla/${BuildConfig.VERSION_NAME}")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException("GitHub respondió HTTP $code${message?.let { ": $it" }.orEmpty()}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = org.json.JSONArray(body)
            return buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(it) }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun findApkUrl(release: JSONObject): String {
        val assets = release.optJSONArray("assets")
        if (assets != null) {
            var fallback: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url")
                if (url.isBlank()) continue
                if (name.contains("ryx", ignoreCase = true) || name.contains("isla", ignoreCase = true)) {
                    return url
                }
                if (fallback == null) fallback = url
            }
            if (fallback != null) return fallback
        }
        return LATEST_APK_URL
    }

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        if (leftParts.isEmpty() && rightParts.isEmpty()) {
            return left.compareTo(right, ignoreCase = true)
        }
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val a = leftParts.getOrElse(index) { 0 }
            val b = rightParts.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> =
        version.split(Regex("[^0-9]+"))
            .mapNotNull { it.toIntOrNull() }
}
