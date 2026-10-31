package com.d4viddf.hyperbridge.util

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Detección de dispositivo y ROM pensada para Android 15+.
 *
 * Ryx Isla no necesita HyperOS para funcionar: este objeto solo describe el
 * sistema para mostrarlo en la app y para elegir el atajo correcto de ajustes
 * de batería/inicio automático. Ninguna comprobación de compatibilidad exige
 * una marca o una ROM concreta.
 */
object DeviceUtils {

    data class DeviceInfo(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val marketName: String,
        val romName: String,
        val romVersion: String,
        val androidRelease: String,
        val sdkInt: Int,
        val isCompatible: Boolean,
        val isXiaomiFamily: Boolean,
        val isHyperOs: Boolean,
        val isMiui: Boolean,
        val isChinaRom: Boolean,
        val isEmulator: Boolean
    ) {
        val androidVersion: String
            get() = "Android $androidRelease (API $sdkInt)"

        val summary: String
            get() = if (romName.isBlank()) androidVersion else "$romName $romVersion · $androidVersion"
    }

    private val properties: Map<String, String> by lazy { readAllProperties() }

    private val cachedInfo: DeviceInfo by lazy { detectDeviceInfo() }

    fun getDeviceInfo(): DeviceInfo = cachedInfo

    val isXiaomi: Boolean
        get() = cachedInfo.isXiaomiFamily

    /** Nombre legible de la capa de personalización o de Android si es stock. */
    fun getSystemName(): String {
        val info = cachedInfo
        return when {
            info.romName.isBlank() -> "Android ${info.androidRelease}"
            info.romVersion.isBlank() -> info.romName
            else -> "${info.romName} ${info.romVersion}"
        }
    }

    fun getAndroidVersion(): String = cachedInfo.androidVersion

    fun getSystemSummary(): String = cachedInfo.summary

    /** Alias conservado para pantallas antiguas. Ya no implica HyperOS. */
    fun getHyperOSVersion(): String = getSystemSummary()

    fun getDeviceMarketName(): String = cachedInfo.marketName

    fun getOemName(): String = cachedInfo.manufacturer

