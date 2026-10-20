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

    /**
     * Apps de pedidos (comida, súper, paquetería): Ryx las reconoce por su paquete para marcarlas como «Pedido»
     * con su propio color e icono. No existe un aviso del SISTEMA para esto: el pedido solo lo sabe la app, así que
     * lo correcto es detectar la app, no inventar un evento del sistema.
     */
    private val ORDER = setOf(
        "com.grability.rappi", "com.rappi.android", "com.rappi", "com.pedidosya", "com.pedidosya.consumer",
        "com.ubercab.eats", "com.glovo", "com.cornershopapp.consumer", "com.didiglobal.food", "com.ifood",
        "br.com.brainweb.ifood", "com.wolt.android", "com.mcdonalds.mobileapp", "com.justo.android",
        "com.deliveryhero.customer", "com.tipti.consumer", "com.rappi.restaurants", "com.uberEats.consumer"
    )

    /** Apps de viajes/transporte: Ryx las reconoce por su paquete para marcarlas como «Viaje». */
    private val RIDE = setOf(
        "com.ubercab", "com.ubercab.driver", "com.didiglobal.passenger", "com.didiglobal.driver",
        "com.cabify.rider", "com.cabify.driver", "com.beat.passenger", "com.rappi.driver",
        "com.pedidosya.driver", "com.bolt.rider", "com.careem.rider", "com.free.taxis"
    )

    /** App de pedidos (comida, súper, paquetería). */
    fun isOrderApp(pkg: String): Boolean = pkg in ORDER

    /** App de viajes/transporte. */
    fun isRideApp(pkg: String): Boolean = pkg in RIDE

    /**
     * Apps de CHAT conocidas (mensajería). Se usan para la lectura por accesibilidad: cuando el usuario abre una
     * de estas apps, Ryx puede leer el texto visible de la conversación y guardarlo en el Historial (incluidos
     * los chats archivados o restringidos, que no publican notificación).
     */
    private val CHAT = setOf(
        // WhatsApp y variantes
        "com.whatsapp", "com.whatsapp.w4b",
        // Telegram
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "org.telegram.plus", "ir.ilmili.telegraph", "com.telegram.messenger",
        // Instagram / Facebook / Messenger
        "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.facebook.lite",
        "com.facebook.mlite", "com.instagram.lite", "com.instagram.barcelona",
        // TikTok y su versión ligera; Wehe
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.tiktok.lite",
        "com.tiktok.lite.go", "com.tiktok.lite.musically", "com.wehe.app", "com.wehe",
        // Otros de mensajería frecuentes
        "org.thoughtcrime.securesms", "org.whispersystems.signal", "com.signal.android",
        "com.snapchat.android", "com.discord", "com.twitter.android", "com.x.android",
        "com.viber.voip", "com.tencent.mm", "com.kakao.talk", "jp.naver.line.android",
        "com.imo.android.imoim", "com.imo.android.imoimbeta", "com.linkedin.android",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.miui.smsextra", "com.miui.smsextra.overseas",
        "com.hellotext.hello", "com.textra", "com.pinger.textfree", "com.chomp.sms",
        "com.microsoft.teams", "com.slack", "com.zhiliaoapp.musically.go",
        "com.tencent.mobileqq", "com.tencent.tim", "com.whatsapp.w4b", "com.google.android.apps.dynamite"
    )

    /** ¿Es una app de chat/mensajería conocida? */
    fun isChatApp(pkg: String): Boolean = pkg in CHAT ||
        pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messenger") ||
        pkg.contains("tiktok") || pkg.contains("instagram") || pkg.contains("snapchat") ||
        pkg.contains("discord") || pkg.contains("signal") || pkg.contains("messaging") ||
        pkg.contains("wehe")

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
