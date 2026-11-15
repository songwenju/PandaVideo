package dev.pandavideo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Taps navigate; long-press drags preview an order and save it once on release. */
@Composable
internal fun BottomNavigationTabs(
    tabs: List<Pair<String, String>>,
    savedOrder: List<String>,
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var orderedTabs by remember {
        mutableStateOf((savedOrder + tabs.map { it.first }).distinct()
            .mapNotNull { route -> tabs.find { it.first == route } })
    }
    var draggedRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedOrder, tabs) {
        if (draggedRoute == null) {
            orderedTabs = (savedOrder + tabs.map { it.first }).distinct()
                .mapNotNull { route -> tabs.find { it.first == route } }
        }
    }
    val saveOrder by rememberUpdatedState(onReorder)
    val haptics = LocalHapticFeedback.current
    val currentHaptics by rememberUpdatedState(haptics)
    Row(
        Modifier.fillMaxWidth().height(54.dp).pointerInput(Unit) {
            var pointerX = 0f
            var originalTabs = orderedTabs
            // Keep the recognizer on the stable row, with no order/selection keys.
            // A dragged item changes slot and recomposes its children; restarting
            // their recognizers on every swap would cancel the active long press.
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    originalTabs = orderedTabs
                    if (orderedTabs.isNotEmpty() && size.width > 0) {
                        pointerX = position.x
                        val index = (pointerX / (size.width.toFloat() / orderedTabs.size)).toInt()
                            .coerceIn(0, orderedTabs.lastIndex)
                        draggedRoute = orderedTabs[index].first
                        currentHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, amount ->
                    change.consume()
                    pointerX += amount.x
                    val route = draggedRoute
                    val from = orderedTabs.indexOfFirst { it.first == route }
                    if (from >= 0 && size.width > 0) {
                        val to = (pointerX / (size.width.toFloat() / orderedTabs.size)).toInt()
                            .coerceIn(0, orderedTabs.lastIndex)
                        if (from != to) orderedTabs = orderedTabs.toMutableList().apply {
                            add(to, removeAt(from))
                        }
                    }
                },
                onDragEnd = {
                    if (draggedRoute != null && orderedTabs != originalTabs) {
                        saveOrder(orderedTabs.map { it.first })
                    }
                    draggedRoute = null
                },
                onDragCancel = {
                    orderedTabs = originalTabs
                    draggedRoute = null
                },
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        orderedTabs.forEach { (target, label) ->
            val dragging = draggedRoute == target
            val tint = if (dragging) Accent
                else if (selectedRoute == target) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .background(
                        if (dragging) Accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(12.dp),
                    )
                    .selectable(selected = selectedRoute == target, role = Role.Tab) { onSelect(target) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(NavigationIcons.forRoute(target), null, Modifier.size(22.dp), tint = tint)
                Text(label, color = tint, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}
