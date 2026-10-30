package com.d4viddf.hyperbridge.util

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

object DeviceUtils {

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Poco", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Redmi", ignoreCase = true)

    /** Returns the detected Android ROM/flavor. Unknown custom ROMs fall back to build metadata. */
    fun getSystemName(): String {
        val version = getSystemProperty("ro.mi.os.version.name")
        if (version.isNotEmpty()) return "HyperOS $version"

        val miuiVer = getSystemProperty("ro.miui.ui.version.name")
        if (miuiVer.isNotEmpty()) return "MIUI $miuiVer"

        val buildInfo = "${Build.DISPLAY} ${Build.FINGERPRINT} ${Build.BRAND} ${Build.MANUFACTURER}".lowercase()
        return when {
            buildInfo.contains("oneui") || buildInfo.contains("samsung") -> "Samsung One UI"
            buildInfo.contains("oxygen") -> "OxygenOS"
            buildInfo.contains("coloros") || buildInfo.contains("oppo") || buildInfo.contains("oplus") -> "ColorOS"
            buildInfo.contains("realme") -> "realme UI"
            buildInfo.contains("funtouch") || buildInfo.contains("vivo") -> "Funtouch OS"
            buildInfo.contains("magicos") || buildInfo.contains("honor") -> "MagicOS"
            buildInfo.contains("nothing") -> "Nothing OS"
            buildInfo.contains("flyme") -> "Flyme OS"
            buildInfo.contains("lineage") -> "LineageOS"
            buildInfo.contains("graphene") -> "GrapheneOS"
            buildInfo.contains("crdroid") -> "crDroid"
            buildInfo.contains("pixel") -> "Pixel UI"
            buildInfo.contains("aosp") || buildInfo.contains("userdebug") -> "Android AOSP / ROM personalizada"
            else -> "Android (${Build.MANUFACTURER})"
        }
    }

    fun getAndroidVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Alias kept for existing screens that used the old Xiaomi-only label. */
    fun getHyperOSVersion(): String = "${getSystemName()} - ${getAndroidVersion()}"

    // NEW: Get Marketing Name (e.g. "Redmi Note 13 Pro+")
    fun getDeviceMarketName(): String {
        // 1. Try Xiaomi specific property
        val marketName = getSystemProperty("ro.product.marketname")
        if (marketName.isNotEmpty()) return marketName

        // 3. Fallback to Model (e.g. "23127PN0CC")
        return Build.MODEL
    }

    // This bridge requires Android 15 APIs. Xiaomi's native featured-island integration
    // remains optional and is checked separately by XiaomiNotificationHelper.
    fun isCompatibleOS(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    val isCNRom: Boolean
        get() {
            val region = getSystemProperty("ro.miui.region")
            if (region.equals("CN", ignoreCase = true)) return true
            return Build.DISPLAY.contains("CNXM", ignoreCase = true)
        }

    private fun getSystemProperty(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine()?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
