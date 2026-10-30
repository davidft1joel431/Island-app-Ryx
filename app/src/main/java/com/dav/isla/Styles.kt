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
    // HyperOS: grafito cálido con un borde dorado discreto.
    val HYPEROS = IslandStyle(
        id = "hyperos", label = "HyperOS",
        bgTop = 0xEE19160E.toInt(), bgBottom = 0xE40E0C08.toInt(),
        strokeColor = 0x66F5B82E, strokeDp = 1.2f, highlight = 0x26F5B82E,
        cornerDp = 28f, iconCircle = false, iconCornerDp = 12f, iconDp = 38,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFFFF8E8.toInt(), subColor = 0xCCD8C9A6.toInt()
    )

    // iOS: negro absoluto con ícono squircle, estilo minimalista.
    val IOS = IslandStyle(
        id = "ios", label = "iOS",
        bgTop = 0xFF000000.toInt(), bgBottom = 0xFF000000.toInt(),
        strokeColor = 0, strokeDp = 0f, highlight = 0,
        cornerDp = 44f, iconCircle = false, iconCornerDp = 10f, iconDp = 38,
        showAppName = false, expandedWidthDp = 360, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFFFFFFF.toInt(), subColor = 0xFF8E8E93.toInt()
    )

    // Samsung: gris oscuro con ícono circular.
    val SAMSUNG = IslandStyle(
        id = "samsung", label = "Samsung",
        bgTop = 0xFF121212.toInt(), bgBottom = 0xFF0C0C0C.toInt(),
        strokeColor = 0x24FFFFFF, strokeDp = 1f, highlight = 0,
        cornerDp = 44f, iconCircle = true, iconCornerDp = 0f, iconDp = 32,
        showAppName = false, expandedWidthDp = 320, titleSp = 13f, bodySp = 11.5f,
        padH = 12, padV = 8, textColor = 0xFFFFFFFF.toInt(), subColor = 0xCC8E8E93.toInt()
    )

    val CLASSIC = IslandStyle(
        id = "classic", label = "Clásico",
        bgTop = 0xFF0B0C10.toInt(), bgBottom = 0xFF08090C.toInt(),
        strokeColor = 0x33FFFFFF, strokeDp = 1f, highlight = 0,
        cornerDp = 38f, iconCircle = false, iconCornerDp = 10f, iconDp = 38,
        showAppName = true, expandedWidthDp = 340, titleSp = 14f, bodySp = 12f,
        padH = 12, padV = 10, textColor = 0xFFFFFFFF.toInt(), subColor = 0xCC9CA3AF.toInt()
    )

    // Aurora: índigo a violeta profundo sobre negro.
    val AURORA = IslandStyle(
        id = "aurora", label = "Aurora",
        bgTop = 0xFF16142A.toInt(), bgBottom = 0xFF0C0B1A.toInt(),
        strokeColor = 0x556366F1.toInt(), strokeDp = 1.4f, highlight = 0x226366F1,
        cornerDp = 30f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFFFE9FF.toInt(), subColor = 0xCCC7B3FF.toInt()
    )

    // Neón: negro absoluto con borde cian brillante.
    val NEON = IslandStyle(
        id = "neon", label = "Neón",
        bgTop = 0xFF020306.toInt(), bgBottom = 0xFF020306.toInt(),
        strokeColor = 0xFF06B6D4.toInt(), strokeDp = 1.6f, highlight = 0x1006B6D4,
        cornerDp = 32f, iconCircle = false, iconCornerDp = 12f, iconDp = 38,
        showAppName = false, expandedWidthDp = 340, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, subColor = 0xCC22D3EE.toInt()
    )

    // Ámbar tenue: dorado oscuro sobre base minimalista.
    val AMBAR = IslandStyle(
        id = "ambar", label = "Ámbar tenue",
        bgTop = 0xFF1A1410.toInt(), bgBottom = 0xFF0E0C0A.toInt(),
        strokeColor = 0x55C0A060.toInt(), strokeDp = 1.2f, highlight = 0x22C0A060,
        cornerDp = 30f, iconCircle = false, iconCornerDp = 12f, iconDp = 38,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFFFF4E6.toInt(), subColor = 0xCCD9C0A0.toInt()
    )

    // Bosque: verde oscuro profundo sobre negro.
    val BOSQUE = IslandStyle(
        id = "bosque", label = "Bosque oscuro",
        bgTop = 0xFF0B140C.toInt(), bgBottom = 0xFF070C08.toInt(),
        strokeColor = 0x5534D399.toInt(), strokeDp = 1.3f, highlight = 0x1810B981,
        cornerDp = 30f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFD1FAE5.toInt(), subColor = 0xCCA7F3D0.toInt()
    )

    // Grafito: gris neutro puro, estilo minimalista extremo.
    val MINIMAL = IslandStyle(
        id = "minimal", label = "Grafito",
        bgTop = 0xFF0E0F12.toInt(), bgBottom = 0xFF0A0B0E.toInt(),
        strokeColor = 0x336B7280.toInt(), strokeDp = 1f, highlight = 0,
        cornerDp = 20f, iconCircle = true, iconCornerDp = 0f, iconDp = 32,
        showAppName = false, expandedWidthDp = 320, titleSp = 13f, bodySp = 11.5f,
        padH = 12, padV = 8, textColor = 0xFFE5E7EB.toInt(), subColor = 0xCC9CA3AF.toInt()
    )

    // Cristal claro: base muy clara con texto oscuro (estilo limpio).
    val CLARO = IslandStyle(
        id = "claro", label = "Cristal claro",
        bgTop = 0xF0F4F8FF.toInt(), bgBottom = 0xE5E9EEFF.toInt(),
        strokeColor = 0xAAFFFFFF.toInt(), strokeDp = 1.2f, highlight = 0x55FFFFFF,
        cornerDp = 28f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF0F172A.toInt(), subColor = 0xCC475569.toInt()
    )

    // Océano: azul profundo sobre negro vacío.
    val OCEANO = IslandStyle(
        id = "oceano", label = "Océano",
        bgTop = 0xFF0C1426.toInt(), bgBottom = 0xFF080C18.toInt(),
        strokeColor = 0x553B82F6.toInt(), strokeDp = 1.3f, highlight = 0x1E3B82F6,
        cornerDp = 30f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFDBEAFE.toInt(), subColor = 0xCC93C5FD.toInt()
    )

    // Rubí: rojo oscuro profundo sobre negro.
    val RUBI = IslandStyle(
        id = "rubi", label = "Rubí",
        bgTop = 0xFF1E0B0B.toInt(), bgBottom = 0xFF0E0707.toInt(),
        strokeColor = 0x55F87171.toInt(), strokeDp = 1.3f, highlight = 0x18EF4444,
        cornerDp = 30f, iconCircle = false, iconCornerDp = 14f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFFEE2E2.toInt(), subColor = 0xCCFCA5A5.toInt()
    )

    // Menta: verde claro sobre base blanca grisácea.
    val MENTA = IslandStyle(
        id = "menta", label = "Menta clara",
        bgTop = 0xF0FDF4FF.toInt(), bgBottom = 0xECFDF5FF.toInt(),
        strokeColor = 0x8810B981.toInt(), strokeDp = 1.2f, highlight = 0x55FFFFFF,
        cornerDp = 28f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF064E3B.toInt(), subColor = 0xCC115E59.toInt()
    )

    // Titanio: gris acero con borde metálico sutil.
    val TITANIO = IslandStyle(
        id = "titanio", label = "Titanio",
        bgTop = 0xFF14181D.toInt(), bgBottom = 0xFF0E1015.toInt(),
        strokeColor = 0x559CA3AF.toInt(), strokeDp = 1.2f, highlight = 0x1EFFFFFF,
        cornerDp = 22f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFE2E8F0.toInt(), subColor = 0xCC94A3B8.toInt()
    )

    // Lavanda: violeta claro suave.
    val LAVANDA = IslandStyle(
        id = "lavanda", label = "Lavanda",
        bgTop = 0xF5F3FEFF.toInt(), bgBottom = 0xFAFAFEFF.toInt(),
        strokeColor = 0x888B5CF6.toInt(), strokeDp = 1.2f, highlight = 0x55FFFFFF,
        cornerDp = 26f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF312E81.toInt(), subColor = 0xCC4338CA.toInt()
    )

    // Atardecer suave: coral oscuro sobre base gris pizarra.
    val ATARDECER = IslandStyle(
        id = "atardecer", label = "Atardecer suave",
        bgTop = 0xFF1E1412.toInt(), bgBottom = 0xFF0E0C0B.toInt(),
        strokeColor = 0x55EF6C6C.toInt(), strokeDp = 1.3f, highlight = 0x22EF4444,
        cornerDp = 26f, iconCircle = false, iconCornerDp = 13f, iconDp = 38,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFFFFF1F0.toInt(), subColor = 0xCCD4A5A3.toInt()
    )

    // Ciber violeta: púrpura profundo sobre negro vacío.
    val CIBER = IslandStyle(
        id = "ciber", label = "Ciber violeta",
        bgTop = 0xFF120A1E.toInt(), bgBottom = 0xFF080510.toInt(),
        strokeColor = 0x558B5CF6.toInt(), strokeDp = 1.4f, highlight = 0x228B5CF6,
        cornerDp = 24f, iconCircle = false, iconCornerDp = 13f, iconDp = 38,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, subColor = 0xCCC7B3FF.toInt()
    )

    // Arena: arena cálida sobre fondo claro.
    val ARENA = IslandStyle(
        id = "arena", label = "Arena",
        bgTop = 0xF7F5F0FF.toInt(), bgBottom = 0xF1EFE7FF.toInt(),
        strokeColor = 0x88C0A060.toInt(), strokeDp = 1.2f, highlight = 0x55FFFFFF,
        cornerDp = 26f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF292524.toInt(), subColor = 0xCC78716C.toInt()
    )

    // Hielo: azul muy claro casi transparente.
    val HIELO = IslandStyle(
        id = "hielo", label = "Hielo",
        bgTop = 0xFFFEFCFF.toInt(), bgBottom = 0xFFF1F5FF.toInt(),
        strokeColor = 0x88FFFFFF.toInt(), strokeDp = 1.4f, highlight = 0x55FFFFFF,
        cornerDp = 26f, iconCircle = false, iconCornerDp = 12f, iconDp = 40,
        showAppName = false, expandedWidthDp = 350, titleSp = 14f, bodySp = 12f,
        padH = 14, padV = 12, textColor = 0xFF0E1726.toInt(), subColor = 0xCC1E3A5C.toInt()
    )

    val ALL = listOf(HYPEROS, IOS, SAMSUNG, CLASSIC, AURORA, NEON, AMBAR, BOSQUE, MINIMAL,
        CLARO, OCEANO, RUBI, MENTA, TITANIO, LAVANDA, ATARDECER, CIBER, ARENA, HIELO)

    fun byId(id: String): IslandStyle = ALL.firstOrNull { it.id == id } ?: HYPEROS
}
