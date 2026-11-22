package dev.pandavideo

import dev.pandavideo.core.model.LineProbe
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.visiblePlaybackLines
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.JianpianSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LineProbeTest {
    @Test fun manualRouteResolutionFailureNeverFallsBackToAnotherLineOrProvider() = runTest {
        val requested = mutableListOf<String>()
        var broken = false
        val body = """{"code":1,"data":{"title":"测试","source_list_source":[
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/main.m3u8"}]},
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/alternate.m3u8"}]}]}}"""
        fun repository() = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host == "probe.test" -> {
                    requested += request.url.encodedPath
                    if (broken && request.url.encodedPath == "/main.m3u8")
                        respond("", HttpStatusCode.NotFound)
                    else respond("#EXTM3U\n")
                }
                request.url.encodedPath.contains("search") -> error("Manual routes must not search another provider")
                else -> respond(body)
            }
        }))
        val first = repository()
        val info = try { first.player(PlayerRequest(page = JianpianSource.address("1"))) }
        finally { first.close() }
        broken = true
        requested.clear()
        val locked = repository()
        try {
            assertFails {
                locked.player(PlayerRequest(page = info.pageUrl, episode = info.selected, allowLineFallback = false))
            }
            assertEquals(listOf("/main.m3u8"), requested)
        } finally { locked.close() }
        requested.clear()
        val automatic = repository()
        try {
            val recovered = automatic.player(PlayerRequest(page = info.pageUrl, episode = info.selected))
            assertEquals(2, recovered.episodes.first { it.path == recovered.selected }.line)
            assertEquals(listOf("/main.m3u8", "/alternate.m3u8"), requested)
        } finally { automatic.close() }
    }

    @Test fun fastestAvailableLinesLeadAndUnknownLinesRemainVisible() {
        val probes = mapOf(1 to LineProbe(300, true), 2 to LineProbe(20, true),
            3 to LineProbe(available = false), 4 to LineProbe(), 5 to LineProbe(20, true))
        assertEquals(listOf(2, 5, 1, 4, 6), visiblePlaybackLines((1..6).toList(), probes))
        assertEquals(emptyList(), visiblePlaybackLines(listOf(3), probes))
    }

    @Test fun pendingTimeoutAndOverloadedRoutesRetryThenHideWhileActiveLineRemains() = runTest {
        val attempts = mutableMapOf<String, Int>()
        val body = """{"code":1,"data":{"title":"测试","source_list_source":[
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/good.m3u8"}]},
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/timeout.m3u8"}]},
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/offline.m3u8"}]},
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/busy.m3u8"}]},
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/deleted.m3u8"}]}]}}"""
        val repository = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            if (request.url.host == "probe.test")
                attempts[request.url.encodedPath] = (attempts[request.url.encodedPath] ?: 0) + 1
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host != "probe.test" -> respond(body)
                request.url.encodedPath == "/timeout.m3u8" -> {
                    delay(10_000); respond("#EXTM3U\n")
                }
                request.url.encodedPath == "/offline.m3u8" -> error("Network disconnected")
                request.url.encodedPath == "/busy.m3u8" -> respond("", HttpStatusCode.ServiceUnavailable)
                request.url.encodedPath == "/deleted.m3u8" -> respond("", HttpStatusCode.NotFound)
                else -> respond("#EXTM3U\n#EXTINF:6,\na.ts\n")
            }
        }))
        try {
            val info = repository.player(PlayerRequest(page = JianpianSource.address("1")))
            val probes = repository.probeLines(info)
            assertEquals(false, probes[2]?.available)
            assertEquals(false, probes[3]?.available)
            assertEquals(false, probes[4]?.available)
            assertEquals(false, probes[5]?.available)
            assertEquals(listOf(1), visiblePlaybackLines((1..5).toList(), probes))
            assertEquals(2, attempts["/timeout.m3u8"])
            assertEquals(2, attempts["/offline.m3u8"])
            assertEquals(2, attempts["/busy.m3u8"])
            assertEquals(1, attempts["/deleted.m3u8"], "Explicit missing media needs no retry")
            assertEquals(1, attempts["/good.m3u8"], "The active line must not be measured again")
        } finally { repository.close() }
    }

    @Test fun everyPendingLineIsMeasuredBeyondOldLimitAndPublishesBeforeBatchCompletes() = runTest {
        val body = """{"code":1,"data":{"title":"测试","source_list_source":[${(1..22).joinToString(",") {
            """{"source_list":[{"source_name":"${if (it == 22) "第 2 集" else "第 1 集"}","url":"https://probe.test/$it.m3u8"}]}"""
        }}]}}"""
        var active = 0
        var peak = 0
        val repository = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host != "probe.test" -> respond(body)
                request.url.encodedPath == "/1.m3u8" -> respond("#EXTM3U\n")
                else -> {
                    active++; peak = maxOf(peak, active)
                    try { delay(2_000); respond("#EXTM3U\n") }
                    finally { active-- }
                }
            }
        }))
        try {
            val info = repository.player(PlayerRequest(page = JianpianSource.address("1")))
            val published = mutableMapOf<Int, LineProbe>()
            val measuring = async {
                repository.probeLines(info.copy(lineProbes = info.lineProbes + (2 to LineProbe()))) {
                    published.putAll(it)
                }
            }
            advanceTimeBy(2_500); runCurrent()
            assertTrue(published.isNotEmpty(), "Completed lines update while slower queued lines are pending")
            assertFalse(measuring.isCompleted)
            val result = measuring.await()
            assertEquals((1..22).toSet(), result.keys)
            assertEquals((2..22).toSet(), published.keys, "Unknown prior measurements must be retried too")
            assertTrue(result.values.all { it.available == true })
            assertEquals(3, peak)
            assertTrue(testScheduler.currentTime > 6_000)
        } finally { repository.close() }
    }

    @Test fun recoveredRetryStaysVisibleAndCancellationNeverMarksAnUnfinishedLineUnavailable() = runTest {
        var requests = 0
        var cancelled = false
        val body = """{"code":1,"data":{"title":"测试","source_list_source":[
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/main.m3u8"}]},
            {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/alternate.m3u8"}]}]}}"""
        val repository = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host != "probe.test" -> respond(body)
                request.url.encodedPath == "/main.m3u8" -> respond("#EXTM3U\n")
                else -> {
                    requests++
                    if (requests == 1) respond("", HttpStatusCode.ServiceUnavailable)
                    else respond("#EXTM3U\n")
                }
            }
        }))
        try {
            val info = repository.player(PlayerRequest(page = JianpianSource.address("1")))
            assertEquals(true, repository.probeLines(info)[2]?.available)
            assertEquals(2, requests)
            // A fresh repository cannot reuse the successful in-memory prefix.
            val pendingRepository = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
                when {
                    request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                    request.url.host != "probe.test" -> respond(body)
                    else -> try { awaitCancellation() } finally { cancelled = true }
                }
            }))
            try {
                val published = mutableMapOf<Int, LineProbe>()
                val job = async { pendingRepository.probeLines(info) { published.putAll(it) } }
                runCurrent()
                job.cancel()
                assertFailsWith<CancellationException> { job.await() }
                assertTrue(cancelled)
                assertTrue(published.isEmpty())
            } finally { pendingRepository.close() }
        } finally { repository.close() }
    }
}
