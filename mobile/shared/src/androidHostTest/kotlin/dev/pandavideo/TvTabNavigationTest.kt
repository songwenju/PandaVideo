package dev.pandavideo

import dev.pandavideo.tvui.TvLibraryState
import dev.pandavideo.tvui.TvMainTabOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class TvTabNavigationTest {
    @Test fun focusingEveryMainTabSelectsItImmediatelyAndPreservesPageState() {
        val state = TvLibraryState()
        state.hotType = "animation"
        state.homeCountry = "中国"
        state.query = "海贼王"
        state.cctvColumn = "saved-column"
        state.focused["精选"] = "saved-poster"
        state.positions["精选"] = 24
        val grid = state.grids.getValue("精选")
        val filters = state.filters
        for ((target, _) in TvMainTabOptions + TvMainTabOptions.reversed()) {
            state.selectMainTab(target)
            assertEquals(target, state.tab)
            assertEquals("animation", state.hotType)
            assertEquals("中国", state.homeCountry)
            assertEquals("海贼王", state.query)
            assertEquals("saved-column", state.cctvColumn)
            assertEquals("saved-poster", state.focused["精选"])
            assertEquals(24, state.positions["精选"])
            assertSame(grid, state.grids.getValue("精选"))
            assertEquals(filters, state.filters)
        }
    }

    @Test fun selectingAnotherMainTabCancelsPendingDetailFocusRestoration() {
        val state = TvLibraryState()
        state.restoreFocus = true
        state.selectMainTab("精选")
        assertFalse(state.restoreFocus)
        state.selectMainTab("精选")
        assertEquals("精选", state.tab)
    }

    @Test fun toolbarActionsAreNotContentTabs() {
        val state = TvLibraryState()
        for (action in listOf("搜索", "记录", "投屏", "央视点播")) {
            state.selectMainTab(action)
            assertEquals("热播", state.tab)
        }
        assertEquals(listOf("热播", "精选", "央视点播", "分类"), TvMainTabOptions.map { it.second })
    }
}
