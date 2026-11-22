package dev.pandavideo

import dev.pandavideo.data.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HomeSourceCatalogTest {
    @Test fun selectedCategoriesReuseJianpianPriorityAcrossPagesAndCachedMirrors() {
        for (kind in listOf("tv", "animation", "variety", "documentary")) {
            val original = dev.pandavideo.core.model.CatalogItem(
                "旧目录", "https://movie.douban.com/subject/1/", rating = 9.9, kind = kind, year = "2020")
            val source = original.copy(title = "荐片目录", url = JianpianSource.address("1"), rating = 8.5)
            val first = mergeHighRatedCatalog(listOf(original, source))
            assertEquals(listOf(source, original), first)
            val upgraded = original.copy(url = JianpianSource.address("3"), rating = 8.8)
            val next = source.copy(title = "新一页", url = JianpianSource.address("4"), rating = 9.0)
            assertEquals(listOf(next, upgraded, source), mergeHighRatedCatalog(first + listOf(upgraded, next)))
        }
    }

    @Test fun selectedSeriesCategoriesLoadMatchingJianpianCatalogBeforeHigherRatedSupplement() = runTest {
        for ((kind, category) in listOf("tv" to "2", "anime" to "3", "variety" to "4", "documentary" to "50")) {
            val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
                when {
                    request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                    request.url.encodedPath.endsWith("/resourceDomainConfig") ->
                        respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
                    request.url.encodedPath.endsWith("/crumb/list") -> {
                        assertEquals(category, request.url.parameters["category_id"])
                        assertEquals("rating", request.url.parameters["sort"])
                        respond("""{"code":1,"data":[{"id":1,"title":"荐片作品","score":"8.5"}]}""")
                    }
                    request.url.encodedPath.endsWith("/detailv2") ->
                        respond("""{"code":1,"data":{"year":"2020","area":"美国","types":[{"name":"剧情"}]}}""")
                    else -> respond("""{"data":[{"title":"原精选","url":"https://movie.douban.com/subject/2/","rate":"9.9"}]}""")
                }
            }))
            try {
                val page = repo.list(kind)
                assertEquals(listOf("荐片作品", "原精选"), page.items.map { it.title })
                assertTrue(JianpianSource.owns(page.items.first().playbackPageUrl))
                assertEquals(page, repo.list(kind), "Cached pages must preserve source priority")
            } finally { repo.close() }
        }
    }

    @Test fun enrichedTelevisionMetadataExcludesFactualAndVarietyPrograms() {
        val item = dev.pandavideo.core.model.CatalogItem("高分节目", JianpianSource.address("1"), kind = "tv")
        val filters = dev.pandavideo.core.model.BrowseFilters(type = "tv")
        val factual = JianpianSource.catalogMetadata(
            """{"code":1,"data":{"types":[{"name":"纪录片"}],"area":"中国大陆","year":"2020"}}""", item)
        assertFalse(JianpianSource.matchesFilters(factual, filters))
        assertTrue(JianpianSource.matchesFilters(factual.copy(genres = listOf("剧情")), filters))
    }

    @Test fun selectedCatalogCombinesSourcesAndKeepsIndependentPaginationAndScoreFloor() = runTest {
        val offsets = mutableListOf<Int>()
        val sourcePages = mutableListOf<Int>()
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.encodedPath.endsWith("/resourceDomainConfig") ->
                    respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
                request.url.encodedPath.endsWith("/crumb/list") -> {
                    assertEquals("rating", request.url.parameters["sort"])
                    val page = request.url.parameters["page"]!!.toInt()
                    sourcePages += page
                    respond("""{"code":1,"data":[${if (page > 2) "" else (1..15).joinToString(",") {
                        """{"id":${page * 100 + it},"title":"荐片$page-$it","score":"${if (it == 15) "7.9" else "9.0"}"}"""
                    }}]}""")
                }
                request.url.encodedPath.endsWith("/detailv2") ->
                    respond("""{"code":1,"data":{"year":"2020","area":"美国","types":[{"name":"剧情"}]}}""")
                else -> {
                    offsets += request.url.parameters["start"]!!.toInt()
                    respond("""{"data":[{"title":"原精选","url":"https://movie.douban.com/subject/2/","rate":"8.5"}]}""")
                }
            }
        }))
        try {
            val first = repo.list("movie")
            assertEquals(15, first.items.size)
            assertEquals(14, first.items.count { JianpianSource.owns(it.url) })
            assertTrue(first.items.all { it.rating!! >= 8 })
            assertEquals(20, first.nextStart())
            assertTrue(first.hasMore, "A real 15-row response must not terminate the source catalog")
            val next = repo.list("movie", first.nextStart())
            assertEquals(listOf(1, 2), sourcePages)
            assertEquals(listOf(0, 20), offsets)
            assertFalse(repo.list("movie", next.nextStart()).hasMore)
        } finally { repo.close() }
    }

    @Test fun regionalGenreSelectionRequiresSourceMetadataAndKeepsOriginalOnSourceFailure() = runTest {
        var unavailable = false
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "movie.douban.com" ->
                    respond("""{"data":[{"title":"原战争片","url":"https://movie.douban.com/subject/2/","rate":"8.5"}]}""")
                unavailable -> respond("", HttpStatusCode.ServiceUnavailable)
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.encodedPath.endsWith("/resourceDomainConfig") ->
                    respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
                request.url.encodedPath.endsWith("/crumb/list") ->
                    respond("""{"code":1,"data":[{"id":1,"title":"中国战争片","score":"9"},{"id":2,"title":"外国战争片","score":"9"},{"id":3,"title":"中国喜剧片","score":"9"}]}""")
                else -> {
                    val id = request.url.parameters["id"]
                    respond("""{"code":1,"data":{"year":"2020","area":"${if (id == "2") "美国" else "大陆"}","types":[{"name":"${if (id == "3") "喜剧" else "战争"}"}]}}""")
                }
            }
        }))
        try {
            val page = repo.list("war", country = "cn")
            assertEquals(setOf("中国战争片", "原战争片"), page.items.map { it.title }.toSet())
            unavailable = true
            val partial = repo.list("war", start = 20, country = "cn", refresh = true)
            assertEquals(listOf("原战争片"), partial.items.map { it.title })
            assertTrue(partial.stale)
        } finally { repo.close() }
    }
}
