/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import com.agupta07505.smartisland.ui.glass.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.model.SwipeAction

// What each gesture does right now.

private data class GestureLine(val icon: ImageVector, val gesture: String, val result: String)

private data class GestureGroup(val title: String, val note: String?, val lines: List<GestureLine>)

@Composable
private fun actionText(id: String, default: SwipeAction, enabled: Boolean): String =
    if (!enabled) "Off (switched off above)" else getSwipeActionTitle(SwipeAction.fromId(id, default))

@Composable
private fun gestureGroups(settings: SmartIslandSettings): List<GestureGroup> {
    val pill = settings.enablePillSwipeActions
    val card = settings.enableSwipeActions
    val longPress = SwipeAction.fromId(settings.longPressAction, SwipeAction.TogglePill)
    val longPressTitle = getSwipeActionTitle(longPress)
    val longPressText = if (settings.enableAppShortcuts) {
        "On a glyph: opens your quick-launch apps (see Quick-launch window below). In the middle band: $longPressTitle. " +
            "If the middle band is clear of the camera hole, a long press anywhere on the pill opens the apps. In landscape the pill always opens them."
    } else {
        "$longPressTitle, anywhere on the pill."
    }
    return listOf(
        GestureGroup(
            title = "Collapsed island (the pill)",
            note = "Three touch areas: the glyph on the left, the glyph-free middle band over the camera spot, and the glyph or value on the right. Swipes follow your finger. Pull back before letting go to cancel.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap a glyph (left or right)", "Expands that event. On an auto-hidden pill it brings the pill back."),
                GestureLine(
                    Icons.Rounded.TouchApp, "Tap the middle band",
                    "Portrait, with cards on the island: puts every card away at once (the close with its first step skipped). " +
                        "With only an idle widget or nothing on the pill: brings back cards the idle timeout put away, when there are any. " +
                        "Landscape: the pill is not over the camera, so the whole pill expands."
                ),
                GestureLine(Icons.Rounded.Swipe, "Swipe left", actionText(settings.pillSwipeLeftAction, SwipeAction.PreviousNotification, pill)),
                GestureLine(Icons.Rounded.Swipe, "Swipe right", actionText(settings.pillSwipeRightAction, SwipeAction.NextNotification, pill)),
                GestureLine(Icons.Rounded.ArrowUpward, "Swipe up", actionText(settings.pillSwipeUpAction, SwipeAction.DismissCurrent, pill)),
                GestureLine(Icons.Rounded.ArrowDownward, "Swipe down", "Expands the island (fixed, cannot be changed)."),
                GestureLine(Icons.Rounded.TouchApp, "Mantén pulsado", longPressText),
            )
        ),
        GestureGroup(
            title = "Camera hole",
            note = "Where the pill's middle band does not reach the real camera hole, the hole sits outside the pill and gets its own touch target (at least finger-sized). If the band covers the hole, the band rules above apply. Notch mode counts as covering: the pill is the notch.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap the camera hole", "Nothing. Only a long press is handled there, so a stray tap on the hole never expands or closes anything."),
                GestureLine(Icons.Rounded.TouchApp, "Long press the camera hole", "$longPressTitle. It never opens the quick-launch apps; that is the pill's own long press."),
                GestureLine(Icons.Rounded.TouchApp, "Tap or hold the middle band (hole covered)", "Same as the middle band above: tap puts the cards away, long press runs $longPressTitle, and the glyphs on either side open the apps."),
            )
        ),
        GestureGroup(
            title = "Second and third pill",
            note = "The smaller pills beside the main one that show other events. Expanded, the second pill sits on the camera spot.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap the second pill", "Brings that event to the main pill and expands the island if it was collapsed."),
                GestureLine(Icons.Rounded.TouchApp, "Tap the second pill, expanded, card has siblings", "It shows three lines and opens the list of that app's other cards. A second tap closes the list."),
                GestureLine(Icons.Rounded.TouchApp, "Tap the third pill", "Switches to that event, and expands the island if it was collapsed."),
                GestureLine(Icons.Rounded.TouchApp, "Landscape: the pill beside an open card", "Tap swaps to the other stored card. It takes the pill swipes and the long press just like the main pill."),
            )
        ),
        GestureGroup(
            title = "Idle circle",
            note = "The small circle that stays when there are no cards, mostly seen in landscape. Portrait has no separate circle: the middle band of the pill does its job.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap", "Brings back cards that the idle timeout put away. Landscape, while a card is on the island, the circle stays as a small control: one tap puts every card away at once and the circle swells, then settles."),
                GestureLine(Icons.Rounded.TouchApp, "Mantén pulsado", "$longPressTitle (oculta o muestra la cápsula de forma predeterminada; no abre los accesos directos.)"),
            )
        ),
        GestureGroup(
            title = "Idle widgets (clock, weather and the rest)",
            note = "Shown on the pill when no card is.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap the glyph or the value", "Grows the pill into the small widget island. Tap again to close it."),
                GestureLine(Icons.Rounded.TouchApp, "Tap the middle band", "Brings back the stored cards, when there are any; otherwise nothing."),
                GestureLine(Icons.Rounded.TouchApp, "Tap outside the widget island", "Closes it. Taps on the island itself stay on it."),
                GestureLine(Icons.Rounded.Swipe, "Swipe the widget pill", "Works even when the pill swipe actions are switched off. The small round circle keeps its own behaviour."),
            )
        ),
        GestureGroup(
            title = "Hidden pill",
            note = "After a long press hides it, or when Auto-hide has tucked it away. Its spot stays touchable.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap its spot", "Auto-hidden: brings the pill back. Idle with cards put away: brings them back. Cards on the island: expands."),
            )
        ),
        GestureGroup(
            title = "Expanded card",
            note = "Touch anywhere on the open card.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap the card", "Opens the app the card came from."),
                GestureLine(Icons.Rounded.TouchApp, "Tap the middle of the top edge", "Portrait: that is the camera spot. It closes the island with the second pill's merge, then puts the cards away. A long press there runs $longPressTitle. The glyphs on either side keep switching cards."),
                GestureLine(Icons.Rounded.TouchApp, "Tap outside the card", "Closes things in order: an open picture first, then an open sibling list, then the island collapses back into the pill."),
                GestureLine(Icons.Rounded.ArrowUpward, "Swipe up", actionText(settings.swipeUpAction, SwipeAction.DismissCurrent, card) + ". Inside a card with several steps it goes back one step first."),
                GestureLine(Icons.Rounded.DeleteSweep, "Hold, then swipe up", actionText(settings.swipeHoldUpAction, SwipeAction.DismissAll, card) + ". Hold about 300 ms until you feel the pulse, then swipe."),
                GestureLine(Icons.Rounded.ArrowDownward, "Swipe down", "Opens expandable text, a picture or a sticker when the card has one; otherwise nothing (fixed)."),
                GestureLine(Icons.Rounded.Swipe, "Swipe left", actionText(settings.swipeLeftAction, SwipeAction.None, card)),
                GestureLine(Icons.Rounded.Swipe, "Swipe right", actionText(settings.swipeRightAction, SwipeAction.None, card)),
                GestureLine(Icons.Rounded.ArrowDownward, "Pull the handle", "The handle at the card's bottom edge opens the floating window with a longer, calmer pull (when Floating window is on)."),
            )
        ),
        GestureGroup(
            title = "Card steps: long text, pictures, stickers",
            note = "Step by step: text, preview (last text and last picture), reader (all text and thumbnails), then the picture. Stickers have no steps. In these steps the normal swipe actions are off.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap the arrow", "Opens the next step. The arrow beside a thumbnail opens that picture at full size."),
                GestureLine(Icons.Rounded.ArrowDownward, "Swipe down", "One step deeper. The island follows your finger and finishes the step when you pass the halfway point."),
                GestureLine(Icons.Rounded.ArrowUpward, "Swipe up", "One step back, and it never dismisses the card. The island stays in place."),
                GestureLine(Icons.Rounded.Swipe, "Swipe left or right", "Nothing, and not mistaken for a tap either."),
                GestureLine(Icons.Rounded.TouchApp, "Tap a picture in the picker", "Opens it. With two or more pictures the picker's own taps stay with it; the card's tap does not open the app there."),
                GestureLine(Icons.Rounded.ArrowUpward, "Scroll the reader text", "A drag the text consumes is its own and fires nothing else. Stopping a flinging reader with a touch is never a tap."),
            )
        ),
        GestureGroup(
            title = "Sibling list (cards from the same app)",
            note = "Opened by the second pill while the card has siblings.",
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap a row", "Shows that card."),
                GestureLine(Icons.Rounded.Swipe, "Drag on the rows", "Scrolls the list. A drag that starts on its header is an island swipe."),
                GestureLine(Icons.Rounded.TouchApp, "Tap the second pill again, or outside", "Closes the list."),
            )
        ),
        GestureGroup(
            title = "Quick-launch window",
            note = "Opened by the long press above (App Shortcuts must be on). With no cards on the island, any drag in it belongs to the window.",
            lines = listOf(
                GestureLine(Icons.Rounded.ArrowUpward, "Swipe up or down", "Switches between your apps and the Utilities page, and loops."),
                GestureLine(Icons.Rounded.Swipe, "Swipe left or right", "Switches between Apps and Recents (when Recents is on)."),
                GestureLine(Icons.Rounded.TouchApp, "Tap an app", "Opens it full screen."),
                GestureLine(Icons.Rounded.TouchApp, "Long press an app", "App info, Floating window or Split screen. Keep dragging to move it or drop it on another app to make a folder."),
                GestureLine(Icons.Rounded.TouchApp, "Tap outside", "Closes the window."),
            )
        ),
        GestureGroup(
            title = "Utilities page",
            note = null,
            lines = listOf(
                GestureLine(Icons.Rounded.TouchApp, "Tap a tile", "Runs it: flashlight, Wi-Fi, timer, stopwatch and the rest. The timer asks for a length first."),
                GestureLine(Icons.Rounded.ArrowUpward, "Swipe on a scrolling widget's edge", "A widget that scrolls up and down keeps its own swipes, so use the thin strip on its left or right edge to go back to the apps page."),
            )
        ),
    )
}

