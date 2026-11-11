package dev.pandavideo

import dev.pandavideo.app.AppState
import dev.pandavideo.app.SearchState
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.platform.Haptics
import dev.pandavideo.core.platform.KeyValueStorage
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.core.platform.SilentHaptics
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlayerController
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateTest {
    private class TestPlatform(val testScope: TestScope) : PlatformServices {
        var slowSearch = false
        var catalogDelay = 0L
        var catalogFails = false
        var paginated = false
        var matchDelay = 0L
        var matchFails = false
        var catalogUnavailable = false
        val players = mutableListOf<TestPlayer>()
        val catalogStarts = mutableListOf<Int>()
        val hotRequests = mutableListOf<Pair<String, Int>>()
        val hosts = mutableListOf<String>()
        var volumeLevel = 0.5f
        var brightnessLevel = 0.5f
        val volumeChanges = mutableListOf<Float>()
        val brightnessChanges = mutableListOf<Float>()
        val fullscreenChanges = mutableListOf<Boolean>()
        val fullscreenBlockedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val fullscreenBlocked: Flow<Unit> = fullscreenBlockedEvents.asSharedFlow()
        val hotSorts = mutableListOf<String>()
        override fun httpClient() = HttpClient(testScope.mockEngine(backupSources = catalogUnavailable) { request ->
            hosts += request.url.host
            when (request.url.parameters["type"] ?: request.url.encodedPath) {
                "/api/v2/sys/init" -> respond("""{"code":1,"data":{"secret":"test-secret"}}""")
                "/api/v2/search/videoV2" -> respond("""{"code":1,"data":[]}""")
                "/top250" -> {
                    val start = request.url.parameters["start"]?.toInt() ?: 0
                    catalogStarts += start
                    delay(catalogDelay)
                    if (catalogFails) respond("offline", io.ktor.http.HttpStatusCode.ServiceUnavailable)
                    else if (paginated) respond((start until start + 25).joinToString("") { index ->
                        """<div class="item"><a href="https://movie.douban.com/subject/${index + 1}/"><img alt="电影$index"></a><span class="rating_num">9.0</span></div>"""
                    })
                    else respond(topFixture)
                }
                "auto_search" -> {
                    val query = request.url.parameters["wd"].orEmpty()
                    if (query == "测试电影" || Regex("(电影|电视剧|综艺|动画|纪录片).*\\d+").matches(query)) {
                        // Uncertain upstream responses preserve list tests. Absence tests use
                        // a valid empty search page to distinguish it from a site challenge.
                        respond(if (catalogUnavailable)
                            pomoSearch()
                            else "<html>captcha</html>")
                    } else if (query == "匹配影片") {
                        delay(matchDelay)
                        if (matchFails) respond("offline", io.ktor.http.HttpStatusCode.ServiceUnavailable)
                        else respond(pomoSearch(pomoRow("匹配影片")))
                    } else {
                        if (slowSearch && query == "old") delay(1000)
                        respond(pomoSearch(pomoRow(query, remark = "全2集", genre = "国产剧")))
                    }
                }
                "auto_detail" -> respond(pomoDetailFixture)
                "parse" -> respond(pomoMedia())
                "/j/new_search_subjects" -> {
                    val tags = request.url.parameters["tags"].orEmpty()
                    val start = request.url.parameters["start"]?.toInt() ?: 0
                    val sort = request.url.parameters["sort"].orEmpty()
                    assertTrue(sort in listOf("U", "S", "R"))
                    hotSorts += sort
                    hotRequests += tags to start
                    respond("""{"data":[${(start until start + 20).joinToString(",") {
                        """{"title":"$tags$it","url":"https://movie.douban.com/subject/${it + 1}/","rate":"8.0"}"""
                    }}]}""")
                }
                else -> error("Unexpected upstream path")
            }
        }) { followRedirects = false }
        override fun player(): PlayerController = TestPlayer().also { players += it }
        val storage = FakeStorage()
        override fun storage(): KeyValueStorage = storage
        override val haptics: Haptics = SilentHaptics
        override fun openLink(url: String) {}
        override fun fullscreen(enabled: Boolean) { fullscreenChanges += enabled }
        override fun currentVolume() = volumeLevel
        override fun setVolume(value: Float) {
            volumeLevel = value.coerceIn(0f, 1f); volumeChanges += volumeLevel
        }
        override fun currentBrightness() = brightnessLevel
        override fun setBrightness(value: Float) {
            brightnessLevel = value.coerceIn(0f, 1f); brightnessChanges += brightnessLevel
        }
        override fun resetBrightness() { brightnessLevel = 0.5f }
        // The JVM test harness has no picture-in-picture window; keep the fake unsupported.
        override val pipSupported = false
        override val pictureInPictureActive = MutableStateFlow(false)
        override fun enterPictureInPicture() = false
    }
    private class TestPlayer : PlayerController {
        override val state = MutableStateFlow(PlayerState())
        val loads = mutableListOf<MediaSource>()
        var closed = false
        var pauses = 0
        override fun load(source: MediaSource) {
            loads += source
            state.value = PlayerState(source.id, PlayerPhase.Playing, wantsPlay = source.autoplay)
        }
        override fun play() {}
        override fun pause() { pauses++; state.value = state.value.copy(wantsPlay = false) }
        override fun seekTo(position: Long) {}
        override fun setPlaybackRate(rate: Float) {}
        override fun stop() {}
        override fun close() { closed = true }
    }
    private val matchingItem = CatalogItem("匹配影片", "https://movie.douban.com/subject/123/", year = "2020")

    @Test fun catalogClickNavigatesBeforeMatchingCompletes() = runTest {
        val platform = TestPlatform(this).apply { matchDelay = 1000 }
        val model = AppState(platform, backgroundScope)
        var navigations = 0
        model.match(matchingItem) { navigations++ }
        assertEquals(1, navigations)
        assertEquals(matchingItem, model.playingCatalog)
        val session = assertNotNull(model.session)
        assertTrue(session.state.value.loading)
        runCurrent()
        assertTrue(platform.players.single().loads.isEmpty())
        advanceTimeBy(1000); runCurrent()
        assertFalse(session.state.value.loading)
        assertEquals("匹配影片", session.state.value.info?.title)
        assertEquals(1, platform.players.single().loads.size)
        assertEquals(1, navigations)
        model.close()
    }
    @Test fun leavingDetailsCancelsPendingCatalogMatching() = runTest {
        val platform = TestPlatform(this).apply { matchDelay = 1000 }
        val model = AppState(platform, backgroundScope)
        model.match(matchingItem) {}
        runCurrent()
        model.closePlayer()
        advanceTimeBy(2000); runCurrent()
        assertNull(model.session)
        assertNull(model.playingCatalog)
        assertTrue(platform.players.single().closed)
        assertTrue(platform.players.single().loads.isEmpty())
        model.close()
    }
    @Test fun matchingFailureRemainsInDetailsAndCanBeRetried() = runTest {
        val platform = TestPlatform(this).apply { matchFails = true }
        val model = AppState(platform, backgroundScope)
        var navigations = 0
        model.match(matchingItem) { navigations++ }
        val session = assertNotNull(model.session)
        runCurrent()
        assertSame(session, model.session)
        assertFalse(session.state.value.loading)
        assertNotNull(session.state.value.error)
        assertTrue(platform.players.single().loads.isEmpty())
        platform.matchFails = false
        session.retry()
        assertTrue(session.state.value.loading)
        runCurrent()
        assertNull(session.state.value.error)
        assertEquals(1, platform.players.single().loads.size)
        assertEquals(1, navigations)
        model.close()
    }
    @Test fun freshInstallLoadsWithoutServerConfiguration() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        assertEquals("测试电影", model.discover.value.items.single().title)
        assertEquals("movie.douban.com", platform.hosts.first())
        assertEquals("pomo.mom", platform.hosts.last())
        model.search("new", "tv")
        runCurrent()
        assertEquals("new", model.search.value.result?.results?.single()?.title)
        assertEquals("pomo.mom", platform.hosts.last())
        model.close()
    }
    @Test fun confirmedMissingIsRemovedFromAllCatalogsAndCursorIsPreserved() = runTest {
        val platform = TestPlatform(this).apply { catalogUnavailable = true }
        val model = AppState(platform, backgroundScope)
        runCurrent()
        assertTrue(model.discover.value.items.isEmpty())
        assertEquals(25, model.discover.value.page?.nextStart())
        assertNull(model.discover.value.error)
        model.loadBrowse()
        model.loadHot()
        runCurrent()
        assertTrue(model.browse.value.items.isEmpty())
        assertTrue(model.hot.value.items.isEmpty())
        assertTrue(model.browse.value.page?.hasMore == true)
        assertEquals(20, model.hot.value.page?.nextStart())
        model.close()
    }
    @Test fun newSearchCancelsOldResults() = runTest {
        val platform = TestPlatform(this).apply { slowSearch = true }
        val model = AppState(platform, backgroundScope)
        model.search("old", "tv")
        runCurrent()
        model.search("new", "tv")
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        assertEquals("new", model.search.value.result?.results?.single()?.title)
        model.close()
    }
    @Test fun blankSearchCancelsPendingWork() = runTest {
        val platform = TestPlatform(this).apply { slowSearch = true }
        val model = AppState(platform, backgroundScope)
        model.search("old", "all")
        runCurrent()
        model.search("", "all")
        advanceTimeBy(1500)
        runCurrent()
        assertNull(model.search.value.result)
        assertEquals("请输入影视名称", model.search.value.error)
        model.close()
    }
    @Test fun paginationRequestsOnceAndRefreshReplacesLoadedPages() = runTest {
        val platform = TestPlatform(this).apply { paginated = true }
        val model = AppState(platform, backgroundScope)
        runCurrent()
        assertEquals(25, model.discover.value.items.size)
        model.loadDiscover(more = true); model.loadDiscover(more = true)
        assertTrue(model.discover.value.loadingMore)
        runCurrent()
        assertEquals(listOf(0, 25), platform.catalogStarts)
        assertEquals(50, model.discover.value.items.size)
        platform.catalogDelay = 1000
        model.loadDiscover()
        assertEquals(50, model.discover.value.items.size)
        assertFalse(model.discover.value.loadingMore)
        advanceTimeBy(1000); runCurrent()
        assertEquals(25, model.discover.value.items.size)
        assertEquals(0, model.discover.value.page?.start)
        assertFalse(model.discover.value.loading)
        model.close()
    }
    @Test fun failedRefreshKeepsCachedCatalogVisible() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val previous = model.discover.value.items
        platform.catalogFails = true
        model.loadDiscover()
        assertEquals(previous, model.discover.value.items)
        runCurrent()
        assertEquals(previous, model.discover.value.items)
        assertTrue(model.discover.value.page?.stale == true)
        assertFalse(model.discover.value.loading)
        model.close()
    }
    @Test fun hotCatalogPagesAndTypeSwitchRemainIndependentOfHomeAndBrowse() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val home = model.discover.value
        model.loadHot()
        runCurrent()
        assertEquals("电视剧" to 0, platform.hotRequests.single())
        assertEquals(20, model.hot.value.items.size)
        model.loadHot(more = true); model.loadHot(more = true)
        runCurrent()
        assertEquals(40, model.hot.value.items.size)
        assertEquals(listOf("电视剧" to 0, "电视剧" to 20), platform.hotRequests)
        model.loadHot("movie")
        runCurrent()
        assertEquals("电影" to 0, platform.hotRequests.last())
        assertEquals(20, model.hot.value.items.size)
        assertEquals("最近热播 · 电影", model.hot.value.page?.label)
        assertEquals(home, model.discover.value)
        assertNull(model.browse.value.page)
        assertTrue(platform.hotSorts.all { it == "U" })
        model.close()
    }
    @Test fun clearingHomeSearchCancelsPendingResults() = runTest {
        val platform = TestPlatform(this).apply { slowSearch = true }
        val model = AppState(platform, backgroundScope)
        model.search("old", "all")
        runCurrent()
        model.clearSearch()
        advanceTimeBy(1500); runCurrent()
        assertEquals(SearchState(), model.search.value)
        model.close()
    }
    @Test fun loadHotRejectsUnknownTypeWithoutTouchingNetwork() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        model.loadHot("unknown")
        runCurrent()
        assertTrue(model.hot.value.items.isEmpty())
        assertNull(model.hot.value.page)
        assertTrue(platform.hotRequests.isEmpty())
        model.close()
    }
    @Test fun addedHotCategoriesUseTheirOwnTagsAndResetPaging() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val home = model.discover.value
        for ((type, tag, label) in listOf(
            Triple("variety", "综艺", "综艺"),
            Triple("animation", "动画", "动漫"),
            Triple("documentary", "纪录片", "纪录片"),
        )) {
            model.loadHot(type)
            runCurrent()
            assertEquals(tag to 0, platform.hotRequests.last())
            assertEquals("最近热播 · $label", model.hot.value.page?.label)
            assertEquals(20, model.hot.value.items.size)
            assertTrue(model.hot.value.items.all { it.kind == type })
            model.loadHot(more = true)
            runCurrent()
            assertEquals(tag to 20, platform.hotRequests.last())
            assertEquals(40, model.hot.value.items.size)
        }
        assertEquals(home, model.discover.value)
        assertNull(model.browse.value.page)
        assertTrue(platform.hotSorts.all { it == "U" })
        model.close()
    }
    @Test fun closePlayerRestoresBrightnessAndLeavesFullscreen() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        model.match(matchingItem) {}
        runCurrent()
        assertNotNull(model.session)
        platform.setBrightness(0.2f)
        model.closePlayer()
        assertNull(model.session)
        assertNull(model.playingCatalog)
        assertEquals(0.5f, platform.brightnessLevel)
        assertEquals(false, platform.fullscreenChanges.last())
        model.close()
    }
    @Test fun minimizePlayerPausesAndKeepsSessionForReopening() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        model.match(matchingItem) {}
        runCurrent()
        val session = assertNotNull(model.session)
        platform.fullscreen(true)
        platform.setBrightness(0.2f)
        model.minimizePlayer()
        // The mini bar keeps the same session and title context; the player is not released.
        assertSame(session, model.session)
        assertSame(matchingItem, model.playingCatalog)
        assertFalse(platform.players.single().closed)
        assertFalse(platform.players.single().state.value.wantsPlay)
        assertEquals(1, platform.players.single().pauses)
        assertEquals(0.5f, platform.brightnessLevel)
        assertEquals(false, platform.fullscreenChanges.last())
        model.close()
    }
    @Test fun leavingPageWithVisiblePictureInPictureKeepsPlayback() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        model.match(matchingItem) {}
        runCurrent()
        platform.pictureInPictureActive.value = true
        model.minimizePlayer()
        assertTrue(platform.players.single().state.value.wantsPlay)
        assertEquals(0, platform.players.single().pauses)
        platform.pictureInPictureActive.value = false
        model.minimizePlayer()
        assertFalse(platform.players.single().state.value.wantsPlay)
        model.close()
    }
    @Test fun sleepTimerPausesMinimizedPlaybackAndCanBeCancelled() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        model.match(matchingItem) {}
        runCurrent()
        model.setSleepTimer(1)
        model.minimizePlayer()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, platform.players.single().pauses)
        assertEquals(0, model.sleepRemaining.value)
        assertNotNull(model.session)
        model.setSleepTimer(1)
        model.setSleepTimer(0)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, platform.players.single().pauses)
        model.close()
    }
    @Test fun rememberedLevelsSurviveMinimizeAndExplicitClose() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        model.settings.setRememberBrightness(true, 0.3f)
        model.settings.setRememberVolume(true, 0.7f)
        model.match(matchingItem) {}
        runCurrent()
        model.restorePlayerLevels()
        assertEquals(0.3f, platform.brightnessLevel)
        assertEquals(0.7f, platform.volumeLevel)
        model.minimizePlayer()
        assertEquals(0.5f, platform.brightnessLevel)
        model.setSleepTimer(1)
        model.closePlayer()
        assertEquals(0.3f, model.settings.settings.value.brightness)
        assertEquals(0, model.sleepRemaining.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, platform.players.single().pauses)
        model.close()
    }
    @Test fun posterKeepsOnlyWebUrls() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        assertEquals(
            "https://image/poster.jpg",
            model.poster(CatalogItem("影片", "https://x/1", cover = "https://image/poster.jpg")),
        )
        assertNull(model.poster(CatalogItem("影片", "https://x/2", cover = "file:///local.png")))
        assertEquals(
            "https://image/thumb.jpg",
            model.poster(SearchItem("影片", "阳光电影", thumbnailUrl = "https://image/thumb.jpg")),
        )
        assertNull(model.poster(SearchItem("影片", "阳光电影", thumbnailUrl = "not-a-url")))
        model.close()
    }
    @Test fun coverForPrefersStoredSnapshotAndBackfillsFromCatalogPool() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        // The home rail cached the same title that the favorite/history record lacks artwork for.
        assertEquals("https://image/poster.jpg", model.coverFor("测试电影", null))
        // A valid persisted snapshot always wins over the pool.
        assertEquals("https://img/saved.jpg", model.coverFor("测试电影", "https://img/saved.jpg"))
        // Invalid snapshots are discarded and the pool supplies the poster.
        assertEquals("https://image/poster.jpg", model.coverFor("测试电影", "not-a-url"))
        // Unknown titles without a snapshot stay empty instead of showing a wrong poster.
        assertNull(model.coverFor("不存在的影片", null))
        assertNull(model.coverFor(null, null))
        model.close()
    }
    @Test fun toggleFavoriteWithoutCoverBackfillsFromCatalogPool() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val page = "https://huolot.com/voddetail/8.html"
        assertTrue(model.toggleFavorite("测试电影", page, null, "2020"))
        assertEquals("https://image/poster.jpg", model.user.favorites.value.single().cover)
        model.close()
    }
    @Test fun fullscreenBlockedByOsEmitsOrientationLockHint() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val received = mutableListOf<String>()
        val collectJob = backgroundScope.launch { model.userMessages.collect { received += it } }
        runCurrent()
        platform.fullscreenBlockedEvents.tryEmit(Unit)
        runCurrent()
        assertEquals(1, received.size)
        assertTrue(received.first().contains("竖排方向锁定"))
        collectJob.cancel()
        model.close()
    }
    @Test fun blankSearchReportsValidationErrorWithoutCallingSource() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val hostsBefore = platform.hosts.toList()
        model.search("   ", "all")
        runCurrent()
        assertEquals("请输入影视名称", model.search.value.error)
        assertNull(model.search.value.result)
        assertEquals(hostsBefore, platform.hosts)
        model.close()
    }
    @Test fun browseFiltersAreSentUpstreamAndExposeTwentyItems() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        model.loadBrowse(BrowseFilters(type = "movie", genre = "action", year = "2021", sort = "rating"))
        runCurrent()
        assertEquals("电影,动作" to 0, platform.hotRequests.single())
        assertEquals("S", platform.hotSorts.single())
        assertEquals(20, model.browse.value.items.size)
        assertFalse(model.browse.value.loading)
        assertNull(model.browse.value.error)
        model.close()
    }
    @Test fun reopenSavedResumesTheUnfinishedEpisodeAndKeepsResumeLabel() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val page = pomoPage
        model.rememberPlayed("测试电影", page, null, "2020", "第 2 集", "$page#1-1")
        model.saveProgress(page, "$page#1-1", "第 2 集", 120_000, 600_000)
        val record = model.user.history.value.single()
        assertEquals("看到 第 2 集 · 2:00", model.resumeLabel(record))
        model.reopenSaved(page, "测试电影")
        runCurrent()
        assertEquals("$page#1-1", model.session?.state?.value?.info?.selected)
        model.close()
    }
    @Test fun reopenSavedExposesTheHistoryCoverForTheMiniPlayer() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val page = pomoPage
        val cover = "https://img.example.com/poster.jpg"
        model.rememberPlayed("测试电影", page, cover, "2020", "第 2 集", "$page#1-1")
        model.reopenSaved(page, "测试电影", cover)
        runCurrent()
        assertEquals(cover, model.playingCover)
        assertEquals(cover, platform.players.single().loads.single().artworkUrl)
        model.closePlayer()
        assertNull(model.playingCover)
        model.close()
    }
    @Test fun finishedEpisodeHasNoResumeLabelAndReopensFromStart() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val page = pomoPage
        model.rememberPlayed("测试电影", page, null, "2020", "第 2 集", "$page#1-1")
        model.saveProgress(page, "$page#1-1", "第 2 集", 590_000, 600_000)
        assertNull(model.resumeLabel(model.user.history.value.single()))
        model.reopenSaved(page, "测试电影")
        runCurrent()
        // No episode hint is sent, so the source default (first episode) is selected.
        assertEquals("$page#1-0", model.session?.state?.value?.info?.selected)
        model.close()
    }
    @Test fun themePreferencePersistsAcrossAppStateInstances() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        assertEquals(dev.pandavideo.data.user.ThemeMode.System, model.settings.settings.value.themeMode)
        model.settings.setThemeMode(dev.pandavideo.data.user.ThemeMode.Dark)
        val reopened = AppState(platform, backgroundScope)
        assertEquals(dev.pandavideo.data.user.ThemeMode.Dark, reopened.settings.settings.value.themeMode)
        model.close(); reopened.close()
    }
    @Test fun favoriteToggleEmitsOneShotChineseMessages() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        val messages = mutableListOf<String>()
        val job = backgroundScope.launch { model.userMessages.collect { messages += it } }
        runCurrent()
        val page = "https://huolot.com/voddetail/9.html"
        assertTrue(model.toggleFavorite("测试影片", page, null, "2024"))
        runCurrent()
        assertEquals(listOf("已加入收藏"), messages)
        assertFalse(model.toggleFavorite("测试影片", page, null, "2024"))
        runCurrent()
        assertEquals(listOf("已加入收藏", "已取消收藏"), messages)
        job.cancel()
        model.close()
    }
}
