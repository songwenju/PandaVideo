package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.platform.DesktopPlatform
import dev.pandavideo.data.repository.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Opt-in network smoke check; normal unit tests never depend on public source availability. */
class LiveSourceProbeTest {
    @Test fun measureAndResolveRealBackup() = runBlocking {
        assumeTrue(System.getenv("PANDAVIDEO_LIVE_SOURCES") == "1")
        val client = DesktopPlatform().httpClient()
        val repo = ContentRepository(client)
        suspend fun fetch(address: String, budget: Long, headers: Map<String, String>): String =
            withTimeout(budget) {
                val response = client.get(address) {
                    header(HttpHeaders.UserAgent, "Mozilla/5.0")
                    headers.forEach { (name, value) -> header(name, value) }
                }
                assertTrue(response.status.isSuccess())
                response.bodyAsText()
            }
        val backup = JianpianClient(fetch = ::fetch)
        try {
            repeat(3) { round ->
                for (provider in listOf("Jianpian", "Pomo")) {
                    val started = TimeSource.Monotonic.markNow()
                    val result = if (provider == "Jianpian") backup.search("春夏秋冬又一春", "movie")
                    else PomoSource.search(fetch(PomoSource.address("auto_search",
                        "wd" to "春夏秋冬又一春"), 8_000, emptyMap()), "movie")
                    assertTrue(result.results.isNotEmpty())
                    println("SOURCE_PROBE round=${round + 1} provider=$provider search_ms=${started.elapsedNow().inWholeMilliseconds}")
                }
            }
            val started = TimeSource.Monotonic.markNow()
            val info = repo.player(PlayerRequest(page = JianpianSource.address("12866"),
                title = "春夏秋冬又一春"))
            assertTrue(info.kind == "hls")
            var manifestUrl = info.url
            var manifest = fetch(manifestUrl, 10_000, emptyMap())
            repeat(3) {
                assertTrue(manifest.trimStart().startsWith("#EXTM3U"))
                if ("#EXT-X-STREAM-INF" in manifest) {
                    val child = manifest.lineSequence().first { it.isNotBlank() && !it.startsWith("#") }
                    manifestUrl = URLBuilder(manifestUrl).apply { takeFrom(child.trim()) }.buildString()
                    manifest = fetch(manifestUrl, 10_000, emptyMap())
                }
            }
            assertTrue("#EXTINF" in manifest)
            val segment = manifest.lineSequence().first { it.isNotBlank() && !it.startsWith("#") }
            val segmentUrl = URLBuilder(manifestUrl).apply { takeFrom(segment.trim()) }.buildString()
            withTimeout(10_000) {
                client.prepareGet(segmentUrl) { header(HttpHeaders.Range, "bytes=0-8191") }.execute {
                    assertTrue(it.status.isSuccess())
                    val channel = it.bodyAsChannel()
                    try { assertTrue(channel.readAvailable(ByteArray(8192)) > 0) }
                    finally { channel.cancel(null) }
                }
            }
            println("SOURCE_PROBE resolved_provider=${if (JianpianSource.owns(info.pageUrl)) "Jianpian" else "Pomo"} hls_valid=true resolve_ms=${started.elapsedNow().inWholeMilliseconds}")
            // Simulate every returned primary stream failing, while retaining real backup
            // search/detail/parse requests. This verifies provider failover independently
            // of the current primary media host's changing DNS or uptime.
            val rejected = if (JianpianSource.owns(info.pageUrl))
                JianpianSource.episodes(backup.detail(info.pageUrl), info.pageUrl).map { it.second }.toSet() + info.url
            else setOf(info.url)
            val alternative = repo.player(PlayerRequest(page = info.pageUrl, title = info.title,
                episode = info.selected, rejectedMedia = rejected))
            assertTrue(alternative.url != info.url)
            if (JianpianSource.owns(info.pageUrl)) assertTrue(!JianpianSource.owns(alternative.pageUrl))
            val backupManifest = fetch(alternative.url, 10_000, emptyMap())
            assertTrue(backupManifest.trimStart().startsWith("#EXTM3U"))
            println("SOURCE_PROBE rejected_media_failover=true provider=${if (JianpianSource.owns(alternative.pageUrl)) "Jianpian" else "Pomo"}")
        } finally { repo.close() }
    }
}
