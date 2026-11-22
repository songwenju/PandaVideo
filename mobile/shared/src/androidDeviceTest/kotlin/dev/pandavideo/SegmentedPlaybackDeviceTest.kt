package dev.pandavideo

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.platform.AndroidPlayer
import dev.pandavideo.core.player.PlayerPhase
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Opt-in public decoder/service test; diagnostics contain only positions and playback state. */
@RunWith(AndroidJUnit4::class)
class SegmentedPlaybackDeviceTest {
    @Test fun publicMp4ContinuousPlaybackAndBidirectionalSeekPreserveWholeProgramState() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cctvSegments") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val player = withContext(Dispatchers.Main) { AndroidPlayer(context) }
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        suspend fun await(condition: () -> Boolean) = withTimeout(60_000) {
            while (!withContext(Dispatchers.Main) {
                assertNotEquals(PlayerPhase.Error, player.state.value.phase, player.state.value.error)
                condition()
            }) delay(50)
        }
        try {
            await { player.nativePlayer?.also { it.setVideoSurface(surface) } != null }
            withContext(Dispatchers.Main) { player.load(cctvSegmentFixture()) }
            await { player.state.value.phase == PlayerPhase.Playing && player.state.value.position >= 120_500 }
            assertTrue(player.state.value.duration in 1_797_000..1_801_000)
            println("CCTV_SEGMENTS android natural_transition_ms=${player.state.value.position} duration_ms=${player.state.value.duration}")
            withContext(Dispatchers.Main) { player.pause(); player.setPlaybackRate(1.5f); player.seekTo(245_000) }
            await { player.state.value.phase == PlayerPhase.Paused && player.state.value.position in 244_750..245_250 }
            assertFalse(player.state.value.wantsPlay)
            withContext(Dispatchers.Main) { player.seekTo(115_000) }
            await { player.state.value.phase == PlayerPhase.Paused && player.state.value.position in 114_750..115_250 }
            assertFalse(player.state.value.wantsPlay)
            assertEquals(1.5f, player.state.value.rate)
            println("CCTV_SEGMENTS android forward_ms=245000 backward_ms=115000 paused=true rate=1.5")
            withContext(Dispatchers.Main) { player.play() }
            await { player.state.value.phase == PlayerPhase.Playing && player.state.value.position >= 120_500 }
            withContext(Dispatchers.Main) { player.seekTo(player.state.value.duration - 1_000) }
            await { player.state.value.phase == PlayerPhase.Ended }
            println("CCTV_SEGMENTS android final_segment_ended=true")
        } finally {
            withContext(Dispatchers.Main) { player.nativePlayer?.clearVideoSurface(); player.stop(); player.close() }
            surface.release()
            texture.release()
        }
    }
}
