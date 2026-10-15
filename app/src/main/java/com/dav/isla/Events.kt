package com.dav.isla

/** Catálogo de eventos que puede mostrar la isla, agrupados como en Smart Island. */
data class IslandEvent(
    val id: String,
    val label: String,
    val symbol: String,   // nombre del icono vectorial (ic_<nombre>)
    val title: String,    // texto de ejemplo para la tarjeta
    val body: String,
    val short: Boolean    // true = se va sola; false = queda hasta que se borre
)

data class EventGroup(val id: String, val label: String, val symbol: String, val events: List<IslandEvent>)

object Events {
    val POWER = EventGroup("power", "Energía", "plug", listOf(
        IslandEvent("plugged", "Conectado", "plug", "Cargando", "Cargador conectado", true),
        IslandEvent("unplugged", "Desconectado", "plug_off", "Desconectado", "Cargador retirado", true),
        IslandEvent("full", "Completa", "battery", "Batería completa", "100% cargada", true),
        IslandEvent("low", "Batería baja", "battery_alert", "Batería baja", "Quedan 15%", true),
        IslandEvent("saver", "Ahorro", "eco", "Ahorro de batería", "Modo ahorro activado", true)
    ))
    val CONNECTIVITY = EventGroup("conn", "Conectividad", "wifi", listOf(
        IslandEvent("wifi", "Wi-Fi", "wifi", "Wi-Fi", "Conectado a la red", true),
        IslandEvent("data", "Datos móviles", "signal", "Datos móviles", "Conexión activa", true),
        IslandEvent("bt", "Bluetooth", "bluetooth", "Bluetooth", "Dispositivo conectado", true),
        IslandEvent("airplane", "Modo avión", "airplane", "Modo avión", "Activado", true),
        IslandEvent("hotspot", "Punto de acceso", "wifi", "Punto de acceso", "Compartiendo internet", false),
        IslandEvent("vpn", "VPN", "vpn", "VPN", "Conectada", false)
    ))
    val ACTIVITIES = EventGroup("act", "Actividades", "phone", listOf(
        IslandEvent("call", "Llamada", "phone", "Llamada entrante", "Mamá", false),
        IslandEvent("missed", "Llamada perdida", "missed", "Llamada perdida", "Juan", true),
        IslandEvent("msg", "Mensaje", "message", "Mensaje nuevo", "Hola, ¿cómo estás?", true),
        IslandEvent("media", "Música", "music", "Reproduciendo", "Artista · Canción", false),
        IslandEvent("timer", "Temporizador", "timer", "Temporizador", "04:32", false),
        IslandEvent("alarm", "Alarma", "timer", "Alarma", "07:00", true),
        IslandEvent("nav", "Navegación", "nav", "Navegación", "Gira a la derecha en 200 m", false),
        IslandEvent("rec", "Grabación de voz", "mic", "Grabando", "00:12", false),
        IslandEvent("screenrec", "Grabación de pantalla", "record", "Grabando pantalla", "00:45", false),
        IslandEvent("download", "Descarga", "download", "Descargando", "archivo.zip · 64%", false),
        IslandEvent("upload", "Subida", "upload", "Subiendo", "foto.jpg · 40%", false),
        IslandEvent("ride", "Viaje", "car", "Tu viaje", "El conductor llega en 3 min", false),
        IslandEvent("delivery", "Pedido", "bag", "Tu pedido", "Llega en 12 min", false),
        IslandEvent("sport", "Marcador", "ball", "Marcador", "2 - 1 · 78'", false),
        IslandEvent("flash", "Linterna", "flash", "Linterna", "Encendida", false),
        IslandEvent("dnd", "No molestar", "moon", "No molestar", "Activado", true)
    ))
    val PRIVACY = EventGroup("priv", "Privacidad", "camera", listOf(
        IslandEvent("cam", "Cámara en uso", "camera", "Cámara en uso", "Una app usa la cámara", false),
        IslandEvent("mic", "Micrófono en uso", "mic", "Micrófono en uso", "Una app usa el micrófono", false),
        IslandEvent("loc", "Ubicación en uso", "location", "Ubicación en uso", "Una app usa tu ubicación", false),
        IslandEvent("share", "Compartiendo pantalla", "screen", "Compartiendo pantalla", "Pantalla visible para otros", false)
    ))
    val GROUPS = listOf(POWER, CONNECTIVITY, ACTIVITIES, PRIVACY)
    fun find(id: String): IslandEvent? = GROUPS.flatMap { it.events }.firstOrNull { it.id == id }
}
