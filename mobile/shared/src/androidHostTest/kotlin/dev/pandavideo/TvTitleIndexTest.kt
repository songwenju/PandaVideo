package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.tvui.TvTitleIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvTitleIndexTest {
    private val movie = CatalogItem("肖申克的救赎", "subject:1", year = "1994")
    private val series = CatalogItem("西游记", "subject:2", kind = "tv", year = "1986")
    private val index = TvTitleIndex(listOf(movie, series, CatalogItem("1917", "subject:3")))

    @Test fun remoteInitialsAndFullPinyinFindKnownTitles() {
        assertEquals(listOf(movie), index.search("XSKDJS", "movie"))
        assertEquals(listOf(movie), index.search("xiao shen ke de jiu shu", "movie"))
        assertEquals(listOf(series), index.search("xyj", "tv"))
    }

    @Test fun completeTitlesNumbersAndTypeBoundariesRemainDistinct() {
        assertEquals(listOf(movie), index.search("肖申克", "movie"))
        assertEquals("1917", index.search("1917", "movie").single().title)
        assertTrue(index.search("xyj", "movie").isEmpty())
        assertTrue(index.search("   ", "tv").isEmpty())
        assertTrue(index.search("unindexed", "movie").isEmpty())
    }

    @Test fun allScopeFindsOnePieceByChineseTitleInitialsAndFullPinyin() {
        val animation = CatalogItem("海贼王", "subject:one-piece", kind = "animation", year = "1999")
        val titles = TvTitleIndex(listOf(movie, animation))
        for (query in listOf("海贼王", "HZW", "hai zei wang")) {
            assertEquals(listOf(animation), titles.search(query, "all"))
            assertEquals(listOf(animation), titles.search(query, "animation"))
            assertTrue(titles.search(query, "movie").isEmpty())
        }
    }

    @Test fun exactThenPrefixThenContainsMatchesPrecedeProviderOrder() {
        val contains = CatalogItem("寻找海贼王", "contains")
        val prefix = CatalogItem("海贼王特别篇", "prefix")
        val exact = CatalogItem("海贼王", "exact")
        val titles = TvTitleIndex(listOf(contains, prefix, exact))
        for (query in listOf("海贼王", "HZW", "hai zei wang")) {
            assertEquals(listOf(exact, prefix, contains), titles.search(query, "all"))
        }
    }
}
