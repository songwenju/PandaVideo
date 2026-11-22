package dev.pandavideo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.platform.AndroidPlayer
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlayerPhase
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Decoder/service integration; the controlled origin records playlist and TS request counts. */
@RunWith(AndroidJUnit4::class)
class HlsBootstrapDeviceTest {
    @Test fun nativePlaybackReadsPreloadedPlaylistAndFirstSegment() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("bootstrapFixtureBase")
        assumeTrue("Supply a controlled MPEG-TS origin", base != null)
        val player = withContext(Dispatchers.Main) {
            AndroidPlayer(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        val source = MediaSource(100, "$base/video.m3u8", "hls", "Bootstrap fixture")
        try {
            withContext(Dispatchers.Main) { player.preload(source.copy(autoplay = false)) }
            // The fixture includes a delayed first fragment so the load joins an in-flight
            // bootstrap, rather than only exercising an already completed native preload.
            delay(300)
            withContext(Dispatchers.Main) { player.load(source); player.pause() }
            withTimeout(20_000) {
                while (!withContext(Dispatchers.Main) {
                    assertNotEquals(PlayerPhase.Error, player.state.value.phase, player.state.value.error)
                    player.state.value.phase == PlayerPhase.Paused && player.state.value.duration > 0
                }) delay(50)
            }
            withContext(Dispatchers.Main) {
                assertTrue(player.state.value.dataPreloadHit)
                assertFalse(player.state.value.wantsPlay)
                player.play()
            }
            withTimeout(10_000) {
                while (player.state.value.position < 600) {
                    assertNotEquals(PlayerPhase.Error, player.state.value.phase)
                    delay(50)
                }
            }
            withContext(Dispatchers.Main) { player.seekTo(5_000) }
            withTimeout(10_000) {
                while (player.state.value.position < 5_500) delay(50)
            }
            println("HLS_BOOTSTRAP native_playback=true cache_hit=true pending_pause=true seek=true")
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.close() }
        }
    }
}
