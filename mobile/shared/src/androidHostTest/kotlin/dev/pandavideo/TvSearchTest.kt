package dev.pandavideo

import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.data.repository.PomoSource
import dev.pandavideo.tvui.tvSearchResults
import dev.pandavideo.tvui.tvSearchSourceType
import dev.pandavideo.tvui.tvSearchOrder
import dev.pandavideo.tvui.TvLibraryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSearchTest {
    @Test fun searchBackReturnsToOriginWithoutLosingQueryFiltersOrResultPosition() {
        for (origin in listOf("热播", "精选", "分类", "记录")) {
            val state = TvLibraryState()
            state.tab = origin
            state.searchType = "animation"
            state.query = "HZW"
            state.focused[origin] = "original-card"
            state.positions[origin] = 12
            state.focused["搜索"] = "search-card"
            state.positions["搜索"] = 6
            state.openSearch()
            assertEquals("搜索", state.tab)
            state.openSearch() // Repeated entry must not replace the return destination.
            state.closeSearch()
            assertEquals(origin, state.tab)
            assertTrue(state.restoreFocus)
            assertEquals("HZW", state.query)
            assertEquals("animation", state.searchType)
            assertEquals("original-card", state.focused[origin])
            assertEquals(12, state.positions[origin])
            assertEquals("search-card", state.focused["搜索"])
            assertEquals(6, state.positions["搜索"])
        }
    }

    @Test fun closingSearchOutsideSearchDoesNotNavigateOrRequestFocusRestoration() {
        val state = TvLibraryState()
        state.tab = "分类"
        state.closeSearch()
        assertEquals("分类", state.tab)
        assertTrue(!state.restoreFocus)
    }

    @Test fun exactChineseAndPhoneticTitlesPrecedeNewerSpinOffsAndLooseProviderMatches() {
        val original = SearchItem("海贼王", "片源", "series", true, "series", year = "1999")
        val spinOff = original.copy(title = "海贼王特别篇：迷雾岛大冒险", detailUrl = "special", year = "2015")
        val loose = original.copy(title = "杭州网球公开赛决赛", detailUrl = "tennis", year = "2026")
        for (query in listOf("海贼王", "HZW", "hai zei wang")) {
            val results = tvSearchResults(listOf(loose, spinOff, original), "all", query)
            assertEquals(original, results.first())
            // 杭州网球 also starts with HZW, so non-exact initials can tie.
            assertEquals(setOf(original, spinOff, loose), results.toSet())
        }
        assertEquals(listOf(loose), tvSearchResults(listOf(loose), "all", "provider alias"),
            "Provider aliases absent from local phonetic matching must remain usable")
    }

    @Test fun verifiedReleaseAliasesMatchChinesePinyinAndInitialsExactly() {
        val original = SearchItem("海贼王国语1999", "片源", "series", true, "series", year = "1999")
        val spinOff = original.copy(title = "海贼王特别篇", detailUrl = "special", year = "2015")
        for (query in listOf("海贼王", "HZW", "hai zei wang")) {
            assertEquals(listOf(original, spinOff),
                tvSearchResults(listOf(spinOff, original), "all", query))
        }
    }

    @Test fun onlineRankingDoesNotApplyTheSixtyTitleLocalSearchLimit() {
        val loose = SearchItem("片源识别的别名", "片源", "loose", true, "loose")
        val exact = (0..64).map { loose.copy(title = "海贼王", detailUrl = "exact-$it") }
        assertEquals(exact + loose, tvSearchResults(listOf(loose) + exact, "all", "HZW"))
    }

    @Test fun gridRanksOnlineExactMatchesAheadOfLocalPartialMatchesAndPreservesIdentities() {
        val localContains = CatalogItem("寻找海贼王", "catalog-contains")
        val localPrefix = CatalogItem("海贼王特别篇", "catalog-prefix")
        val localExact = CatalogItem("海贼王", "catalog-exact")
        val onlineExact = SearchItem("海贼王国语1999", "片源", "original", true, "provider-exact", year = "1999")
        val onlinePrefix = onlineExact.copy(title = "海贼王剧场版", detailUrl = "provider-prefix")
        for (query in listOf("海贼王", "HZW", "hai zei wang")) {
            assertEquals(listOf(localExact.url, onlineExact.detailUrl, localPrefix.url,
                onlinePrefix.detailUrl, localContains.url),
                tvSearchOrder(listOf(localContains, localPrefix, localExact),
                    tvSearchResults(listOf(onlinePrefix, onlineExact), "all", query), query))
            assertEquals(listOf(onlineExact.detailUrl, localPrefix.url, localContains.url),
                tvSearchOrder(listOf(localContains, localPrefix), listOf(onlineExact), query))
        }
        assertEquals(listOf(localPrefix.url, onlineExact.detailUrl),
            tvSearchOrder(listOf(localPrefix), listOf(onlineExact), ""))
    }

    @Test fun remoteInitialsKeepOnlineAnimationEvenWithoutLocalCatalogCoverage() {
        // Real provider search shape: initials resolve a serial animation, which the
        // old default movie scope removed before the television could display it.
        val body = """{"code":200,"data":{"暴风":[{"id":12349,"name":"海贼王",
            "remarks":"更新至第1180集","year":"1999","type_name":"日韩动漫"}]}}"""
        val source = tvSearchSourceType("all")
        assertEquals("all", source)
        val result = PomoSource.search(body, source)
        assertEquals("海贼王", tvSearchResults(result.results, "all").single().title)
        assertEquals("海贼王", tvSearchResults(result.results, "animation").single().title)
        assertTrue(tvSearchResults(result.results, "movie").isEmpty())
        val sparse = result.results.single().copy(genre = "")
        assertEquals(listOf(sparse), tvSearchResults(listOf(sparse), "animation"),
            "An episode badge alone cannot classify an animation as live-action television")
    }

    @Test fun genreButtonsUseSupportedProviderScopesAndKeepUnknownMetadata() {
        for (kind in listOf("animation", "documentary", "variety")) {
            assertEquals("all", tvSearchSourceType(kind))
        }
        assertEquals("movie", tvSearchSourceType("movie"))
        assertEquals("tv", tvSearchSourceType("tv"))
        val sparse = SearchItem("待确认片名", "片源", "id", true, "page")
        val missing = sparse.copy(detailUrl = "missing", playable = false)
        val movie = sparse.copy(detailUrl = "movie", genre = "电影")
        assertEquals(listOf(sparse), tvSearchResults(listOf(sparse, sparse, missing, movie), "animation"))
    }
}
