package dev.pandavideo

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.platform.AndroidPlayer
import dev.pandavideo.core.platform.PlaybackMediaSessionService
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Opt-in, same-device cold/warm transport comparison; output excludes media locators. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackPreloadDeviceTest {
    @Test fun sharedSessionConsumesTheNativeNextEpisodePreload() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("preloadFixtureBase")
        assumeTrue("Provide preloadFixtureBase", base != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var controller: AndroidPlayer
        lateinit var session: PlaybackSession
        val episodes = listOf(Episode("one", "1", 1), Episode("two", "2", 1))
        try {
            withContext(Dispatchers.Main) {
                controller = AndroidPlayer(context)
                session = PlaybackSession({
                    val selected = it.episode ?: "one"
                    PlaybackInfo("Session fixture", "$base/video.m3u8?episode=$selected", "hls",
                        episodes, selected, "https://fixture.test")
                }, controller, scope)
                session.load(PlayerRequest(page = "https://fixture.test"))
            }
            waitUntil { controller.nativePlayer?.also { it.setVideoSurface(surface) } != null }
            withTimeout(20_000) { while (!preloadReady(controller)) delay(25) }
            withContext(Dispatchers.Main) { session.select(episodes[1]) }
            waitUntil {
                controller.state.value.mediaId == 2L && controller.state.value.firstFrameRendered &&
                    controller.state.value.phase == PlayerPhase.Playing && controller.state.value.position > 0
            }
            withContext(Dispatchers.Main) {
                assertTrue(controller.state.value.dataPreloadHit)
                assertTrue(session.startupSamples.value.last().addressPreloadHit)
                assertNotNull(session.startupSamples.value.last().firstFrameMs)
                assertEquals("two", session.state.value.info?.selected)
            }
        } finally {
            withContext(Dispatchers.Main) {
                controller.nativePlayer?.clearVideoSurface()
                controller.stop()
                session.close()
            }
            scope.cancel()
            surface.release()
            texture.release()
        }
    }

    @Test fun coldAndPreloadedMp4AndHlsRenderWithPausedIntentPreserved() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val base = arguments.getString("preloadFixtureBase")
        assumeTrue("Provide preloadFixtureBase serving short HLS/MP4 fixtures", base != null)
        val repetitions = (arguments.getString("preloadRuns")?.toIntOrNull() ?: 10).coerceIn(1, 30)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        lateinit var controller: AndroidPlayer
        withContext(Dispatchers.Main) { controller = AndroidPlayer(context) }
        var id = 0L
        try {
            waitUntil {
                controller.nativePlayer?.also { it.setVideoSurface(surface) } != null
            }
            for ((file, kind) in listOf("video.mp4" to "video", "video.m3u8" to "hls")) {
                repeat(repetitions) { run ->
                    for (warm in listOf(false, true)) {
                        val source = MediaSource(++id, "$base/$file?run=$run&warm=$warm",
                            kind, "Preload fixture")
                        withContext(Dispatchers.Main) {
                            controller.stop()
                            controller.preload(if (warm) source.copy(autoplay = false) else null)
                        }
                        if (warm) withTimeout(20_000) {
                            while (!preloadReady(controller)) delay(25)
                        }
                        withContext(Dispatchers.Main) {
                            assertFalse(controller.state.value.wantsPlay)
                            assertEquals(PlayerPhase.Idle, controller.state.value.phase)
                        }
                        val start = System.nanoTime()
                        withContext(Dispatchers.Main) { controller.load(source) }
                        waitUntil {
                            assertNull(controller.nativePlayer?.playerError)
                            controller.state.value.mediaId == source.id &&
                                controller.state.value.firstFrameRendered &&
                                controller.state.value.phase == PlayerPhase.Playing &&
                                controller.state.value.position > 0
                        }
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000
                        withContext(Dispatchers.Main) {
                            assertEquals(warm, controller.state.value.dataPreloadHit)
                            controller.pause()
                            assertFalse(controller.state.value.wantsPlay)
                        }
                        println("PRELOAD_BENCH kind=$kind warm=$warm run=$run ttff_ms=$elapsedMs")
                    }
                }
                // A preloaded load must still respect an explicitly paused source.
                val source = MediaSource(++id, "$base/$file?paused=true", kind, "Paused fixture", autoplay = false)
                withContext(Dispatchers.Main) { controller.stop(); controller.preload(source) }
                withTimeout(20_000) { while (!preloadReady(controller)) delay(25) }
                withContext(Dispatchers.Main) { controller.load(source) }
                waitUntil { controller.nativePlayer?.playbackState == androidx.media3.common.Player.STATE_READY }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.nativePlayer!!.playWhenReady)
                    assertTrue(controller.state.value.dataPreloadHit)
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                controller.nativePlayer?.clearVideoSurface()
                controller.preload(null)
                controller.stop()
                controller.close()
            }
            surface.release()
            texture.release()
        }
    }

    private suspend fun waitUntil(check: () -> Boolean) = withTimeout(20_000) {
        while (!withContext(Dispatchers.Main) { check() }) delay(10)
    }

    private suspend fun preloadReady(player: AndroidPlayer): Boolean {
        val future = withContext(Dispatchers.Main) {
            (player.nativePlayer as MediaController).sendCustomCommand(
                SessionCommand(PlaybackMediaSessionService.ACTION_PRELOAD_STATUS, Bundle.EMPTY), Bundle.EMPTY)
        }
        return withContext(Dispatchers.IO) { future.get(5, TimeUnit.SECONDS).extras.getBoolean("ready") }
    }
}