    /**
     * Ryx Isla usa una ventana de superposición propia. El único requisito de
     * sistema es Android 15 (API 35) o posterior.
     */
    fun isCompatibleOS(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    val isCNRom: Boolean
        get() = cachedInfo.isChinaRom

    fun getSystemProperty(key: String): String = properties[key].orEmpty()

    private fun detectDeviceInfo(): DeviceInfo {
        val manufacturer = Build.MANUFACTURER.trim().ifBlank { Build.BRAND.trim() }
        val brand = Build.BRAND.trim()
        val model = Build.MODEL.trim()
        val marketName = firstNonBlank(
            prop("ro.product.marketname"),
            prop("ro.product.odm.marketname"),
            prop("ro.product.vendor.marketname"),
            prop("ro.product.system.marketname"),
            model
        )
        val rom = detectRom()
        val xiaomiFamily = isXiaomiBuild(manufacturer, brand)
        val hyperOs = rom.name == "HyperOS"
        val miui = rom.name == "MIUI"
        val chinaRom = detectChinaRom()

        return DeviceInfo(
            manufacturer = manufacturer,
            brand = brand,
            model = model,
            marketName = marketName,
            romName = rom.name,
            romVersion = rom.version,
            androidRelease = Build.VERSION.RELEASE.ifBlank { Build.VERSION.CODENAME },
            sdkInt = Build.VERSION.SDK_INT,
            isCompatible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM,
            isXiaomiFamily = xiaomiFamily,
            isHyperOs = hyperOs,
            isMiui = miui,
            isChinaRom = chinaRom,
            isEmulator = isEmulatorBuild()
        )
    }

    private data class Rom(val name: String, val version: String)

    private fun detectRom(): Rom {
        val buildText = listOf(
            Build.DISPLAY,
            Build.FINGERPRINT,
            Build.BRAND,
            Build.MANUFACTURER,
            Build.PRODUCT,
            Build.DEVICE
        ).joinToString(" ").lowercase()
        val vendorText = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase()

        // --- Explicit ROM properties (most reliable) ---
        // Xiaomi / Redmi / POCO
        val hyperOsVersion = firstNonBlank(
            prop("ro.mi.os.version.name"),
            prop("ro.mi.os.version.incremental"),
            prop("ro.mi.os.version.code")
        )
        if (hyperOsVersion.isNotBlank()) return Rom("HyperOS", cleanVersion(hyperOsVersion))

        val miuiVersion = prop("ro.miui.ui.version.name")
        if (miuiVersion.isNotBlank()) return Rom("MIUI", cleanVersion(miuiVersion))

        // Samsung
        val oneUiVersion = prop("ro.build.version.oneui")
        if (oneUiVersion.isNotBlank()) return Rom("One UI", formatOneUi(oneUiVersion))

        val semVersion = prop("ro.build.version.sem")
        if (semVersion.isNotBlank()) return Rom("One UI", "")

        // vivo / iQOO
        val originVersion = firstNonBlank(
            prop("ro.build.version.originos"),
            prop("ro.vivo.os.version")
        )
        if (prop("ro.build.version.originos").isNotBlank()) {
            return Rom("OriginOS", cleanVersion(originVersion))
        }

        val funtouchVersion = firstNonBlank(
            prop("ro.build.version.funtouch"),
            prop("ro.vivo.os.version")
        )
        if (prop("ro.build.version.funtouch").isNotBlank()) {
            return Rom("Funtouch OS", cleanVersion(funtouchVersion))
        }

        // HONOR / Huawei
        val magicVersion = firstNonBlank(
            prop("ro.build.version.magic"),
            prop("ro.build.version.magicos"),
            prop("ro.magic.version")
        )
        if (magicVersion.isNotBlank()) return Rom("MagicOS", cleanVersion(magicVersion))

        val harmonyVersion = firstNonBlank(
            prop("ro.build.version.harmony"),
            prop("ro.harmony.version"),
            prop("hw_sc.build.platform.version")
        )
        if (harmonyVersion.isNotBlank()) return Rom("HarmonyOS", cleanVersion(harmonyVersion))

        val emuiVersion = prop("ro.build.version.emui")
        if (emuiVersion.isNotBlank()) return Rom("EMUI", cleanVersion(emuiVersion))

        // Nothing / Meizu / ASUS / Motorola / Sony / ZTE / Nubia
        val nothingVersion = firstNonBlank(prop("ro.build.version.nothing"), prop("ro.nothing.version"))
        if (nothingVersion.isNotBlank()) return Rom("Nothing OS", cleanVersion(nothingVersion))

        val flymeVersion = prop("ro.flyme.version")
        if (flymeVersion.isNotBlank()) return Rom("Flyme", cleanVersion(flymeVersion))

        val zenUiVersion = firstNonBlank(prop("ro.build.version.zenui"), prop("ro.build.version.asus"))
        if (zenUiVersion.isNotBlank()) return Rom("ZenUI / ROG UI", cleanVersion(zenUiVersion))

        val myUxVersion = firstNonBlank(prop("ro.build.version.motoui"), prop("ro.moto.build.version"))
        if (myUxVersion.isNotBlank()) return Rom("My UX", cleanVersion(myUxVersion))

        val xperiaVersion = firstNonBlank(prop("ro.semc.version.sw"), prop("ro.build.version.sony"))
        if (xperiaVersion.isNotBlank()) return Rom("Xperia UI", cleanVersion(xperiaVersion))

        val myOsVersion = firstNonBlank(prop("ro.build.version.zte"), prop("ro.build.version.myos"))
        if (myOsVersion.isNotBlank()) return Rom("MyOS / Nubia UI", cleanVersion(myOsVersion))

        // --- Custom ROM markers (win over OEM brand fallbacks) ---
        detectCustomRom(buildText)?.let { return it }

        // --- OEM brand fallbacks for stock builds without version props ---
        return when {
            vendorText.contains("samsung") -> Rom("One UI", "")
            vendorText.contains("oneplus") -> Rom(
                "OxygenOS",
                cleanVersion(firstNonBlank(
                    prop("ro.build.version.oxygenos"),
                    prop("ro.oxygen.version"),
                    prop("ro.build.version.oplusrom")
                ))
            )
            vendorText.contains("realme") -> Rom(
                "realme UI",
                cleanVersion(firstNonBlank(
                    prop("ro.build.version.realmeui"),
                    prop("ro.realmeui.version"),
                    prop("ro.build.version.oplusrom")
                ))
            )
            vendorText.contains("oppo") -> Rom(
                "ColorOS",
                cleanVersion(firstNonBlank(
                    prop("ro.build.version.oplusrom"),
                    prop("ro.build.version.opporom"),
                    prop("ro.coloros.version"),
                    prop("ro.build.version.oplusrom.display")
                ))
            )
            vendorText.contains("vivo") || vendorText.contains("iqoo") -> {
                val vivoOsName = prop("ro.vivo.os.name")
                val vivoOsVersion = firstNonBlank(prop("ro.vivo.os.version"), vivoOsName)
                if (vivoOsName.contains("funtouch", ignoreCase = true) ||
                    buildText.contains("funtouch")
                ) {
                    Rom("Funtouch OS", cleanVersion(vivoOsVersion))
                } else {
                    Rom("OriginOS", cleanVersion(vivoOsVersion))
                }
            }
            vendorText.contains("honor") -> Rom("MagicOS", "")
            vendorText.contains("huawei") -> Rom("HarmonyOS", "")
            vendorText.contains("nothing") -> Rom("Nothing OS", "")
            vendorText.contains("meizu") -> Rom("Flyme", "")
            vendorText.contains("asus") -> Rom("ZenUI / ROG UI", "")
            vendorText.contains("motorola") -> Rom("My UX", "")
            vendorText.contains("sony") -> Rom("Xperia UI", "")
            vendorText.contains("zte") || vendorText.contains("nubia") -> Rom("MyOS / Nubia UI", "")
            buildText.contains("aosp") || buildText.contains("userdebug") -> Rom("AOSP", "")
            buildText.contains("pixel") -> Rom("Pixel UI", "")
            else -> Rom("", "")
        }
    }

    private fun detectCustomRom(buildText: String): Rom? = when {
        buildText.contains("graphene") -> Rom("GrapheneOS", "")
        buildText.contains("lineage") -> Rom("LineageOS", "")
        buildText.contains("crdroid") -> Rom("crDroid", "")
        buildText.contains("evolution") -> Rom("Evolution X", "")
        buildText.contains("pixelos") -> Rom("PixelOS", "")
        buildText.contains("derpfest") -> Rom("DerpFest", "")
        buildText.contains("arrow") -> Rom("ArrowOS", "")
        buildText.contains("paranoid") -> Rom("Paranoid Android", "")
        else -> null
    }

    private fun isXiaomiBuild(manufacturer: String, brand: String): Boolean {
        val names = listOf(manufacturer, brand, prop("ro.product.manufacturer"), prop("ro.product.brand"))
        return names.any {
            it.equals("xiaomi", true) ||
                it.equals("redmi", true) ||
                it.equals("poco", true)
        } || prop("ro.miui.ui.version.name").isNotBlank() ||
            prop("ro.mi.os.version.name").isNotBlank() ||
            prop("ro.miui.region").isNotBlank()
    }

    private fun detectChinaRom(): Boolean {
        val region = firstNonBlank(
            prop("ro.miui.region"),
            prop("ro.product.locale.region"),
            prop("ro.csc.country_code"),
            prop("ro.config.region")
        )
        if (region.equals("CN", true) || region.equals("CHN", true)) return true
        val build = "${Build.DISPLAY} ${Build.FINGERPRINT}".lowercase()
        return build.contains("cnxm") || build.contains("china")
    }

    private fun isEmulatorBuild(): Boolean {
        val text = "${Build.FINGERPRINT} ${Build.MODEL} ${Build.PRODUCT} ${Build.HARDWARE}".lowercase()
        return text.contains("generic") ||
            text.contains("emulator") ||
            text.contains("sdk_gphone") ||
            text.contains("goldfish") ||
            text.contains("ranchu")
    }

    private fun formatOneUi(raw: String): String {
        val cleaned = cleanVersion(raw)
        if (cleaned.isBlank()) return ""
        // Samsung uses values such as 70000 for One UI 7.0.
        val number = cleaned.toIntOrNull()
        if (number != null && number >= 1000) {
            val major = number / 10000
            val minor = (number % 10000) / 1000
            return if (minor > 0) "$major.$minor" else major.toString()
        }
        return cleaned
    }

    private fun cleanVersion(raw: String): String = raw
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .replace(Regex("""\s+"""), " ")
        .take(40)

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun prop(key: String): String = properties[key].orEmpty()

    /**
     * Lee todas las propiedades del sistema en una sola llamada a getprop.
     * Es más rápido y fiable que lanzar un proceso por cada propiedad.
     */
    private fun readAllProperties(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop"))
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    val match = Regex("""^\[([^\]]+)]\s*:\s*\[(.*)]$""").find(line.trim())
                    if (match != null) {
                        result[match.groupValues[1]] = match.groupValues[2]
                    }
                }
            }
            process.waitFor()
        } catch (_: Exception) {
            // getprop is not available in some restricted environments.
        }
        return result
    }
}
