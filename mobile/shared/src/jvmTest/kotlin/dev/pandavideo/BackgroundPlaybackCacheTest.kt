package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

class BackgroundPlaybackCacheTest {
    @Test fun verifiedMirrorListsSurviveRestartAndResolveTheirOriginalEpisodePaths() = runTest {
        val file = File.createTempFile("playback-mirrors-", ".db")
        val mirror = pomoPage.replace("id=1", "id=2")
        var searches = 0
        fun open() = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "pomo.mom" -> respond(when (request.url.parameters["type"]) {
                    "auto_detail" -> pomoDetailFixture
                    "auto_search" -> {
                        searches++
                        pomoSearch(pomoRow("测试电影"), pomoRow("测试电影", id = 2))
                    }
                    "parse" -> pomoMedia()
                    else -> error("Unexpected request")
                })
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host.endsWith("zxfmj.com") -> respond("""{"code":1,"data":[]}""")
                else -> respond("#EXTM3U\n#EXTINF:6,\na.ts\n")
            }
        }), CatalogDatabase(file.absolutePath))
        var repository = open()
        try {
            val initial = repository.player(PlayerRequest(page = pomoPage, reuseSourceInfo = true))
            val supplemented = repository.supplementPlayback(initial)
            val target = supplemented.episodes.first { it.path == "$mirror#1-1" }
            val previousSearches = searches
            repository.close()
            repository = open()
            val restored = repository.player(PlayerRequest(page = pomoPage, episode = target.path,
                allowLineFallback = false, reuseSourceInfo = true))
            assertEquals(target.path, restored.selected)
            assertEquals(target.line, restored.episodes.first { it.path == target.path }.line)
            assertEquals(previousSearches, searches, "Cached mirror history needs no blocking source search")
            assertEquals(supplemented.episodes, restored.episodes)
        } finally {
            repository.close()
            file.delete()
        }
    }
}
