package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.data.repository.ApiError
import dev.pandavideo.data.repository.ContentParsers
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.Top250Categories
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class Top250CategoriesTest {
    @Test fun rankingMetadataSeparatesMultipleReleaseYearsFromProductionRegions() {
        val item = ContentParsers.top250(topFixture.replace(
            "2020 / 中国", "1961(中国大陆) / 1978(中国大陆) / 中国大陆 中国香港 / 剧情 动画 奇幻",
        )).single()
        assertEquals("1961", item.year)
        assertEquals(listOf("中国大陆", "中国香港"), item.countries)
        assertEquals(listOf("剧情", "动画", "奇幻"), item.genres)
        assertEquals("animation", item.kind)
        val documentary = ContentParsers.top250(topFixture.replace("中国", "美国 / 纪录片")).single()
        assertEquals("documentary", documentary.kind)
    }

    @Test fun categoriesScanTheWholeRankingBeforePaginatingAndKeepOnlyHighRatings() = runTest {
        // Ranking classification remains a standalone metadata utility. Full homepage
        // categories now use JSON and must never invoke this Top250-only path.
        val ranking = (0..225 step 25).map { start ->
            val items = ContentParsers.top250((start until start + 25).joinToString("") { index ->
                topFixture.replace("123", "${1000 + index}")
                    .replace("中国", "中国大陆 / ${if (index >= 200) "战争" else "喜剧"}")
                    .replace("9.1", if (index == 249) "7.9" else "9.1")
            })
            CatalogPage(items, start, 25, start < 225)
        }
        run {
            val first = Top250Categories.page(ranking, "war", "all", 0)
            assertEquals(20, first.items.size)
            assertTrue(first.hasMore)
            assertEquals(20, first.nextStart())
            val second = Top250Categories.page(ranking, "war", "all", 20)
            val last = Top250Categories.page(ranking, "war", "all", 40)
            assertEquals(20, second.items.size)
            assertEquals(9, last.items.size)
            assertFalse(last.hasMore)
            assertEquals(49, (first.items + second.items + last.items).distinctBy { it.url }.size)
            assertTrue(last.items.all { "战争" in it.genres && it.rating!! >= 8.0 })
        }
    }

    @Test fun emptyCategoryIsAValidRankingResultAndOfflineRefreshPreservesMatches() = runTest {
        var offline = false
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) {
            if (offline) respond("", HttpStatusCode.Forbidden)
            else if (it.url.encodedPath.endsWith("/init"))
                respond("""{"code":1,"data":{"secret":"s"}}""")
            else if (it.url.encodedPath.endsWith("/resourceDomainConfig"))
                respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
            else if (it.url.encodedPath.endsWith("/crumb/list"))
                respond("""{"code":1,"data":[]}""")
            else if (it.url.parameters["tags"] == "电影,科幻") respond("""{"data":[]}""")
            else respond("""{"data":[{"title":"传记作品","url":"https://movie.douban.com/subject/123/","rate":"9.1"}]}""")
        }))
        try {
            val biography = repo.list("biography")
            assertEquals(1, biography.items.size)
            val empty = repo.list("scifi")
            assertTrue(empty.items.isEmpty())
            assertFalse(empty.hasMore)
            offline = true
            assertEquals(biography.copy(stale = true), repo.list("biography", refresh = true))
        } finally { repo.close() }
    }

    @Test fun genreCountryAndCuratedThemesAlwaysIntersectRankingMembership() {
        fun film(id: String, genres: List<String>, countries: List<String>, kind: String = "movie") =
            CatalogItem(id, "https://movie.douban.com/subject/$id/", rating = 9.0,
                kind = kind, genres = genres, countries = countries)
        val items = listOf(
            film("1", listOf("战争", "动作"), listOf("中国大陆", "中国香港")),
            film("2", listOf("战争", "动作"), listOf("美国")),
            film("3", listOf("剧情"), listOf("美国")),
            film("4", listOf("动作", "动画"), listOf("美国"), "animation"),
            film("1849031", listOf("剧情", "传记"), listOf("美国")),
            film("3442220", listOf("纪录片"), listOf("美国"), "documentary"),
        )
        val ranking = listOf(CatalogPage(items, 0, 25, false))
        fun ids(kind: String, country: String = "all") =
            Top250Categories.page(ranking, kind, country, 0).items.map { it.title }
        assertEquals(listOf("1", "2"), ids("war"))
        assertEquals(listOf("1"), ids("war", "cn"))
        assertEquals(listOf("1"), ids("war", "hk"))
        assertEquals(emptyList(), ids("war", "jp"))
        assertEquals(listOf("2"), ids("hollywood"))
        assertEquals(listOf("1"), ids("hollywood", "cn"))
        assertEquals(listOf("4"), ids("anime"))
        assertEquals(listOf("3442220"), ids("documentary"))
        assertEquals(listOf("3442220"), ids("nature"))
        assertEquals(listOf("1849031"), ids("finance"))
        assertTrue(ids("movie").toSet() == items.map { it.title }.toSet())
    }
}
