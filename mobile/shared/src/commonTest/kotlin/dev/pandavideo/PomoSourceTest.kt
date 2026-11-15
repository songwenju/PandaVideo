package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.*
import dev.pandavideo.data.user.UserStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PomoSourceTest {
    @Test fun searchMergesJourneyToWestMirrorsAndSortsByRating() = runTest {
        val body = """{"code":200,"data":{
            "西瓜":[
                {"id":1,"name":"西游记1986","year":"1986","area":"大陆","douban_score":"9.8"},
                {"id":2,"name":"西游记2010","year":"2010","area":"大陆","douban_score":"6.0"},
                {"id":3,"name":"西游记续集","year":"2000","area":"大陆","douban_score":"9.1"}],
            "暴风":[
                {"id":4,"name":"西游记86版","year":"1986","area":"中国大陆","douban_score":"9.8"},
                {"id":5,"name":"西游记2010","year":"2010","area":"大陆","douban_score":"6.0"}]}}"""
        val repo = ContentRepository(HttpClient(mockEngine { respond(body) }))
        try {
            val result = repo.search("西游记", "all")
            assertEquals(listOf("西游记1986", "西游记续集", "西游记2010"), result.results.map { it.title })
            assertEquals(pomoPage, result.results.first().detailUrl)
            assertEquals(3, result.sources.sumOf { it.count })
        } finally { repo.close() }
    }

    @Test fun sparseSearchMetadataDoesNotMarkAnUnresolvedResourceUnavailable() {
        val body = """{"code":200,"data":{"西瓜":[{"id":86058,"name":"欢迎来龙餐馆",
            "year":"2026","remarks":"HD中字","cover":"https://image.test/poster.jpg"}]}}"""
        val item = PomoSource.search(body, "movie").results.single()
        assertTrue(item.playable)
        assertEquals("https://image.test/poster.jpg", item.thumbnailUrl)
        assertFalse(PomoSource.search(body.replace("\"year\":", "\"play_url\":\"\",\"year\":"), "movie")
            .results.single().playable)
    }

    @Test fun referencesRejectForeignHostsDuplicateParametersAndForgedEpisodes() {
        for (page in listOf(pomoPage.replace("https:", "http:"), pomoPage.replace("pomo.mom", "other"),
            "$pomoPage&id=2", "$pomoPage&url=https://other", "$pomoPage#../1", "$pomoPage#0-1")) {
            assertFailsWith<ApiError> { ContentParsers.pageUrl(page) }
        }
        assertEquals("$pomoPage#2-0", ContentParsers.pageUrl("$pomoPage#2-0"))
    }

    @Test fun commentaryIsExcludedAndDuplicateProvidersDoNotBlockCatalogMatching() = runTest {
        val search = pomoSearch(pomoRow("测试电影"), pomoRow("测试电影[电影解说]", 3))
            .replace("\"data\":{", "\"data\":{\"暴风\":[${pomoRow("测试电影", 2)}],")
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertNotEquals("parse", request.url.parameters["type"])
            respond(if (request.url.parameters["type"] == "auto_search") search else pomoDetailFixture)
        }))
        try {
            assertEquals(1, repo.search("测试电影", "movie").results.size)
            assertEquals(pomoPage, repo.resolve(CatalogItem("测试电影", "", "2020")).pageUrl)
        } finally { repo.close() }
    }

    @Test fun searchDedupDoesNotRemoveCatalogMatchingFallbackMirrors() = runTest {
        val body = pomoSearch(pomoRow("测试电影"))
            .replace("\"data\":{", "\"data\":{\"暴风\":[${pomoRow("测试电影", 2)}],")
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(when (request.url.parameters["type"]) {
                "auto_search" -> body
                else -> if (request.url.parameters["site"] == "西瓜") """{"code":200,"data":{"name":"测试电影","routes":[]}}"""
                    else pomoDetailFixture
            })
        }))
        try {
            assertEquals(1, repo.search("测试电影", "movie").results.size)
            val match = repo.resolve(CatalogItem("测试电影", "", "2020"))
            assertTrue(match.playable)
            assertEquals("暴风", io.ktor.http.Url(match.pageUrl).parameters["site"])
        } finally { repo.close() }
    }

    @Test fun defaultPrefersHlsAndForeignEpisodeNeverReachesParser() = runTest {
        val detail = pomoDetailFixture.replace("https://media/1.m3u8", "https://media/embed")
            .replace("\"url\":\"https://media/embed\"}]}]", "\"url\":\"https://media/alternate.m3u8\"}]}]")
        var parses = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.parameters["type"] == "auto_detail") respond(detail) else {
                parses++
                assertEquals("https://media/alternate.m3u8", request.url.parameters["url"])
                respond(pomoMedia())
            }
        }))
        try {
            assertEquals("$pomoPage#2-0", repo.player(PlayerRequest(page = pomoPage)).selected)
            assertFailsWith<ApiError> {
                repo.player(PlayerRequest(page = pomoPage, episode = "${pomoPage.replace("id=1", "id=2")}#1-1"))
            }
            assertEquals(1, parses)
        } finally { repo.close() }
    }

    @Test fun proxyFallbackAndUnavailableLineAreHandled() = runTest {
        assertEquals("hls", PomoSource.media("""{"code":200,"data":"${PomoSource.endpoint}?type=proxy&url=x"}""").second)
        assertFailsWith<ApiError> { PomoSource.media("""{"code":200,"data":"https://other/embed"}""") }
        var attempts = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.parameters["type"] == "auto_detail") respond(pomoDetailFixture)
            else respond(if (++attempts == 1) """{"code":500}""" else pomoMedia())
        }))
        try {
            assertEquals("$pomoPage#2-0", repo.player(PlayerRequest(page = pomoPage)).selected)
            assertEquals(2, attempts)
        } finally { repo.close() }
    }

    @Test fun backupRoundTripKeepsEpisodeResumeAndRejectsCrossTitleProgress() {
        val store = UserStore(FakeStorage())
        store.recordHistory("测试电影", pomoPage, "", "2020", "第 2 集", "$pomoPage#1-1")
        store.saveProgress(pomoPage, "$pomoPage#1-1", "第 2 集", 120_000, 600_000)
        val backup = store.exportBackup()
        val restored = UserStore(FakeStorage())
        restored.importBackup(backup)
        assertEquals(store.progress.value, restored.progress.value)
        assertFails { UserStore(FakeStorage()).importBackup(backup.replace("#1-1", "#0-1")) }
    }

    @Test fun failedMovieLineIsSkippedEvenWhenParserRotatesItsUrl() = runTest {
        val detail = """{"code":200,"data":{"name":"测试电影","routes":[
            {"name":"线路1","episodes":[{"name":"国语","url":"https://media/primary.m3u8"}]},
            {"name":"线路2","episodes":[{"name":"HD","url":"https://media/backup.m3u8"}]}]}}"""
        val parsed = mutableListOf<String>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.parameters["type"] == "auto_detail") respond(detail) else {
                parsed += request.url.parameters["url"].orEmpty()
                respond(pomoMedia("new-token"))
            }
        }))
        try {
            val result = repo.player(PlayerRequest(page = pomoPage, episode = "$pomoPage#1-0",
                rejectedMedia = setOf("https://media/stream.m3u8?signature=old-token"),
                rejectedEpisodes = setOf("$pomoPage#1-0")))
            assertEquals("$pomoPage#2-0", result.selected)
            assertEquals(listOf("https://media/backup.m3u8"), parsed)
        } finally { repo.close() }
    }

    @Test fun failedSeriesEpisodeNeverFallsIntoAnUnrelatedSingletonLine() = runTest {
        val parsed = mutableListOf<String>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when (request.url.parameters["type"]) {
                "auto_detail" -> respond(pomoDetailFixture)
                "parse" -> {
                    parsed += request.url.parameters["url"].orEmpty()
                    respond(pomoMedia())
                }
                else -> respond("""{"code":200,"data":[]}""")
            }
        }))
        try {
            assertFailsWith<ApiError> {
                repo.player(PlayerRequest(page = pomoPage, episode = "$pomoPage#1-1",
                    rejectedEpisodes = setOf("$pomoPage#1-1")))
            }
            assertTrue(parsed.isEmpty())
        } finally { repo.close() }
    }

    @Test fun singletonEpisodeRoutesStillRequireTheSameEpisode() = runTest {
        val detail = """{"code":200,"data":{"name":"测试剧","routes":[
            {"name":"线路1","episodes":[{"name":"第 2 集","url":"https://media/2.m3u8"}]},
            {"name":"线路2","episodes":[{"name":"第 3 集","url":"https://media/3.m3u8"}]}]}}"""
        var parses = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when (request.url.parameters["type"]) {
                "auto_detail" -> respond(detail)
                "parse" -> { parses++; respond(pomoMedia()) }
                else -> respond("""{"code":200,"data":[]}""")
            }
        }))
        try {
            assertFailsWith<ApiError> {
                repo.player(PlayerRequest(page = pomoPage, episode = "$pomoPage#1-0",
                    rejectedEpisodes = setOf("$pomoPage#1-0")))
            }
            assertEquals(0, parses)
        } finally { repo.close() }
    }
}
