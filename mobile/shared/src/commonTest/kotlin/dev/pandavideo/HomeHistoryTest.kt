package dev.pandavideo

import dev.pandavideo.app.CatalogState
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.data.user.HistoryTitle
import dev.pandavideo.data.user.SettingsStore
import dev.pandavideo.data.user.UserStore
import dev.pandavideo.ui.catalog.catalogWithoutHistory
import dev.pandavideo.ui.catalog.historyCatalogMatch
import dev.pandavideo.ui.catalog.historyCatalogPoster
import dev.pandavideo.ui.catalog.homeHistoryFor
import kotlin.test.*

class HomeHistoryTest {
    private val recent = HistoryTitle("最后播放", pomoPage, "https://cover.example/recent.jpg", "2020",
        episode = "第 4 集", episodePath = "$pomoPage#1-3", positionMs = 120_000, durationMs = 600_000)
    private val routes = listOf("hot", "discover", "browse", "profile")

    @Test fun savedFirstTabPinsHistoryAfterEveryPossibleSwapAndColdReload() {
        for (first in routes) {
            val storage = FakeStorage()
            SettingsStore(storage).setTabOrder(listOf(first) + routes.filterNot { it == first })
            val restored = SettingsStore(storage).settings.value.tabOrder
            for (destination in routes) {
                val expected = recent.takeIf { destination == first && first != "profile" }
                assertEquals(expected, homeHistoryFor(restored, destination, listOf(recent)))
            }
        }
    }

    @Test fun swappingFirstTabMovesHistoryImmediatelyWithoutChangingHistory() {
        val settings = SettingsStore(FakeStorage())
        val history = listOf(recent)
        assertSame(recent, homeHistoryFor(settings.settings.value.tabOrder, "hot", history))
        settings.setTabOrder(listOf("browse", "discover", "hot", "profile"))
        assertNull(homeHistoryFor(settings.settings.value.tabOrder, "hot", history))
        assertSame(recent, homeHistoryFor(settings.settings.value.tabOrder, "browse", history))
        assertSame(recent, history.first())
    }

    @Test fun onlyTheFirstCategoryPinsHistoryAndOtherCategoryTabsKeepTheirCatalog() {
        for (destination in listOf("hot", "discover", "browse")) {
            val order = listOf(destination) + routes.filterNot { it == destination }
            assertSame(recent, homeHistoryFor(order, destination, listOf(recent), categoryIndex = 0))
            for (index in 1..15) {
                assertNull(homeHistoryFor(order, destination, listOf(recent), categoryIndex = index))
            }
        }
    }

    @Test fun noHistoryOrNonHomeDestinationKeepsCatalogUnchanged() {
        assertNull(homeHistoryFor(routes, "hot", emptyList()))
        assertNull(homeHistoryFor(routes, "discover", listOf(recent)))
        assertNull(homeHistoryFor(emptyList(), "hot", listOf(recent)))
        val items = listOf(CatalogItem("原始首位", "https://catalog.example/1"))
        assertSame(items, catalogWithoutHistory(items, null))
    }

    @Test fun latestPlayedTitleWinsAndClearingHistoryRemovesThePin() {
        val user = UserStore(FakeStorage())
        user.recordHistory("旧影片", pomoPage, "", "2020")
        user.recordHistory("新影片", "https://www.pomo.cn/detail/222.html", "", "2021")
        assertEquals("新影片", homeHistoryFor(routes, "hot", user.history.value)?.title)
        user.clearHistory()
        assertNull(homeHistoryFor(routes, "hot", user.history.value))
    }

    @Test fun savedTitleAppearsEvenWhenTheCatalogIsEmptyOrLoading() {
        val state = CatalogState(loading = true)
        val poster = historyCatalogPoster(recent, state.items)
        assertEquals(recent.title, poster.title)
        assertEquals(recent.cover, poster.cover)
        assertEquals(recent.year, poster.year)
        assertEquals(recent.pageUrl, poster.url)
        assertEquals("$pomoPage#1-3", recent.episodePath)
        assertTrue(state.loading)
    }
    @Test fun storedYearAndRatingSurviveMissingOrUnrelatedCatalogRows() {
        val saved = recent.copy(rating = 9.1)
        for (items in listOf(emptyList(), listOf(CatalogItem("其他影片", "https://catalog.example/other", "1994", 9.7)))) {
            val poster = historyCatalogPoster(saved, items)
            assertEquals("2020", poster.year)
            assertEquals(9.1, poster.rating)
            assertEquals(saved.pageUrl, poster.url)
        }
        val remake = CatalogItem(saved.title, "https://catalog.example/remake", "1980", 8.2)
        assertEquals(9.1, historyCatalogPoster(saved, listOf(remake)).rating)
        assertEquals("2020", historyCatalogPoster(saved, listOf(remake)).year)
    }

    @Test fun matchedTitleIsRemovedOnceWhileSourcePagingAndRecommendationsStayIntact() {
        val originalFirst = CatalogItem("原始首位", "https://catalog.example/1")
        val matching = CatalogItem(recent.title, "https://catalog.example/2", "2020", 9.1)
        val other = CatalogItem("其他影片", "https://catalog.example/3")
        val items = listOf(originalFirst, matching, other)
        val page = CatalogPage(items, 20, 20, true)
        val visible = catalogWithoutHistory(items, recent)
        assertEquals(listOf(originalFirst, other), visible)
        assertSame(items, page.items)
        assertEquals(40, page.nextStart())
        assertEquals(9.1, historyCatalogPoster(recent, items).rating)
    }

    @Test fun sameNameRemakesAreKeptAndUnknownYearsNeverGuessBetweenEditions() {
        val older = CatalogItem(recent.title, "https://catalog.example/old", "1980")
        val latest = CatalogItem(recent.title, "https://catalog.example/new", "2020")
        val items = listOf(older, latest)
        assertSame(latest, historyCatalogMatch(recent, items))
        assertEquals(listOf(older), catalogWithoutHistory(items, recent))
        assertNull(historyCatalogMatch(recent.copy(year = ""), items))
        assertSame(items, catalogWithoutHistory(items, recent.copy(year = "")))
    }

    @Test fun exactSourceIdentityWinsAndUniqueLegacyTitleCanSupplyMetadata() {
        val exact = CatalogItem("来源别名", recent.pageUrl, "2020", cover = "https://cover.example/catalog.jpg")
        assertSame(exact, historyCatalogMatch(recent, listOf(exact)))
        val legacy = recent.copy(year = "", cover = "")
        val unique = exact.copy(title = recent.title, url = "https://catalog.example/unique")
        assertSame(unique, historyCatalogMatch(legacy, listOf(unique)))
        assertEquals(unique.cover, historyCatalogPoster(legacy, listOf(unique)).cover)
    }
}
