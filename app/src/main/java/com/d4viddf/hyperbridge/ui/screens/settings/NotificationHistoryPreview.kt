package com.d4viddf.hyperbridge.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.d4viddf.hyperbridge.data.db.NotificationHistoryEntry
import com.d4viddf.hyperbridge.models.NotificationType
import com.d4viddf.hyperbridge.ui.theme.HyperBridgeTheme

/** Sample data for the history previews; no database or package manager involved. */
private fun previewHistory(): List<NotificationHistoryEntry> {
    val now = System.currentTimeMillis()
    fun minutesAgo(minutes: Int): Long = now - minutes * 60_000L

    return listOf(
        entry(
            id = 1,
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "María",
            text = "¿Nos vemos mañana a las 20:00?",
            type = NotificationType.MESSAGE.name,
            postedAt = minutesAgo(8)
        ),
        entry(
            id = 2,
            packageName = "com.google.android.gm",
            appLabel = "Gmail",
            title = "Banco",
            text = "Cargo de S/ 129.90 en tu tarjeta terminada en 4821.",
            type = NotificationType.MESSAGE.name,
            postedAt = minutesAgo(95)
        ),
        entry(
            id = 3,
            packageName = "com.google.android.dialer",
            appLabel = "Teléfono",
            title = "Mamá",
            text = "Llamada perdida",
            type = NotificationType.CALL.name,
            postedAt = minutesAgo(180)
        ),
        entry(
            id = 4,
            packageName = "com.google.android.apps.docs",
            appLabel = "Drive",
            title = "Documento.pdf",
            text = "Descarga completada",
            type = NotificationType.DOWNLOAD.name,
            postedAt = minutesAgo(300)
        ),
        entry(
            id = 5,
            packageName = "com.android.vending",
            appLabel = "Play Store",
            title = "Actualizando apps",
            text = "3 de 5 aplicaciones actualizadas",
            type = NotificationType.PROGRESS.name,
            postedAt = minutesAgo(420)
        ),
        entry(
            id = 6,
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Carlos",
            text = "Te envío el archivo cuando llegue a casa.",
            type = NotificationType.MESSAGE.name,
            postedAt = minutesAgo(25 * 60)
        ),
        entry(
            id = 7,
            packageName = "com.instagram.android",
            appLabel = "Instagram",
            title = "Nueva actividad",
            text = "A 3 personas les gustó tu publicación.",
            type = NotificationType.MESSAGE.name,
            postedAt = minutesAgo(28 * 60)
        ),
        entry(
            id = 8,
            packageName = "com.spotify.music",
            appLabel = "Spotify",
            title = "Tu playlist semanal",
            text = "Ya está lista para escuchar.",
            type = "OTHER",
            postedAt = minutesAgo(30 * 60)
        ),
        entry(
            id = 9,
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Ana",
            text = "Confirmado para el sábado.",
            type = NotificationType.MESSAGE.name,
            postedAt = minutesAgo(50 * 60)
        )
    )
}

private fun entry(
    id: Long,
    packageName: String,
    appLabel: String,
    title: String,
    text: String,
    type: String,
    postedAt: Long
) = NotificationHistoryEntry(
    id = id,
    notificationKey = "preview-$id",
    packageName = packageName,
    appLabel = appLabel,
    title = title,
    text = text,
    type = type,
    postedAt = postedAt
)

@Preview(
    name = "Historial · agrupado por día",
    showBackground = true,
    widthDp = 400,
    heightDp = 860
)
@Composable
private fun NotificationHistoryDayPreview() {
    HyperBridgeTheme {
        NotificationHistoryContent(
            history = previewHistory(),
            query = "",
            onQueryChange = {},
            filter = HistoryFilter.ALL,
            onFilterChange = {},
            grouping = HistoryGrouping.DAY,
            onGroupingChange = {},
            sort = HistorySort.RECENT,
            onSortChange = {},
            onBack = {},
            onDelete = { _ -> },
            onClearHistory = {}
        )
    }
}

@Preview(
    name = "Historial · agrupado por app",
    showBackground = true,
    widthDp = 400,
    heightDp = 860
)
@Composable
private fun NotificationHistoryAppPreview() {
    HyperBridgeTheme {
        NotificationHistoryContent(
            history = previewHistory(),
            query = "",
            onQueryChange = {},
            filter = HistoryFilter.ALL,
            onFilterChange = {},
            grouping = HistoryGrouping.APP,
            onGroupingChange = {},
            sort = HistorySort.APP,
            onSortChange = {},
            onBack = {},
            onDelete = { _ -> },
            onClearHistory = {}
        )
    }
}

@Preview(
    name = "Historial · sin resultados",
    showBackground = true,
    widthDp = 400,
    heightDp = 860
)
@Composable
private fun NotificationHistoryEmptyPreview() {
    HyperBridgeTheme {
        NotificationHistoryContent(
            history = previewHistory(),
            query = "no-existe",
            onQueryChange = {},
            filter = HistoryFilter.MESSAGES,
            onFilterChange = {},
            grouping = HistoryGrouping.DAY,
            onGroupingChange = {},
            sort = HistorySort.RECENT,
            onSortChange = {},
            onBack = {},
            onDelete = { _ -> },
            onClearHistory = {}
        )
    }
}
