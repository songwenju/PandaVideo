package dev.pandavideo.ui.common

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Moves content with a horizontal drag, then slides to the next tab or springs back.
 *
 * Locks the axis before vertical children receive drag movement, so a slightly diagonal
 * horizontal swipe cannot also pull to refresh. Apply to the vertical content below any
 * horizontal filter rows, never to the root navigation container.
 */
internal fun Modifier.topTabSwipe(
    selectedIndex: Int, tabCount: Int, clipContent: Boolean = true, onSelect: (Int) -> Unit,
): Modifier = composed {
    val selected by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState(onSelect)
    val minimumDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var animatedIndex by remember { mutableIntStateOf(selectedIndex) }
    var animation by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(selectedIndex) {
        // Tapping the category row uses the same directional arrival. A swipe records
        // its destination before selecting it, so it owns the complete exit/entry animation.
        if (selectedIndex != animatedIndex) {
            val direction = if (selectedIndex > animatedIndex) 1 else -1
            animatedIndex = selectedIndex
            animation?.cancel()
            animation = scope.launch {
                offset = direction * width * 0.18f
                animate(offset, 0f, animationSpec = tween(220)) { value, _ -> offset = value }
            }
        }
    }
    (if (clipContent) clipToBounds() else this).onSizeChanged { width = it.width }.pointerInput(tabCount, minimumDistance) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            animation?.cancel()
            var distance = 0f
            var verticalDistance = 0f
            var horizontal = false
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // Multi-touch and an ancestor claiming the gesture cancel tab navigation.
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                    if (!change.pressed) {
                        if (!horizontal) break
                        change.consume()
                        released = true
                        val direction = if (distance < 0f) 1 else -1
                        val destination = (selected + direction).coerceIn(0, tabCount - 1)
                        val switch = selected in 0 until tabCount &&
                            abs(distance) >= minimumDistance && destination != selected
                        animation = scope.launch {
                            if (switch) {
                                // Both pages already exist. Commit only after the destination
                                // reaches the viewport, then rebase coordinates in the same frame.
                                animate(offset, -direction * width.toFloat(), animationSpec = tween(120)) {
                                    value, _ -> offset = value
                                }
                                animatedIndex = destination
                                offset = 0f
                                select(destination)
                            }
                            animate(offset, 0f, animationSpec = tween(220)) { value, _ -> offset = value }
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
                    val destination = selected + if (distance < 0f) 1 else -1
                    // The first/last category resists the drag instead of suggesting another page.
                    offset = (distance * if (destination in 0 until tabCount) 1f else 0.2f)
                        .coerceIn(-width.toFloat(), width.toFloat())
                }
            } finally {
                if (!released && offset != 0f) {
                    animation = scope.launch {
                        animate(offset, 0f, animationSpec = tween(220)) { value, _ -> offset = value }
                    }
                }
            }
        }
    }.graphicsLayer { translationX = offset }
}

/**
 * Places independently keyed category pages on both sides of a clipped viewport.
 *
 * The gesture translates the whole strip, so the neighboring grid is visible before release.
 * Only the viewport clips: clipping the translated strip would discard its offscreen pages.
 */
@Composable
internal fun TopTabPager(
    selectedIndex: Int, tabCount: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, page: @Composable (Int) -> Unit,
) {
    Box(modifier.fillMaxSize().clipToBounds()) {
        val indices = ((selectedIndex - 1).coerceAtLeast(0)..(selectedIndex + 1).coerceAtMost(tabCount - 1)).toList()
        Layout(
            modifier = Modifier.fillMaxSize().topTabSwipe(selectedIndex, tabCount, clipContent = false, onSelect = onSelect),
            content = {
                indices.forEach { index -> key(index) { Box(Modifier.fillMaxSize()) { page(index) } } }
            },
        ) { measurables, constraints ->
            val pages = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                pages.forEachIndexed { position, placeable ->
                    placeable.place((indices[position] - selectedIndex) * constraints.maxWidth, 0)
                }
            }
        }
    }
}
