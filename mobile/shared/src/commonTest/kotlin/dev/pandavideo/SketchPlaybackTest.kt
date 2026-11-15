package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.player.BilibiliMedia
import dev.pandavideo.data.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.*

internal const val sketchPage = "https://www.dieyin.cn/video/407962.html"
internal const val biliEmbed = """<iframe src="/down/play/bz.php?id=BV1A6ZMB3EtZ"></iframe>"""
internal const val biliView = """{"code":0,"data":{"bvid":"BV1A6ZMB3EtZ","title":"又来了","pages":[{"cid":36108634671,"part":"正片"}]}}"""
internal fun biliMedia(token: Int = 1) =
    """{"code":0,"data":{"format":"mp4720","durl":[{"url":"https://test.bilivideo.com/up/video.mp4?token=$token"}]}}"""

class SketchPlaybackTest {
    @Test fun resolvesFreshMp4AndStableEpisodeOnEveryReplay() = runTest {
        var tokens = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(when (request.url.encodedPath) {
                "/video/407962.html" -> biliEmbed
                "/x/web-interface/view" -> biliView
                "/x/player/playurl" -> biliMedia(++tokens)
                else -> error("Must not title-search or use third-party parsers")
            })
        }))
        try {
            val first = repo.player(PlayerRequest(page = sketchPage))
            val replay = repo.player(PlayerRequest(page = sketchPage, episode = first.selected,
                rejectedMedia = setOf(first.url)))
            assertEquals("video", first.kind)
            assertEquals(sketchPage, first.pageUrl)
            assertEquals(1, first.episodes.single().line)
            assertEquals("正片", first.episodes.single().label)
            assertEquals(first.selected, replay.selected)
            assertNotEquals(first.url, replay.url)
            assertEquals(2, tokens)
            assertFailsWith<ApiError> { repo.player(PlayerRequest(page = sketchPage, episode = "foreign")) }
        } finally { repo.close() }
    }

    @Test fun rejectsUntrustedEmbedsAndCdnUrls() {
        assertEquals("BV1A6ZMB3EtZ", SketchPlayback.videoId(sketchPage, biliEmbed))
        listOf(
            """<p>BV1A6ZMB3EtZ</p>""",
            """<iframe src="https://evil/down/play/bz.php?id=BV1A6ZMB3EtZ"></iframe>""",
            biliEmbed.replace("id=", "extra=1&amp;id="),
            biliEmbed.replace("BV1A6ZMB3EtZ", "not-a-bvid"),
        ).forEach { assertNull(SketchPlayback.videoId(sketchPage, it)) }
        listOf("http://test.bilivideo.com/video.mp4", "https://bilivideo.com.evil/video.mp4",
            "https://user@test.bilivideo.com/video.mp4", "https://test.bilivideo.com/index.m3u8")
            .forEach { assertFalse(BilibiliMedia.owns(it)) }
    }

    @Test fun rejectsPartialSegmentsAndApiErrorsWithoutMovieFallback() = runTest {
        var response = """{"code":-404}"""
        val resolver = SketchPlayback { address ->
            when {
                address == sketchPage -> biliEmbed
                "/view?" in address -> biliView
                else -> response
            }
        }
        assertFailsWith<ApiError> { resolver.resolve(PlayerRequest(page = sketchPage)) }
        response = """{"code":0,"data":{"durl":[{"url":"https://test.bilivideo.com/a.mp4"},{"url":"https://test.bilivideo.com/b.mp4"}]}}"""
        assertFailsWith<ApiError> { resolver.resolve(PlayerRequest(page = sketchPage)) }
        response = biliMedia()
        assertFailsWith<ApiError> { resolver.resolve(PlayerRequest(page = sketchPage,
            rejectedMedia = setOf("https://test.bilivideo.com/up/video.mp4?token=1"))) }
    }
}
