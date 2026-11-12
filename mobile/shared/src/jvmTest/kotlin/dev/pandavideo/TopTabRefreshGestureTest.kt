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
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.pandavideo.ui.common.topTabSwipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun verifyDrag(
        from: Offset, to: Offset, initialTab: Int, expectedTab: Int, expectedRefreshes: Int,
    ) = runTest {
        var selected by mutableStateOf(initialTab)
        var refreshCalls = 0
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
                    Box(Modifier.fillMaxSize().topTabSwipe(selected, 3) { selected = it }) {
                        PullToRefreshBox(false, { refreshCalls++ }, state = pull) {
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
            scene.sendPointerEvent(PointerEventType.Release, to, type = PointerType.Touch)
            pump()
            assertEquals(expectedRefreshes, refreshCalls, "Only vertical gestures may refresh")
            assertEquals(expectedTab, selected, "Only horizontal gestures may switch tabs")
        } finally {
            scene.close()
        }
    }
}
