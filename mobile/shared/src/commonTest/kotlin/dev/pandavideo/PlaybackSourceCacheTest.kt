package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.app.AppState
import dev.pandavideo.data.repository.ApiError
import dev.pandavideo.data.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSourceCacheTest {
    @Test fun sharedAppSessionReusesEpisodeCacheForLocalTvAndCastingNavigation() = runTest {
        val platform = AppStateTest.TestPlatform(this)
        val model = AppState(platform, backgroundScope,
            resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            model.startPlayer(PlayerRequest(page = pomoPage, episode = "$pomoPage#1-0",
                allowLineFallback = false))
            runCurrent()
            val session = assertNotNull(model.session)
            val player = platform.players.single()
            assertEquals(1, platform.parsedMedia.size)
            // All platform episode surfaces dispatch to this same AppState-owned session.
            player.state.value = player.state.value.copy(firstFrameRendered = true)
            runCurrent()
            assertEquals(2, platform.parsedMedia.size)
            val episodes = assertNotNull(session.state.value.info).episodes
            session.select(episodes.first { it.path == "$pomoPage#1-1" })
            runCurrent()
            assertTrue(session.startupSamples.value.last().addressPreloadHit)
            session.select(episodes.first { it.path == "$pomoPage#1-0" })
            runCurrent()
            assertEquals(2, platform.parsedMedia.size, "Returning to a loaded episode uses the shared cache")
            session.selectLine(2)
            runCurrent()
            assertEquals("$pomoPage#2-0", session.state.value.info?.selected)
            assertEquals(3, platform.parsedMedia.size)
            session.selectLine(1)
            runCurrent()
            assertEquals("$pomoPage#1-0", session.state.value.info?.selected)
            assertEquals(3, platform.parsedMedia.size, "Returning to the original line reuses its URL")
            assertSame(session, model.session)
            assertEquals(1, platform.players.size, "Platform navigation must retain the shared player")
        } finally { model.close() }
    }

    @Test fun automaticMeasurementsCacheSuccessfulAlternatesButNeverRejectedMedia() = runTest {
        var parses = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when (request.url.parameters["type"]) {
                "auto_detail" -> respond(pomoDetailFixture)
                "auto_search" -> respond(pomoSearch())
                "parse" -> { parses++; respond(pomoMedia()) }
                else -> error("Unexpected request")
            }
        }))
        try {
            val initial = repo.player(PlayerRequest(page = pomoPage, preferFastestLine = true))
            assertEquals(2, parses, "Initial entry still compares both routes")
            repo.player(PlayerRequest(page = pomoPage, episode = "$pomoPage#2-0",
                allowLineFallback = false, reuseSourceInfo = true))
            assertEquals(2, parses, "A validated alternate can be selected without reparsing")
            val rejected = PlayerRequest(page = pomoPage, episode = "$pomoPage#1-1",
                preferFastestLine = true, rejectedMedia = setOf(initial.url))
            assertFailsWith<ApiError> { repo.player(rejected) }
            assertEquals(3, parses)
            repo.player(rejected.copy(preferFastestLine = false, rejectedMedia = emptySet(),
                allowLineFallback = false, reuseSourceInfo = true))
            assertEquals(4, parses, "Batch validation must not cache rejected signed URLs")
        } finally { repo.close() }
    }

    @Test fun episodeNavigationReusesSourceAndValidatedUrlsWithoutCrossingPagesOrLines() = runTest {
        val parses = mutableListOf<String>()
        var details = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when (request.url.parameters["type"]) {
                "auto_detail" -> { details++; respond(pomoDetailFixture) }
                "parse" -> {
                    parses += request.url.parameters["url"].orEmpty()
                    respond(pomoMedia())
                }
                else -> error("Unexpected request")
            }
        }))
        try {
            val first = repo.player(PlayerRequest(page = pomoPage))
            val next = first.episodes.first { it.path == "$pomoPage#1-1" }
            val navigation = PlayerRequest(page = pomoPage, episode = next.path, reuseSourceInfo = true)
            repo.player(navigation)
            assertEquals(listOf("https://media/1.m3u8", "https://media/2.m3u8"), parses)
            repo.player(navigation.copy(episode = first.selected))
            repo.player(navigation)
            assertEquals(2, parses.size, "Switching back must not parse or probe a cached episode")
            assertEquals(1, details)
            repo.player(navigation.copy(episode = "$pomoPage#2-0", allowLineFallback = false))
            assertEquals("https://media/embed", parses.last())
            val other = pomoPage.replace("id=1", "id=2")
            repo.player(navigation.copy(page = other, episode = "$other#1-1"))
            assertEquals(4, parses.size, "Identical media addresses on another page own separate cache entries")
            assertEquals(2, details)
            repo.clearCache()
            repo.player(navigation)
            assertEquals(5, parses.size)
            assertEquals(3, details)
        } finally { repo.close() }
    }

    @Test fun retriesFailuresAndExpiredMediaRequireFreshValidationWithoutExtendingTtl() = runTest {
        var parses = 0
        var now = 0L
        var failing = false
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when (request.url.parameters["type"]) {
                "auto_detail" -> respond(pomoDetailFixture)
                "parse" -> {
                    parses++
                    if (failing) respond("unavailable", HttpStatusCode.ServiceUnavailable)
                    else respond(pomoMedia())
                }
                else -> error("Unexpected request")
            }
        }), playbackCacheNowMs = { now })
        val request = PlayerRequest(page = pomoPage, episode = "$pomoPage#1-1",
            allowLineFallback = false, reuseSourceInfo = true)
        try {
            val first = repo.player(request)
            now = 290_000
            repo.player(request)
            assertEquals(1, parses)
            now = 300_000
            repo.player(request)
            assertEquals(2, parses, "Cache hits must not extend signed URL lifetime")
            repo.player(request.copy(reuseSourceInfo = false))
            assertEquals(3, parses, "Retry/refresh bypass cached media")
            failing = true
            assertFailsWith<ApiError> { repo.player(request.copy(reuseSourceInfo = false)) }
            failing = false
            repo.player(request)
            assertEquals(5, parses, "Failed refresh invalidates the old success")
            assertFailsWith<ApiError> {
                repo.player(request.copy(rejectedMedia = setOf(first.url)))
            }
            repo.player(request)
            assertEquals(7, parses, "Rejected media cannot remain cached")
        } finally { repo.close() }
    }
}
