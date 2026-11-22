@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo

import dev.pandavideo.core.platform.IosPlayer
import dev.pandavideo.core.platform.IosHlsBootstrapServer
import dev.pandavideo.core.player.HlsBootstrap
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlayerPhase
import platform.Foundation.*
import platform.AVFoundation.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlin.test.*

/** Explicitly enabled decoder test against the request-counting, delayed MPEG-TS fixture. */
class IosHlsBootstrapTest {
    @Test fun idleServerResumesAndServesConcurrentConnections() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bootstrap = HlsBootstrap("#EXTM3U\n${HlsBootstrap.FIRST_SEGMENT}\n", ByteArray(564))
        val server = IosHlsBootstrapServer.create(bootstrap, scope)
        val client = HttpClient(Darwin)
        try {
            withTimeout(5_000) {
                assertEquals("#EXTM3U\nfirst.ts\n", client.get(server.url).bodyAsText())
                delay(150)
                coroutineScope {
                    List(8) {
                        async { assertEquals("#EXTM3U\nfirst.ts\n", client.get(server.url).bodyAsText()) }
                    }.awaitAll()
                }
            }
        } finally { client.close(); server.close(); scope.cancel() }
    }

    @Test fun cancellingOwnerClosesIdleListener() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = IosHlsBootstrapServer.create(
            HlsBootstrap("#EXTM3U\n${HlsBootstrap.FIRST_SEGMENT}\n", ByteArray(564)), scope,
        )
        val client = HttpClient(Darwin)
        try {
            withTimeout(5_000) {
                client.get(server.url).bodyAsText()
                scope.cancel()
                // Cancellation closes the listener asynchronously on its serial dispatch queue.
                var refused = false
                while (!refused) {
                    delay(20)
                    try {
                        client.get(server.url).bodyAsText()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        refused = true
                    }
                }
                assertTrue(refused)
            }
        } finally { client.close(); server.close(); scope.cancel() }
    }

    @Test fun loopbackServesManifestAndRangeBytesThroughDarwin() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val segment = ByteArray(564) { (it % 251).toByte() }
        val bootstrap = HlsBootstrap("#EXTM3U\n${HlsBootstrap.FIRST_SEGMENT}\n", segment)
        val server = IosHlsBootstrapServer.create(bootstrap, scope)
        val client = HttpClient(Darwin)
        try {
            withTimeout(5_000) {
                assertEquals("#EXTM3U\nfirst.ts\n", client.get(server.url).bodyAsText())
                val response = client.get(server.url.replace("index.m3u8", "first.ts")) {
                    header("Range", "bytes=188-375")
                }
                assertEquals(206, response.status.value)
                assertContentEquals(segment.copyOfRange(188, 376), response.bodyAsBytes())
            }
        } finally { client.close(); server.close(); scope.cancel() }
    }

    @Test fun avPlayerReadsLoopbackBootstrapAndPreservesPendingPause() {
        val base = NSProcessInfo.processInfo.environment["PANDAVIDEO_BOOTSTRAP_FIXTURE"] as? String ?: return
        val player = IosPlayer()
        val source = MediaSource(100, "$base/video.m3u8", "hls", "Bootstrap fixture")
        fun pump(seconds: Double) {
            NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
        }
        fun waitFor(predicate: () -> Boolean) {
            val deadline = NSDate().timeIntervalSince1970 + 25
            while (!predicate()) {
                val error = player.native.currentItem?.error
                assertNotEquals(PlayerPhase.Error, player.state.value.phase,
                    "Native error domain=${error?.domain} code=${error?.code}; ${player.state.value.error}")
                check(NSDate().timeIntervalSince1970 < deadline) { "Native bootstrap timed out" }
                pump(0.02)
            }
        }
        try {
            player.preload(source.copy(autoplay = false))
            pump(0.3)
            player.load(source)
            player.pause()
            player.setPlaybackRate(1.5f)
            waitFor { player.state.value.phase == PlayerPhase.Paused && player.state.value.duration > 0 }
            assertTrue(player.state.value.dataPreloadHit)
            assertFalse(player.state.value.wantsPlay)
            assertEquals(1.5f, player.state.value.rate)
            player.play()
            waitFor { player.state.value.position > 600 }
            player.seekTo(5_000)
            waitFor { player.state.value.position > 5_500 }
            println("HLS_BOOTSTRAP avplayer=true cache_hit=true pending_pause=true seek=true")
        } finally { player.stop(); player.close(); pump(0.05) }
    }
}
