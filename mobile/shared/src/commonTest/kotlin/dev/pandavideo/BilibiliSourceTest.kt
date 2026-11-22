package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BilibiliSourceTest {
    private val page = "https://www.bilibili.com/video/BV1A6ZMB3EtZ"

    @Test fun canonicalReferencesRejectSpoofedHostsAndInvalidParts() {
        assertEquals(page, BilibiliSource.reference("BV1A6ZMB3EtZ")?.page)
        assertEquals(2, BilibiliSource.reference("$page/?p=2&share_source=copy")?.part)
        assertEquals(page, ContentParsers.pageUrl("$page/?share_source=copy"))
        listOf("https://www.bilibili.com.evil/video/BV1A6ZMB3EtZ",
            "https://user@www.bilibili.com/video/BV1A6ZMB3EtZ",
            "http://www.bilibili.com/video/BV1A6ZMB3EtZ",
            "https://www.bilibili.com:444/video/BV1A6ZMB3EtZ",
            "$page?p=0", "$page?p=2&p=3", "https://evil/?id=BV1A6ZMB3EtZ")
            .forEach { assertNull(BilibiliSource.reference(it), it) }
    }

    @Test fun keywordSearchUsesAnonymousVisitorAndKeepsVideoRankingWithoutMovieRatings() = runTest {
        var fingerprints = 0
        var searches = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals("api.bilibili.com", request.url.host)
            when (request.url.encodedPath) {
                "/x/frontend/finger/spi" -> {
                    fingerprints++
                    assertNull(request.headers[HttpHeaders.Cookie])
                    respond("""{"code":0,"data":{"b_3":"guest-3","b_4":"guest-4=="}}""")
                }
                "/x/web-interface/search/type" -> {
                    searches++
                    assertEquals("video", request.url.parameters["search_type"])
                    assertEquals("buvid3=guest-3; buvid4=guest-4==", request.headers[HttpHeaders.Cookie])
                    assertEquals("https://www.bilibili.com/", request.headers[HttpHeaders.Referrer])
                    respond("""{"code":0,"data":{"result":[
                        {"bvid":"BV1A6ZMB3EtZ","title":"<em>科普</em>2026","author":"UP甲","pic":"//i0.hdslb.com/a.jpg","duration":"02:30"},
                        {"bvid":"BV1xx411c7mD","title":"科普","author":"UP乙"},
                        {"bvid":"not-valid","title":"bad"}
                    ]}}""")
                }
                else -> error("Public videos must not request movie/actor APIs")
            }
        }))
        try {
            val result = repo.search("科普", "bilibili")
            assertEquals(listOf("科普2026", "科普"), result.results.map { it.title })
            assertTrue(result.results.all { it.playable && it.rating == null && it.year.isBlank() })
            assertEquals("https://i0.hdslb.com/a.jpg", result.results.first().thumbnailUrl)
            assertEquals("UP甲", result.results.first().uploader)
            assertTrue(result.results.all { it.cast.isBlank() })
            assertEquals(result, repo.searchRatings(result, "科普"))
            assertEquals(result, repo.search("科普", "bilibili"))
            repo.search("其他", "bilibili")
            assertEquals(1, fingerprints)
            assertEquals(3, searches)
        } finally { repo.close() }
    }

    @Test fun directSearchAndMultipartPlaybackRefreshTokensAndNeverUseFilmFallback() = runTest {
        var tokens = 0
        val multipart = biliView.replace("\"title\"", "\"pic\":\"http://i0.hdslb.com/a.jpg\",\"duration\":2613,\"title\"").replace("]}}",
            """,{"cid":22,"part":"第二部分"}]}}""")
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertNull(request.headers[HttpHeaders.Cookie])
            respond(when (request.url.encodedPath) {
                "/x/web-interface/view" -> multipart
                "/x/player/playurl" -> {
                    assertEquals("22", request.url.parameters["cid"])
                    biliMedia(++tokens)
                }
                else -> error("Must not use guest search, film fallback or cached media")
            })
        }))
        try {
            val row = repo.search("$page?p=2", "all").results.single()
            assertEquals("https://i0.hdslb.com/a.jpg", row.thumbnailUrl)
            assertEquals("43:33", row.remark)
            val first = repo.player(PlayerRequest(page = row.detailUrl))
            assertEquals("$page#bili-22", first.selected)
            assertEquals(2, first.episodes.size)
            val replay = repo.player(PlayerRequest(page = first.pageUrl, episode = first.selected,
                rejectedMedia = setOf(first.url)))
            assertEquals(first.selected, replay.selected)
            assertNotEquals(first.url, replay.url)
            assertEquals(2, tokens)
            assertFailsWith<ApiError> { repo.player(PlayerRequest(page = page, episode = "$page#bili-999")) }
            assertFailsWith<ApiError> { repo.player(PlayerRequest(page = "$page?p=3")) }
        } finally { repo.close() }
    }

    @Test fun restrictedVideosAndPartialSegmentsAreExplicitFailures() = runTest {
        var view = biliView.replace("\"pages\"", "\"rights\":{\"pay\":1},\"pages\"")
        var media = biliMedia()
        val source = BilibiliSource { address, _ -> if ("/view?" in address) view else media }
        assertFailsWith<ApiError> { source.search("BV1A6ZMB3EtZ") }
        assertFailsWith<ApiError> { source.resolve(PlayerRequest(page = page)) }
        view = biliView.replace("BV1A6ZMB3EtZ", "BV1xx411c7mD")
        assertFailsWith<ApiError> { source.resolve(PlayerRequest(page = page)) }
        view = biliView
        media = """{"code":0,"data":{"durl":[{"url":"https://test.bilivideo.com/a.mp4"},{"url":"https://test.bilivideo.com/b.mp4"}]}}"""
        assertFailsWith<ApiError> { source.resolve(PlayerRequest(page = page)) }
        media = """{"code":-10403,"message":"login required"}"""
        assertFailsWith<ApiError> { source.resolve(PlayerRequest(page = page)) }
        media = biliMedia().replace("test.bilivideo.com", "bilivideo.com.evil")
        assertFailsWith<ApiError> { source.resolve(PlayerRequest(page = page)) }
    }

    @Test fun anonymousCookiesCannotFollowCrossOriginRedirects() = runTest {
        var escapedRequests = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when (request.url.host) {
                "api.bilibili.com" -> if (request.url.encodedPath == "/x/frontend/finger/spi")
                    respond("""{"code":0,"data":{"b_3":"guest-3","b_4":"guest-4"}}""")
                else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/search"))
                else -> { escapedRequests++; respond("{}") }
            }
        }) { followRedirects = false })
        try {
            assertFailsWith<ApiError> { repo.search("科普", "bilibili") }
            assertEquals(0, escapedRequests)
        } finally { repo.close() }
    }
}
