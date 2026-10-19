package com.dav.isla

/**
 * Clasificación de los avisos del SISTEMA (los de Android: batería, Wi-Fi, red, Bluetooth…)
 * frente a los de las aplicaciones normales.
 *
 * Se usa para dos cosas:
 *  · separar el historial en dos grandes secciones («Aplicaciones» y «Sistema»);
 *  · unir todos los avisos de batería en UNA sola tarjeta con el nivel en tiempo real,
 *    en lugar de dejar cuatro notificaciones sueltas y mal registradas.
 */
object Sys {

    private val PKGS = setOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.server.telecom", "com.android.bluetooth",
        "com.android.shell", "com.android.providers.downloads"
    )

    /** ¿El paquete es del propio sistema (no de una app normal)? */
    fun isSystemPkg(pkg: String): Boolean =
        pkg in PKGS || pkg.startsWith("com.android.providers.") || pkg.startsWith("com.android.systemui")

    /** Subcategoría del aviso dentro de «Sistema»: battery · wifi · net · bt · other. */
    fun kind(pkg: String, title: CharSequence?, text: CharSequence?): String {
        val s = ((title?.toString() ?: "") + " " + (text?.toString() ?: "")).lowercase()
        return when {
            s.contains("bater") || s.contains("carg") || s.contains("battery") || s.contains("charg") -> "battery"
            s.contains("wi-fi") || s.contains("wifi") || s.contains("wlan") -> "wifi"
            s.contains("bluetooth") -> "bt"
            s.contains("datos") || s.contains("móvil") || s.contains("movil") ||
                s.contains("mobile") || s.contains("network") || s.contains("internet") ||
                s.contains("red ") || s.contains("red\n") -> "net"
            else -> "other"
        }
    }

    /** Aviso de batería del propio sistema (debe unirse en una sola tarjeta). */
    fun isBatteryNotif(pkg: String, title: CharSequence?, text: CharSequence?): Boolean =
        isSystemPkg(pkg) && kind(pkg, title, text) == "battery"

    fun label(k: String): String = when (k) {
        "battery" -> "Batería"
        "wifi" -> "Wi-Fi"
        "net" -> "Red"
        "bt" -> "Bluetooth"
        else -> "Sistema"
    }

    fun symbol(k: String): String = when (k) {
        "battery" -> "battery"
        "wifi" -> "wifi"
        "net" -> "signal"
        "bt" -> "bluetooth"
        else -> "settings"
    }

    fun color(k: String): Int = when (k) {
        "battery" -> 0xFF30D158.toInt()
        "wifi" -> 0xFF0A84FF.toInt()
        "net" -> 0xFF40C8E0.toInt()
        "bt" -> 0xFF5E5CE6.toInt()
        else -> 0xFF6A62E8.toInt()
    }
}
