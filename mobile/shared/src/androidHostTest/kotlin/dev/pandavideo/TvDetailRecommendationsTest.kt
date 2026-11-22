package dev.pandavideo

import dev.pandavideo.app.AppState
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.data.repository.CctvSource
import dev.pandavideo.tvui.TvDetailSelection
import dev.pandavideo.tvui.tvDetailRecommendations
import dev.pandavideo.tvui.tvRelatedSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvDetailRecommendationsTest {
    private val info = PlaybackInfo("当前剧", "media", "tv", pageUrl = pomoPage,
        region = "中国大陆", episodes = listOf(
            Episode("one", "第1集", 0), Episode("two", "第2集", 0)))
    private val history = TvDetailSelection(info.title, null,
        PlayerRequest(page = info.pageUrl, title = info.title, resumeHistory = true))
    private val search = history.copy(search = SearchItem(info.title, "fixture",
        detailUrl = info.pageUrl, genre = "国产剧", region = "中国大陆"))
    private val regionalFixture = """{"data":[
        {"id":"501","title":"区域推荐剧","url":"https://movie.douban.com/subject/501/",
         "rate":"9.1","cover":"","year":"2020","countries":["中国大陆"],"genres":["剧情"]},
        {"id":"502","title":"低分剧","url":"https://movie.douban.com/subject/502/",
         "rate":"7.9","cover":"","year":"2020","countries":["中国大陆"],"genres":["剧情"]}
    ]}"""

    @Test fun searchAndHistoryLoadRegionalRecommendationsWithoutAnOriginatingList() = runTest {
        for (selection in listOf(search, history)) {
            val platform = AppStateTest.TestPlatform(this).apply { browseCatalog = regionalFixture }
            val model = AppState(platform, backgroundScope,
                resolveDispatcher = StandardTestDispatcher(testScheduler))
            try {
                val snapshots = mutableListOf<List<CatalogItem>>()
                val related = tvDetailRecommendations(model, selection, info, emptyList()) {
                    snapshots += it
                }
                assertEquals(listOf("区域推荐剧"), related.map { it.title })
                assertTrue(snapshots.any { it == related })
                assertTrue(platform.hotRequests.any { "中国大陆" in it.first && "电视剧" in it.first })
                assertNull(model.session)
                assertTrue(platform.players.isEmpty())
                assertTrue(model.user.history.value.isEmpty())
                assertEquals("all", model.filters.country)
                val next = tvRelatedSelection(related.single(), selection.recommendations)
                assertNull(next.recommendations, "Following a recommendation must retain automatic loading")
            } finally { model.close() }
        }
    }

    @Test fun originatingListsAndCctvKeepTheirOwnRecommendations() = runTest {
        val platform = AppStateTest.TestPlatform(this)
        val model = AppState(platform, backgroundScope,
            resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            val original = listOf(CatalogItem("筛选内影片", "subject:1"))
            val before = platform.hotRequests.toList()
            assertEquals(original, tvDetailRecommendations(model,
                history.copy(recommendations = original), info, emptyList()))
            assertEquals(emptyList(), tvDetailRecommendations(model,
                history.copy(recommendations = emptyList()), info, original))
            assertEquals(emptyList(), tvDetailRecommendations(model,
                history.copy(request = PlayerRequest(page = CctvSource.samplePage)), info, original))
            assertEquals(before, platform.hotRequests)
            assertEquals(original, tvRelatedSelection(original.single(), original).recommendations)
        } finally { model.close() }
    }

    @Test fun unknownRegionDoesNotBorrowGlobalSuggestions() = runTest {
        val platform = AppStateTest.TestPlatform(this)
        val model = AppState(platform, backgroundScope,
            resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            val candidate = CatalogItem("中国高分剧", "subject:1", rating = 9.0,
                kind = "tv", countries = listOf("中国大陆"))
            assertTrue(tvDetailRecommendations(model, history, info.copy(region = ""),
                listOf(candidate)).isEmpty())
            assertTrue(platform.hotRequests.isEmpty())
        } finally { model.close() }
    }

    @Test fun verifiedCatalogMetadataEnablesHistoryRecommendationsWhenProviderRegionIsMissing() = runTest {
        val platform = AppStateTest.TestPlatform(this).apply { browseCatalog = regionalFixture }
        val model = AppState(platform, backgroundScope,
            resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            val verified = CatalogItem(info.title, "https://movie.douban.com/subject/500/",
                year = "2024", kind = "tv", countries = listOf("中国大陆"))
            val related = tvDetailRecommendations(model, history.copy(catalog = verified),
                info.copy(region = ""), emptyList())
            assertEquals(listOf("区域推荐剧"), related.map { it.title })
        } finally { model.close() }
    }

    @Test fun leavingDetailCancelsTheRecommendationRequest() = runTest {
        val platform = AppStateTest.TestPlatform(this).apply {
            browseCatalog = regionalFixture
            browseDelay = 5_000
        }
        val model = AppState(platform, backgroundScope,
            resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            val snapshots = mutableListOf<List<CatalogItem>>()
            val loading = async { tvDetailRecommendations(model, history, info, emptyList()) {
                snapshots += it
            } }
            runCurrent()
            loading.cancelAndJoin()
            val before = snapshots.toList()
            advanceTimeBy(6_000)
            runCurrent()
            assertTrue(loading.isCancelled)
            assertEquals(before, snapshots)
        } finally { model.close() }
    }
}
