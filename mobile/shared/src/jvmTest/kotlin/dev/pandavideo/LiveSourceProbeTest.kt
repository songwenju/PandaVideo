package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.CatalogItem
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
    @Test fun jianpianPostersUseLiveResourceConfiguration() = runBlocking {
        assumeTrue(System.getenv("PANDAVIDEO_LIVE_SOURCES") == "1")
        val client = DesktopPlatform().httpClient()
        val backup = JianpianClient(fetch = { address, budget, headers ->
            withTimeout(budget) {
                client.get(address) {
                    header(HttpHeaders.UserAgent, "Mozilla/5.0")
                    headers.forEach { (name, value) -> header(name, value) }
                }.also { assertTrue(it.status.isSuccess()) }.bodyAsText()
            }
        })
        try {
            val item = backup.search("欢迎来龙餐馆", "movie").results.first { it.title == "欢迎来龙餐馆" }
            assertTrue(isWebUrl(item.thumbnailUrl))
            assertTrue(Url(item.thumbnailUrl).host != "static.ztcuc.com")
            withTimeout(10_000) {
                client.prepareGet(item.thumbnailUrl) {
                    header(HttpHeaders.UserAgent, "Mozilla/5.0")
                    header(HttpHeaders.Referrer, "https://pomo.mom/")
                }.execute {
                    assertTrue(it.status.isSuccess())
                    assertTrue(it.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/"))
                    val channel = it.bodyAsChannel()
                    try { assertTrue(channel.readAvailable(ByteArray(128)) > 0) }
                    finally { channel.cancel(null) }
                }
            }
            println("JIANPIAN_POSTER_PROBE exact_title=true configured_host=true poster_image=true")
        } finally { client.close() }
    }

    @Test fun searchFindsDragonRestaurantAndLoadsItsRealPoster() = runBlocking {
        assumeTrue(System.getenv("PANDAVIDEO_LIVE_SOURCES") == "1")
        val client = DesktopPlatform().httpClient()
        val repo = ContentRepository(client)
        try {
            val title = "欢迎来龙餐馆"
            val broad = repo.search("欢迎", "all")
            val index = broad.results.indexOfFirst { it.title == title }
            assertTrue(index in 0..3, "The recent matching title must be visible near the top: $index")
            val exact = repo.search(title, "movie").results.first()
            assertTrue(exact.title == title && exact.playable)
            assertTrue(isWebUrl(exact.thumbnailUrl))
            withTimeout(10_000) {
                client.prepareGet(exact.thumbnailUrl) {
                    header(HttpHeaders.UserAgent, "Mozilla/5.0")
                    header(HttpHeaders.Referrer, "https://pomo.mom/")
                }.execute {
                    assertTrue(it.status.isSuccess())
                    assertTrue(it.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/"))
                    val channel = it.bodyAsChannel()
                    try { assertTrue(channel.readAvailable(ByteArray(128)) > 0) }
                    finally { channel.cancel(null) }
                }
            }
            println("SEARCH_POSTER_PROBE broad_rank=${index + 1} exact_title=true source=${exact.source} poster_image=true")
        } finally { repo.close() }
    }

    @Test fun catalogNeZhaResolvesTheSameFilmAsSearch() = runBlocking {
        assumeTrue(System.getenv("PANDAVIDEO_LIVE_SOURCES") == "1")
        val repo = ContentRepository(DesktopPlatform().httpClient())
        try {
            val item = CatalogItem("哪吒之魔童闹海", "https://movie.douban.com/subject/34780991/",
                casts = listOf("吕艳婷", "囧森瑟夫", "瀚墨"), kind = "movie")
            val search = repo.search(item.title, "movie")
            println("CATALOG_PROBE search=${search.results.map { "${it.title}:${it.source}:${it.year}" }}")
            val match = repo.resolve(item)
            println("CATALOG_PROBE match=$match")
            assertTrue(match.playable)
            val info = repo.player(PlayerRequest(page = match.pageUrl, title = item.title))
            println("CATALOG_PROBE playable=${info.source} kind=${info.kind}")
            assertTrue(info.url.isNotBlank())
        } finally { repo.close() }
    }

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
