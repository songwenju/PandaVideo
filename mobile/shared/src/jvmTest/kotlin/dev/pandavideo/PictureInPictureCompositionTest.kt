package dev.pandavideo

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.pandavideo.ui.player.PersistentVideoContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM runtime avoids Android's unimplemented Trace stubs while testing shared Compose code. */
@OptIn(ExperimentalCoroutinesApi::class)
class PictureInPictureCompositionTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test fun pipEntryAndExitKeepTheNativeSurfaceAlive() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val composition = Composition(EmptyApplier(), recomposer)
        val job = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val pip = mutableStateOf(false)
        var surfaceCreated = 0
        var surfaceReleased = 0
        var overlaysCreated = 0
        var overlaysReleased = 0
        try {
            composition.setContent {
                PersistentVideoContent(
                    pip.value,
                    surface = {
                        DisposableEffect(Unit) {
                            surfaceCreated++
                            onDispose { surfaceReleased++ }
                        }
                    },
                    overlays = {
                        DisposableEffect(Unit) {
                            overlaysCreated++
                            onDispose { overlaysReleased++ }
                        }
                    },
                )
            }
            assertEquals(1, surfaceCreated)
            assertEquals(1, overlaysCreated)

            repeat(3) { transition ->
                pip.value = true
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(transition * 2L)
                runCurrent()
                assertEquals(0, surfaceReleased, "PiP entry must not detach AVPlayerLayer")
                assertEquals(transition + 1, overlaysReleased)

                pip.value = false
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(transition * 2L + 1)
                runCurrent()
                assertEquals(1, surfaceCreated, "PiP exit must reuse the same surface")
                assertEquals(0, surfaceReleased)
                assertEquals(transition + 2, overlaysCreated)
            }
            composition.dispose()
            assertEquals(1, surfaceReleased, "Leaving the player releases the surface once")
        } finally {
            composition.dispose()
            recomposer.cancel()
            job.cancel()
        }
    }
}
