package dev.pandavideo

import dev.pandavideo.app.BottomTabs
import dev.pandavideo.app.BrowseGenreOptions
import dev.pandavideo.app.BrowseSortOptions
import dev.pandavideo.app.BrowseTypeOptions
import dev.pandavideo.app.HomeCategoryOptions
import dev.pandavideo.app.HotTypeOptions
import dev.pandavideo.app.browseYearOptions
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.player.PlaybackRates
import dev.pandavideo.ui.catalog.annualTvFilters
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.data.user.DefaultTabOrder
import kotlin.test.*

/** Guards the bottom-tab swap and the filter option lists shared by the catalog screens. */
class NavigationConfigTest {
    @Test fun bottomTabsFollowHotDiscoverBrowseProfileOrder() {
        assertEquals(
            listOf(
                "hot" to "最近热播",
                "discover" to "发现",
                "browse" to "分类",
                "profile" to "我的",
            ),
            BottomTabs,
        )
    }

    @Test fun bottomTabIdsAndLabelsAreUniqueAndEveryRouteHasAnIcon() {
        assertEquals(DefaultTabOrder, BottomTabs.map { it.first })
        assertEquals(BottomTabs.size, BottomTabs.map { it.first }.distinct().size)
        assertEquals(BottomTabs.size, BottomTabs.map { it.second }.distinct().size)
        BottomTabs.forEach { (route, label) ->
            assertTrue(label.isNotBlank())
            // NavigationIcons.forRoute throws on an unknown destination, so a returned
            // vector proves the tab route can actually be rendered.
            assertNotNull(NavigationIcons.forRoute(route))
        }
    }

    @Test fun browseFilterOptionsStayAlignedWithRepositoryKeys() {
        assertEquals(listOf("movie", "tv", "documentary", "animation"), BrowseTypeOptions.map { it.first })
        assertEquals(
            listOf(
                "all", "drama", "comedy", "action", "romance", "scifi", "mystery",
                "finance", "nature", "biography", "war", "hollywood",
            ),
            BrowseGenreOptions.map { it.first },
        )
        assertEquals(listOf("hot", "rating", "latest"), BrowseSortOptions.map { it.first })
        assertTrue(BrowseTypeOptions.all { it.second.isNotBlank() })
        assertTrue(BrowseGenreOptions.all { it.second.isNotBlank() })
        assertTrue(BrowseSortOptions.all { it.second.isNotBlank() })
    }

    @Test fun yearOptionsStartWithAllAndDescendAcrossTheFullRange() {
        val options = browseYearOptions(2026)
        assertEquals("all" to "全部年份", options.first())
        val years = options.drop(1)
        assertEquals(127, years.size)
        assertEquals("2026", years.first().first)
        assertEquals("1900", years.last().first)
        val numeric = years.map { it.first.toInt() }
        assertEquals(numeric, numeric.sortedDescending())
        assertTrue(numeric.all { it in 1900..2026 })
    }

    @Test fun annualTvPresetMatchesWebAndClearsOtherFilters() {
        assertEquals(BrowseFilters(type = "tv", year = "2026", sort = "hot"), annualTvFilters(2026))
        assertEquals("2027", annualTvFilters(2027).year)
        assertEquals("2027", browseYearOptions(2027)[1].first)
    }

    @Test fun hotAndHomeOptionsExposeStableKeys() {
        assertEquals(
            listOf(
                "tv" to "热播剧集", "movie" to "热门电影", "variety" to "热门综艺",
                "animation" to "热门动漫", "documentary" to "纪录片",
            ),
            HotTypeOptions,
        )
        assertEquals(
            listOf(
                "top250", "tv", "comedy", "anime", "nature", "movie", "documentary",
                "drama", "action", "romance", "scifi", "mystery", "finance", "biography", "war", "hollywood",
            ),
            HomeCategoryOptions.map { it.first },
        )
        assertEquals(HomeCategoryOptions.size, HomeCategoryOptions.map { it.first }.distinct().size)
    }

    @Test fun playbackRatesAreOrderedAndIncludeNormalSpeed() {
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 2f), PlaybackRates)
        assertEquals(PlaybackRates, PlaybackRates.sorted())
        assertTrue(PlaybackRates.contains(1f))
    }
}
