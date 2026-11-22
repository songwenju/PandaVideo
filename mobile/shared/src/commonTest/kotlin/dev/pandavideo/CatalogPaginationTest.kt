package dev.pandavideo

import dev.pandavideo.app.CatalogState
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.ui.catalog.catalogAutoLoadStart
import kotlin.test.*

class CatalogPaginationTest {
    private val items = List(3) { CatalogItem("影片 $it", "https://catalog.example/$it") }
    private val cached = CatalogState(items, CatalogPage(items, 0, 20, true, stale = true))

    @Test fun expiredCachedPageStillLoadsNextPageAtTheBottom() {
        assertEquals(20, catalogAutoLoadStart(cached, true, 3, 3))
        assertEquals(40, catalogAutoLoadStart(cached.copy(page = cached.page!!.copy(start = 20)), true, 3, 3))
    }

    @Test fun loadingErrorsInactiveTabsAndCompletedPagesStopAutomaticRequests() {
        assertNull(catalogAutoLoadStart(cached, false, 3, 3))
        assertNull(catalogAutoLoadStart(cached.copy(loading = true), true, 3, 3))
        assertNull(catalogAutoLoadStart(cached.copy(error = "加载失败"), true, 3, 3))
        assertNull(catalogAutoLoadStart(cached.copy(page = cached.page!!.copy(hasMore = false)), true, 3, 3))
        assertNull(catalogAutoLoadStart(CatalogState(), true, -1, 0))
    }

    @Test fun prefetchThresholdUsesDisplayedRowsButPreservesSourceOffset() {
        val state = cached.copy(page = cached.page!!.copy(stale = false))
        assertNull(catalogAutoLoadStart(state, true, 13, 20))
        assertEquals(20, catalogAutoLoadStart(state, true, 14, 20))
        assertNull(catalogAutoLoadStart(state, true, -1, 3))
    }

    @Test fun fullyFilteredCachedPageCanAdvanceWithoutAVisiblePoster() {
        assertEquals(20, catalogAutoLoadStart(cached.copy(items = emptyList()), true, -1, 0))
    }

    @Test fun televisionPrefetchStartsAtHalfwayAndMovesForwardAfterAppend() {
        val state = cached.copy(items = List(20) { items[it % items.size] })
        assertNull(catalogAutoLoadStart(state, true, 8, 20, prefetchAtHalfway = true))
        assertEquals(20, catalogAutoLoadStart(state, true, 9, 20, prefetchAtHalfway = true))
        val appended = state.copy(items = state.items + state.items, page = state.page!!.copy(start = 20))
        assertNull(catalogAutoLoadStart(appended, true, 9, 40, prefetchAtHalfway = true))
        assertEquals(40, catalogAutoLoadStart(appended, true, 19, 40, prefetchAtHalfway = true))
        assertNull(catalogAutoLoadStart(appended.copy(page = appended.page!!.copy(hasMore = false)),
            true, 39, 40, prefetchAtHalfway = true))
    }

    @Test fun televisionPrefetchWaitsForLayoutAndStopsDuringLoadingOrErrors() {
        assertNull(catalogAutoLoadStart(cached, true, -1, 3, prefetchAtHalfway = true))
        assertNull(catalogAutoLoadStart(cached, true, 0, 3, prefetchAtHalfway = true))
        assertEquals(20, catalogAutoLoadStart(cached, true, 1, 3, prefetchAtHalfway = true))
        for (state in listOf(cached.copy(loading = true), cached.copy(loadingMore = true),
            cached.copy(error = "加载失败"))) {
            assertNull(catalogAutoLoadStart(state, true, 2, 3, prefetchAtHalfway = true))
        }
        assertNull(catalogAutoLoadStart(cached, false, 2, 3, prefetchAtHalfway = true))
        assertEquals(20, catalogAutoLoadStart(cached.copy(items = emptyList()),
            true, -1, 0, prefetchAtHalfway = true))
    }
}
