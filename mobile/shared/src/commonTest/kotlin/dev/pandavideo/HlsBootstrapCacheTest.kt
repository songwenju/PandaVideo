package dev.pandavideo

import dev.pandavideo.core.player.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HlsBootstrapCacheTest {
    private val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nfirst.ts?token=one\n#EXTINF:4,\n../last.ts\n#EXT-X-ENDLIST\n"
    private val ts = ByteArray(564).apply { this[0] = 0x47; this[188] = 0x47; this[376] = 0x47 }
    private fun source(url: String = "https://fixture.test/a/movie.m3u8") = MediaSource(1, url, "hls", "fixture")
    private fun TestScope.mock(handler: MockRequestHandler) = HttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler(handler)
    })) { followRedirects = false }

    @Test fun rewritesRelativeUrisAgainstRedirectedPlaylist() {
        val parsed = assertNotNull(parseHlsBootstrap("https://cdn.test/new/movie.m3u8?auth=one", playlist))
        assertEquals("https://cdn.test/new/first.ts?token=one", parsed.second)
        assertTrue("https://cdn.test/last.ts" in parsed.first)
        assertTrue(HlsBootstrap.FIRST_SEGMENT in parsed.first)
        assertEquals("https://cdn.test/other/movie.m3u8", absoluteHlsUrl("https://cdn.test/new/movie.m3u8", "../other/movie.m3u8"))
    }

    @Test fun rejectsUnsupportedOrIncompletePresentations() {
        for (tag in listOf("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXT-X-BYTERANGE:10@0", "#EXT-X-DEFINE:NAME=\"a\",VALUE=\"b\"", "#EXT-X-PART:DURATION=1,URI=\"part.ts\"")) {
            assertNull(parseHlsBootstrap("https://fixture.test/a/movie.m3u8", playlist.replace("#EXTINF:4,", "$tag\n#EXTINF:4,")))
        }
        assertNull(parseHlsBootstrap("https://fixture.test/a/movie.m3u8", playlist.replace("#EXT-X-ENDLIST", "")))
        assertNull(parseHlsBootstrap("https://fixture.test/a/movie.m3u8", playlist.replace("first.ts?token=one", "first.m4s")))
        assertNull(parseHlsBootstrap("https://fixture.test/a/movie.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchild.m3u8\n#EXT-X-ENDLIST"))
    }

    @Test fun playbackJoinsInFlightDownloadAndWarmReplayMakesNoRequests() = runTest {
        val requests = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val client = mock { request ->
            requests += request.url.encodedPath
            if (request.url.encodedPath.endsWith(".m3u8")) respond(playlist)
            else { gate.await(); respond(ts) }
        }
        val cache = HlsBootstrapCache(client, backgroundScope) { testScheduler.currentTime }
        cache.preload(source())
        runCurrent()
        val waiter = async { cache.await(source()) }
        runCurrent()
        assertFalse(waiter.isCompleted)
        assertEquals(2, requests.size)
        gate.complete(Unit)
        assertContentEquals(ts, assertNotNull(waiter.await()).segment)
        assertNotNull(cache.await(source()))
        assertEquals(2, requests.size)
        cache.close(); client.close()
    }

    @Test fun waiterCancellationDoesNotCancelSharedWorkAndExactUrlsExpire() = runTest {
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val client = mock {
            count++
            if (it.url.encodedPath.endsWith(".m3u8")) respond(playlist)
            else { gate.await(); respond(ts) }
        }
        val cache = HlsBootstrapCache(client, backgroundScope) { testScheduler.currentTime }
        cache.preload(source()); runCurrent()
        val waiter = launch { cache.await(source()) }; runCurrent(); waiter.cancelAndJoin()
        gate.complete(Unit); runCurrent()
        assertNotNull(cache.await(source()))
        assertNull(cache.await(source("https://fixture.test/a/movie.m3u8?other=1")))
        advanceTimeBy(HlsBootstrapCache.TTL_MS)
        assertNull(cache.await(source()))
        assertEquals(2, count)
        cache.close(); client.close()
    }

    @Test fun followsRedirectBeforeResolvingSegmentsAndFallsBackOnFailure() = runTest {
        val requests = mutableListOf<String>()
        val client = mock {
            requests += it.url.toString()
            when (it.url.host) {
                "fixture.test" -> respond("", HttpStatusCode.Found, headersOf("Location", "https://cdn.test/new/movie.m3u8"))
                else -> if (it.url.encodedPath.endsWith(".m3u8")) respond(playlist) else respond("bad", HttpStatusCode.Forbidden)
            }
        }
        val cache = HlsBootstrapCache(client, backgroundScope) { testScheduler.currentTime }
        cache.preload(source())
        assertNull(cache.await(source()))
        assertEquals("https://cdn.test/new/first.ts?token=one", requests.last())
        assertEquals(3, requests.size)
        cache.close(); client.close()
    }

    @Test fun switchingCandidateCancelsOldDownloadAndColdMissDoesNotFetch() = runTest {
        var cancelled = false
        var count = 0
        val client = mock {
            count++
            try { awaitCancellation() } finally { cancelled = true }
        }
        val cache = HlsBootstrapCache(client, backgroundScope) { testScheduler.currentTime }
        assertNull(cache.await(source())); assertEquals(0, count)
        cache.preload(source()); runCurrent()
        cache.preload(null); runCurrent()
        assertTrue(cancelled)
        assertNull(cache.await(source()))
        cache.close(); client.close()
    }

    @Test fun loopbackSupportsHeadRangesAndRejectsOtherResources() {
        fun request(method: String = "GET", path: String = "/first.ts", range: String = "") =
            bootstrapHttpResponse("$method $path HTTP/1.1\r\n$range\r\n", "manifest".encodeToByteArray(), ts)
        val partial = request(range = "Range: bytes=188-375\r\n")
        assertTrue(partial.header.startsWith("HTTP/1.1 206"))
        assertEquals(188, partial.body.size)
        assertTrue("Content-Range: bytes 188-375/564" in partial.header)
        assertEquals(0, request("HEAD").body.size)
        assertEquals(10, request(range = "Range: bytes=-10\r\n").body.size)
        assertTrue(request(range = "Range: bytes=999-\r\n").header.contains("416"))
        assertTrue(request(range = "Range: bytes=0-1,3-4\r\n").header.contains("416"))
        assertTrue(request(path = "/../../private").header.contains("404"))
        assertTrue(request("POST").header.contains("405"))
    }
}
