package dev.pandavideo

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.platform.AndroidOfflineEngine
import dev.pandavideo.core.platform.OfflineMediaSourceFactory
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import android.graphics.SurfaceTexture
import android.view.Surface
import kotlin.test.*

/** Exercises the production cache and decoder against an explicitly supplied short fixture. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineMediaDeviceTest {
    @Test fun hlsAndMp4StillPlayAndSeekWhenTheOriginIsUnavailable() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("offlineFixtureBase")
        assumeTrue("Provide offlineFixtureBase to run real media verification", base != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AndroidOfflineEngine.get(context)
        for ((filename, kind) in listOf("video.m3u8" to "hls", "video.mp4" to "video")) {
            val id = "device-fixture-${kind}-${System.nanoTime()}"
            var player: ExoPlayer? = null
            val texture = SurfaceTexture(false)
            val surface = Surface(texture)
            val firstFrame = CompletableDeferred<Unit>()
            try {
                withContext(Dispatchers.IO) { URL("$base/unblock").readText() }
                val locator = withTimeout(60_000) {
                    engine.download(id, PlaybackInfo("Offline fixture", "$base/$filename", kind,
                        pageUrl = "https://fixture.test/$kind")) {}
                }
                val local = assertNotNull(engine.asset(id, locator))
                withContext(Dispatchers.IO) { URL("$base/block").readText() }
                val requests = withContext(Dispatchers.IO) { URL("$base/count").readText() }
                withContext(Dispatchers.Main) {
                    player = ExoPlayer.Builder(context).setMediaSourceFactory(OfflineMediaSourceFactory(context)).build()
                    player.setVideoSurface(surface)
                    player.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { firstFrame.complete(Unit) }
                    })
                    player.setMediaItem(MediaItem.fromUri(local.url))
                    player.prepare()
                    player.playWhenReady = true
                }
                withTimeout(15_000) { firstFrame.await() }
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) {
                            val current = checkNotNull(player)
                            assertNull(current.playerError)
                            current.playbackState == Player.STATE_READY && current.duration >= 7_000
                        }) delay(50)
                }
                withContext(Dispatchers.Main) { player!!.seekTo(4_000) }
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) {
                            val current = checkNotNull(player)
                            assertNull(current.playerError)
                            current.currentPosition >= 4_500
                        }) delay(50)
                }
                assertEquals(requests, withContext(Dispatchers.IO) { URL("$base/count").readText() },
                    "Offline playback must not request the origin")
            } finally {
                withContext(Dispatchers.Main) { player?.release() }
                surface.release()
                texture.release()
                engine.remove(id)
                withContext(Dispatchers.IO) { URL("$base/unblock").readText() }
            }
        }
    }
}
