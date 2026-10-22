package com.dav.isla

data class IslandStyle(
    val id: String,
    val label: String,
    val bgTop: Int,
    val bgBottom: Int,
    val strokeColor: Int,
    val strokeDp: Float,
    val highlight: Int,        // brillo superior (0 = sin brillo)
    val cornerDp: Float,
    val iconCircle: Boolean,
    val iconCornerDp: Float,
    val iconDp: Int,
    val showAppName: Boolean,
    val expandedWidthDp: Int,
    val titleSp: Float,
    val bodySp: Float,
    val padH: Int,
    val padV: Int,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val subColor: Int = 0xCCFFFFFF.toInt()
)

object Styles {
    // Imitacion de "Liquid Glass": fondo translucido, borde claro y brillo superior.
    val HYPEROS = IslandStyle(
        id = "hyperos", label = "HyperOS · Liquid Glass",
        bgTop = 0xEE343A46.toInt(), bgBottom = 0xE424282F.toInt(),
        strokeColor = 0x66FFFFFF, strokeDp = 1.2f, highlight = 0x40FFFFFF,
        cornerDp = 30f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12
    )

    // Negro puro, capsula completa, icono tipo squircle.
    val IOS = IslandStyle(
        id = "ios", label = "iOS",
        bgTop = 0xFF000000.toInt(), bgBottom = 0xFF000000.toInt(),
        strokeColor = 0, strokeDp = 0f, highlight = 0,
        cornerDp = 44f, iconCircle = false, iconCornerDp = 10f, iconDp = 38,
        showAppName = false, expandedWidthDp = 360, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12
    )

    // Capsula compacta, icono circular.
    val SAMSUNG = IslandStyle(
        id = "samsung", label = "Samsung",
        bgTop = 0xF2121214.toInt(), bgBottom = 0xF2121214.toInt(),
        strokeColor = 0x24FFFFFF, strokeDp = 1f, highlight = 0,
        cornerDp = 44f, iconCircle = true, iconCornerDp = 0f, iconDp = 32,
        showAppName = false, expandedWidthDp = 320, titleSp = 13f, bodySp = 11.5f,
        padH = 12, padV = 8
    )

    val CLASSIC = IslandStyle(
        id = "classic", label = "Clásico",
        bgTop = 0xFF000000.toInt(), bgBottom = 0xFF000000.toInt(),
        strokeColor = 0x33FFFFFF, strokeDp = 1f, highlight = 0,
        cornerDp = 40f, iconCircle = false, iconCornerDp = 10f, iconDp = 38,
        showAppName = true, expandedWidthDp = 340, titleSp = 14f, bodySp = 12f,
        padH = 12, padV = 10
    )

    // Violeta a azul profundo con borde luminoso.
    val AURORA = IslandStyle(
        id = "aurora", label = "Aurora",
        bgTop = 0xEB2A1458.toInt(), bgBottom = 0xEB0F2D55.toInt(),
        strokeColor = 0x99B388FF.toInt(), strokeDp = 1.4f, highlight = 0x33FFFFFF,
        cornerDp = 32f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12
    )

    // Negro con borde cian brillante.
    val NEON = IslandStyle(
        id = "neon", label = "Neón",
        bgTop = 0xFF05060A.toInt(), bgBottom = 0xFF05060A.toInt(),
        strokeColor = 0xFF22E5FF.toInt(), strokeDp = 1.6f, highlight = 0,
        cornerDp = 36f, iconCircle = false, iconCornerDp = 12f, iconDp = 38,
        showAppName = false, expandedWidthDp = 340, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, subColor = 0xCC9BF3FF.toInt()
    )

    // Naranja Ryx degradado.
    val AMBAR = IslandStyle(
        id = "ambar", label = "Ámbar Ryx",
        bgTop = 0xF5FF9A5C.toInt(), bgBottom = 0xF5E2571F.toInt(),
        strokeColor = 0x66FFFFFF, strokeDp = 1.2f, highlight = 0x40FFFFFF,
        cornerDp = 34f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF1B0B02.toInt(), subColor = 0xCC3A1A08.toInt()
    )

    // Verde bosque.
    val BOSQUE = IslandStyle(
        id = "bosque", label = "Bosque",
        bgTop = 0xEB123A28.toInt(), bgBottom = 0xEB0A1F18.toInt(),
        strokeColor = 0x8840D87A.toInt(), strokeDp = 1.3f, highlight = 0x26FFFFFF,
        cornerDp = 32f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12
    )

    // Gris plano, pequeña y discreta.
    val MINIMAL = IslandStyle(
        id = "minimal", label = "Minimal",
        bgTop = 0xF21C1C1E.toInt(), bgBottom = 0xF21C1C1E.toInt(),
        strokeColor = 0, strokeDp = 0f, highlight = 0,
        cornerDp = 22f, iconCircle = true, iconCornerDp = 0f, iconDp = 32,
        showAppName = false, expandedWidthDp = 320, titleSp = 13f, bodySp = 11.5f,
        padH = 12, padV = 8
    )

