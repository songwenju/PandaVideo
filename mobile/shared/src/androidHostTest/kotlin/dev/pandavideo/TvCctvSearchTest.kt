package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.data.repository.CctvSource
import dev.pandavideo.tvui.tvCctvMatches
import dev.pandavideo.tvui.tvRelatedSelection
import dev.pandavideo.tvui.tvCctvProgramTitle
import kotlin.test.*

class TvCctvSearchTest {
    @Test fun keywordDatesAndRemoteInitialsKeepProgramIdentity() {
        val row = CatalogItem("《今日说法》 20261030 田间办案化民忧",
            CctvSource.samplePage, kind = "variety")
        val items = listOf(row, CatalogItem("动物世界", "other", kind = "variety"))
        for (query in listOf("田间办案", "202610", "jrsf"))
            assertEquals(listOf(row), tvCctvMatches(items, query))
        assertEquals(items, tvCctvMatches(items, ""))
        assertEquals("田间办案化民忧", tvCctvProgramTitle(row))
        assertEquals("动物世界", tvCctvProgramTitle(items[1]))
        assertTrue(tvCctvMatches(items, "没有这个主题").isEmpty())
        val selection = tvRelatedSelection(row, items)
        assertEquals(row.url, selection.request.page)
        assertEquals(items, selection.recommendations)
        assertNull(tvRelatedSelection(CatalogItem("电影", "subject:1"), emptyList()).request.page)
    }
}