/** The full list of gestures, grouped by where they happen. Shows the actions as they are set right now. */
@Composable
fun GestureReferenceCard(settings: SmartIslandSettings) {
    val groups = gestureGroups(settings)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Gesture, size = 36)
                Column {
                    Text("All gestures", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "What every touch does right now. Change the actions in the cards above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            groups.forEachIndexed { index, group ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                group.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                group.lines.forEach { line ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(line.icon, size = 30)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(line.gesture, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(line.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size((size * 0.55f).dp))
    }
}

// The tour for new users: a few steps, one idea each, in the order a person meets them.

private data class TourStep(val icon: ImageVector, val title: String, val points: List<String>, val tip: String)

@Composable
private fun tourSteps(settings: SmartIslandSettings): List<TourStep> {
    val left = getSwipeActionTitle(SwipeAction.fromId(settings.pillSwipeLeftAction, SwipeAction.PreviousNotification))
    val right = getSwipeActionTitle(SwipeAction.fromId(settings.pillSwipeRightAction, SwipeAction.NextNotification))
    val up = getSwipeActionTitle(SwipeAction.fromId(settings.pillSwipeUpAction, SwipeAction.DismissCurrent))
    val cardUp = getSwipeActionTitle(SwipeAction.fromId(settings.swipeUpAction, SwipeAction.DismissCurrent))
    val cardHold = getSwipeActionTitle(SwipeAction.fromId(settings.swipeHoldUpAction, SwipeAction.DismissAll))
    return listOf(
        TourStep(
            Icons.Rounded.Gesture, "Conoce Ryx Isla",
            listOf(
                "Ryx Isla se sitúa alrededor del recorte de la cámara y muestra llamadas, música, temporizadores, carga, descargas y notificaciones.",
                "Al principio aparece como una cápsula. Tócala para abrir el resto de funciones.",
                "Necesita algunos permisos para funcionar. La sección Permisos de la app te guía paso a paso."
            ),
            "¿Aún no aparece ninguna tarjeta? Reproduce música o inicia un temporizador para verla."
        ),
        TourStep(
            Icons.Rounded.TouchApp, "Toca para abrir",
            listOf(
                "Toca una vez el icono de la cápsula para expandirla y ver sus botones.",
                "Toca el centro vacío de la cápsula, sobre la cámara, para contraer las tarjetas. Si está abierta, toca ese mismo lugar arriba para cerrarla.",
                "Toca una tarjeta para abrir la aplicación que la envió.",
                "Toca fuera de la tarjeta para volver a cerrarla.",
                "Toca una cápsula pequeña junto a la principal para mostrar ese evento."
            ),
            "Si la cápsula está oculta, tócala una vez para mostrarla. Tocar directamente el recorte de la cámara no hace nada."
        ),
        TourStep(
            Icons.Rounded.Swipe, "Desliza la cápsula",
            listOf(
                "Desliza a la izquierda: $left.",
                "Desliza a la derecha: $right.",
                "Desliza hacia arriba: $up.",
                "Desliza hacia abajo para expandir la isla."
            ),
            "La isla sigue el movimiento de tu dedo. Si cambias de idea, vuelve al inicio para cancelar."
        ),
        TourStep(
            Icons.Rounded.TouchApp, "Mantén pulsado",
            listOf(
                if (settings.enableAppShortcuts) {
                    "Mantén pulsada la cápsula para abrir tus aplicaciones favoritas. Añádelas en Accesos directos."
                } else {
                    "Mantén pulsada la cápsula para ocultarla o mostrarla."
                },
                "Mantén pulsado el recorte de la cámara cuando quede fuera de la cápsula, o la zona central, para ejecutar la acción configurada.",
                "Mantén pulsado el pequeño círculo cuando la isla esté inactiva para ocultar o mostrar la cápsula."
            ),
            "¿Prefieres ocultar o mostrar la cápsula al mantenerla pulsada? Desactiva Accesos directos o cambia la acción de pulsación larga."
        ),
        TourStep(
            Icons.Rounded.ArrowUpward, "En la tarjeta expandida",
            listOf(
                "Desliza hacia arriba: $cardUp.",
                "Mantén pulsado hasta sentir una vibración y, después, desliza hacia arriba: $cardHold.",
                "Desliza hacia abajo para ampliar el texto, las imágenes o los stickers, paso a paso. Desliza hacia arriba para volver.",
                "Arrastra el tirador del borde inferior de la tarjeta para abrir la app en una ventana flotante."
            ),
            "Los botones de la tarjeta pertenecen a la app que envió la notificación; responder, pausar o detener funcionan igual que en el panel de notificaciones."
        ),
        TourStep(
            Icons.Rounded.Apps, "Accesos directos y utilidades",
            listOf(
                "En la ventana de accesos directos, desliza arriba o abajo para alternar entre tus apps y Utilidades.",
                "Desliza a izquierda o derecha para ver las aplicaciones recientes, si está activado.",
                "Las utilidades son accesos de un toque: linterna, wifi, temporizador, cronómetro, captura de pantalla y más. También puedes añadir widgets.",
                "Si un widget permite desplazarse, usa la franja estrecha de sus bordes para volver a las aplicaciones."
            ),
            "Personaliza la cuadrícula en Accesos directos."
        ),
        TourStep(
            Icons.Rounded.Tune, "Personaliza la isla",
            listOf(
                "Puedes cambiar o desactivar cada gesto en la sección Gestos.",
                "¿La sensibilidad es demasiado alta o baja? Ajusta la sensibilidad de los gestos.",
                "En Ajustes también puedes cambiar la intensidad de vibración, la velocidad de animación y los colores."
            ),
            "Puedes volver a abrir esta guía cuando quieras desde la parte superior de Gestos."
        ),
    )
}

