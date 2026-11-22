package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CctvSourceTest {
    private val page = CctvSource.samplePage
    private val stream = "https://hls.cntv.lxdns.com/asp/hls/main/program/main.m3u8"
    private fun chapter(index: Int, duration: String = "120", tier: String = "hd") =
        """{"url":"https://vod.cntv.lxdns.com/flash/mp4video63/program/$tier-$index.mp4","duration":"$duration"}"""

    @Test fun completePublicHdTiersKeepFullProgramFallbackAndStableIdentity() = runTest {
        val body = """{"ack":"yes","hls_url":"$stream","video":{"totalLength":"179.16","chapters4":[${chapter(1)},${chapter(2, "59.16")}],"chapters3":[${chapter(1, "179.16", "lower")}]}}"""
        val info = CctvSource.resolve(PlayerRequest(page = page)) { body }
        assertEquals(2, info.segments.size)
        assertEquals(179_160L, info.segments.sumOf { it.durationMs })
        assertEquals(stream, info.url)
        assertEquals("hls", info.kind)
        assertEquals(page, info.selected)
        val lower = CctvSource.resolve(PlayerRequest(page = page,
            rejectedMedia = setOf(info.segments.last().url))) { body }
        assertEquals(1, lower.segments.size)
        val fallback = CctvSource.resolve(PlayerRequest(page = page,
            rejectedMedia = (info.segments + lower.segments).map { it.url }.toSet())) { body }
        assertTrue(fallback.segments.isEmpty())
        assertEquals(stream, fallback.url)
    }

    @Test fun missingProtectedSpoofedDuplicateAndInvalidChaptersCannotTruncateProgram() = runTest {
        val valid = chapter(1)
        for (chapters in listOf(
            """[$valid,{"url":"","duration":"120"}]""",
            "[$valid]", "[$valid,$valid]",
            "[${valid.replace("vod.cntv.lxdns.com", "vod.cntv.lxdns.com.evil")},${chapter(2)}]",
            "[${valid.replace("https:", "http:")},${chapter(2)}]",
            "[${valid.replace("/flash/mp4video63", "/asp/enc2")},${chapter(2)}]",
            "[${chapter(1, "NaN")},${chapter(2)}]",
            "[${chapter(1, "-120")},${chapter(2)}]", "[null,${chapter(2)}]")) {
            val info = CctvSource.resolve(PlayerRequest(page = page)) {
                """{"ack":"yes","hls_url":"$stream","video":{"totalLength":"240","chapters4":$chapters}}"""
            }
            assertTrue(info.segments.isEmpty(), chapters)
            assertEquals(stream, info.url)
        }
    }

    @Test fun dateFiltersAndBroadcastMetadataStaySeparateFromMovieYear() {
        val column = CctvSource.columns.keys.first()
        assertEquals("202610", io.ktor.http.Url(CctvSource.listAddress(column, 20, "202610")).parameters["d"])
        assertEquals("2026", io.ktor.http.Url(CctvSource.listAddress(column, 0, "2026")).parameters["d"])
        assertNull(io.ktor.http.Url(CctvSource.listAddress(column, 0)).parameters["d"])
        for (invalid in listOf("202600", "202613", "2026&n=999", "10", "2026-10"))
            assertFailsWith<ApiError> { CctvSource.listAddress(column, 0, invalid) }
        val body = """{"data":{"total":1,"list":[{"guid":"e12d9692ad3f47d699a320a819162cdf","title":"节目","mode":0,"time":"2026-10-30 13:02:00","length":"00:27:58","brief":"  节目简介  "}]}}"""
        val item = CctvSource.programs(body, column, 0).items.single()
        assertEquals("2026-10-30", item.broadcastDate)
        assertEquals("今日说法", item.programColumn)
        assertEquals("00:27:58", item.remark)
        assertEquals("节目简介", item.synopsis)
        assertEquals("", item.year)
        val grouped = CctvSource.channels.values.flatten()
        assertEquals(CctvSource.columns.values.toSet(), grouped.toSet())
        assertTrue(CctvSource.channels.values.all { it.size == it.toSet().size })
        assertTrue("今日说法" in CctvSource.channels.getValue("CCTV-1 综合"))
        assertTrue("探索·发现" in CctvSource.channels.getValue("CCTV-10 科教"))
        assertTrue("天网" in CctvSource.channels.getValue("CCTV-12 社会与法"))
        assertTrue("焦点访谈" in CctvSource.channels.getValue("CCTV-1 综合"))
        assertTrue("焦点访谈" in CctvSource.channels.getValue("CCTV-13 新闻"))
    }

    @Test fun fullProgramListsFilterClipsAndKeepStablePagedIdentities() {
        val column = CctvSource.columns.keys.first()
        val id = "e12d9692ad3f47d699a320a819162cdf"
        val valid = """{"guid":"$id","title":"今日说法","mode":0,"length":"00:27:59"}"""
        val body = """{"data":{"total":45,"list":[$valid,$valid,{"guid":"bad","title":"坏数据","mode":0},{"guid":"00402092f9704ee0860304a9f32649fe","title":"片段","mode":1},null]}}"""
        val result = CctvSource.programs(body, column, 20)
        assertEquals(1, result.items.size)
        assertEquals(id, io.ktor.http.Url(result.items.single().url).parameters["pid"])
        assertEquals(result.items.single().url, result.items.single().playbackPageUrl)
        assertEquals(40, result.nextStart())
        assertTrue(result.hasMore)
        assertTrue(CctvSource.listAddress(column, 20).contains("&p=2&mode=0"))
        assertFalse(CctvSource.programs("""{"data":{"total":0,"list":[]}}""", column, 0).hasMore)
        for (bad in listOf("{}", "null", "{", """{"data":{"total":1,"list":null}}"""))
            assertFailsWith<ApiError> { CctvSource.programs(bad, column, 0) }
        assertFailsWith<ApiError> { CctvSource.listAddress("unknown", 0) }
        assertFailsWith<ApiError> { CctvSource.listAddress(column, 1) }
    }

    @Test fun columnSelectionResolvesTheChosenProgramWithoutMovieSearch() = runTest {
        val columns = CctvSource.columns.keys.toList()
        val ids = columns.indices.map { (it + 1).toString(16).padStart(32, '0') }
        val resolved = mutableListOf<String>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertNull(request.headers[HttpHeaders.Cookie])
            if (request.url.host == "api.cntv.cn") {
                val index = columns.indexOf(request.url.parameters["id"])
                assertTrue(index >= 0)
                assertEquals("0", request.url.parameters["mode"])
                respond("""{"data":{"total":1,"list":[{"guid":"${ids[index]}","title":"${CctvSource.columns[columns[index]]}","mode":0}]}}""")
            } else {
                assertEquals("vdn.apps.cntv.cn", request.url.host)
                resolved += request.url.parameters["pid"].orEmpty()
                respond("""{"ack":"yes","is_preview":"0","hls_url":"$stream"}""")
            }
        }))
        try {
            for (column in columns) {
                val item = repo.cctvPrograms(column).items.single()
                assertEquals(item.url, repo.player(PlayerRequest(page = item.url)).pageUrl)
            }
            assertEquals(ids, resolved)
        } finally { repo.close() }
    }

    @Test fun repositoryRefreshesVideoFromStableProgramIdWithoutMovieFallback() = runTest {
        var calls = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals("vdn.apps.cntv.cn", request.url.host)
            assertEquals("androidapp", request.url.parameters["client"])
            assertNull(request.headers[HttpHeaders.Cookie])
            calls++
            respond("""{"ack":"yes","is_preview":"0","title":"新闻联播","hls_url":"$stream?t=$calls"}""")
        }))
        try {
            val first = repo.player(PlayerRequest(page = page))
            assertEquals("hls", first.kind)
            assertEquals("央视", first.source)
            assertEquals(page, first.pageUrl)
            assertEquals(page, first.episodes.single().path)
            val second = repo.player(PlayerRequest(page = first.pageUrl, episode = first.selected))
            assertNotEquals(first.url, second.url)
            assertEquals(2, calls)
        } finally { repo.close() }
    }

    @Test fun rejectsNonVideoProtectedInvalidAndFailedRoutes() = runTest {
        for (body in listOf("null", "{", """{"ack":"no"}""",
            """{"ack":"yes","manifest":{"hls_audio_url":"$stream"}}""",
            """{"ack":"yes","is_preview":"1","hls_url":"$stream"}""",
            """{"ack":"yes","hls_url":"https://hls.cntv.lxdns.com/asp/enc2/hls/main.m3u8"}""",
            """{"ack":"yes","hls_url":"https://hls.cntv.lxdns.com.evil/asp/hls/main.m3u8"}""",
            """{"ack":"yes","hls_url":"https://newcntv.qcloudcdn.com/asp/hlsaudio/main.m3u8"}""")) {
            assertFailsWith<ApiError> { CctvSource.resolve(PlayerRequest(page = page)) { body } }
        }
        assertFailsWith<ApiError> {
            CctvSource.resolve(PlayerRequest(page = page, rejectedMedia = setOf(stream))) {
                """{"ack":"yes","hls_url":"$stream"}"""
            }
        }
        val alternate = stream.replace("hls.cntv.lxdns.com", "newcntv.qcloudcdn.com")
        assertEquals(alternate, CctvSource.resolve(PlayerRequest(page = page)) {
            """{"ack":"yes","hls_url":"$alternate"}"""
        }.url)
    }

    @Test fun canonicalProgramReferencesRejectSpoofedHostsAndAmbiguousIds() {
        assertEquals(page, CctvSource.page(page + "&unused=1"))
        for (address in listOf(page.replace("https:", "http:"),
            page.replace("vdn.apps.cntv.cn", "user@vdn.apps.cntv.cn"),
            page.replace("vdn.apps.cntv.cn", "vdn.apps.cntv.cn.evil"),
            page.replace("vdn.apps.cntv.cn", "vdn.apps.cntv.cn:444"),
            "$page&pid=00000000000000000000000000000000", "$page#part",
            page.replace("d70ba25b901c4acdad2c22384c9b2d12", "invalid"))) {
            assertFailsWith<ApiError> { CctvSource.page(address) }
        }
    }
}
