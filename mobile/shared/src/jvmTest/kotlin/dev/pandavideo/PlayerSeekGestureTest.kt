package dev.pandavideo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.IntSize
import dev.pandavideo.ui.player.AdjustKind
import dev.pandavideo.ui.player.PlayerGestureZone
import dev.pandavideo.ui.player.dragSeekPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sends real touch events through the shared drag detector after playback recomposes. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PlayerSeekGestureTest {
    @Test fun eachDragStartsAtTheLatestPlaybackPosition() = exercise(cancel = false)
    @Test fun cancellingADragDoesNotCommitItsPreview() = exercise(cancel = true)

    private fun exercise(cancel: Boolean) = runTest {
        val position = mutableStateOf(10_000L)
        val preview = mutableStateOf<Float?>(null)
        val starts = mutableListOf<Long>()
        val commits = mutableListOf<Long>()
        var base = 0L
        val duration = 120_000L
        val scene = CanvasLayersComposeScene(
            size = IntSize(640, 360), coroutineContext = coroutineContext,
        )
        val canvas = Canvas(ImageBitmap(640, 360))
        var time = 0L
        fun render() {
            Snapshot.sendApplyNotifications()
            runCurrent()
            scene.render(canvas, time++ * 16_000_000)
            runCurrent()
        }
        fun touch(type: PointerEventType, x: Float, pressed: Boolean) {
            scene.sendPointerEvent(type,
                listOf(ComposeScenePointer(PointerId(0), Offset(x, 100f), pressed, PointerType.Touch)),
                timeMillis = time++ * 16)
            render()
        }
        try {
            scene.setContent {
                // Capture an immutable playback snapshot just as VideoFrame does. Keeping
                // pointerInput enabled must not keep callbacks from this first composition.
                val currentPosition = position.value
                PlayerGestureZone(
                    Modifier.fillMaxSize(), AdjustKind.Brightness, dragSeekEnabled = true,
                    readLevel = { 0.5f },
                    onSeekStart = {
                        base = currentPosition
                        starts += base
                    },
                    onSeekDelta = { delta ->
                        preview.value = dragSeekPosition(base, preview.value, duration, delta, 640)
                    },
                    onSeekEnd = {
                        preview.value?.let { commits += it.toLong() }
                        preview.value = null
                    },
                    onSeekCancel = { preview.value = null },
                    onAdjust = {}, onAdjustFinish = {},
                )
            }
            render()
            for (current in listOf(60_000L, 90_000L)) {
                position.value = current
                render()
                touch(PointerEventType.Press, 200f, true)
                assertNull(preview.value, "A press alone must not move the timeline")
                assertTrue(commits.isEmpty() || !cancel)
                touch(PointerEventType.Move, 260f, true)
                assertEquals(current, starts.last(), "The seek origin must be the current time")
                assertTrue(preview.value!! > current, "A rightward drag must advance from current time")
                if (cancel) {
                    scene.cancelPointerInput()
                    render()
                    assertTrue(commits.isEmpty(), "Cancellation must not seek playback")
                } else {
                    val target = preview.value!!.toLong()
                    touch(PointerEventType.Release, 260f, false)
                    assertEquals(target, commits.last())
                }
                assertNull(preview.value)
            }
            assertEquals(listOf(60_000L, 90_000L), starts)
        } finally {
            scene.close()
        }
    }
}
