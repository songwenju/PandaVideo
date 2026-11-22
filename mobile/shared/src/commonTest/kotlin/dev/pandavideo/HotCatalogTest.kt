package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.JianpianSource
import dev.pandavideo.data.repository.mergeHotCatalog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HotCatalogTest {
    @Test fun missingReleaseMetadataRetainsUnratedPlayableHotTitle() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") ->
                    respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.encodedPath.endsWith("/resourceDomainConfig") ->
                    respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
                request.url.encodedPath.endsWith("/crumb/list") ->
                    respond("""{"code":1,"data":[{"id":1,"title":"热播电影","score":"0"}]}""")
                request.url.host == "movie.douban.com" -> respond("""{"data":[]}""")
                else -> respond("", HttpStatusCode.ServiceUnavailable)
            }
        }))
        try {
            val page = repo.hotCatalog("movie")
            val item = page.items.single()
            assertEquals(JianpianSource.address("1"), item.playbackPageUrl)
            assertTrue(item.year.isEmpty())
            assertNull(item.rating)
            assertTrue(page.stale)
        } finally { repo.close() }
    }

    @Test fun jianpianWinsDuplicatesAcrossPagesAndOtherOriginalTitlesRemain() {
        val original = CatalogItem(" 测试 影片 ", "https://movie.douban.com/subject/1/", year = "2020")
        val retained = original.copy(title = "其他影片", url = "https://movie.douban.com/subject/2/")
        val source = original.copy(title = "测试影片国语", url = JianpianSource.address("1"), year = "")
        assertEquals(listOf(source, retained), mergeHotCatalog(listOf(original, retained, source)))
        val next = retained.copy(url = JianpianSource.address("2"))
        assertEquals(listOf(source, next), mergeHotCatalog(listOf(source, retained) + listOf(next, original)))
    }

    @Test fun differentRemakesTypesAndSeasonsAreNeverMergedThroughUnknownYears() {
        val original = CatalogItem("同名电影", "https://catalog.test/1", year = "1980")
        val remake = original.copy(url = "https://catalog.test/2", year = "2020")
        val uncertain = original.copy(url = JianpianSource.address("3"), year = "")
        val drama = remake.copy(url = "https://catalog.test/4", kind = "tv")
        val season = drama.copy(title = "同名电影 第二季", url = "https://catalog.test/5")
        val merged = mergeHotCatalog(listOf(original, remake, uncertain, drama, season))
        assertEquals(listOf(uncertain, original, remake, drama, season), merged)
    }

    @Test fun fifteenRowProviderPagesRestoreOriginalCatalogAndKeepIndependentOffsets() = runTest {
        val primaryPages = mutableListOf<Int>()
        val originalOffsets = mutableListOf<Int>()
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.encodedPath.endsWith("/resourceDomainConfig") ->
                    respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
                request.url.encodedPath.endsWith("/video/detailv2") ->
                    respond("""{"code":1,"data":{"year":"2026"}}""")
                request.url.encodedPath.endsWith("/crumb/list") -> {
                    assertEquals("hot", request.url.parameters["sort"])
                    val page = request.url.parameters["page"]!!.toInt()
                    primaryPages += page
                    respond("""{"code":1,"data":[${if (page > 2) "" else (1..15).joinToString(",") {
                        val id = (page - 1) * 15 + it
                        """{"id":$id,"title":"荐片$id","score":"7.0"}"""
                    }}]}""")
                }
                else -> {
                    assertEquals("movie.douban.com", request.url.host)
                    assertEquals("U", request.url.parameters["sort"])
                    assertEquals("0,10", request.url.parameters["range"])
                    val start = request.url.parameters["start"]!!.toInt()
                    originalOffsets += start
                    respond("""{"data":[${if (start >= 40) "" else (1..20).joinToString(",") {
                        val title = if (it == 1) "荐片${start / 20 * 15 + 1}" else "旧列表${start + it}"
                        """{"title":"$title","url":"https://movie.douban.com/subject/${start + it}/","rate":"7.5"}"""
                    }}]}""")
                }
            }
        }))
        try {
            val first = repo.hotCatalog("movie")
            assertEquals(34, first.items.size)
            assertTrue(first.items.take(15).all { JianpianSource.owns(it.url) })
            assertTrue(first.items.take(15).all { it.year == "2026" && it.rating == 7.0 })
            assertTrue(first.items.drop(15).all { it.title.startsWith("旧列表") })
            assertTrue(first.hasMore, "A real 15-row provider page is not the complete catalog")
            val next = repo.hotCatalog("movie", first.nextStart())
            assertEquals(listOf(1, 2), primaryPages)
            assertEquals(listOf(0, 20), originalOffsets)
            val combined = mergeHotCatalog(first.items + next.items)
            assertEquals(68, combined.size)
            assertTrue(combined.take(30).all { JianpianSource.owns(it.url) })
            assertFalse(repo.hotCatalog("movie", next.nextStart()).hasMore)
        } finally { repo.close() }
    }

    @Test fun primarySnapshotDoesNotCancelSlowSupplementAndFailuresRetainWorkingRows() = runTest {
        for (supplementFails in listOf(false, true)) {
            val snapshots = mutableListOf<Boolean>()
            val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
                when {
                    request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                    request.url.encodedPath.endsWith("/resourceDomainConfig") ->
                        respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
                    request.url.encodedPath.endsWith("/video/detailv2") ->
                        respond("""{"code":1,"data":{"year":"2026"}}""")
                    request.url.encodedPath.endsWith("/crumb/list") ->
                        respond("""{"code":1,"data":[{"id":1,"title":"荐片电影","score":"0"}]}""")
                    else -> {
                        delay(500)
                        if (supplementFails) respond("", HttpStatusCode.ServiceUnavailable)
                        else respond("""{"data":[{"title":"改版前影片","url":"https://movie.douban.com/subject/2/","rate":"7.0"}]}""")
                    }
                }
            }))
            try {
                val page = repo.hotCatalog("movie", onCached = { snapshots += it.hasMore })
                assertEquals(listOf(false), snapshots, "Partial snapshots cannot auto-page past pending supplements")
                assertEquals("荐片电影", page.items.first().title)
                assertEquals(if (supplementFails) 1 else 2, page.items.size)
                assertEquals(supplementFails, page.stale)
                assertTrue(page.hasMore)
            } finally { repo.close() }
        }
    }
}
