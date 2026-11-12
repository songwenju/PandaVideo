package dev.pandavideo.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Changes one local tab after a deliberate horizontal swipe finishes.
 *
 * Locks the axis before vertical children receive drag movement, so a slightly diagonal
 * horizontal swipe cannot also pull to refresh. Apply to the vertical content below any
 * horizontal filter rows, never to the root navigation container.
 */
internal fun Modifier.topTabSwipe(
    selectedIndex: Int, tabCount: Int, onSelect: (Int) -> Unit,
): Modifier = composed {
    val selected by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState(onSelect)
    val minimumDistance = with(LocalDensity.current) { 48.dp.toPx() }
    pointerInput(tabCount, minimumDistance) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var distance = 0f
            var verticalDistance = 0f
            var horizontal = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // Multi-touch and an ancestor claiming the gesture cancel tab navigation.
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                if (!change.pressed) {
                    if (!horizontal) break
                    change.consume()
                    if (selected in 0 until tabCount && abs(distance) >= minimumDistance) {
                        val direction = if (distance < 0f) 1 else -1
                        val destination = (selected + direction).coerceIn(0, tabCount - 1)
                        if (destination != selected) select(destination)
                    }
                    break
                }
                val delta = change.positionChange()
                distance += delta.x
                verticalDistance += delta.y
                if (!horizontal) {
                    if (distance * distance + verticalDistance * verticalDistance <
                        viewConfiguration.touchSlop * viewConfiguration.touchSlop) continue
                    if (abs(distance) <= abs(verticalDistance)) break
                    horizontal = true
                }
                // Consume both axes in Initial. Main-pass arbitration is too late: the
                // vertical list can already dispatch nested scroll into pull-to-refresh.
                change.consume()
            }
        }
    }
}
