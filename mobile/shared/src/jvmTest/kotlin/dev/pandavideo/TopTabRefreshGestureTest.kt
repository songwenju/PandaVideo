package dev.pandavideo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.pandavideo.ui.common.TopTabPager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises actual nested-scroll arbitration rather than a separate direction helper. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class,
    ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
class TopTabRefreshGestureTest {
    @Test fun diagonalLeftSwipeDoesNotRefresh() =
        verifyDrag(Offset(340f, 120f), Offset(40f, 370f), 0, 1, 0)

    @Test fun diagonalRightSwipeDoesNotRefresh() =
        verifyDrag(Offset(40f, 120f), Offset(340f, 370f), 1, 0, 0)

    @Test fun verticalPullStillRefreshes() =
        verifyDrag(Offset(180f, 120f), Offset(180f, 420f), 0, 0, 1)

    @Test fun diagonalVerticalPullDoesNotSwitchTab() =
        verifyDrag(Offset(340f, 120f), Offset(100f, 420f), 0, 0, 1)

    @Test fun shortHorizontalDragSpringsBack() =
        verifyDrag(Offset(180f, 120f), Offset(145f, 120f), 1, 1, 0)

    @Test fun firstCategoryResistsAndReturns() =
        verifyDrag(Offset(40f, 120f), Offset(340f, 120f), 0, 0, 0)

    @Test fun lastCategoryResistsAndReturns() =
        verifyDrag(Offset(340f, 120f), Offset(40f, 120f), 2, 2, 0)

    @Test fun categoryTapAlsoSlidesContent() =
        verifyDrag(Offset(180f, 120f), Offset(180f, 120f), 1, 1, 0, tapTarget = 2)

    @Test fun neighboringPageIsVisibleBeforeRelease() =
        verifyDrag(Offset(340f, 120f), Offset(140f, 120f), 0, 1, 0)

    private fun verifyDrag(
        from: Offset, to: Offset, initialTab: Int, expectedTab: Int, expectedRefreshes: Int,
        tapTarget: Int? = null,
    ) = runTest {
        var selected by mutableStateOf(initialTab)
        var refreshCalls = 0
        var coordinates: LayoutCoordinates? = null
        val pageCoordinates = mutableMapOf<Int, LayoutCoordinates>()
        val pull = PullToRefreshState()
        val scene = CanvasLayersComposeScene(size = IntSize(390, 640), coroutineContext = coroutineContext)
        val canvas = Canvas(ImageBitmap(390, 640))
        var frame = 0L
        fun pump() {
            repeat(6) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                scene.render(canvas, frame++ * 16_000_000L)
                runCurrent()
            }
        }
        try {
            scene.setContent {
                MaterialTheme {
                    TopTabPager(selected, 3, { selected = it }) { index ->
                        PullToRefreshBox(false, { refreshCalls++ }, state = pull,
                            modifier = Modifier.onGloballyPositioned {
                                pageCoordinates[index] = it
                                if (index == selected) coordinates = it
                            }) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                Spacer(Modifier.height(1400.dp))
                            }
                        }
                    }
                }
            }
            pump()
            scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Touch)
            for (step in 1..12) {
                scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 12f),
                    type = PointerType.Touch)
                pump()
            }
            val horizontal = kotlin.math.abs(to.x - from.x) > kotlin.math.abs(to.y - from.y)
            val draggedX = coordinates!!.localToRoot(Offset.Zero).x
            if (horizontal) {
                assertTrue(kotlin.math.abs(draggedX) > 1f, "Content must follow the finger before release")
                assertTrue(draggedX * (to.x - from.x) > 0f, "Content must move in the drag direction")
                val neighbor = initialTab + if (to.x < from.x) 1 else -1
                if (neighbor in 0..2) {
                    val neighborX = pageCoordinates.getValue(neighbor).localToRoot(Offset.Zero).x
                    assertEquals(draggedX + (neighbor - initialTab) * 390f, neighborX, 0.5f,
                        "Adjacent content must follow the same drag")
                    assertTrue(neighborX < 390f && neighborX + 390f > 0f,
                        "Adjacent page must be visible before release")
                }
                if (expectedTab == initialTab && kotlin.math.abs(to.x - from.x) > 48f)
                    assertTrue(kotlin.math.abs(draggedX) < 100f, "Boundary drags must resist movement")
            } else assertEquals(0f, draggedX, "Vertical scrolling must not move content sideways")
            assertEquals(initialTab, selected, "Do not replace the category while dragging")
            scene.sendPointerEvent(PointerEventType.Release, to, type = PointerType.Touch)
            pump()
            if (horizontal)
                assertTrue(kotlin.math.abs(coordinates.localToRoot(Offset.Zero).x) > 1f,
                    "Release must animate rather than instantly reset the content")
            repeat(5) { pump() }
            assertEquals(expectedRefreshes, refreshCalls, "Only vertical gestures may refresh")
            assertEquals(expectedTab, selected, "Only horizontal gestures may switch tabs")
            assertEquals(0f, coordinates.localToRoot(Offset.Zero).x, 0.5f,
                "Content must settle inside the viewport")
            if (tapTarget != null) {
                selected = tapTarget
                pump()
                val arrivingX = coordinates.localToRoot(Offset.Zero).x
                assertTrue(kotlin.math.abs(arrivingX) > 1f, "Category taps must also animate")
                assertTrue(arrivingX * (tapTarget - expectedTab) > 0f, "Arrival must match tab order")
                repeat(3) { pump() }
                assertEquals(0f, coordinates.localToRoot(Offset.Zero).x, 0.5f)
            }
        } finally {
            scene.close()
        }
    }
}
