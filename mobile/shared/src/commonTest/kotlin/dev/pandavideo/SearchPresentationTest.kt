package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.SearchResult
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.DoubanArtwork
import dev.pandavideo.data.repository.SearchPresentation
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SearchPresentationTest {
    private fun item(title: String = "西游记", year: String = "1986", id: Int = 1,
        genre: String = "", rating: Double? = null, playable: Boolean = true) =
        SearchItem(title, "来源$id", detailUrl = "https://source.test/$id", year = year,
            genre = genre, rating = rating, playable = playable)

    @Test fun releaseDedupKeepsRemakesSequelsTypesAndUnknownYearsSeparate() {
        val rows = listOf(
            item("西游记1986"), item("西游记86版", id = 2),
            item("西游记国语1986", id = 3), item("西游记", year = "2010", id = 4),
            item("西游记续集", year = "2000", id = 5),
            item(year = "2010", id = 6, genre = "动画"),
            item(year = "", id = 7), item(year = "", id = 8),
        )
        val result = SearchPresentation.apply(SearchResult(rows), "西游记")
        assertEquals(6, result.results.size)
        assertTrue(result.results.any { it.title == "西游记续集" })
        assertEquals(2, result.results.count { it.year == "2010" })
        assertEquals(2, result.results.count { it.year.isBlank() })
    }

    @Test fun dedupSelectsPlayableMirrorAndKeepsItsResourceIdentity() {
        val unavailable = item(playable = false)
        val playable = item(id = 2, rating = 9.7).copy(thumbnailUrl = "https://poster.test/1986.jpg")
        val result = SearchPresentation.apply(SearchResult(listOf(unavailable, playable)), "西游记")
        assertEquals(playable, result.results.single())
    }

    @Test fun exactTitlesTakePriorityOverRatingAndYearWhileInvalidRatingsRemainUnknown() {
        val rows = listOf(
            item(year = "2010", rating = 6.0),
            item("西游记续集", year = "2000", id = 2, rating = 9.1),
            item(id = 3, rating = 9.7),
            item("未评分", id = 4), item("错误评分", id = 5, rating = 99.0),
        )
        val result = SearchPresentation.apply(SearchResult(rows), "西游记")
        assertEquals(listOf(9.7, 6.0, 9.1, null, null), result.results.map { it.rating })
    }

    @Test fun unratedExactTitleAndVerifiedReleaseAliasesPrecedeHighlyRatedPartialMatches() {
        val exact = item("《海贼王》", year = "1999")
        val alias = item("海贼王国语1999", year = "1999", id = 2, genre = "动画")
        val prefix = item("海贼王特别篇", id = 3, rating = 9.8)
        val contains = item("寻找海贼王", id = 4, rating = 9.9)
        val unrelated = item("其他高分作品", id = 5, rating = 10.0)
        val result = SearchPresentation.apply(SearchResult(listOf(unrelated, contains, prefix, alias, exact)), "海 贼 王")
        assertEquals(listOf(alias, exact, prefix, contains, unrelated), result.results)
    }

    @Test fun conflictingCastsAndRegionsAreNotCollapsed() {
        val rows = listOf(item().copy(cast = "六小龄童"),
            item(id = 2).copy(cast = "陈浩民"),
            item(id = 3).copy(region = "日本"),
            item(id = 4).copy(region = "中国大陆"))
        val result = SearchPresentation.apply(SearchResult(rows), "西游记")
        // Missing metadata may join one compatible group, but never bridge conflicting rows.
        assertTrue(result.results.size >= 2)
        assertTrue(result.results.any { it.cast == "陈浩民" })
    }

    @Test fun scoreLookupRejectsMissingYearsConflictingSubjectsAndWrongReleaseDetails() {
        val suggestions = """[
            {"id":"1","type":"movie","title":"西游记","year":"1986","episode":"25"},
            {"id":"2","type":"movie","title":"西游记","year":"2010","episode":"52"}]"""
        assertEquals("1", DoubanArtwork.searchSuggestion(suggestions, item("西游记86版"))?.id)
        assertNull(DoubanArtwork.searchSuggestion(suggestions, item(year = "")))
        assertNull(DoubanArtwork.searchSuggestion(suggestions.replace("\"2010\"", "\"1986\""), item()))
        assertFalse(SearchPresentation.matchesCatalog(item(), CatalogItem("西游记", "", "2010", 6.0, kind = "tv")))
        assertFalse(SearchPresentation.matchesCatalog(item(genre = "动画"),
            CatalogItem("西游记", "", "1986", 9.7, kind = "tv")))
    }

    @Test fun optionalRatingsSortRealVersionsAndRetainScoresWhenBudgetExpires() = runTest {
        var cancelled = false
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when {
                request.url.encodedPath.endsWith("subject_suggest") -> {
                    if (request.url.parameters["q"] == "慢速作品") {
                        try { delay(10_000) } finally { cancelled = true }
                    }
                    respond("""[{"id":"2156663","type":"movie","title":"西游记","year":"1986","episode":"25"}]""")
                }
                else -> respond("""{"title":"西游记","year":"1986","is_tv":true,"rating":{"value":9.7}}""")
            }
        }))
        try {
            val result = repo.searchRatings(SearchResult(listOf(
                item("慢速作品", year = "2026", id = 3),
                item("西游记2010", year = "2010", id = 2, rating = 6.0),
                item("西游记86版"),
            )), "西游记")
            assertEquals(listOf(9.7, 6.0, null), result.results.map { it.rating })
            assertTrue(cancelled)
        } finally { repo.close() }
    }

    @Test fun unavailableRatingsNeverFailOrErasePlayableSearchResults() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { respond("", HttpStatusCode.Forbidden) }))
        try {
            val original = SearchResult(listOf(item()))
            assertEquals(original, repo.searchRatings(original, "西游记"))
            assertTrue(repo.searchRatings(original, "西游记").results.single().playable)
        } finally { repo.close() }
    }

    @Test fun sameTitleVersionsShareOneSubjectQuery() = runTest {
        var suggestions = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.encodedPath.endsWith("subject_suggest")) {
                suggestions++
                respond("""[
                    {"id":"1","type":"movie","title":"西游记","year":"1986","episode":"25"},
                    {"id":"2","type":"movie","title":"西游记","year":"2010","episode":"52"}]""")
            } else {
                val old = request.url.encodedPath.endsWith("/1")
                respond("""{"title":"西游记","year":"${if (old) "1986" else "2010"}","is_tv":true,"rating":{"value":${if (old) 9.7 else 6.0}}}""")
            }
        }))
        try {
            val result = repo.searchRatings(SearchResult(listOf(item(),
                item(year = "2010", id = 2))), "西游记")
            assertEquals(listOf(9.7, 6.0), result.results.map { it.rating })
            assertEquals(1, suggestions)
        } finally { repo.close() }
    }
}
