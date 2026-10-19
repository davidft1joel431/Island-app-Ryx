package com.dav.isla

/** Catálogo de eventos que puede mostrar la isla, agrupados como en Smart Island. */
data class IslandEvent(
    val id: String,
    val label: String,
    val symbol: String,   // nombre del icono vectorial (ic_<nombre>)
    val title: String,    // texto de ejemplo para la tarjeta
    val body: String,
    val short: Boolean,   // true = se va sola; false = queda hasta que se borre
    val color: Int = 0,   // color propio del icono (0 = el de acento)
    val target: String? = null   // a qué pantalla del sistema lleva al tocarlo (ver NotifListener.targetIntents)
)

data class EventGroup(val id: String, val label: String, val symbol: String, val events: List<IslandEvent>)

object Events {
    val POWER = EventGroup("power", "Energía", "plug", listOf(
        IslandEvent("plugged", "Conectado", "plug", "Cargando", "Cargador conectado", true, 0xFF30D158.toInt(), "battery"),
        IslandEvent("unplugged", "Desconectado", "plug_off", "Desconectado", "Cargador retirado", true, 0xFFFF9F0A.toInt(), "battery"),
        IslandEvent("full", "Completa", "battery", "Batería completa", "100% cargada", true, 0xFF30D158.toInt(), "battery"),
        IslandEvent("low", "Batería baja", "battery_alert", "Batería baja", "Quedan 15%", true, 0xFFFF453A.toInt(), "saver"),
        IslandEvent("saver", "Ahorro", "eco", "Ahorro de batería", "Modo ahorro activado", true, 0xFFFFD60A.toInt(), "saver")
    ))
    val CONNECTIVITY = EventGroup("conn", "Conectividad", "wifi", listOf(
        IslandEvent("wifi", "Wi-Fi", "wifi", "Wi-Fi", "Conectado a la red", true, 0xFF0A84FF.toInt(), "wifi"),
        IslandEvent("data", "Datos móviles", "signal", "Datos móviles", "Conexión activa", true, 0xFF30D158.toInt(), "data"),
        IslandEvent("bt", "Bluetooth", "bluetooth", "Bluetooth", "Dispositivo conectado", true, 0xFF5E5CE6.toInt(), "bluetooth"),
        IslandEvent("airplane", "Modo avión", "airplane", "Modo avión", "Activado", true, 0xFFFF9F0A.toInt(), "airplane"),
        IslandEvent("hotspot", "Punto de acceso", "wifi", "Punto de acceso", "Compartiendo internet", false, 0xFF40C8E0.toInt(), "hotspot"),
        IslandEvent("vpn", "VPN", "vpn", "VPN", "Conectada", false, 0xFFBF5AF2.toInt(), "vpn")
    ))
    val ACTIVITIES = EventGroup("act", "Actividades", "phone", listOf(
        IslandEvent("call", "Llamada", "phone", "Llamada entrante", "Mamá", false, 0xFF30D158.toInt(), "dialer"),
        IslandEvent("missed", "Llamada perdida", "missed", "Llamada perdida", "Juan", true, 0xFFFF453A.toInt(), "calllog"),
        IslandEvent("msg", "Mensaje", "message", "Mensaje nuevo", "Hola, ¿cómo estás?", true, 0xFF30D158.toInt(), "messages"),
        IslandEvent("media", "Música", "music", "Reproduciendo", "Artista · Canción", false, 0xFFFF375F.toInt(), null),
        IslandEvent("timer", "Temporizador", "timer", "Temporizador", "04:32", false, 0xFFFF9F0A.toInt(), "alarms"),
        IslandEvent("alarm", "Alarma", "timer", "Alarma", "07:00", true, 0xFFFF9F0A.toInt(), "alarms"),
        IslandEvent("nav", "Navegación", "nav", "Navegación", "Gira a la derecha en 200 m", false, 0xFF0A84FF.toInt(), null),
        IslandEvent("rec", "Grabación de voz", "mic", "Grabando", "00:12", false, 0xFFFF453A.toInt(), null),
        IslandEvent("screenrec", "Grabación de pantalla", "record", "Grabando pantalla", "00:45", false, 0xFFFF453A.toInt(), null),
        IslandEvent("download", "Descarga", "download", "Descargando", "archivo.zip · 64%", false, 0xFF0A84FF.toInt(), "downloads"),
        IslandEvent("upload", "Subida", "upload", "Subiendo", "foto.jpg · 40%", false, 0xFF0A84FF.toInt(), "downloads"),
        IslandEvent("ride", "Viaje", "car", "Tu viaje", "El conductor llega en 3 min", false, 0xFF40C8E0.toInt(), null),
        IslandEvent("delivery", "Pedido", "bag", "Tu pedido", "Llega en 12 min", false, 0xFFFF9F0A.toInt(), null),
        IslandEvent("sport", "Marcador", "ball", "Marcador", "2 - 1 · 78'", false, 0xFF30D158.toInt(), null),
        IslandEvent("flash", "Linterna", "flash", "Linterna", "Encendida", false, 0xFFFFD60A.toInt(), null),
        IslandEvent("dnd", "No molestar", "moon", "No molestar", "Activado", true, 0xFFBF5AF2.toInt(), "dnd")
    ))
    val PRIVACY = EventGroup("priv", "Privacidad", "camera", listOf(
        IslandEvent("cam", "Cámara en uso", "camera", "Cámara en uso", "Una app usa la cámara", false, 0xFF30D158.toInt(), "privacy"),
        IslandEvent("mic", "Micrófono en uso", "mic", "Micrófono en uso", "Una app usa el micrófono", false, 0xFFFF9F0A.toInt(), "privacy"),
        IslandEvent("loc", "Ubicación en uso", "location", "Ubicación en uso", "Una app usa tu ubicación", false, 0xFF0A84FF.toInt(), "privacy"),
        IslandEvent("share", "Compartiendo pantalla", "screen", "Compartiendo pantalla", "Pantalla visible para otros", false, 0xFFFF453A.toInt(), null)
    ))
    val GROUPS = listOf(POWER, CONNECTIVITY, ACTIVITIES, PRIVACY)
    fun find(id: String): IslandEvent? = GROUPS.flatMap { it.events }.firstOrNull { it.id == id }
}
