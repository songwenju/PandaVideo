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
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import android.graphics.SurfaceTexture
import android.view.Surface
import kotlin.test.*

/** Exercises the production cache and decoder against an explicitly supplied short fixture. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineMediaDeviceTest {
    @Test fun pausedMp4ResumesFromCachedBytesAndNeverPublishesAPartialAsset() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AndroidOfflineEngine.get(context)
        val id = "device-resume-${System.nanoTime()}"
        val server = ServerSocket(0)
        val offsets = CopyOnWriteArrayList<Int>()
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val serving = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader()
                        input.readLine()
                        var offset = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true))
                                offset = line.substringAfter("bytes=").substringBefore('-').toInt()
                        }
                        offsets += offset
                        val output = socket.getOutputStream()
                        val headers = if (offset > 0)
                            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $offset-${bytes.lastIndex}/${bytes.size}\r\n"
                            else "HTTP/1.1 200 OK\r\n"
                        output.write((headers + "Content-Length: ${bytes.size - offset}\r\n" +
                            "Accept-Ranges: bytes\r\nContent-Type: video/mp4\r\nConnection: close\r\n\r\n").toByteArray())
                        while (offset < bytes.size) {
                            val count = minOf(16 * 1024, bytes.size - offset)
                            output.write(bytes, offset, count)
                            output.flush()
                            offset += count
                            // Keep the first transfer in flight long enough for a real pause.
                            Thread.sleep(3)
                        }
                    }
                } catch (_: java.io.IOException) { /* A pause intentionally closes the socket. */ }
            }
        }
        try {
            val info = PlaybackInfo("Resume fixture", "http://127.0.0.1:${server.localPort}/video.mp4",
                "video", pageUrl = "https://fixture.test/resume")
            val partial = CompletableDeferred<Unit>()
            val downloading = async(Dispatchers.IO) {
                engine.download(id, info) { if (it != null && it >= 0.15f) partial.complete(Unit) }
            }
            withTimeout(15_000) { partial.await() }
            engine.cancel(id)
            downloading.cancelAndJoin()
            assertNull(engine.asset(id, id), "Incomplete media must not be playable")
            val locator = withTimeout(30_000) { engine.download(id, info) {} }
            assertNotNull(engine.asset(id, locator))
            assertTrue(offsets.drop(1).any { it > 0 }, "Resume must request a nonzero byte range: $offsets")
            println("OFFLINE_RESUME range_offsets=$offsets partial_asset=false completed_asset=true")
        } finally {
            server.close()
            serving.join(5_000)
            engine.remove(id)
        }
    }

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
