package dev.pandavideo

import dev.pandavideo.core.model.LineProbe
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.visiblePlaybackLines
import dev.pandavideo.core.platform.currentEpochMillis
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.JianpianSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

class LineProbePersistenceTest {
    @Test fun automaticEntryRemeasuresEveryTimeAndIgnoresSavedRankingWhileManualChoiceKeepsPriority() = runBlocking {
        val file = File.createTempFile("auto-line-", ".db")
        val database = CatalogDatabase(file.absolutePath)
        val page = JianpianSource.address("1")
        val mediaRequests = java.util.Collections.synchronizedList(mutableListOf<String>())
        var fastest = "/b2.m3u8"
        var broken = ""
        var advertisedLine = 0
        var repository: ContentRepository? = null
        fun open(): ContentRepository = ContentRepository(HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = Dispatchers.Default
            addHandler { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host == "probe.test" -> {
                    mediaRequests += request.url.encodedPath
                    // Keep response delays and production timeouts on the real clock,
                    // so concurrent elapsed measurements distinguish slow and fast lines.
                    delay(if (request.url.encodedPath == fastest) 10 else 100)
                    if (request.url.encodedPath == broken) return@addHandler respond("<html>removed</html>")
                    respond("#EXTM3U\n#EXTINF:6,\na.ts\n")
                }
                else -> respond("""{"code":1,"data":{"title":"测试","source_list_source":[
                    {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/a1.m3u8"},
                        {"source_name":"第 2 集","url":"https://probe.test/a2.m3u8"}]},
                    {"source_list":[{"source_name":"第 1 集","url":"https://probe.test/b1.m3u8"},
                        {"source_name":"第 2 集","url":"https://probe.test/b2.m3u8"}]}]}}""")
            }
            }
        })), database, lineHasAds = { _, episode -> episode.line == advertisedLine })
        try {
            repository = open()
            val initial = repository.player(PlayerRequest(page = page))
            val a2 = initial.episodes.first { it.line == 1 && it.label == "第 2 集" }
            val b2 = initial.episodes.first { it.line == 2 && it.label == "第 2 集" }
            val aKey = repository.lineProbeKey(page, a2, "https://probe.test/a2.m3u8")
            val bKey = repository.lineProbeKey(page, b2, "https://probe.test/b2.m3u8")
            database.writeResponse(aKey, Json.encodeToString(LineProbe(1, true)), currentEpochMillis())
            database.writeResponse(bKey, Json.encodeToString(LineProbe(900, true)), currentEpochMillis())
            suspend fun choose(automatic: Boolean = true): String {
                mediaRequests.clear()
                val resolved = repository!!.player(PlayerRequest(page = page, episode = a2.path,
                    preferFastestLine = automatic))
                assertEquals(if (automatic) setOf("/a2.m3u8", "/b2.m3u8") else setOf("/a2.m3u8"),
                    mediaRequests.toSet(), "Every automatic entry checks the corresponding episode on every line")
                assertEquals(if (automatic) 2 else 1, mediaRequests.size,
                    "Freshly validated media must be reused for the selected line")
                assertEquals(a2.path.takeIf { resolved.selected != a2.path }, resolved.resumeFromEpisode)
                return resolved.selected
            }
            assertEquals(b2.path, choose(), "Fresh measurements override the persisted fastest line")
            fastest = "/a2.m3u8"
            assertEquals(a2.path, choose(), "Reentry bypasses both SQLite and in-memory measurements")
            fastest = "/b2.m3u8"
            repository.close()
            repository = open()
            assertEquals(a2.path, choose(false), "Manual requests only validate the requested route")
            advertisedLine = 2
            assertEquals(a2.path, choose(), "Advertising avoidance still has priority")
            advertisedLine = 0
            broken = fastest
            assertEquals(a2.path, choose(), "A newly unavailable formerly fast line must not be selected")
            broken = ""
            repository.close()
            repository = open()
            assertEquals(b2.path, choose(), "Restart also checks every route afresh")
        } finally { repository?.close(); file.delete() }
    }

    @Test fun measurementsSurviveRestartExpireAndDoNotCrossMediaOrEpisodes() = runTest {
        val file = File.createTempFile("line-probe-", ".db")
        val page = JianpianSource.address("1")
        var alternateAddress = "https://probe.test/alternate.m3u8"
        var alternateRequests = 0
        var brokenRequests = 0
        fun client() = HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host == "probe.test" -> {
                    when (request.url.encodedPath) {
                        "/alternate.m3u8", "/rotated.m3u8" -> alternateRequests++
                        "/broken.m3u8" -> brokenRequests++
                    }
                    if (request.url.encodedPath == "/broken.m3u8") respond("<html>removed</html>")
                    else respond("#EXTM3U\n#EXTINF:6,\na.ts\n")
                }
                else -> respond("""{"code":1,"data":{"title":"测试","source_list_source":[
                    {"name":"主线","source_list":[{"source_name":"第 1 集","url":"https://probe.test/main.m3u8"},
                        {"source_name":"第 2 集","url":"https://probe.test/main2.m3u8"}]},
                    {"name":"备用","source_list":[{"source_name":"第 1 集","url":"$alternateAddress"},
                        {"source_name":"第 2 集","url":"https://probe.test/second.m3u8"}]},
                    {"name":"失效","source_list":[{"source_name":"第 1 集","url":"https://probe.test/broken.m3u8"}]}]}}""")
            }
        })
        val database = CatalogDatabase(file.absolutePath)
        var repository = ContentRepository(client(), database)
        try {
            val info = repository.player(PlayerRequest(page = page))
            val probes = repository.probeLines(info)
            assertEquals(true, probes[2]?.available)
            assertEquals(false, probes[3]?.available)
            val alternate = info.episodes.first { it.line == 2 }
            val broken = info.episodes.first { it.line == 3 }
            val key = repository.lineProbeKey(page, alternate, alternateAddress)
            val brokenKey = repository.lineProbeKey(page, broken, "https://probe.test/broken.m3u8")
            assertNotNull(database.readResponse(key))
            // A deterministic persisted latency demonstrates that restart really reads SQLite.
            database.writeResponse(key, Json.encodeToString(LineProbe(7, true)), currentEpochMillis())
            repository.close()
            repository = ContentRepository(client(), CatalogDatabase(file.absolutePath))
            val reopened = repository.player(PlayerRequest(page = page))
            assertEquals(7L, reopened.lineProbes[2]?.elapsedMs)
            assertEquals(false, reopened.lineProbes[3]?.available)
            repository.probeLines(reopened)
            assertEquals(1, alternateRequests)
            assertEquals(1, brokenRequests)
            assertFalse(3 in visiblePlaybackLines(listOf(1, 2, 3), reopened.lineProbes))

            database.writeResponse(key, Json.encodeToString(LineProbe(7, true)), currentEpochMillis() - 121_000)
            database.writeResponse(brokenKey, Json.encodeToString(LineProbe(available = false)), currentEpochMillis() - 31_000)
            repository.close()
            repository = ContentRepository(client(), database)
            val expired = repository.player(PlayerRequest(page = page))
            assertNull(expired.lineProbes[2])
            assertNull(expired.lineProbes[3], "Expired failures must stop hiding repaired routes")
            repository.probeLines(expired)
            assertEquals(2, alternateRequests)
            assertEquals(2, brokenRequests)

            alternateAddress = "https://probe.test/rotated.m3u8"
            val storedDetail = assertNotNull(database.readResponse("detail:$page"))
            database.writeResponse("detail:$page", storedDetail.payload.replace(
                "https://probe.test/alternate.m3u8", alternateAddress), currentEpochMillis())
            repository.close()
            repository = ContentRepository(client(), database)
            val rotated = repository.player(PlayerRequest(page = page))
            assertNull(rotated.lineProbes[2], "Changed media addresses invalidate prior measurements")
            repository.probeLines(rotated)
            assertEquals(3, alternateRequests)
            val second = repository.player(PlayerRequest(page = page,
                episode = rotated.episodes.first { it.line == 1 && it.label == "第 2 集" }.path))
            assertNull(second.lineProbes[2], "Another episode must not inherit this episode's availability")
            database.clearCache()
            assertNull(database.readResponse(key))
        } finally { repository.close(); file.delete() }
    }
}
