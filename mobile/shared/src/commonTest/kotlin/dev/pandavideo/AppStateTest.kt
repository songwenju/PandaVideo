package dev.pandavideo

import dev.pandavideo.core.model.PlayerRequest

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
import dev.pandavideo.data.repository.OfflineEngine
import dev.pandavideo.data.repository.OfflineAsset
import dev.pandavideo.data.repository.UnsupportedOfflineEngine
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
    private class TestPlatform(
        val testScope: TestScope,
        private val offline: OfflineEngine = UnsupportedOfflineEngine,
    ) : PlatformServices {
        var slowSearch = false
        var catalogDelay = 0L
        var browseDelay = 0L
        var catalogFails = false
        var paginated = false
        var matchDelay = 0L
        var matchFails = false
        var detailFixture = pomoDetailFixture
        var catalogUnavailable = false
        var networkUnavailable = false
        var actorSuggestions = """{"data":{"templates":[{"template":105,"albumInfo":{"qipuId":208582705,"title":"朱一龙","img":"https://pic8.iqiyipic.com/avatar.jpg"}}]}}"""
        val players = mutableListOf<TestPlayer>()
        val catalogStarts = mutableListOf<Int>()
        val hotRequests = mutableListOf<Pair<String, Int>>()
        val hosts = mutableListOf<String>()
        val openedLinks = mutableListOf<String>()
        var nativeSketch = false
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
            if (networkUnavailable) return@mockEngine respond("offline", io.ktor.http.HttpStatusCode.ServiceUnavailable)
            if (request.url.host in setOf("www.xiaopin.tv", "www.dieyin.cn")) {
                if (nativeSketch && request.url.encodedPath == "/video/407962.html")
                    return@mockEngine respond(biliEmbed)
                return@mockEngine respond(if (request.url.host == "www.xiaopin.tv")
                    sketchFixture() else dieyinSketchFixture())
            }
            if (request.url.host == "api.bilibili.com")
                return@mockEngine respond(if (request.url.encodedPath.endsWith("/view")) biliView else biliMedia())
            // Actor identity lookup uses the public iQIYI person template.
            if (request.url.host == "mesh.if.iqiyi.com" && request.url.encodedPath.endsWith("/search/homePageV3")) {
                return@mockEngine respond(actorSuggestions)
            }
            when (request.url.parameters["type"] ?: request.url.encodedPath) {
                "/api/v2/sys/init" -> respond("""{"code":1,"data":{"secret":"test-secret"}}""")
                "/api/v2/search/videoV2" -> respond("""{"code":1,"data":[]}""")
                "/top250" -> {
                    val start = request.url.parameters["start"]?.toInt() ?: 0
                    catalogStarts += start
                    delay(catalogDelay)
                    if (catalogFails) respond("offline", io.ktor.http.HttpStatusCode.ServiceUnavailable)
                    else if (paginated) respond((start until start + 25).joinToString("") { index ->
                        """<div class="item"><a href="https://movie.douban.com/subject/${index + 1}/"><img alt="电影$index"></a><p>导演<br>2020 / 中国大陆 日本 / 剧情 战争</p><span class="rating_num">9.0</span></div>"""
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
                "auto_detail" -> respond(detailFixture)
                "parse" -> respond(pomoMedia())
                "/j/new_search_subjects" -> {
                    delay(browseDelay)
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
        override fun offlineEngine(): OfflineEngine = offline
        override val haptics: Haptics = SilentHaptics
        override fun openLink(url: String) { openedLinks += url }
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

    @Test fun cachedHotListEndsLoadingWhileRevalidationIsPending() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            // Warm the same source key through Browse, then enter an empty Hot screen.
            model.loadBrowse(BrowseFilters(type = "tv", sort = "hot"))
            runCurrent()
            val expected = model.browse.value.items
            assertEquals(20, expected.size)
            platform.browseDelay = 1_000
            model.loadHot()
            runCurrent()
            assertEquals(expected, model.hot.value.items)
            assertFalse(model.hot.value.loading)
            assertFalse(model.hot.value.refreshing)
            assertTrue(model.hot.value.page!!.stale)
            assertEquals("最近热播 · 剧集", model.hot.value.page!!.label)
            advanceTimeBy(1_000); runCurrent()
            assertFalse(model.hot.value.page!!.stale)
            model.loadHot(refresh = true)
            runCurrent()
            assertEquals(expected, model.hot.value.items)
            assertTrue(model.hot.value.refreshing)
            advanceTimeBy(1_000); runCurrent()
            assertFalse(model.hot.value.refreshing)
        } finally { model.close() }
    }

    @Test fun neighborPreloadDoesNotSelectAndIsAdoptedWithoutReload() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        runCurrent()
        val original = model.discover.value
        val preview = model.discoverPage("tv", "all")
        runCurrent()
        assertEquals("top250", model.kind)
        assertSame(original, model.discover.value)
        assertTrue(preview.value.items.isNotEmpty())
        val requests = platform.hotRequests.size
        model.loadDiscover("tv")
        runCurrent()
        assertEquals("tv", model.kind)
        assertSame(preview.value.items, model.discover.value.items)
        assertEquals(requests, platform.hotRequests.size)
        model.loadDiscover("top250")
        runCurrent()
        assertSame(original.items, model.discover.value.items)
        model.close()
    }

    @Test fun browseAndHotPreviewsKeepActiveFiltersAndFailuresSilent() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope)
        model.loadBrowse(BrowseFilters(country = "cn"))
        model.loadHot("tv")
        runCurrent()
        val original = model.filters
        val browse = model.browsePage(original.copy(type = "tv"))
        val hot = model.hotPage("movie")
        runCurrent()
        assertEquals(original, model.filters)
        assertEquals("tv", model.hotType)
        assertTrue(browse.value.items.isNotEmpty())
        assertTrue(hot.value.items.isNotEmpty())
        val requests = platform.hotRequests.size
        model.loadBrowse(original.copy(type = "tv"))
        model.loadHot("movie")
        runCurrent()
        assertSame(browse.value.items, model.browse.value.items)
        assertSame(hot.value.items, model.hot.value.items)
        assertEquals(requests, platform.hotRequests.size)
        platform.networkUnavailable = true
        val failure = model.browsePage(original.copy(type = "animation"))
        runCurrent()
        assertNotNull(failure.value.error)
        assertTrue(model.retryPrompts.pending.value.isEmpty())
        model.close()
    }

    @Test fun failedCatalogRetryPreservesFiltersAndReplacementClearsOldPrompt() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        platform.networkUnavailable = true
        val filters = BrowseFilters(type = "movie", genre = "action", year = "2021", sort = "rating", country = "cn")
        model.loadBrowse(filters)
        advanceTimeBy(30_000); runCurrent()
        val prompt = model.retryPrompts.pending.value.single { it.key == "browse" }
        assertEquals("browse", prompt.destination)
        assertTrue(prompt.message.contains("HTTP 503"), "The dialog must retain the upstream failure")
        assertNotNull(model.browse.value.error)
        platform.networkUnavailable = false
        model.retryPrompts.retry(prompt.id)
        runCurrent()
        assertEquals(filters, model.filters)
        assertTrue(model.browse.value.items.isNotEmpty())
        assertNull(model.browse.value.error)
        assertTrue(model.retryPrompts.pending.value.none { it.key == "browse" })
        // A different selection supersedes any failed request; its old action cannot reload it.
        platform.networkUnavailable = true
        model.loadBrowse(filters.copy(genre = "war"))
        advanceTimeBy(30_000); runCurrent()
        val old = model.retryPrompts.pending.value.single { it.key == "browse" }
        platform.networkUnavailable = false
        model.loadBrowse(filters.copy(type = "tv"))
        model.retryPrompts.retry(old.id)
        runCurrent()
        assertEquals("tv", model.filters.type)
        model.close()
    }

    @Test fun failedSearchRetryKeepsKeywordAndClearSearchDiscardsItsAction() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        platform.networkUnavailable = true
        model.search("专门重试的影片", "tv")
        advanceTimeBy(30_000); runCurrent()
        val prompt = model.retryPrompts.pending.value.single { it.key == "search" }
        platform.networkUnavailable = false
        model.retryPrompts.retry(prompt.id)
        runCurrent()
        assertEquals("专门重试的影片", model.search.value.result?.results?.single()?.title)
        assertNull(model.search.value.error)
        platform.networkUnavailable = true
        model.search("另一个失败的关键词", "all")
        advanceTimeBy(30_000); runCurrent()
        val old = model.retryPrompts.pending.value.single { it.key == "search" }
        model.clearSearch()
        model.retryPrompts.retry(old.id)
        assertEquals(SearchState(), model.search.value)
        assertTrue(model.retryPrompts.pending.value.none { it.key == "search" })
        model.close()
    }

    @Test fun failedPlaybackRetryKeepsTheTitleAndClosingInvalidatesIt() = runTest {
        val platform = TestPlatform(this).apply { matchFails = true }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.match(matchingItem) {}
        advanceTimeBy(30_000); runCurrent()
        val prompt = model.retryPrompts.pending.value.single { it.key == "player" }
        val session = assertNotNull(model.session)
        assertNotNull(session.state.value.error)
        assertEquals(matchingItem, model.playingCatalog)
        assertTrue(prompt.message.contains("HTTP 503"))
        assertTrue(prompt.message.startsWith("具体原因："))
        platform.matchFails = false
        model.retryPrompts.retry(prompt.id)
        runCurrent()
        assertSame(session, model.session)
        assertNotNull(session.state.value.info)
        assertNull(session.state.value.error)
        model.closePlayer()
        model.retryPrompts.retry(prompt.id)
        assertNull(model.session)
        assertTrue(model.retryPrompts.pending.value.none { it.key == "player" })
        model.close()
    }

    @Test fun discoverRetainsCountryForCategoryRefreshAndPagingButResetsOnRegionChange() = runTest {
        val platform = TestPlatform(this).apply { paginated = true }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        model.loadDiscover("war", country = "cn")
        runCurrent()
        assertEquals("cn", model.discoverCountry)
        assertEquals("电影,战争,中国大陆" to 0, platform.hotRequests.last())
        assertEquals(20, model.discover.value.page?.nextStart())
        assertEquals(0, platform.hotRequests.last().second)
        model.loadDiscover(more = true)
        runCurrent()
        assertEquals("cn", model.discoverCountry)
        assertEquals(40, model.discover.value.items.size)
        assertEquals(40, model.discover.value.page?.nextStart())
        model.loadDiscover("drama")
        runCurrent()
        assertEquals("电影,剧情,中国大陆" to 0, platform.hotRequests.last())
        assertEquals(20, model.discover.value.items.size)
        val requests = platform.hotRequests.size
        model.loadDiscover()
        runCurrent()
        assertEquals(requests, platform.hotRequests.size)
        model.loadDiscover(country = "jp")
        assertTrue(model.discover.value.items.isEmpty())
        runCurrent()
        assertEquals("jp", model.discoverCountry)
        assertEquals("电影,剧情,日本" to 0, platform.hotRequests.last())
        assertEquals(20, model.discover.value.items.size)
        model.close()
    }

    @Test fun relatedSelectionKeepsOriginalFilterSnapshotAndSearchClearsIt() = runTest {
        val model = AppState(TestPlatform(this), backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        val related = matchingItem.copy(title = "同筛选影片", url = "https://movie.douban.com/subject/124/")
        val filtered = mutableListOf(matchingItem, related)
        model.match(matchingItem, filtered) {}
        filtered.clear()
        assertEquals(listOf(matchingItem, related), model.playingRecommendations)
        model.match(related) {}
        assertEquals(listOf(matchingItem, related), model.playingRecommendations)
        model.startPlayer(dev.pandavideo.core.model.PlayerRequest(page = pomoPage, title = "搜索影片"))
        assertNull(model.playingRecommendations)
        model.close()
    }

    @Test fun catalogClickNavigatesBeforeMatchingCompletes() = runTest {
        val platform = TestPlatform(this).apply { matchDelay = 1000 }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals("测试电影", model.discover.value.items.single().title)
        assertEquals("movie.douban.com", platform.hosts.first())
        assertEquals("pomo.mom", platform.hosts.last())
        model.search("new", "tv")
        runCurrent()
        assertEquals("new", model.search.value.result?.results?.single()?.title)
        assertEquals("Pomo · 西瓜", model.search.value.result?.results?.single()?.source)
        model.close()
    }
    @Test fun actorFilmographyRetainsConfirmedPlayingCreditWhenProviderIsUnavailable() = runTest {
        val platform = TestPlatform(this).apply {
            actorSuggestions = """{"data":{"templates":[{"template":105,
                "albumInfo":{"qipuId":214761605,"title":"倪大红"}}]}}"""
        }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            val search = SearchItem("都挺好", "Pomo", year = "2019", cast = "姚晨,倪大红,郭京飞")
            model.startPlayer(PlayerRequest(page = pomoPage, title = "都挺好"), search = search)
            runCurrent()
            val session = assertNotNull(model.session)
            assertNotNull(session.state.value.info)
            assertNotNull(model.findCastActor("倪大红"))
            platform.networkUnavailable = true
            val before = platform.players.single().state.value
            val snapshot = model.actorWorks(dev.pandavideo.core.model.ActorRef("iqiyi:214761605", "倪大红"))
            assertEquals("都挺好", snapshot.works.single().title)
            assertEquals(pomoPage, snapshot.works.single().catalog().url)
            assertTrue(snapshot.stale)
            assertSame(session, model.session)
            assertEquals(before, platform.players.single().state.value)
            assertFails { model.actorWorks(dev.pandavideo.core.model.ActorRef("iqiyi:other", "倪大红")) }
            assertFails { model.actorWorks(dev.pandavideo.core.model.ActorRef("iqiyi:unknown", "其他演员")) }
        } finally { model.close() }
    }

    @Test fun actorPlaybackUsesVerifiedPageWithoutRepeatingSourceSearch() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            var opened = false
            val item = CatalogItem("都挺好", pomoPage, "2019", kind = "tv")
            model.match(item, recommendations = listOf(item)) { opened = true }
            runCurrent()
            assertTrue(opened)
            assertNotNull(model.session?.state?.value?.info)
            assertFalse("api.jianpianapp.com" in platform.hosts)
            assertEquals(listOf(item), model.playingRecommendations)
        } finally { model.close() }
    }

    @Test fun castIdentityUsesIqiyiAvatarWithoutCrossSourceRequests() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.startPlayer(dev.pandavideo.core.model.PlayerRequest(page = "https://pomo.mom/voddetail/1.html"))
        runCurrent()
        val session = assertNotNull(model.session)
        val player = platform.players.single()
        val actor = assertNotNull(model.findCastActor("朱一龙"))
        assertEquals("iqiyi:208582705", actor.id)
        assertEquals("朱一龙", actor.name)
        assertEquals("https://pic8.iqiyipic.com/avatar.jpg", actor.avatar)
        // The actor panel now fetches profiles while the current player remains visible.
        // Neither a playing nor a paused session may be replaced or manipulated by that fetch.
        for (paused in listOf(false, true)) {
            if (paused) player.pause()
            runCurrent()
            val before = player.state.value
            val loadCount = player.loads.size
            assertEquals(actor.id, model.actorProfile(actor).id)
            runCurrent()
            assertSame(session, model.session)
            assertEquals(before, player.state.value)
            assertEquals(loadCount, player.loads.size)
        }
        model.close()
    }
    @Test fun ambiguousCastNamesStayUnresolvedInsteadOfMergingPeople() = runTest {
        val platform = TestPlatform(this).apply {
            actorSuggestions = """{"data":{"templates":[
                {"template":105,"albumInfo":{"qipuId":1,"title":"张三"}},
                {"template":105,"albumInfo":{"qipuId":2,"title":"张三"}}
            ]}}"""
        }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        assertNull(model.findCastActor("张三"))
        model.close()
    }
    @Test fun confirmedMissingIsRemovedFromAllCatalogsAndCursorIsPreserved() = runTest {
        val platform = TestPlatform(this).apply { catalogUnavailable = true }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(25, model.discover.value.items.size)
        model.loadDiscover(more = true); model.loadDiscover(more = true)
        assertTrue(model.discover.value.loadingMore)
        runCurrent()
        assertEquals(listOf(0, 25), platform.catalogStarts)
        assertEquals(50, model.discover.value.items.size)
        platform.catalogDelay = 1000
        model.loadDiscover(refresh = true)
        assertEquals(50, model.discover.value.items.size)
        assertFalse(model.discover.value.loadingMore)
        assertTrue(model.discover.value.refreshing)
        advanceTimeBy(1000); runCurrent()
        assertEquals(25, model.discover.value.items.size)
        assertEquals(0, model.discover.value.page?.start)
        assertFalse(model.discover.value.loading)
        assertFalse(model.discover.value.refreshing)
        model.close()
    }
    @Test fun repeatedSameSelectionReusesPublishedCatalogWithoutReloading() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val loaded = model.discover.value
        assertEquals(listOf(0), platform.catalogStarts)
        // Re-entering the tab or tapping the active category keeps the same published page.
        model.loadDiscover()
        model.loadDiscover("top250", country = "all")
        assertSame(loaded, model.discover.value)
        assertFalse(model.discover.value.loading)
        runCurrent()
        assertEquals(listOf(0), platform.catalogStarts)
        model.close()
    }
    @Test fun refreshWithIdenticalRowsKeepsExistingItemIdentity() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val rows = model.discover.value.items
        assertTrue(rows.isNotEmpty())
        model.loadDiscover(refresh = true)
        runCurrent()
        // Identical upstream content must not rebuild every card and its decoded poster.
        assertSame(rows, model.discover.value.items)
        assertEquals(listOf(0, 0), platform.catalogStarts)
        assertFalse(model.discover.value.refreshing)
        model.close()
    }
    @Test fun failedRefreshKeepsCachedCatalogVisible() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val previous = model.discover.value.items
        platform.catalogFails = true
        model.loadDiscover(refresh = true)
        assertTrue(model.discover.value.refreshing)
        assertEquals(previous, model.discover.value.items)
        runCurrent()
        assertEquals(previous, model.discover.value.items)
        assertTrue(model.discover.value.page?.stale == true)
        assertFalse(model.discover.value.loading)
        assertFalse(model.discover.value.refreshing)
        model.close()
    }
    @Test fun tabLoadsAndCancelledRefreshesDoNotShowPullIndicator() = runTest {
        val platform = TestPlatform(this).apply { catalogDelay = 1000 }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(model.discover.value.loading)
        assertFalse(model.discover.value.refreshing)
        model.loadDiscover(refresh = true)
        assertTrue(model.discover.value.refreshing)
        model.loadDiscover()
        assertTrue(model.discover.value.loading)
        assertFalse(model.discover.value.refreshing)
        advanceTimeBy(1000); runCurrent()
        assertFalse(model.discover.value.refreshing)
        model.loadBrowse()
        assertFalse(model.browse.value.refreshing)
        model.loadBrowse(refresh = true)
        assertTrue(model.browse.value.refreshing)
        model.loadBrowse(BrowseFilters(type = "tv"))
        assertFalse(model.browse.value.refreshing)
        runCurrent()
        assertFalse(model.browse.value.loading)
        model.loadBrowse(refresh = true)
        runCurrent()
        assertFalse(model.browse.value.refreshing)
        model.loadHot()
        assertFalse(model.hot.value.refreshing)
        model.loadHot(refresh = true)
        assertTrue(model.hot.value.refreshing)
        model.loadHot("movie")
        assertFalse(model.hot.value.refreshing)
        runCurrent()
        model.loadHot(refresh = true)
        runCurrent()
        assertFalse(model.hot.value.refreshing)
        model.close()
    }
    @Test fun hotCatalogPagesAndTypeSwitchRemainIndependentOfHomeAndBrowse() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.search("old", "all")
        runCurrent()
        model.clearSearch()
        advanceTimeBy(1500); runCurrent()
        assertEquals(SearchState(), model.search.value)
        model.close()
    }
    @Test fun loadHotRejectsUnknownTypeWithoutTouchingNetwork() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        model.loadHot("unknown")
        runCurrent()
        assertTrue(model.hot.value.items.isEmpty())
        assertNull(model.hot.value.page)
        assertTrue(platform.hotRequests.isEmpty())
        model.close()
    }
    @Test fun sketchesExcludeYoukuSourceAndRetainUnsupportedEmbedFallback() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        // AppState also warms the homepage at initialization; measure only this tab load.
        platform.hosts.clear()
        model.loadHot("xiaopin")
        runCurrent()
        assertEquals("最近热播 · 小品", model.hot.value.page?.label)
        assertEquals(1, model.hot.value.items.size)
        assertTrue(model.hot.value.items.all { it.kind == "xiaopin" })
        assertTrue(platform.hotRequests.isEmpty())
        assertTrue(platform.hosts.all { it == "www.dieyin.cn" })
        model.match(model.hot.value.items.first()) { fail("Browser playback must not navigate to the native player") }
        runCurrent()
        assertEquals(listOf(sketchPage), platform.openedLinks)
        assertNull(model.session)
        assertTrue(platform.players.isEmpty())
        model.match(CatalogItem("无效地址", "https://evil/video/1.html", kind = "xiaopin")) {
            fail("Invalid sketch pages must not open a player")
        }
        assertEquals(1, platform.openedLinks.size)
        model.youth.enable("123456")
        model.match(model.hot.value.items.last()) { fail("Youth mode must not bypass app watch-time limits") }
        assertEquals(1, platform.openedLinks.size)
        model.loadHot("movie")
        runCurrent()
        assertTrue(model.hot.value.items.all { it.kind == "movie" })
        model.close()
    }

    @Test fun verifiedBilibiliSketchNavigatesToNativePlayer() = runTest {
        val platform = TestPlatform(this).apply { nativeSketch = true }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        var navigated = false
        model.match(CatalogItem("又来了", sketchPage, kind = "xiaopin")) { navigated = true }
        runCurrent()
        assertTrue(navigated)
        assertTrue(platform.openedLinks.isEmpty())
        assertEquals(sketchPage, model.session?.state?.value?.info?.pageUrl)
        assertEquals("video", model.session?.state?.value?.info?.kind)
        model.close()
    }

    @Test fun sketchNetworkFailureOffersRetryOnHotTabWithoutBrowserFallback() = runTest {
        val platform = TestPlatform(this).apply { nativeSketch = true }
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        platform.networkUnavailable = true
        var navigated = false
        model.match(CatalogItem("又来了", sketchPage, kind = "xiaopin")) { navigated = true }
        runCurrent()
        val prompt = model.retryPrompts.pending.value.single { it.key == "sketch" }
        assertEquals("hot", prompt.destination)
        assertFalse(navigated)
        assertTrue(platform.openedLinks.isEmpty())
        platform.networkUnavailable = false
        model.retryPrompts.retry(prompt.id)
        runCurrent()
        assertTrue(navigated)
        assertEquals(sketchPage, model.session?.state?.value?.info?.pageUrl)
        model.close()
    }

    @Test fun addedHotCategoriesUseTheirOwnTagsAndResetPaging() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
    @Test fun deletingCurrentEpisodeCacheDoesNotRequireClosingOnlinePlayer() = runTest {
        val removed = mutableListOf<String>()
        val engine = object : OfflineEngine {
            override fun supports(kind: String) = kind == "hls"
            override suspend fun download(
                id: String, info: dev.pandavideo.core.model.PlaybackInfo, progress: (Float?) -> Unit,
            ): String = awaitCancellation()
            override fun cancel(id: String) {}
            override suspend fun remove(id: String) { removed += id }
            override fun asset(id: String, locator: String): OfflineAsset? = null
        }
        val platform = TestPlatform(this, engine)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.match(matchingItem) {}
        runCurrent()
        val session = assertNotNull(model.session)
        model.cacheCurrentEpisode()
        runCurrent()
        val id = model.downloads.tasks.value.single().id
        model.removeDownload(id)
        runCurrent()
        assertEquals(listOf(id), removed)
        assertTrue(model.downloads.tasks.value.isEmpty())
        assertSame(session, model.session)
        assertFalse(platform.players.single().closed)
        assertEquals(0, platform.players.single().pauses)
        assertTrue(platform.players.single().state.value.wantsPlay)
        model.close()
    }
    @Test fun minimizePlayerPausesAndKeepsSessionForReopening() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
    @Test fun sleepAfterCurrentAndCountdownReplaceEachOtherAndClearOnCompletion() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.match(matchingItem) {}; runCurrent()
        model.setSleepTimer(1)
        model.setSleepAfterCurrent()
        assertTrue(model.sleepAfterCurrent.value)
        assertEquals(0, model.sleepRemaining.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, platform.players.single().pauses)
        platform.players.single().state.value = platform.players.single().state.value.copy(phase = PlayerPhase.Ended)
        runCurrent()
        assertFalse(model.sleepAfterCurrent.value)
        assertEquals(1, platform.players.single().pauses)
        assertFalse(model.session!!.state.value.ended)
        model.setSleepAfterCurrent()
        model.setSleepTimer(30)
        assertFalse(model.sleepAfterCurrent.value)
        assertEquals(1_800, model.sleepRemaining.value)
        model.close()
    }

    @Test fun sleepAfterCurrentCanBeCancelledAndSurvivesTitleReplacement() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.setSleepAfterCurrent()
        model.match(matchingItem) {}; runCurrent()
        model.startPlayer(dev.pandavideo.core.model.PlayerRequest(page = pomoPage, title = "新影片"))
        runCurrent()
        assertEquals(1, platform.players.last().loads.size)
        assertTrue(model.sleepAfterCurrent.value)
        platform.players.last().state.value = platform.players.last().state.value.copy(phase = PlayerPhase.Ended)
        runCurrent()
        assertFalse(model.sleepAfterCurrent.value)
        assertEquals(1, platform.players.last().pauses)
        model.startPlayer(dev.pandavideo.core.model.PlayerRequest(page = pomoPage, title = "新影片"))
        runCurrent()
        model.setSleepAfterCurrent()
        model.setSleepTimer(0)
        assertFalse(model.sleepAfterCurrent.value)
        platform.players.last().state.value = platform.players.last().state.value.copy(phase = PlayerPhase.Ended)
        runCurrent()
        assertEquals(0, platform.players.last().pauses)
        model.close()
    }

    @Test fun sleepTimerSetBeforePlaybackSurvivesOpeningAndSwitchingTitles() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.setSleepTimer(1)
        assertEquals(1, model.sleepTimerMinutes.value)
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        model.match(matchingItem) {}
        runCurrent()
        assertEquals(50, model.sleepRemaining.value)
        advanceTimeBy(10_000); runCurrent()
        model.startPlayer(dev.pandavideo.core.model.PlayerRequest(page = "https://x/title", title = "新影片"))
        runCurrent()
        assertEquals(40, model.sleepRemaining.value)
        advanceTimeBy(40_000); runCurrent()
        assertEquals(0, model.sleepRemaining.value)
        assertEquals(0, model.sleepTimerMinutes.value)
        assertFalse(platform.players.last().state.value.wantsPlay)
        assertEquals(1, platform.players.last().pauses)
        model.close()
    }

    @Test fun customSleepDurationPausesOnlyAtTheRequestedMinute() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.match(matchingItem) {}
        runCurrent()
        model.setSleepTimer(7)
        assertEquals(7, model.sleepTimerMinutes.value)
        assertEquals(420, model.sleepRemaining.value)
        runCurrent()
        advanceTimeBy(419_000); runCurrent()
        assertEquals(1, model.sleepRemaining.value)
        assertEquals(0, platform.players.single().pauses)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, model.sleepRemaining.value)
        assertEquals(0, model.sleepTimerMinutes.value)
        assertEquals(1, platform.players.single().pauses)
        model.close()
    }

    @Test fun replacingSleepTimerCancelsOldDeadlineAndExplicitCloseDisablesIt() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.match(matchingItem) {}
        runCurrent()
        model.setSleepTimer(1)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        model.setSleepTimer(2)
        assertEquals(2, model.sleepTimerMinutes.value)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(90, model.sleepRemaining.value)
        assertEquals(0, platform.players.single().pauses)
        model.closePlayer()
        assertEquals(0, model.sleepRemaining.value)
        assertEquals(0, model.sleepTimerMinutes.value)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, platform.players.single().pauses)
        model.close()
    }

    @Test fun sleepTimerPausesMinimizedPlaybackAndCanBeCancelled() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val page = "https://huolot.com/voddetail/8.html"
        assertTrue(model.toggleFavorite("测试电影", page, null, "2020"))
        assertEquals("https://image/poster.jpg", model.user.favorites.value.single().cover)
        model.close()
    }
    @Test fun fullscreenBlockedByOsEmitsOrientationLockHint() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
    @Test fun homeResumeUsesRecordedFourthEpisodeAndKeepsItsPosition() = runTest {
        val platform = TestPlatform(this)
        platform.detailFixture = pomoDetailFixture.replace(
            """{"name":"第 2 集","url":"https://media/2.m3u8"}""",
            (2..5).joinToString(",") { """{"name":"第 $it 集","url":"https://media/$it.m3u8"}""" },
        )
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.rememberPlayed("测试电影", pomoPage, null, "2020", "第 4 集", "$pomoPage#1-3")
        model.saveProgress(pomoPage, "$pomoPage#1-3", "第 4 集", 120_000, 600_000)
        model.resumeHomeHistory(model.user.history.value.first())
        runCurrent()
        assertEquals("$pomoPage#1-3", model.session?.state?.value?.info?.selected)
        assertEquals(120_000L, platform.players.last().loads.single().startPosition)
        model.closePlayer()
        model.saveProgress(pomoPage, "$pomoPage#1-3", "第 4 集", 600_000, 600_000)
        assertEquals("$pomoPage#1-4", model.user.continueEpisode(pomoPage))
        model.resumeHomeHistory(model.user.history.value.first())
        runCurrent()
        assertEquals("$pomoPage#1-3", model.session?.state?.value?.info?.selected,
            "The home card must open the recorded episode, not a completed episode's successor")
        assertEquals(0L, platform.players.last().loads.single().startPosition)
        model.close()
    }

    @Test fun reopenSavedResumesTheUnfinishedEpisodeAndKeepsResumeLabel() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val page = pomoPage
        model.rememberPlayed("测试电影", page, null, "2020", "第 2 集", "$page#1-1")
        model.saveProgress(page, "$page#1-1", "第 2 集", 120_000, 600_000)
        val record = model.user.history.value.single()
        assertEquals("看到 第 2 集 · 2:00", model.resumeLabel(record))
        model.reopenSaved(page, "测试电影")
        runCurrent()
        assertEquals("$page#1-1", model.session?.state?.value?.info?.selected)
        assertEquals(120_000L, platform.players.single().loads.single().startPosition)
        model.close()
    }
    @Test fun historyMetadataUsesOtherLoadedCatalogsAndSurvivesColdRestart() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.loadDiscover()
        runCurrent()
        model.rememberPlayed("测试电影", pomoPage, null, "")
        val saved = model.user.history.value.single()
        assertEquals("2020", saved.year)
        assertEquals(9.1, saved.rating)
        assertEquals(9.1, model.historyPoster(saved, emptyList()).rating)
        model.close()
        val restarted = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        val poster = restarted.historyPoster(restarted.user.history.value.single(), emptyList())
        assertEquals("2020", poster.year)
        assertEquals(9.1, poster.rating)
        restarted.close()
    }
    @Test fun playbackSourceAliasesKeepTheOriginatingCatalogYearAndRating() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.startPlayer(PlayerRequest(page = pomoPage),
            catalog = CatalogItem("目录片名", "https://movie.douban.com/subject/123/", "2020", 9.1))
        runCurrent()
        val record = model.user.history.value.single()
        assertEquals("测试电影", record.title)
        assertEquals("2020", record.year)
        assertEquals(9.1, record.rating)
        model.close()
    }
    @Test fun legacyHistoryMetadataIsRepairedWithoutChangingResumePosition() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.user.recordHistory("测试电影", pomoPage, "", "", episodePath = "$pomoPage#1-1")
        model.saveProgress(pomoPage, "$pomoPage#1-1", "第2集", 120_000, 600_000)
        val original = model.user.history.value.single()
        val item = CatalogItem("测试电影", "https://movie.douban.com/subject/123/", "2020", 9.1)
        model.fillHistoryMetadata(original, listOf(item))
        assertEquals(original.copy(year = "2020", rating = 9.1), model.user.history.value.single())
        val poster = model.historyPoster(model.user.history.value.single(), emptyList())
        assertEquals("2020", poster.year)
        assertEquals(9.1, poster.rating)
        model.close()
    }
    @Test fun automaticResumeSavesUpdatedPositionOnExit() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        val page = pomoPage
        model.rememberPlayed("测试电影", page, null, "2020", "第 2 集", "$page#1-1")
        model.saveProgress(page, "$page#1-1", "第 2 集", 120_000, 600_000)
        model.reopenSaved(page, "测试电影")
        runCurrent()
        val player = platform.players.last()
        assertEquals(120_000L, player.loads.single().startPosition)
        player.state.value = player.state.value.copy(position = 230_000, duration = 600_000)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(120_000L, model.progressFor(page, "$page#1-1")?.position)
        model.closePlayer()
        assertEquals(230_000L, model.progressFor(page, "$page#1-1")?.position)
        model.reopenSaved(page, "测试电影")
        runCurrent()
        assertEquals(230_000L, platform.players.last().loads.single().startPosition)
        model.close()
    }
    @Test fun defaultEntriesRestoreFourthEpisodeAfterRestartAndExplicitSelectionWins() = runTest {
        val platform = TestPlatform(this)
        platform.detailFixture = pomoDetailFixture.replace(
            """{"name":"第 2 集","url":"https://media/2.m3u8"}""",
            (2..5).joinToString(",") { """{"name":"第 $it 集","url":"https://media/$it.m3u8"}""" },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = AppState(platform, backgroundScope, resolveDispatcher = dispatcher)
        first.rememberPlayed("测试电影", pomoPage, null, "2020", "第 4 集", "$pomoPage#1-3")
        first.saveProgress(pomoPage, "$pomoPage#1-3", "第 4 集", 120_000, 600_000)
        first.close()
        val model = AppState(platform, backgroundScope, resolveDispatcher = dispatcher)
        for (entry in 0..2) {
            when (entry) {
                0 -> model.reopenSaved(pomoPage, "测试电影")
                1 -> model.startPlayer(PlayerRequest(page = pomoPage, title = "测试电影"))
                else -> model.match(matchingItem) {}
            }
            runCurrent()
            assertEquals("$pomoPage#1-3", model.session?.state?.value?.info?.selected)
            assertEquals(120_000L, platform.players.last().loads.single().startPosition)
            model.session!!.retry()
            runCurrent()
            assertEquals("$pomoPage#1-3", model.session?.state?.value?.info?.selected)
            assertEquals(120_000L, platform.players.last().loads.last().startPosition)
        }
        model.startPlayer(PlayerRequest(page = pomoPage, episode = "$pomoPage#1-0"))
        runCurrent()
        assertEquals("$pomoPage#1-0", model.session?.state?.value?.info?.selected)
        assertEquals(0L, platform.players.last().loads.single().startPosition)
        model.close()
    }
    @Test fun reopenSavedExposesTheHistoryCoverForTheMiniPlayer() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
    @Test fun finishedFinalEpisodeHasNoResumeLabelAndReplaysSameEpisode() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val page = pomoPage
        model.rememberPlayed("测试电影", page, null, "2020", "第 2 集", "$page#1-1")
        model.saveProgress(page, "$page#1-1", "第 2 集", 590_000, 600_000)
        assertNull(model.resumeLabel(model.user.history.value.single()))
        model.reopenSaved(page, "测试电影")
        runCurrent()
        assertEquals("$page#1-1", model.session?.state?.value?.info?.selected)
        model.close()
    }
    @Test fun completedFirstEpisodeReopensSecondAfterRestartWithLegacyProgress() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        val page = pomoPage
        model.rememberPlayed("测试电影", page, null, "2020", "第 1 集", "$page#1-0")
        model.saveProgress(page, "$page#1-0", "第 1 集", 600_000, 600_000)
        model.close()
        val restarted = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        restarted.reopenSaved(page, "测试电影")
        runCurrent()
        assertEquals("$page#1-1", restarted.session?.state?.value?.info?.selected)
        assertEquals("第 2 集", platform.players.last().loads.single().episodeLabel)
        restarted.session!!.retry()
        runCurrent()
        assertEquals("$page#1-1", restarted.session?.state?.value?.info?.selected)
        // An explicit replay tap must continue to select the completed first episode.
        restarted.session!!.select(restarted.session!!.state.value.info!!.episodes.first())
        runCurrent()
        assertEquals("$page#1-0", restarted.session?.state?.value?.info?.selected)
        restarted.close()
    }
    @Test fun closeAfterImmediateAdvanceThenHistoryReopenKeepsSecondEpisode() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        model.startPlayer(dev.pandavideo.core.model.PlayerRequest(page = pomoPage, title = "测试电影"))
        runCurrent()
        val player = platform.players.single()
        player.state.value = player.state.value.copy(phase = PlayerPhase.Ended, duration = 600_000, position = 599_000)
        runCurrent()
        assertTrue(model.user.latestProgress(pomoPage)!!.finished)
        model.reopenSaved(pomoPage, "测试电影")
        runCurrent()
        assertEquals("$pomoPage#1-1", model.session?.state?.value?.info?.selected)
        model.close()
    }
    @Test fun themePreferencePersistsAcrossAppStateInstances() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(dev.pandavideo.data.user.ThemeMode.System, model.settings.settings.value.themeMode)
        model.settings.setThemeMode(dev.pandavideo.data.user.ThemeMode.Dark)
        val reopened = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(dev.pandavideo.data.user.ThemeMode.Dark, reopened.settings.settings.value.themeMode)
        model.close(); reopened.close()
    }
    @Test fun favoriteToggleEmitsOneShotChineseMessages() = runTest {
        val platform = TestPlatform(this)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
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