    // Cristal claro con texto oscuro.
    val CLARO = IslandStyle(
        id = "claro", label = "Cristal claro",
        bgTop = 0xE6F7F9FC.toInt(), bgBottom = 0xD9DCE3EE.toInt(),
        strokeColor = 0xAAFFFFFF.toInt(), strokeDp = 1.2f, highlight = 0x66FFFFFF,
        cornerDp = 30f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF14161C.toInt(), subColor = 0xCC3A3F4A.toInt()
    )

    // Azul océano profundo con borde luminoso.
    val OCEANO = IslandStyle(
        id = "oceano", label = "Océano",
        bgTop = 0xEB0F2C55.toInt(), bgBottom = 0xEB08192F.toInt(),
        strokeColor = 0x884DA3FF.toInt(), strokeDp = 1.3f, highlight = 0x33FFFFFF,
        cornerDp = 32f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12
    )

    // Rojo rubí con borde cálido.
    val RUBI = IslandStyle(
        id = "rubi", label = "Rubí",
        bgTop = 0xEB4A0F1C.toInt(), bgBottom = 0xEB26070F.toInt(),
        strokeColor = 0x88FF5A5F.toInt(), strokeDp = 1.3f, highlight = 0x33FFFFFF,
        cornerDp = 32f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12
    )

    // Menta suave, con texto oscuro.
    val MENTA = IslandStyle(
        id = "menta", label = "Menta",
        bgTop = 0xF2D6FBF2.toInt(), bgBottom = 0xF2AEEBDD.toInt(),
        strokeColor = 0x882EE6C5.toInt(), strokeDp = 1.2f, highlight = 0x66FFFFFF,
        cornerDp = 30f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF0E2A24.toInt(), subColor = 0xCC1E4A40.toInt()
    )

    // Titanio cepillado: gris con brillo metálico y borde frío.
    val TITANIO = IslandStyle(
        id = "titanio", label = "Titanio",
        bgTop = 0xF22A3039.toInt(), bgBottom = 0xF20C0F14.toInt(),
        strokeColor = 0x70DCE6F2.toInt(), strokeDp = 1.4f, highlight = 0x4DFFFFFF,
        cornerDp = 24f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12
    )

    // Lavanda clara, suave y luminosa.
    val LAVANDA = IslandStyle(
        id = "lavanda", label = "Lavanda",
        bgTop = 0xF2F0EBFF.toInt(), bgBottom = 0xF2DCD2FF.toInt(),
        strokeColor = 0x997C5CFF.toInt(), strokeDp = 1.2f, highlight = 0x77FFFFFF,
        cornerDp = 26f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF241A3A.toInt(), subColor = 0xCC4A3A6A.toInt()
    )

    // Atardecer coral con texto blanco.
    val ATARDECER = IslandStyle(
        id = "atardecer", label = "Atardecer",
        bgTop = 0xF5FF8A65.toInt(), bgBottom = 0xF5C9184A.toInt(),
        strokeColor = 0x66FFE0D6.toInt(), strokeDp = 1.3f, highlight = 0x40FFFFFF,
        cornerDp = 26f, iconCircle = false, iconCornerDp = 13f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12
    )

    // Ciberpúrpura con borde neón rosa.
    val CIBER = IslandStyle(
        id = "ciber", label = "Ciber",
        bgTop = 0xF51A0826.toInt(), bgBottom = 0xF50A0312.toInt(),
        strokeColor = 0xFFB026FF.toInt(), strokeDp = 1.6f, highlight = 0x33FF6EC7,
        cornerDp = 24f, iconCircle = false, iconCornerDp = 13f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, subColor = 0xCCF0B6FF.toInt()
    )

    // Arena cálida para quien prefiere algo claro y sobrio.
    val ARENA = IslandStyle(
        id = "arena", label = "Arena",
        bgTop = 0xF2F7EDD9.toInt(), bgBottom = 0xF2E8D5B5.toInt(),
        strokeColor = 0x889C7A4A.toInt(), strokeDp = 1.2f, highlight = 0x66FFFFFF,
        cornerDp = 26f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF2B2114.toInt(), subColor = 0xCC5A4A32.toInt()
    )

    // Hielo: azul muy claro, casi transparente, con texto profundo.
    val HIELO = IslandStyle(
        id = "hielo", label = "Hielo",
        bgTop = 0xEEE6F7FF.toInt(), bgBottom = 0xEEB9DFFF.toInt(),
        strokeColor = 0x99FFFFFF.toInt(), strokeDp = 1.4f, highlight = 0x77FFFFFF,
        cornerDp = 26f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF08243A.toInt(), subColor = 0xCC164A6A.toInt()
    )

    val ALL = listOf(HYPEROS, IOS, SAMSUNG, CLASSIC, AURORA, NEON, AMBAR, BOSQUE, MINIMAL,
        CLARO, OCEANO, RUBI, MENTA, TITANIO, LAVANDA, ATARDECER, CIBER, ARENA, HIELO)

    fun byId(id: String): IslandStyle = ALL.firstOrNull { it.id == id } ?: HYPEROS
}
