package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.*
import dev.pandavideo.data.user.UserStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.*

private val jpPage = JianpianSource.address("1")
private val jpSearch = """{"code":1,"data":[{"id":1,"title":"测试电影","top_category":{"id":1},"years":[{"year":"2020"}],"actors":["张三"],"types":["剧情"]}]}"""
private fun jpDetail(url: String = "https://media.test/jp.m3u8", year: String = "2020") =
    """{"code":1,"data":{"id":1,"title":"测试电影","year":"$year","source_list_source":[{"source_list":[{"source_name":"第 1 集","url":"$url"},{"source_name":"第 2 集","url":"https://media.test/second.m3u8"}]}]}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class JianpianSourceTest {
    @Test fun stableReferencesRejectForeignHostsPortsAndDuplicateIds() {
        assertEquals("$jpPage#1-1", ContentParsers.pageUrl("$jpPage#1-1"))
        for (value in listOf(jpPage.replace("https:", "http:"), "$jpPage&id=2", "$jpPage#0-1",
            jpPage.replace("japi.zxfmj.com", "other.test"), jpPage.replace(".com/", ".com:5800/"),
            "$jpPage&url=https://other.test"))
            assertFailsWith<ApiError> { ContentParsers.pageUrl(value) }
        assertTrue(JianpianSource.episodes(jpDetail("ftp://server/video.mp4"), jpPage)
            .none { it.second.startsWith("ftp") })
    }

    @Test fun fastestMirrorUsesItsOwnSecretAndRetriesMalformedResponses() = runTest {
        val requests = mutableListOf<String>()
        var broken = false
        val client = JianpianClient(fetch = { address, _, headers ->
            val url = Url(address)
            val secret = if (url.host.startsWith("pv2026")) "fast-secret" else "slow-secret"
            if (url.encodedPath.endsWith("/init")) {
                delay(if (secret == "fast-secret") 10 else 50)
                """{"code":1,"data":{"secret":"$secret"}}"""
            } else {
                assertEquals("/api/v2/search/videoV2", url.encodedPath)
                assertEquals("测试电影", url.parameters["key"])
                requests += url.host
                assertEquals(("504" + headers["timestamp"] + secret).encodeUtf8().md5().hex(), headers["signature"])
                assertEquals("pc", headers["platform"])
                if (broken && secret == "fast-secret") """{"code":1,"data":{}}""" else jpSearch
            }
        }, clock = { testScheduler.currentTime })
        assertEquals(1, client.search("测试电影", "movie").results.size)
        assertEquals(listOf("pv2026.zxfmj.com"), requests)
        broken = true
        assertEquals(1, client.search("测试电影", "movie").results.size)
        assertEquals(listOf("pv2026.zxfmj.com", "pv2026.zxfmj.com", "japi.zxfmj.com"), requests)
    }

    @Test fun usefulFastSearchCancelsTheSlowerProvider() = runTest {
        var cancelled = false
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            if (request.url.host == "pomo.mom") {
                try { awaitCancellation() } finally { cancelled = true }
            } else if (request.url.encodedPath.endsWith("/init"))
                respond("""{"code":1,"data":{"secret":"test-secret"}}""")
            else respond(jpSearch)
        }))
        try {
            assertEquals("荐片", repo.search("测试电影", "movie").results.single().source)
            assertTrue(cancelled)
        } finally { repo.close() }
    }
    @Test fun failedApisDiscoverReplacementWithinTheSameOperation() = runTest {
        val hosts = mutableListOf<String>()
        val client = JianpianClient(fetch = { address, _, _ ->
            val url = Url(address)
            when {
                url.encodedPath.endsWith("domain.txt") -> "replacement.test"
                url.host in setOf("dns.alidns.com", "doh.pub", "1.1.1.1") -> """{"Answer":[]}"""
                url.encodedPath.endsWith("/init") -> """{"code":1,"data":{"secret":"s"}}"""
                else -> {
                    hosts += url.host
                    if (url.host == "pv2026.replacement.test") jpSearch
                    else throw ApiError("offline")
                }
            }
        }, clock = { testScheduler.currentTime })
        assertEquals(1, client.search("测试电影", "movie").results.size)
        assertEquals("pv2026.replacement.test", hosts.last())
        assertEquals(3, hosts.size)
    }

    @Test fun emptyPrimaryWaitsForBackupAndPartialFailureDoesNotHideCatalog() = runTest {
        var backupFails = false
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "pomo.mom" -> respond(pomoSearch())
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                backupFails -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> { delay(50); respond(jpSearch) }
            }
        }))
        try {
            assertEquals("荐片", repo.search("测试电影", "movie").results.single().source)
            backupFails = true
            val result = repo.search("其他电影", "movie")
            assertTrue(result.results.isEmpty())
            assertTrue(result.stale)
            val item = CatalogItem("其他电影", "", "2020")
            val missing = mutableListOf<CatalogItem>()
            repo.inspectCatalog(listOf(item)) { missing += it }
            assertTrue(missing.isEmpty())
        } finally { repo.close() }
    }

    @Test fun deadMediaFallsBackToSameEpisodeOfTheSameRelease() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "media.test" -> respond("<html>login required</html>")
                request.url.host == "pomo.mom" -> respond(when (request.url.parameters["type"]) {
                    "auto_search" -> pomoSearch(pomoRow("测试电影"))
                    "auto_detail" -> pomoDetailFixture
                    else -> pomoMedia()
                })
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                else -> respond(jpDetail())
            }
        }))
        try {
            val info = repo.player(PlayerRequest(page = jpPage, episode = "$jpPage#1-1"))
            assertEquals("$pomoPage#1-1", info.selected)
            assertTrue(info.source.startsWith("Pomo"))
            assertEquals(pomoPage, info.pageUrl)
            assertFailsWith<ApiError> { repo.player(PlayerRequest(page = jpPage, episode = "${JianpianSource.address("2")}#1-1")) }
        } finally { repo.close() }
    }

    @Test fun directHlsAndSavedProgressSurviveMirrorChanges() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "media.test" -> {
                    assertEquals("bytes=0-8191", request.headers[HttpHeaders.Range])
                    respond("#EXTM3U\n#EXTINF:6,\nsegment.ts\n")
                }
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                else -> respond(jpDetail())
            }
        }))
        try {
            val info = repo.player(PlayerRequest(page = jpPage))
            assertEquals("hls", info.kind)
            assertEquals("$jpPage#1-0", info.selected)
            val store = UserStore(FakeStorage())
            store.recordHistory("测试电影", jpPage, "", "2020", "第 1 集", info.selected)
            store.saveProgress(jpPage, info.selected, "第 1 集", 1000, 10000)
            val restored = UserStore(FakeStorage())
            restored.importBackup(store.exportBackup())
            assertEquals(store.progress.value, restored.progress.value)
        } finally { repo.close() }
    }

    @Test fun anotherReleaseIsNeverUsedAsPlaybackFallback() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "media.test" -> respond("", HttpStatusCode.ServiceUnavailable)
                request.url.host == "pomo.mom" -> respond(pomoSearch(pomoRow("测试电影", year = "2021")))
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                else -> respond(jpDetail())
            }
        }))
        try { assertFailsWith<ApiError> { repo.player(PlayerRequest(page = jpPage)) } }
        finally { repo.close() }
    }
}