/**
 * The new-user tour: a dialog of steps with Back / Next. Reached from the welcome dialog and from the top of the
 * Gestures screen. [onFinish] is called by Done, by Skip and by tapping outside.
 */
@Composable
fun NewUserTour(settings: SmartIslandSettings, onFinish: () -> Unit) {
    val steps = tourSteps(settings)
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val last = index >= steps.lastIndex
    Dialog(onDismissRequest = onFinish, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(12.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Paso ${index + 1} de ${steps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!last) TextButton(onClick = onFinish) { Text("Omitir") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.indices.forEach { i ->
                        Box(
                            Modifier
                                .height(4.dp)
                                .weight(1f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (i <= index) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(step.icon, size = 48)
                    Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    step.points.forEach { point ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier
                                    .padding(top = 7.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Text(point, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(step.tip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (index > 0) {
                        TextButton(onClick = { index -= 1 }) { Text("Atr?s") }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (last) onFinish() else index += 1 }, shape = RoundedCornerShape(14.dp)) {
                        Text(if (last) "Listo" else "Siguiente", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** The card at the top of the Gestures screen that opens the tour. */
@Composable
fun NewUserTourCard(onStart: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onStart),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconBadge(Icons.Rounded.Lightbulb, size = 40)
            Column(modifier = Modifier.weight(1f)) {
                Text("¿Primera vez con Ryx Isla?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Conoce los gestos principales en una guía de un minuto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("Empezar", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
