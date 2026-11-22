package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.platform.DesktopPlatform
import dev.pandavideo.core.player.BilibiliMedia
import dev.pandavideo.data.repository.ContentRepository
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicitly enabled smoke test; routine host tests remain independent of public network services. */
class BilibiliLiveProbeTest {
    @Test fun anonymousSearchAndDirectVideoProvideReadableMp4() = runBlocking {
        assumeTrue(System.getenv("PANDAVIDEO_LIVE_BILIBILI") == "1")
        val client = DesktopPlatform().httpClient()
        val repo = ContentRepository(client)
        try {
            withTimeout(60_000) {
                val results = repo.search("小品", "bilibili")
                assertTrue(results.results.isNotEmpty())
                assertTrue(results.results.all { it.playable && it.sourceId.startsWith("bilibili:") })
                val direct = repo.search("BV1A6ZMB3EtZ", "all").results.single()
                assertTrue(direct.thumbnailUrl.startsWith("https://"))
                assertTrue(direct.uploader.isNotBlank() && direct.cast.isBlank())
                client.prepareGet(direct.thumbnailUrl) {
                    header(HttpHeaders.UserAgent, "Mozilla/5.0")
                    header(HttpHeaders.Referrer, dev.pandavideo.ui.common.posterReferrer(direct.thumbnailUrl))
                }.execute { response ->
                    assertTrue(response.status.isSuccess(), "Public video artwork must accept the production referrer")
                    assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/"))
                    val channel = response.bodyAsChannel()
                    try { assertTrue(channel.readAvailable(ByteArray(4096)) > 0) }
                    finally { channel.cancel(null) }
                }
                val info = repo.player(PlayerRequest(page = direct.detailUrl))
                assertTrue(BilibiliMedia.owns(info.url))
                assertTrue(info.episodes.isNotEmpty())
                client.prepareGet(info.url) {
                    header(HttpHeaders.UserAgent, BilibiliMedia.userAgent)
                    header(HttpHeaders.Range, "bytes=0-65535")
                }.execute { response ->
                    assertTrue(response.status.isSuccess(), "Official media CDN must return readable bytes")
                    val channel = response.bodyAsChannel()
                    try { assertTrue(channel.readAvailable(ByteArray(4096)) > 0) }
                    finally { channel.cancel(null) }
                }
                println("BILIBILI_PUBLIC_PROBE rows=${results.results.size} episodes=${info.episodes.size} media_readable=true")
            }
        } finally { repo.close() }
    }
}
