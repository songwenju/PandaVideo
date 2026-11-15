package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.ApiError
import dev.pandavideo.data.repository.ContentParsers
import dev.pandavideo.data.repository.JianpianSource
import dev.pandavideo.core.model.Episode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CatalogPlaybackMatchTest {
    @Test fun africaDocumentarySeriesDoesNotUseTheTelevisionOnlySearchFilter() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(when (request.url.parameters["type"]) {
                // Sparse providers call documentaries HD even when their details have episodes.
                "auto_search" -> pomoSearch(pomoRow("非洲", year = "2013", genre = "纪录片", remark = "HD"))
                "auto_detail" -> pomoDetailFixture.replace("测试电影", "非洲").replace("2020", "2013")
                else -> pomoMedia()
            })
        }))
        try {
            val item = CatalogItem("非洲", "",
                year = "2013", kind = "documentary", isSeries = true)
            val match = repo.playbackMatch(item)
            assertTrue(match.playable)
            val info = repo.player(PlayerRequest(page = match.pageUrl, title = item.title))
            assertEquals("非洲", info.title)
            assertEquals(2, info.episodes.count { it.line == 1 })
        } finally { repo.close() }
    }

    @Test fun playbackMatchingDistinguishesMissingAndUnconfirmedSources() = runTest {
        for (unavailable in listOf(false, true)) {
            val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
                when {
                    unavailable -> respond("offline", HttpStatusCode.ServiceUnavailable)
                    request.url.host == "pomo.mom" -> respond(pomoSearch())
                    request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                    else -> respond("""{"code":1,"data":[]}""")
                }
            }))
            try {
                val error = assertFailsWith<ApiError> {
                    repo.playbackMatch(CatalogItem("非洲", "", kind = "documentary"))
                }
                assertTrue(error.message.orEmpty().contains(if (unavailable) "暂时不可用" else "暂未找到"))
            } finally { repo.close() }
        }
    }

    @Test fun wulinTelevisionRejectsCompletedMovieAndUsesNumberedSeries() = runTest {
        // Real Pomo results omit type_name and label both 2006 TV and 2011 film "已完结".
        // Cast overlap can rank the film first, so validation must precede the rank cutoff.
        val movie = pomoRow("武林外传", 1, "2011", actor = "闫妮", remark = "已完结", genre = "")
        val series = pomoRow("武林外传2006", 2, "2006", actor = "", remark = "已完结", genre = "")
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(if (request.url.parameters["type"] == "auto_search") pomoSearch(movie, series)
            else if (request.url.parameters["id"] == "1")
                """{"code":200,"data":{"name":"武林外传","remarks":"已完结","routes":[
                    {"name":"线路1","episodes":[{"name":"HD","url":"https://media/movie.m3u8"}]},
                    {"name":"线路2","episodes":[{"name":"HD国语","url":"https://media/movie2.m3u8"}]}]}}"""
            else pomoDetailFixture.replace("测试电影", "武林外传2006").replace("\"year\":\"2020\",", ""))
        }))
        try {
            val match = repo.resolve(CatalogItem("武林外传", "", casts = listOf("闫妮"), kind = "tv"))
            assertTrue(match.playable)
            assertEquals(pomoPage.replace("id=1", "id=2"), match.pageUrl)
        } finally { repo.close() }
    }

    @Test fun filmWithCompletedRemarkCannotSatisfyTelevisionCatalog() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(if (request.url.parameters["type"] == "auto_search")
                pomoSearch(pomoRow("武林外传", remark = "已完结", genre = ""))
            else """{"code":200,"data":{"name":"武林外传","routes":[
                {"episodes":[{"name":"HD","url":"https://media/movie.m3u8"}]}]}}""")
        }))
        try {
            assertFalse(repo.resolve(CatalogItem("武林外传", "", kind = "tv")).playable)
            assertTrue(repo.resolve(CatalogItem("武林外传", "", kind = "movie")).playable)
        } finally { repo.close() }
    }

    @Test fun sparseSingleEpisodeAndNumberedHdEpisodesAreNotMistakenForFilms() {
        assertFalse(ContentParsers.movieOnlyEpisodes(listOf(Episode("1", "播放", 1))))
        assertFalse(ContentParsers.movieOnlyEpisodes(listOf(Episode("1", "第1集 HD", 1))))
        assertFalse(ContentParsers.movieOnlyEpisodes(listOf(Episode("1", "HD", 1), Episode("2", "HD", 1))))
        assertFalse(ContentParsers.movieOnlyEpisodes(emptyList()))
        assertTrue(ContentParsers.movieOnlyEpisodes(listOf(Episode("1", "HD", 1), Episode("2", "正片", 2))))
    }

    @Test fun failingTelevisionStreamNeverFallsBackToSameTitleFilmWithoutYear() = runTest {
        val page = JianpianSource.address("1")
        var parsedFilm = false
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "media.test" -> respond("", HttpStatusCode.ServiceUnavailable)
                request.url.host == "pomo.mom" -> respond(when (request.url.parameters["type"]) {
                    "auto_search" -> pomoSearch(pomoRow("武林外传", year = "", genre = "", remark = "已完结"))
                    "auto_detail" -> """{"code":200,"data":{"name":"武林外传","routes":[
                        {"episodes":[{"name":"HD","url":"https://media/movie.m3u8"}]}]}}"""
                    else -> { parsedFilm = true; pomoMedia() }
                })
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                else -> respond("""{"code":1,"data":{"title":"武林外传","source_list_source":[
                    {"source_list":[{"source_name":"第1集","url":"https://media.test/1.m3u8"},
                    {"source_name":"第2集","url":"https://media.test/2.m3u8"}]}]}}""")
            }
        }))
        try {
            assertFailsWith<ApiError> { repo.player(PlayerRequest(page = page)) }
            assertFalse(parsedFilm)
        } finally { repo.close() }
    }

    @Test fun yearlessCatalogPlaysTheOnlyYearSuffixedReleaseLikeSearch() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(when (request.url.parameters["type"]) {
                "auto_search" -> pomoSearch(pomoRow("美丽人生1997", year = "1997"))
                "auto_detail" -> pomoDetailFixture.replace("测试电影", "美丽人生").replace("2020", "1997")
                else -> pomoMedia()
            })
        }))
        try {
            val search = repo.search("美丽人生", "all").results.single()
            val searchPlayback = repo.player(PlayerRequest(page = search.detailUrl))
            val match = repo.resolve(CatalogItem("美丽人生", "", kind = "movie"))
            assertTrue(match.playable)
            assertEquals(searchPlayback.pageUrl, match.pageUrl)
            assertEquals(searchPlayback.selected, repo.player(PlayerRequest(page = match.pageUrl)).selected)
        } finally { repo.close() }
    }

    @Test fun animationAndDocumentaryCatalogsCanPlaySeriesLikeSearch() = runTest {
        for (kind in listOf("animation", "documentary")) {
            val repo = ContentRepository(HttpClient(mockEngine { request ->
                respond(when (request.url.parameters["type"]) {
                    "auto_search" -> pomoSearch(pomoRow("测试剧", remark = "全2集", genre = "国产剧"))
                    "auto_detail" -> pomoDetailFixture.replace("测试电影", "测试剧")
                    else -> pomoMedia()
                })
            }))
            try {
                val search = repo.search("测试剧", "all").results.single()
                assertEquals(search.detailUrl, repo.resolve(CatalogItem("测试剧", "", kind = kind)).pageUrl, kind)
            } finally { repo.close() }
        }
    }

    @Test fun yearlessCatalogDoesNotPreferBareTitleOverAnotherRemake() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(if (request.url.parameters["type"] == "auto_search")
                pomoSearch(pomoRow("美丽人生", year = "1997"), pomoRow("美丽人生2023", 2, "2023"))
            else pomoDetailFixture.replace("2020",
                if (request.url.parameters["id"] == "2") "2023" else "1997"))
        }))
        try { assertFalse(repo.resolve(CatalogItem("美丽人生", "", kind = "movie")).playable) }
        finally { repo.close() }
    }
}
