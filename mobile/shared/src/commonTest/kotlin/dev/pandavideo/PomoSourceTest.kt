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
            assertEquals(2, repo.search("测试电影", "movie").results.size)
            assertEquals(pomoPage, repo.resolve(CatalogItem("测试电影", "", "2020")).pageUrl)
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
}
