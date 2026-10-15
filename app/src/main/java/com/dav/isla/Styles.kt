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
    val padV: Int
)

object Styles {
    // Imitacion de "Liquid Glass": fondo translucido, borde claro y brillo superior.
    val HYPEROS = IslandStyle(
        id = "hyperos", label = "HyperOS · Liquid Glass",
        bgTop = 0xB8343A46.toInt(), bgBottom = 0xA624282F.toInt(),
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

    val ALL = listOf(HYPEROS, IOS, SAMSUNG, CLASSIC)

    fun byId(id: String): IslandStyle = ALL.firstOrNull { it.id == id } ?: HYPEROS
}
