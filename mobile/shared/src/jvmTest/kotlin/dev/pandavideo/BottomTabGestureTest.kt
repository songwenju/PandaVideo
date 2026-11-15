package dev.pandavideo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import dev.pandavideo.data.user.SettingsStore
import dev.pandavideo.ui.common.BottomNavigationTabs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sends touch events through the production tabs, including recomposition during dragging. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class BottomTabGestureTest {
    private val tabs = listOf("hot" to "热播", "discover" to "发现", "browse" to "分类", "profile" to "我的")
    private val initialOrder = tabs.map { it.first }

    @Test fun longDragAcrossSeveralTabsPersistsOrderWithoutNavigating() =
        verifyGesture(initialOrder, 50f, 350f, true, listOf("discover", "browse", "profile", "hot"), "hot")

    @Test fun longDragLeftUsesPreviouslySavedOrder() =
        verifyGesture(listOf("profile", "hot", "browse", "discover"), 350f, 50f, true,
            listOf("discover", "profile", "hot", "browse"), "hot")

    @Test fun shortSwipeDoesNotReorderOrNavigate() =
        verifyGesture(initialOrder, 50f, 350f, false, initialOrder, "hot")

    @Test fun ordinaryTapStillSelectsDestination() =
        verifyGesture(initialOrder, 350f, 350f, false, initialOrder, "profile")

    @Test fun anotherLongDragStillWorksAfterSavedOrderRecomposes() =
        verifyGesture(initialOrder, 50f, 350f, true, listOf("discover", "browse", "profile", "hot"), "hot",
            dragBack = true)

    private fun verifyGesture(
        initial: List<String>, fromX: Float, toX: Float, longPress: Boolean,
        expected: List<String>, expectedSelection: String, dragBack: Boolean = false,
    ) = runTest {
        val storage = FakeStorage()
        val settings = SettingsStore(storage)
        settings.setTabOrder(initial)
        var order by mutableStateOf(initial)
        var selected by mutableStateOf("hot")
        var saves = 0
        val scene = CanvasLayersComposeScene(size = IntSize(400, 100), coroutineContext = coroutineContext)
        val canvas = Canvas(ImageBitmap(400, 100))
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
                    BottomNavigationTabs(tabs, order, selected, { selected = it }) {
                        saves++
                        settings.setTabOrder(it)
                        order = settings.settings.value.tabOrder
                    }
                }
            }
            pump()
            val from = Offset(fromX, 27f)
            val to = Offset(toX, 27f)
            scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Touch)
            pump()
            if (longPress) {
                advanceTimeBy(700)
                pump()
            }
            if (from != to) {
                for (step in 1..12) {
                    scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 12f),
                        type = PointerType.Touch)
                    pump()
                }
            }
            assertEquals(0, saves, "Save only after releasing the drag")
            scene.sendPointerEvent(PointerEventType.Release, to, type = PointerType.Touch)
            pump()
            assertEquals(expected, order)
            assertEquals(expected, SettingsStore(storage).settings.value.tabOrder, "Restore the saved order")
            assertEquals(expectedSelection, selected, "Reordering must keep the current page")
            assertEquals(if (longPress) 1 else 0, saves)
            if (dragBack) {
                scene.sendPointerEvent(PointerEventType.Press, to, type = PointerType.Touch)
                pump()
                advanceTimeBy(700)
                pump()
                for (step in 1..12) {
                    scene.sendPointerEvent(PointerEventType.Move, to + (from - to) * (step / 12f),
                        type = PointerType.Touch)
                    pump()
                }
                scene.sendPointerEvent(PointerEventType.Release, from, type = PointerType.Touch)
                pump()
                assertEquals(initial, order, "A new drag must update the newly saved order")
                assertEquals(initial, SettingsStore(storage).settings.value.tabOrder)
                assertEquals(expectedSelection, selected)
                assertEquals(2, saves)
            }
        } finally {
            scene.close()
        }
    }
}
