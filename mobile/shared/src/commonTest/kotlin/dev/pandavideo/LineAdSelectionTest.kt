package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.JianpianSource
import dev.pandavideo.data.user.LineAdStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LineAdSelectionTest {
    @Test fun automaticSelectionAvoidsReportsButManualChoiceAndLastResortRemainPlayable() = runTest {
        val page = JianpianSource.address("1")
        val store = LineAdStore(FakeStorage())
        var alternateUnavailable = false
        val repository = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host == "probe.test" ->
                    if (alternateUnavailable && request.url.encodedPath == "/b.m3u8")
                        respond("", HttpStatusCode.NotFound)
                    else respond("#EXTM3U\n#EXTINF:6,\na.ts\n")
                else -> respond("""{"code":1,"data":{"title":"测试","source_list_source":[
                    {"name":"PL","source_list":[{"source_name":"第 1 集","url":"https://probe.test/a.m3u8"}]},
                    {"name":"WJ","source_list":[{"source_name":"第 1 集","url":"https://probe.test/b.m3u8"}]}]}}""")
            }
        }), lineHasAds = store::hasAds)
        try {
            val initial = repository.player(PlayerRequest(page = page))
            val primary = initial.episodes.first { it.line == 1 }
            val alternate = initial.episodes.first { it.line == 2 }
            assertTrue(store.setHasAds(page, primary, true))
            val automatic = PlayerRequest(page = page, episode = primary.path, preferFastestLine = true)
            assertEquals(alternate.path, repository.player(automatic).selected)
            assertEquals(primary.path, repository.player(automatic.copy(allowLineFallback = false)).selected)
            alternateUnavailable = true
            repository.clearCache()
            assertTrue(store.hasAds(page, primary), "Cache cleanup must not remove manual reports")
            assertEquals(primary.path, repository.player(automatic).selected,
                "An advertising report is a preference, not a playback ban")
            alternateUnavailable = false
            assertTrue(store.setHasAds(page, alternate, true))
            // Both reported lines remain playable. Their measured response times
            // decide the winner, so the mock's real clock must not pin it to line 1.
            assertTrue(repository.player(automatic).selected in listOf(primary.path, alternate.path),
                "All reported lines must remain available as a last resort")
            assertTrue(store.setHasAds(page, primary, false))
            assertEquals(primary.path, repository.player(automatic).selected)
        } finally { repository.close() }
    }
}
