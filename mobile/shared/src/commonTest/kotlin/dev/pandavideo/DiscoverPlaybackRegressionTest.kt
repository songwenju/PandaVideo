package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.JianpianSource
import dev.pandavideo.data.repository.PomoSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverPlaybackRegressionTest {
    @Test fun shippedCartoonIdentityBypassesAmbiguousRemakeSearch() = runTest {
        val page = PomoSource.address("auto_detail", "site" to "暴风", "id" to "21926")
        var searched = false
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.parameters["type"] == "auto_search") searched = true
            respond(pomoDetailFixture)
        }))
        try {
            val match = repo.resolve(CatalogItem(
                "猫和老鼠", "https://movie.douban.com/subject/4321270/", "1965",
                kind = "animation", playbackPageUrl = page, isSeries = true,
            ))
            assertTrue(match.playable)
            assertEquals(page, match.pageUrl)
            assertFalse(searched)
        } finally { repo.close() }
    }

    @Test fun successfulParserWithDeadMediaFallsBackToWorkingNativeStream() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when {
                request.url.host == "dead.test" ->
                    respond("", HttpStatusCode.ServiceUnavailable)
                request.url.host == "native.test" ->
                    respond("#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n")
                request.url.parameters["type"] == "auto_detail" ->
                    respond("""{"code":200,"data":{"name":"西游记1986","routes":[
                        {"episodes":[{"name":"第01集","url":"https://native.test/1.m3u8"}]}]}}""")
                else -> respond("""{"code":200,"direct":"https://dead.test/1.m3u8","data":""}""")
            }
        }))
        try {
            val playback = repo.player(PlayerRequest(page = pomoPage))
            assertEquals("https://native.test/1.m3u8", playback.url)
        } finally { repo.close() }
    }

    @Test fun aggregatedSeriesExcludeFilmLinesWithoutRenumberingEpisodeKeys() {
        val page = JianpianSource.address("1899")
        val body = """{"code":1,"data":{"source_list_source":[
            {"source_list":[{"source_name":"正片","url":"https://media/movie.m3u8"}]},
            {"source_list":[{"source_name":"第01集","url":"https://media/1.m3u8"},
                {"source_name":"第02集","url":"https://media/2.m3u8"}]},
            {"source_list":[{"source_name":"HD国语版","url":"https://media/remake.m3u8"}]}
        ]}}"""
        val entries = JianpianSource.episodes(body, page)
        assertEquals(listOf("$page#2-0", "$page#2-1"), entries.map { it.first.path })
        assertEquals(listOf("第01集", "第02集"), entries.map { it.first.label })
    }
}
