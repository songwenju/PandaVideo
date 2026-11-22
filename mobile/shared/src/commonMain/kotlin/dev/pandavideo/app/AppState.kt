package dev.pandavideo.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.SearchResult
import dev.pandavideo.core.model.appendCatalog
import dev.pandavideo.core.model.withPlayingCredit
import dev.pandavideo.core.model.withCatalogCredits
import dev.pandavideo.core.model.formatMinutesSeconds
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.data.repository.ApiError
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ParserRulesStore
import dev.pandavideo.data.repository.UpdateStore
import dev.pandavideo.data.user.YouthStore
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.data.repository.isWebUrl
import dev.pandavideo.data.repository.mergeHotCatalog
import dev.pandavideo.data.repository.mergeHighRatedCatalog
import dev.pandavideo.data.user.HistoryTitle
import dev.pandavideo.data.user.SettingsStore
import dev.pandavideo.data.user.SearchHistoryStore
import dev.pandavideo.data.user.UserStore
import dev.pandavideo.data.user.progressFraction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class CatalogState(
    val items: List<CatalogItem> = emptyList(), val page: CatalogPage? = null,
    val loading: Boolean = false, val error: String? = null, val loadingMore: Boolean = false,
    /** True only for a user-requested pull refresh, never initial or tab loading. */
    val refreshing: Boolean = false,
)
data class SearchState(val result: SearchResult? = null, val loading: Boolean = false, val error: String? = null)
/** Application-owned direct-source requests and playback lifetime. */
class AppState(
    val platform: PlatformServices, private val scope: CoroutineScope,
    private val posterImageLoader: coil3.ImageLoader? = null,
    /**
     * Dispatcher for source resolution. Detail pages are large and episode lists can hold
     * hundreds of entries, so resolving on the UI dispatcher delays the player's first frame.
     */
    private val resolveDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cachePosters: suspend (List<String>) -> Unit = {},
) {
    private val discoverMutable = MutableStateFlow(CatalogState())
    val discover = discoverMutable.asStateFlow()
    private val browseMutable = MutableStateFlow(CatalogState())
    val browse = browseMutable.asStateFlow()
    private val hotMutable = MutableStateFlow(CatalogState())
    val hot = hotMutable.asStateFlow()
    private val searchMutable = MutableStateFlow(SearchState())
    val search = searchMutable.asStateFlow()
    internal val retryPrompts = RetryPrompts()
    private val messages = Channel<String>(Channel.BUFFERED)
    /** One-shot Chinese user messages, collected by the host Snackbar. */
    val userMessages: Flow<String> = messages.receiveAsFlow()
    /** Enqueues a transient user-facing message without suspending the caller. */
    fun emitMessage(text: String) {
        messages.trySend(text)
    }
    val user = UserStore(platform.storage(), platform.catalogDatabasePath()?.let { "$it.playback.db" })
    val settings = SettingsStore(platform.storage())
    /** Manual source-line reports survive restarts and catalog cache cleanup. */
    val lineAds = dev.pandavideo.data.user.LineAdStore(platform.storage())
    /** Source-owned series skip preferences, snapshotted at each new episode load. */
    val seriesSkipping = dev.pandavideo.data.user.SkipDurationsStore(platform.storage())
    val searchHistory = SearchHistoryStore(platform.storage())
    val youth = YouthStore(platform.storage())
    val updates = UpdateStore(platform.storage())
    private val checkingUpdateMutable = MutableStateFlow(false)
    val checkingUpdate = checkingUpdateMutable.asStateFlow()
    fun checkUpdate(url: String, userInitiated: Boolean = true) {
        if (checkingUpdateMutable.value) return
        checkingUpdateMutable.value = true
        retryPrompts.clear("update")
        serviceScope.launch {
            try {
                updates.accept(api.configuration(url.trim()), url.trim())
                emitMessage(if (updates.notice.value == null) "当前已是最新版本" else "发现新版本，请在设置中查看")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (userInitiated) retryPrompts.offer("update", "检查更新失败",
                    "无法获取更新信息，请检查地址和网络后重试", "settings") { checkUpdate(url) }
            }
            finally { checkingUpdateMutable.value = false }
        }
    }
    val parserRules = ParserRulesStore(platform.storage())
    private val api = ContentRepository(
        platform.httpClient(), platform.catalogDatabasePath()?.let { CatalogDatabase(it, initializeFromBundle = true) },
        doubanSession = platform.doubanSession(),
        lineHasAds = lineAds::hasAds,
    ) { parserRules.rules.value }
    private val updatingRulesMutable = MutableStateFlow(false)
    val updatingRules = updatingRulesMutable.asStateFlow()
    fun updateParserRules(url: String, userInitiated: Boolean = true) {
        if (updatingRulesMutable.value) return
        updatingRulesMutable.value = true
        retryPrompts.clear("rules")
        serviceScope.launch {
            try {
                parserRules.import(api.configuration(url.trim()), url.trim())
                emitMessage("解析规则已更新")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (userInitiated) retryPrompts.offer("rules", "规则更新失败",
                    "请检查地址和网络后重试，原有规则已保留", "settings") { updateParserRules(url) }
            }
            finally { updatingRulesMutable.value = false }
        }
    }
    private var serviceJob = SupervisorJob(scope.coroutineContext[Job])
    private var serviceScope = CoroutineScope(scope.coroutineContext + serviceJob)
    internal val douban = DoubanConnection(platform.doubanSession(), serviceScope, api::doubanIdentity) {
        loadDiscover(refresh = true)
        loadBrowse(refresh = true)
    }
    internal val actors = ActorBrowser(serviceScope, api::searchActors)
    private val resolvedCastActors = linkedMapOf<String, dev.pandavideo.core.model.ActorRef>()

    /** Resolves a filmography without changing the active playback session. */
    internal suspend fun actorWorks(
        actor: dev.pandavideo.core.model.ActorRef,
        onSnapshot: suspend (dev.pandavideo.core.model.ActorFilmography) -> Unit = {},
    ): dev.pandavideo.core.model.ActorFilmography {
        val info = session?.state?.value?.info
        val catalog = playingCatalog
        val search = playingSearch
        val page = dev.pandavideo.data.repository.ContentParsers.playbackPageOrNull(info?.pageUrl.orEmpty())
        var known = visibleActorWorks(listOf(discoverMutable.value, browseMutable.value, hotMutable.value)
            .flatMap { it.items }.plus(tabPages.values.flatMap { it.value.items })
            .filter { actor.name.trim() in it.casts.map(String::trim) })
        var confirmed = dev.pandavideo.core.model.ActorFilmography(emptyList())
            .withPlayingCredit(actor, info, catalog, search, page)
            .withCatalogCredits(actor, known)
        if (confirmed.works.isNotEmpty()) {
            // Catalog and player casts carry names, not person IDs. Supplement only the unique
            // source identity; selecting another same-name person must not inherit these credits.
            val identity = resolvedCastActors[actor.name] ?: try { findCastActor(actor.name) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
            if (identity?.id != actor.id) confirmed = confirmed.copy(works = emptyList())
        }
        if (confirmed.works.isNotEmpty()) onSnapshot(confirmed.copy(stale = true, limited = true))
        val stored = try { api.actorCatalogSnapshot() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { emptyList() }
        // In-memory credits render before the wider disk scan. Only uniquely identified
        // people may inherit name-only catalog casts, including after restoring a route.
        if (stored.any { actor.name.trim() in it.casts.map(String::trim) }) {
            val identity = resolvedCastActors[actor.name] ?: try { findCastActor(actor.name) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
            if (identity?.id == actor.id) {
                known = visibleActorWorks((known + stored.filter {
                    actor.name.trim() in it.casts.map(String::trim)
                }).distinctBy { it.url })
                confirmed = confirmed.withCatalogCredits(actor, known)
                if (confirmed.works.isNotEmpty()) onSnapshot(confirmed.copy(stale = true, limited = true))
            }
        }
        val snapshot = try { api.actorWorks(actor) { cached ->
            onSnapshot(if (confirmed.works.isEmpty()) cached else
                cached.withCatalogCredits(actor, known).withPlayingCredit(actor, info, catalog, search, page))
        } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (confirmed.works.isEmpty()) throw error
            return confirmed.copy(stale = true, limited = true)
        }
        return if (confirmed.works.isEmpty()) snapshot
            else snapshot.withCatalogCredits(actor, known).withPlayingCredit(actor, info, catalog, search, page)
    }
    /** Loads source-owned person properties independently of filmography and playback. */
    internal suspend fun actorProfile(
        actor: dev.pandavideo.core.model.ActorRef,
        onCached: suspend (dev.pandavideo.core.model.ActorRef) -> Unit = {},
    ) = api.actorProfile(actor, onCached)
    internal suspend fun actorCatalogWork(work: dev.pandavideo.core.model.ActorWork) = api.actorCatalogWork(work)

    /** Resolves unique exact-name iQIYI identities without merging same-name people. */
    internal suspend fun findCastActor(name: String): dev.pandavideo.core.model.ActorRef? {
        val actor = api.searchActors(name).actors.filter { it.name == name }.singleOrNull()
        if (actor == null) resolvedCastActors.remove(name) else resolvedCastActors[name] = actor
        if (resolvedCastActors.size > 64) resolvedCastActors.remove(resolvedCastActors.keys.first())
        return actor
    }

    /** Applies known resource failures and inspects new rows without treating errors as misses. */
    internal suspend fun visibleActorWorks(items: List<CatalogItem>) =
        api.visibleCatalog(CatalogPage(items, 0, items.size, false)).items

    internal suspend fun inspectActorWorks(items: List<CatalogItem>, missing: (CatalogItem) -> Unit) =
        api.inspectCatalog(items, missing)
    /** App-owned maintenance of expendable image/content caches, independent of playback. */
    internal val cacheMaintenance: dev.pandavideo.data.repository.CacheMaintenance =
        dev.pandavideo.data.repository.CacheMaintenance(
        serviceScope,
        posterBytes = { withContext(Dispatchers.Default) { posterImageLoader?.diskCache?.size ?: 0L } },
        contentUsage = api::cacheUsage,
        clearPosters = {
            withContext(Dispatchers.Default) {
                // Coil owns its journal and active snapshots. Its clear API safely evicts
                // cached entries; recursively deleting the directory would bypass that owner.
                posterImageLoader?.memoryCache?.clear()
                posterImageLoader?.diskCache?.clear()
            }
        },
        clearContent = api::clearCache,
        onFailure = { message, retry ->
            retryPrompts.offer("maintenance", "缓存管理失败", message, "settings",
                available = { cacheMaintenance.state.value.error != null && !cacheMaintenance.state.value.busy },
                action = retry)
        },
    )
    /** Explicit episode downloads and offline playback metadata. */
    val downloads = dev.pandavideo.data.repository.OfflineDownloads(
        platform.storage(), platform.offlineEngine(), serviceScope,
        onFailure = ::reportDownloadFailure,
        network = platform.downloadNetwork(),
        onNetworkWarning = { proceed, available ->
            retryPrompts.offer("download-network", "当前未连接 Wi-Fi",
                "继续缓存可能消耗移动流量。是否继续？取消后将在连接 Wi-Fi 时继续缓存。",
                available = available, confirmLabel = "继续缓存", action = proceed)
        },
        resolve = api::player,
    )
    private fun reportDownloadFailure(entry: dev.pandavideo.data.repository.OfflineDownload, retry: () -> Unit) {
        val deleting = entry.error == "删除失败，请重试"
        retryPrompts.offer("download:${entry.id}", if (deleting) "删除缓存失败" else "下载失败",
            "${entry.info.title} ${entry.episodeLabel}\n${entry.error}".trim(),
            available = {
                downloads.tasks.value.any {
                    it.id == entry.id && it.status == dev.pandavideo.data.repository.DownloadStatus.Failed
                }
            }, action = retry)
    }
    /** Queues the resolved current episode and reports a transient result. */
    fun cacheCurrentEpisode() {
        val info = session?.state?.value?.takeUnless { it.loading }?.info ?: return
        try {
            downloads.enqueue(info, playingCover.orEmpty())
            emitMessage("已加入缓存，可在我的离线缓存中查看")
        } catch (_: Exception) { emitMessage("当前视频暂不支持缓存") }
    }
    /**
     * Queues the user's episode selection without changing the active playback session.
     *
     * @param info Snapshot of the detail page shown by the cache picker.
     * @param paths Selected episode paths belonging to that snapshot.
     * @param cover Optional artwork for the offline task list.
     * @return Whether the selection was accepted by the offline queue.
     */
    fun cacheEpisodes(info: PlaybackInfo, paths: Collection<String>, cover: String = ""): Boolean {
        return try {
            val ids = downloads.enqueueEpisodes(info, paths, cover)
            emitMessage("已加入 ${ids.size} 集缓存，可在我的离线缓存中查看")
            true
        } catch (_: Exception) {
            emitMessage("无法加入缓存，请检查选集或稍后重试")
            false
        }
    }
    /** Opens a completed episode directly from private media, without source lookup. */
    fun playDownload(entry: dev.pandavideo.data.repository.OfflineDownload) {
        startPlayer(PlayerRequest(page = entry.info.pageUrl, title = entry.info.title,
            episode = entry.info.selected), cover = entry.cover, offlineOnly = true)
    }
    /** Deletes an episode cache without requiring the current playback session to close. */
    fun removeDownload(id: String) {
        retryPrompts.clear("download:$id")
        downloads.remove(id)
    }
    private var discoverJob: Job? = null
    private val catalogWarmJobs = mutableMapOf<String, Job>()
    private var browseJob: Job? = null
    private var hotJob: Job? = null
    private var searchJob: Job? = null
    private var playerErrorJob: Job? = null
    private var artworkJob: Job? = null
    private var sleepJob: Job? = null
    private var playerLevelsActive = false
    private val sleepRemainingMutable = MutableStateFlow(0)
    /** Remaining sleep timer seconds; zero means disabled. Timers are never persisted. */
    val sleepRemaining = sleepRemainingMutable.asStateFlow()
    private val sleepTimerMinutesMutable = MutableStateFlow(0)
    /** Selected sleep duration in minutes; retained during countdown and cleared on cancellation or expiry. */
    val sleepTimerMinutes = sleepTimerMinutesMutable.asStateFlow()
    private val sleepAfterCurrentMutable = MutableStateFlow(false)
    /** True while the current-episode sleep action is armed; mutually exclusive with a countdown. */
    val sleepAfterCurrent = sleepAfterCurrentMutable.asStateFlow()
    /** Replaces the countdown with a one-shot pause when the current episode finishes. */
    fun setSleepAfterCurrent() {
        setSleepTimer(0)
        sleepAfterCurrentMutable.value = true
        session?.setPauseAtEnd(true)
    }
    private fun finishSleepAfterCurrent() {
        sleepAfterCurrentMutable.value = false
        emitMessage("当前已播完，播放已暂停")
    }
    /**
     * Starts or replaces the app-wide sleep countdown, including before playback opens.
     *
     * @param minutes Duration clamped to 0–180 minutes; zero cancels the countdown.
     */
    fun setSleepTimer(minutes: Int) {
        sleepAfterCurrentMutable.value = false
        session?.setPauseAtEnd(false)
        sleepJob?.cancel()
        sleepJob = null
        val duration = minutes.coerceIn(0, 180)
        sleepTimerMinutesMutable.value = duration
        sleepRemainingMutable.value = duration * 60
        if (duration == 0) return
        sleepJob = serviceScope.launch {
            while (sleepRemainingMutable.value > 0) {
                delay(1000)
                sleepRemainingMutable.value--
            }
            sleepTimerMinutesMutable.value = 0
            val active = session
            active?.pauseForSleepTimer()
            emitMessage(if (active == null) "睡眠定时已结束" else "睡眠定时已结束，播放已暂停")
        }
    }
    /** Restores only the screen settings explicitly enabled by the user. */
    fun restorePlayerLevels() {
        playerLevelsActive = true
        val value = settings.settings.value
        if (value.rememberVolume) platform.setVolume(value.volume)
        if (value.rememberBrightness) platform.setBrightness(value.brightness)
    }
    fun rememberPlayerLevels() {
        if (playerLevelsActive) settings.saveLevels(platform.currentVolume(), platform.currentBrightness())
    }
    /** Snapshot-observable so the mini bar and player route recompose on open/close. */
    var session: PlaybackSession? by mutableStateOf(null)
        private set
    var playingCatalog: CatalogItem? by mutableStateOf(null)
        private set
    /** Catalog snapshot that opened this session; null allows unscoped search/history suggestions. */
    var playingRecommendations: List<CatalogItem>? by mutableStateOf(null)
        private set

    /**
     * Loads a high-rated regional catalog without changing navigation or active filters.
     * Originating snapshots constrain membership, including when cached rows lack countries.
     */
    internal suspend fun playerRecommendations(
        selection: dev.pandavideo.data.repository.PlayerRecommendationScope,
        candidates: List<CatalogItem>, originating: List<CatalogItem>?,
        onSnapshot: suspend (List<CatalogItem>) -> Unit = {},
    ): List<CatalogItem> {
        val local = selection.select(candidates, originating)
        val country = selection.country ?: return local
        if (originating?.isEmpty() == true) return local
        if (selection.kind !in setOf("movie", "tv", "animation", "documentary", "variety")) return local
        // Reuse full cached subject metadata for genre/cast ranking before requesting more
        // pages. A populated rail must still be reranked when better metadata becomes available.
        val stored = try { api.actorCatalogSnapshot() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { emptyList() }
        val allKnown = candidates + stored
        val known = try {
            api.visibleCatalog(CatalogPage(allKnown, 0, allKnown.size, false)).items
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { candidates }
        val enriched = selection.select(known, originating)
        onSnapshot(enriched)
        var related = enriched
        var regional = emptyList<CatalogItem>()
        try {
            // A single regional page often omits titles from the originating catalog.
            // Enrich up to three pages while preserving its exact membership; publish each
            // result immediately so a slow later page cannot delay usable recommendations.
            var start = 0
            repeat(3) {
                val page = api.visibleCatalog(api.recommendationCatalog(selection.kind, country, start))
                regional = (regional + page.items).distinctBy { it.url.trimEnd('/') }
                related = selection.select(regional + known, originating)
                onSnapshot(related)
                // Later pages can outrank earlier high-score but unrelated candidates.
                if (!page.hasMore || page.pageSize <= 0) return related
                start = page.start + page.pageSize
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Keep earlier pages when a later request fails. */ }
        return related
    }
    var playingSearch: SearchItem? = null
        private set
    /** Validated cover url for sessions started from history/favorites without a list item. */
    var playingCover: String? = null
        private set
    var kind = "top250"
        private set
    /** Region selected on Discover, retained across category changes, refresh and pagination. */
    var discoverCountry = "all"
        private set
    var filters = BrowseFilters()
        private set
    var hotType = "tv"
        private set

    /** Measures compatible alternate lines independently of the active native player load. */
    internal suspend fun probeLines(
        info: PlaybackInfo, onMeasured: suspend (Map<Int, dev.pandavideo.core.model.LineProbe>) -> Unit = {},
    ): Map<Int, dev.pandavideo.core.model.LineProbe> {
        val callerContext = currentCoroutineContext()
        return withContext(resolveDispatcher) {
            api.probeLines(info) { measured ->
                // Session publication belongs to the caller's UI context, so an episode
                // switch cannot race a worker thread's read/check/write of player state.
                withContext(callerContext) { onMeasured(measured) }
            }
        }
    }

    private data class TabKey(val channel: String, val type: String = "", val country: String = "all", val filters: BrowseFilters? = null)
    private val tabPages = mutableMapOf<TabKey, MutableStateFlow<CatalogState>>()
    private val tabJobs = mutableMapOf<TabKey, Job>()

    /** Loads a neighboring page without changing selections, pagination or retry prompts. */
    private fun preview(
        key: TabKey, fetch: suspend ((suspend (CatalogPage) -> Unit)?) -> CatalogPage,
    ): StateFlow<CatalogState> {
        val target = tabPages.getOrPut(key) { MutableStateFlow(CatalogState(loading = true)) }
        if (target.value.page?.stale != false && tabJobs[key]?.isActive != true && target.value.error == null) {
            tabJobs[key] = serviceScope.launch {
                try {
                    publishCatalog(target, target.value, fetch(cachedPublisher(target, true)), false)
                    warmPosters(target.value.items.map { it.cover })
                    inspectCatalog(target)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    target.value = target.value.copy(loading = false, error = "加载失败，请检查网络后重试")
                }
            }
        }
        return target.asStateFlow()
    }

    /** Returns Discover's independent neighbor snapshot, including the selected region. */
    internal fun discoverPage(type: String, country: String): StateFlow<CatalogState> =
        if (type == kind && country == discoverCountry) discover
        else preview(TabKey("discover", type, country)) { cached -> api.list(type, 0, country, onCached = cached) }

    /** Returns a Browse neighbor without changing any of the other filter dimensions. */
    internal fun browsePage(selected: BrowseFilters): StateFlow<CatalogState> =
        if (selected == filters) browse
        else preview(TabKey("browse", filters = selected)) { cached -> api.browse(selected, 0, onCached = cached) }

    /** Returns a Hot neighbor using the same source and heading as the selected page. */
    internal fun hotPage(type: String): StateFlow<CatalogState> =
        if (type == hotType) hot
        else preview(TabKey("hot", type)) { cached ->
            val label = HotTypeOptions.first { it.first == type }.second.removePrefix("热播").removePrefix("热门")
            val publish: (suspend (CatalogPage) -> Unit)? = cached?.let { callback ->
                { page -> callback(page.copy(label = "最近热播 · $label")) }
            }
            val page = if (type == "xiaopin") api.sketches(0, false, onCached = publish)
                else api.hotCatalog(type, 0, onCached = publish)
            page.copy(label = "最近热播 · $label")
        }

    private fun retainTab(key: TabKey, state: CatalogState) {
        if (state.page != null) {
            tabJobs.remove(key)?.cancel()
            tabPages.getOrPut(key) { MutableStateFlow(state) }.value =
                state.copy(loading = false, loadingMore = false, refreshing = false)
        }
    }

    private fun adoptTab(key: TabKey, target: MutableStateFlow<CatalogState>): Boolean {
        val cached = tabPages[key]?.value ?: return false
        if (cached.page?.stale != false || cached.error != null) return false
        tabJobs.remove(key)?.cancel()
        target.value = cached.copy(loading = false, loadingMore = false, refreshing = false)
        // Cancelled preview checks resume under the active channel's cancellation lifetime.
        return true
    }

    /**
     * Promotes an unfinished neighbor request without restarting its network work.
     * Forward its disk snapshot while waiting, so a stale but usable grid is visible immediately.
     * The collector belongs to the active load; leaving the category cancels that collector and
     * cannot publish late neighbor rows into a different selection. The preview retains ownership
     * of its request, including its existing cancellation and silent-failure behavior.
     */
    private suspend fun awaitTab(key: TabKey, target: MutableStateFlow<CatalogState>): CatalogPage? {
        val job = tabJobs[key] ?: return null
        val source = tabPages[key] ?: return null
        coroutineScope {
            val snapshots = launch(start = CoroutineStart.UNDISPATCHED) {
                source.collect { target.value = it }
            }
            try { job.join() }
            finally { snapshots.cancelAndJoin() }
        }
        currentCoroutineContext().ensureActive()
        // A failed/cancelled preview is not a completed preload. The active request still owns
        // normal error details and retry handling; only successful preview results are reused.
        return source.value.takeIf { !job.isCancelled && it.error == null }?.page
    }

    init {
        loadDiscover()
        if (parserRules.sourceUrl.isNotBlank()) updateParserRules(parserRules.sourceUrl, userInitiated = false)
        if (updates.sourceUrl.isNotBlank()) checkUpdate(updates.sourceUrl, userInitiated = false)
        serviceScope.launch {
            while (true) {
                delay(1_000)
                val active = session ?: continue
                if (youth.enabled.value && active.controller.state.value.wantsPlay) {
                    if (active.controller.state.value.phase == PlayerPhase.Playing) youth.tick()
                    if (!youth.canPlay()) {
                        active.dismissEnded()
                        active.controller.pause()
                        emitMessage("今日观看已满 40 分钟，请休息")
                    }
                }
            }
        }
        serviceScope.launch {
            platform.fullscreenBlocked.collect {
                emitMessage("无法切换横屏全屏，请关闭 iPhone 竖排方向锁定后重试")
            }
        }
    }
    fun loadDiscover(selected: String = kind, more: Boolean = false, country: String = discoverCountry, refresh: Boolean = false) {
        val active = api
        if (more && (discoverMutable.value.loading || discoverMutable.value.page?.hasMore != true)) return
        // Re-entering the same category must keep the visible page and its decoded posters.
        // Only a user refresh, a failed/stale page or a different selection needs the network.
        if (!more && !refresh && selected == kind && country == discoverCountry &&
            discoverMutable.value.page != null && discoverMutable.value.page?.stale != true &&
            discoverMutable.value.error == null
        ) return
        discoverJob?.cancel()
        retryPrompts.clear("discover")
        val switching = selected != kind || country != discoverCountry
        if (switching) retainTab(TabKey("discover", kind, discoverCountry), discoverMutable.value)
        // A stale snapshot still owns usable rows. Revalidate it without briefly replacing
        // the destination grid with skeletons; only an unseen selection starts empty.
        val previous = if (selected == kind && country == discoverCountry) discoverMutable.value
            else tabPages[TabKey("discover", selected, country)]?.value ?: CatalogState()
        kind = selected
        discoverCountry = country
        if (switching && !more && !refresh && adoptTab(TabKey("discover", selected, country), discoverMutable)) {
            discoverJob = serviceScope.launch { inspectCatalog(discoverMutable) }
            return
        }
        discoverMutable.value = previous.copy(loading = true, loadingMore = more, refreshing = refresh && !more, error = null)
        discoverJob = serviceScope.launch {
            try {
                val preloaded = if (switching && !more && !refresh)
                    awaitTab(TabKey("discover", selected, country), discoverMutable) else null
                val page = preloaded ?: if (more && previous.page?.sections.isNullOrEmpty())
                    active.list(selected, previous.page?.nextStart() ?: 0, country)
                else active.list(selected, if (more) previous.page?.nextStart() ?: 0 else 0, country, refresh = refresh,
                    onCached = cachedPublisher(discoverMutable, !more && !refresh))
                ensureActive()
                publishCatalog(discoverMutable, previous, page, more)
                warmPosters(page.items.map { it.cover })
                if (!more && country == "all" && page.sections.isEmpty() && catalogWarmJobs[selected]?.isActive != true) {
                    catalogWarmJobs[selected] = serviceScope.launch { active.warmCatalog(selected, page) }
                }
                inspectCatalog(discoverMutable)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                discoverMutable.value = discoverMutable.value.copy(loading = false, loadingMore = false, refreshing = false,
                    error = "加载失败，请检查网络后重试")
                retryPrompts.offer("discover", "加载失败", failureDetails(error), "discover") {
                    loadDiscover(selected, more, country, refresh)
                }
            }
        }
    }
    fun loadBrowse(selected: BrowseFilters = filters, more: Boolean = false, refresh: Boolean = false) {
        val active = api
        if (more && (browseMutable.value.loading || browseMutable.value.page?.hasMore != true)) return
        // Same rule as Discover: an unchanged filter result stays on screen until the user
        // explicitly refreshes or picks a different filter.
        if (!more && !refresh && selected == filters && browseMutable.value.page != null &&
            browseMutable.value.page?.stale != true && browseMutable.value.error == null
        ) return
        browseJob?.cancel()
        retryPrompts.clear("browse")
        val switching = selected != filters
        if (switching) retainTab(TabKey("browse", filters = filters), browseMutable.value)
        val previous = if (selected == filters) browseMutable.value
            else tabPages[TabKey("browse", filters = selected)]?.value ?: CatalogState()
        filters = selected
        if (switching && !more && !refresh && adoptTab(TabKey("browse", filters = selected), browseMutable)) {
            browseJob = serviceScope.launch { inspectCatalog(browseMutable) }
            return
        }
        browseMutable.value = previous.copy(loading = true, loadingMore = more, refreshing = refresh && !more, error = null)
        browseJob = serviceScope.launch {
            try {
                val preloaded = if (switching && !more && !refresh)
                    awaitTab(TabKey("browse", filters = selected), browseMutable) else null
                val page = preloaded ?: active.browse(selected, if (more) previous.page?.nextStart() ?: 0 else 0, refresh = refresh,
                    onCached = cachedPublisher(browseMutable, !more && !refresh))
                ensureActive()
                publishCatalog(browseMutable, previous, page, more)
                warmPosters(page.items.map { it.cover })
                inspectCatalog(browseMutable)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                browseMutable.value = browseMutable.value.copy(loading = false, loadingMore = false, refreshing = false,
                    error = "加载失败，请检查网络后重试")
                retryPrompts.offer("browse", "加载失败", failureDetails(error), "browse") {
                    loadBrowse(selected, more, refresh)
                }
            }
        }
    }
    fun search(query: String, type: String) {
        searchJob?.cancel()
        actors.clear()
        retryPrompts.clear("search")
        if (youth.enabled.value) {
            searchMutable.value = SearchState(error = "青少年模式下已关闭自由搜索，请由家长选择影片")
            return
        }
        val active = api
        if (query.isBlank()) { searchMutable.value = SearchState(error = "请输入影视名称"); return }
        searchHistory.add(query)
        if (type != "bilibili" && dev.pandavideo.data.repository.BilibiliSource.reference(query) == null)
            actors.search(query)
        searchJob = serviceScope.launch {
            searchMutable.value = SearchState(loading = true)
            try {
                val result = active.search(query, type)
                ensureActive()
                searchMutable.value = SearchState(result)
                warmPosters(result.results.map { it.thumbnailUrl })
                // Optional ratings arrive after usable results. This shares searchJob's
                // cancellation lifetime so an old query cannot overwrite the next search.
                val rated = try { active.searchRatings(result, query) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { result }
                ensureActive()
                searchMutable.value = SearchState(rated)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                searchMutable.value = SearchState(error = "搜索失败，请检查网络后重试")
                retryPrompts.offer("search", "搜索失败", failureDetails(error), "search") { search(query, type) }
            }
        }
    }
    fun clearSearch() { searchJob?.cancel(); actors.clear(); retryPrompts.clear("search"); searchMutable.value = SearchState() }

    /** Returns shipped and previously cached titles for the television's local phonetic index. */
    internal suspend fun televisionSearchCatalog(): List<CatalogItem> = api.actorCatalogSnapshot()

    /** Loads a public full-program CCTV page on the source dispatcher. */
    internal suspend fun televisionCctvPrograms(column: String, start: Int = 0, period: String = ""): CatalogPage =
        withContext(resolveDispatcher) { api.cctvPrograms(column, start, period) }

    /** Removes only catalog titles already confirmed to have no playback resources. */
    internal suspend fun inspectTelevisionSearch(titles: List<CatalogItem>): List<CatalogItem> =
        api.visibleCatalog(CatalogPage(titles, 0, titles.size, false)).items

    /**
     * Resolves television detail metadata without creating a controller or starting playback.
     * Sketch catalogs retain their source-owned embed page; film catalogs use identity
     * matching and search requests retain their provider page. Cancellation belongs
     * to the detail composition.
     */
    internal suspend fun televisionDetail(catalog: CatalogItem?, request: PlayerRequest): PlaybackInfo =
        withContext(resolveDispatcher) {
            // Sketch HTML has an explicit embedded video identity, not movie episode
            // links. Route it through api.player's sketch resolver just like mobile.
            val sketchPage = catalog?.takeIf { it.kind == "xiaopin" &&
                dev.pandavideo.data.repository.SketchSource.owns(it.url) }?.url
            val page = request.page ?: sketchPage ?: catalog?.let { api.playbackMatch(it).pageUrl }
                ?: throw ApiError("未找到播放资源")
            api.player(request.copy(page = page))
        }

    /** Opens the chosen TV episode while retaining its originating recommendation scope. */
    internal fun playTelevisionDetail(
        request: PlayerRequest, catalog: CatalogItem?, search: SearchItem?,
        cover: String?, recommendations: List<CatalogItem>?,
    ) {
        startPlayer(request, catalog = catalog, search = search, cover = cover)
        if (session != null) playingRecommendations = recommendations?.toList()
    }
    fun loadHot(type: String = hotType, more: Boolean = false, refresh: Boolean = false) {
        val option = HotTypeOptions.firstOrNull { it.first == type } ?: return
        if (more && (hotMutable.value.loading || hotMutable.value.page?.hasMore != true)) return
        // Tapping the active hot type again is a no-op instead of a full list reload.
        if (!more && !refresh && type == hotType && hotMutable.value.page != null &&
            hotMutable.value.page?.stale != true && hotMutable.value.error == null
        ) return
        hotJob?.cancel()
        retryPrompts.clear("hot")
        val switching = type != hotType
        if (switching) retainTab(TabKey("hot", hotType), hotMutable.value)
        val previous = if (type == hotType) hotMutable.value
            else tabPages[TabKey("hot", type)]?.value ?: CatalogState()
        hotType = type
        if (switching && !more && !refresh && adoptTab(TabKey("hot", type), hotMutable)) {
            hotJob = serviceScope.launch { inspectCatalog(hotMutable) }
            return
        }
        hotMutable.value = previous.copy(loading = true, loadingMore = more, refreshing = refresh && !more, error = null)
        hotJob = serviceScope.launch {
            try {
                val start = if (more) previous.page?.nextStart() ?: 0 else 0
                val label = "最近热播 · ${option.second.removePrefix("热播").removePrefix("热门")}"
                val cached = cachedPublisher(hotMutable, !more && !refresh) { it.copy(label = label) }
                val preloaded = if (switching && !more && !refresh)
                    awaitTab(TabKey("hot", type), hotMutable) else null
                val page = preloaded ?: if (type == "xiaopin") api.sketches(start, refresh, onCached = cached)
                    else api.hotCatalog(type, start, refresh = refresh, onCached = cached)
                ensureActive()
                publishCatalog(hotMutable, previous,
                    page.copy(label = label), more)
                warmPosters(page.items.map { it.cover })
                inspectCatalog(hotMutable)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                hotMutable.value = hotMutable.value.copy(loading = false, loadingMore = false, refreshing = false,
                    error = "加载失败，请检查网络后重试")
                retryPrompts.offer("hot", "加载失败", failureDetails(error), "hot") { loadHot(type, more, refresh) }
            }
        }
    }
    /** Ends initial loading from disk while the same cancellable job revalidates upstream. */
    private fun cachedPublisher(
        target: MutableStateFlow<CatalogState>, enabled: Boolean,
        transform: (CatalogPage) -> CatalogPage = { it },
    ): (suspend (CatalogPage) -> Unit)? = if (!enabled) null else { page ->
        // Never replace an accumulated list with a cached first page while entering a tab.
        if (target.value.items.isEmpty()) {
            publishCatalog(target, target.value, transform(page), false)
            warmPosters(target.value.items.map { it.cover })
        } else {
            currentCoroutineContext().ensureActive()
            target.value = target.value.copy(loading = false, loadingMore = false, refreshing = false)
        }
    }

    private suspend fun publishCatalog(
        target: MutableStateFlow<CatalogState>, previous: CatalogState, page: CatalogPage, more: Boolean,
    ) {
        val visible = api.visibleCatalog(page)
        currentCoroutineContext().ensureActive()
        // An explicit refresh can return exactly the same rows. Reuse the existing list so the
        // grid keeps its item identity and never rebuilds cards whose posters are already shown.
        target.value = CatalogState(
            when {
                target === hotMutable && hotType != "xiaopin" ->
                    mergeHotCatalog(if (more) previous.items + visible.items else visible.items)
                        .let { if (it == previous.items) previous.items else it }
                target === discoverMutable && kind != "top250" ->
                    mergeHighRatedCatalog(if (more) previous.items + visible.items else visible.items)
                        .let { if (it == previous.items) previous.items else it }
                more -> appendCatalog(previous.items, visible.items)
                previous.items == visible.items -> previous.items
                else -> visible.items
            },
            if (more && visible.sections.isNotEmpty()) visible.copy(sections =
                visible.sections.map { section ->
                    section.copy(items = appendCatalog(
                        previous.page?.sections?.find { it.title == section.title }?.items.orEmpty(),
                        section.items))
                }) else visible,
        )
    }

    private suspend fun inspectCatalog(target: MutableStateFlow<CatalogState>) {
        // This runs inside the screen's load job. Switching filters cancels its checks and
        // prevents old requests from altering the new screen. Recheck the accumulated list
        // after pagination, since loading another page can interrupt unfinished checks.
        api.inspectCatalog(target.value.items) { missing ->
            target.update { current ->
                current.copy(
                    items = current.items.filterNot { it == missing },
                    page = current.page?.let { page ->
                        page.copy(items = page.items.filterNot { it == missing })
                    },
                )
            }
        }
    }

    private fun warmPosters(urls: List<String>) {
        serviceScope.launch {
            try { cachePosters(urls) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Optional prefetch must never interrupt visible content. */ }
        }
    }
    private var sketchOpenJob: Job? = null

    /**
     * Opens a catalog title and retains the originating filter result for related selections.
     *
     * @param item Title to resolve.
     * @param recommendations Originating catalog snapshot; defaults to the active session's scope.
     * @param play Navigation callback invoked immediately while source matching proceeds.
     */
    fun match(item: CatalogItem, recommendations: List<CatalogItem>? = playingRecommendations, play: () -> Unit) {
        sketchOpenJob?.cancel()
        sketchOpenJob = null
        retryPrompts.clear("sketch")
        if (!youth.canPlay()) { emitMessage("今日观看已满 40 分钟，请休息"); return }
        if (reuseAudioPlayer(item.url)) {
            play()
            return
        }
        if (item.kind == "xiaopin") {
            // Browser playback cannot report watch time to the app's parental duration guard.
            if (youth.enabled.value) {
                emitMessage("青少年模式下暂不支持原站播放")
                return
            }
            // These providers embed browser players. Do not search a similarly named movie
            // or pass an HTML document to the native controller as though it were media.
            if (!dev.pandavideo.data.repository.SketchSource.owns(item.url)) {
                emitMessage("无效的小品播放页面")
                return
            }
            fun openBrowser() {
                session?.controller?.pause()
                try { platform.openLink(item.url) }
                catch (_: Exception) { emitMessage("无法打开原站播放页面") }
            }
            if (io.ktor.http.Url(item.url).host == "www.xiaopin.tv") {
                openBrowser()
                return
            }
            sketchOpenJob = serviceScope.launch {
                try {
                    val native = withContext(resolveDispatcher) { api.sketchHasNative(item.url) }
                    ensureActive()
                    sketchOpenJob = null
                    if (native) {
                        startPlayer(PlayerRequest(page = item.url, title = item.title), catalog = item)
                        playingRecommendations = recommendations?.toList()
                        play()
                    } else openBrowser()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    retryPrompts.offer("sketch", "小品加载失败", failureDetails(error), "hot") {
                        match(item, recommendations, play)
                    }
                }
            }
            return
        }
        val active = api
        releasePlayer()
        playingCatalog = item
        playingRecommendations = recommendations?.toList()
        playingCover = poster(item)
        session = PlaybackSession({ request ->
            val resolved = if (request.page == null) {
                val match = active.playbackMatch(item)
                request.copy(page = match.pageUrl)
            } else request
            resolveRememberedEpisode(resolved) { value ->
                downloads.offline(value) ?: active.player(value) { page, episodes ->
                    rememberedEpisode(value, page, episodes)
                }
            }
        }, platform.player(), serviceScope, settings.settings.value.defaultRate,
            ::checkpoint, ::resumePosition, ::playbackArtwork,
            onPauseAtEnd = ::finishSleepAfterCurrent, resolveDispatcher = resolveDispatcher,
            lineHasAds = { info, episode -> lineAds.hasAds(info.pageUrl, episode) },
            supplement = { info -> active.supplementPlayback(info, item) },
            skipDurations = { seriesSkipping.get(it.pageUrl,
                dev.pandavideo.core.player.SkipDurations.defaultSecondsFor(it.region)) }).also {
            it.setPauseAtEnd(sleepAfterCurrentMutable.value)
            it.load(PlayerRequest(
                page = dev.pandavideo.data.repository.ContentParsers.playbackPageOrNull(item.url),
                title = item.title,
            ))
        }
        watchPlayerErrors(session!!)
        loadArtwork(item, item.title, item.year)
        play()
    }
    fun startPlayer(
        request: PlayerRequest, catalog: CatalogItem? = null, search: SearchItem? = null,
        cover: String? = null, offlineOnly: Boolean = false,
    ) {
        if (!youth.canPlay()) { emitMessage("今日观看已满 40 分钟，请休息"); return }
        if (!offlineOnly && reuseAudioPlayer(request.page, request.episode)) return
        val active = api
        releasePlayer()
        playingCatalog = catalog
        playingSearch = search
        playingCover = catalog?.let(::poster) ?: search?.let(::poster) ?: cover?.takeIf(::isWebUrl)
        session = PlaybackSession({ value ->
            suspend fun resolve(value: PlayerRequest) =
                downloads.offline(value) ?: if (offlineOnly) throw ApiError("缓存文件无法播放，请重新缓存")
                    else active.player(value) { page, episodes -> rememberedEpisode(value, page, episodes) }
            resolveRememberedEpisode(value, ::resolve)
        }, platform.player(), serviceScope,
            settings.settings.value.defaultRate, ::checkpoint, ::resumePosition,
            ::playbackArtwork, onPauseAtEnd = ::finishSleepAfterCurrent,
            resolveDispatcher = resolveDispatcher,
            lineHasAds = { info, episode -> lineAds.hasAds(info.pageUrl, episode) },
            supplement = if (offlineOnly) null else { info -> active.supplementPlayback(info, catalog) },
            skipDurations = { seriesSkipping.get(it.pageUrl,
                dev.pandavideo.core.player.SkipDurations.defaultSecondsFor(it.region)) }).also {
            it.setPauseAtEnd(sleepAfterCurrentMutable.value)
            it.load(request)
        }
        watchPlayerErrors(session!!)
        // History and favorites reopen with only a play page, so recover the stored year to
        // keep a same-name remake from supplying the wrong synopsis and cast.
        val savedYear = user.history.value.firstOrNull { it.pageUrl == request.page }?.year.orEmpty()
            .ifBlank { user.favorites.value.firstOrNull { it.pageUrl == request.page }?.year.orEmpty() }
        loadArtwork(catalog, catalog?.title ?: search?.title ?: request.title.orEmpty(),
            catalog?.year ?: search?.year ?: savedYear)
    }

    private suspend fun resolveRememberedEpisode(
        value: PlayerRequest, resolve: suspend (PlayerRequest) -> PlaybackInfo,
    ): PlaybackInfo {
        val info = resolve(value)
        // Default title entries restore history before resumePosition records the new selection.
        // Resolve legacy ordering here as well; explicit episode taps keep their requested episode.
        val target = if (shouldResumeEpisode(value))
            user.continueEpisode(info.pageUrl, info.episodes) else null
        return if (target != null && target != info.selected && target != info.resumeFromEpisode &&
            info.episodes.any { it.path == target })
            resolve(value.copy(page = info.pageUrl, episode = target, resumeHistory = false))
        else info
    }

    private fun rememberedEpisode(
        value: PlayerRequest, page: String, episodes: List<Episode>,
    ): String? = if (shouldResumeEpisode(value)) user.continueEpisode(page, episodes) else null

    private fun shouldResumeEpisode(value: PlayerRequest): Boolean {
        val explicitBilibiliPart = dev.pandavideo.data.repository.BilibiliSource.reference(value.page.orEmpty())
            ?.part?.let { it > 1 } == true
        return (value.resumeHistory || value.episode == null) && (!explicitBilibiliPart || value.resumeHistory)
    }

    /**
     * Fills the player details from catalog metadata once playback info has arrived.
     * The request is best-effort, never blocks playback, and is discarded with its session.
     */
    private fun loadArtwork(item: CatalogItem?, title: String, year: String) {
        artworkJob?.cancel()
        artworkJob = null
        if (title.isBlank()) return
        val active = session ?: return
        artworkJob = serviceScope.launch {
            // Source resolution owns startup bandwidth; metadata only needs to exist by the
            // time the details panel has an episode list to render.
            val info = withTimeoutOrNull(15_000) { active.state.first { it.info != null }.info }
                ?: return@launch
            if (session !== active) return@launch
            // Sketch identities come from the embedded video, not Douban film suggestions.
            if (dev.pandavideo.data.repository.SketchSource.owns(info.pageUrl) ||
                dev.pandavideo.data.repository.CctvSource.owns(info.pageUrl) ||
                dev.pandavideo.data.repository.BilibiliSource.reference(info.pageUrl) != null) return@launch
            val details = try { api.artworkDetail(item, title.ifBlank { item?.title.orEmpty() }, year) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null } ?: return@launch
            if (session !== active) return@launch
            playingCatalog = details
            // Persist resolved artwork so a later reopen can match this exact subject.
            val currentInfo = active.state.value.info ?: return@launch
            rememberPlayed(details.title, currentInfo.pageUrl, details.cover, details.year,
                currentInfo.episodes.find { it.path == currentInfo.selected }?.label.orEmpty(), currentInfo.selected,
                rating = details.rating)
        }
    }
    private fun watchPlayerErrors(active: PlaybackSession) {
        playerErrorJob = serviceScope.launch {
            active.state.collect { state ->
                if (state.loading || state.refreshing || state.error == null) {
                    retryPrompts.clear("player")
                } else {
                    val player = active.controller.state.value
                    // A failed episode switch can retain the previous info. Only a still
                    // active media instance may retry metadata refresh instead of playback.
                    val refresh = state.info != null && player.mediaId == state.mediaId &&
                        player.phase in setOf(PlayerPhase.Ready, PlayerPhase.Playing, PlayerPhase.Paused, PlayerPhase.Buffering)
                    retryPrompts.offer("player", if (refresh) "播放信息刷新失败" else "播放失败",
                        failureDetails(IllegalStateException(state.error)), "player",
                        available = { session === active && active.state.value.error != null }) {
                        if (refresh) active.refresh() else active.retry()
                    }
                }
            }
        }
    }
    private fun playbackArtwork(info: PlaybackInfo): String? =
        coverFor(playingCatalog?.title ?: playingSearch?.title ?: info.title, playingCover)
    private fun checkpoint(info: PlaybackInfo, player: PlayerState) {
        user.saveProgress(info.pageUrl, info.selected,
            info.episodes.find { it.path == info.selected }?.label.orEmpty(),
            if (player.phase == PlayerPhase.Ended) player.duration else player.position, player.duration)
    }
    private fun resumePosition(info: PlaybackInfo): Long? {
        // Cache-based route selection retains the requested episode's own saved position,
        // without guessing from a same-name episode or overwriting the new route identity.
        val saved = progressFor(info.pageUrl, info.resumeFromEpisode ?: info.selected)
        rememberPlayed(info.title, info.pageUrl,
            playingCatalog?.cover ?: playingSearch?.thumbnailUrl ?: playingCover,
            playingCatalog?.year ?: playingSearch?.year.orEmpty(),
            info.episodes.find { it.path == info.selected }?.label.orEmpty(), info.selected,
            rating = playingCatalog?.rating)
        user.rememberEpisodes(info.pageUrl, info.episodes)
        return saved?.takeIf {
            !it.finished && it.position > 0 && it.position < it.duration
        }?.position
    }
    fun poster(item: CatalogItem) = item.cover.takeIf(::isWebUrl)
    fun poster(item: SearchItem) = item.thumbnailUrl.takeIf(::isWebUrl)

    /** In-memory pool of every catalog row currently held by the home/browse/hot rails. */
    private val catalogPool: List<CatalogItem>
        get() = (discover.value.items + browse.value.items + hot.value.items).distinctBy { it.url }

    /** Builds the resume card from its durable snapshot and unambiguous loaded catalog metadata. */
    internal fun historyPoster(record: HistoryTitle, items: List<CatalogItem>): CatalogItem =
        dev.pandavideo.ui.catalog.historyCatalogPoster(record, (items + catalogPool).distinctBy { it.url })

    /** Repairs legacy metadata without blocking catalog rendering or changing the saved episode. */
    internal suspend fun fillHistoryMetadata(record: HistoryTitle, items: List<CatalogItem>) {
        if (record.year.isNotBlank() && record.rating != null) return
        val match = dev.pandavideo.ui.catalog.historyCatalogMatch(record,
            (items + catalogPool).distinctBy { it.url })
        if (match != null) user.fillHistoryMetadata(record.pageUrl, match.year, match.rating, match.cover)
        val current = user.history.value.find { it.pageUrl == record.pageUrl && it.title == record.title } ?: return
        if (current.year.isNotBlank() && current.rating != null) return
        val details = try { api.artworkDetail(match, current.title, current.year) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null } ?: return
        // Metadata may complete after deletion or a source refresh. Only repair the existing
        // title and a compatible edition; never insert/promote a history row from this request.
        if (user.history.value.none { it.pageUrl == record.pageUrl && it.title == record.title }) return
        if (dev.pandavideo.ui.catalog.historyCatalogMatch(current, listOf(details)) == null) return
        user.fillHistoryMetadata(record.pageUrl, details.year, details.rating, details.cover)
    }

    /**
     * Resolves a display cover for titles opened outside catalog lists (history, favorites and
     * the mini player). The persisted snapshot wins; when it is missing — for example older
     * records saved before artwork was available — the in-memory catalog and search pools supply
     * the same poster the title shows on other pages.
     */
    fun coverFor(title: String?, fallback: String? = null): String? {
        fallback?.takeIf(::isWebUrl)?.let { return it }
        if (title.isNullOrBlank()) return null
        catalogPool.firstOrNull { it.title == title }?.let(::poster)?.let { return it }
        search.value.result?.results?.firstOrNull { it.title == title }?.let(::poster)?.let { return it }
        return null
    }

    /** Records a successfully resolved title in playback history (newest first). */
    fun rememberPlayed(
        title: String, pageUrl: String, cover: String?, year: String,
        episode: String = "", episodePath: String = "",
        rating: Double? = null,
    ) {
        val known = dev.pandavideo.ui.catalog.historyCatalogMatch(
            HistoryTitle(title, pageUrl, year = year), (listOfNotNull(playingCatalog) + catalogPool).distinctBy { it.url })
        user.recordHistory(title, pageUrl, cover.orEmpty(), year.ifBlank { known?.year.orEmpty() },
            episode, episodePath, rating = rating ?: known?.rating)
    }

    /** Persists an explicit playback checkpoint when the media is long enough to track. */
    fun saveProgress(pageUrl: String, episodePath: String, episodeLabel: String, position: Long, duration: Long) {
        user.saveProgress(pageUrl, episodePath, episodeLabel, position, duration)
    }

    /** Stored resume record for one episode. */
    fun progressFor(pageUrl: String, episodePath: String) = user.progressFor(pageUrl, episodePath)

    /** Episode label + mm:ss resume label for a history record, or null when nothing resumable. */
    fun resumeLabel(record: HistoryTitle): String? {
        val fraction = progressFraction(record.positionMs, record.durationMs)
        if (record.positionMs < RESUME_MIN_POSITION_MS || fraction < RESUME_MIN_FRACTION ||
            fraction >= UserStore.FINISH_THRESHOLD) return null
        val episode = record.episode.takeIf { it.isNotBlank() }?.let { "看到 $it · " }.orEmpty()
        return episode + formatMinutesSeconds(record.positionMs)
    }

    /** Toggles favorite state for the currently open title; returns the new saved state. */
    fun toggleFavorite(title: String, pageUrl: String, cover: String?, year: String): Boolean {
        // The player screen may have no artwork snapshot (e.g. reopened from an old favorite);
        // backfill from the cached catalog pools so the saved record never starts cover-less.
        val resolved = cover?.takeIf(::isWebUrl) ?: coverFor(title)
        val saved = user.toggleFavorite(title, pageUrl, resolved.orEmpty(), year)
        emitMessage(if (saved) "已加入收藏" else "已取消收藏")
        return saved
    }

    /** Opens the home card's recorded episode, retaining its saved position and artwork. */
    internal fun resumeHomeHistory(record: HistoryTitle) {
        // This explicit home entry opens the episode shown by the saved record, including a
        // completed episode. Generic library continuation may choose its successor instead.
        startPlayer(PlayerRequest(page = record.pageUrl, title = record.title,
            episode = record.episodePath.takeIf { it.isNotBlank() }),
            cover = coverFor(record.title, record.cover))
    }

    /** Returns to the same audio session without resolving media or changing playback intent. */
    private fun reuseAudioPlayer(pageUrl: String?, episode: String? = null): Boolean {
        val active = session ?: return false
        val current = active.state.value
        if (!current.audioOnly || current.finished || current.error != null || pageUrl == null) return false
        // Catalog identity and resolved playback identity differ; never match by title alone.
        if (pageUrl != playingCatalog?.url && pageUrl != current.info?.pageUrl) return false
        if (episode != null && episode != current.info?.selected) return false
        active.setPlaybackVisible(true)
        return true
    }

    /** Reopens a saved title, retaining ongoing audio or restoring its unfinished episode. */
    fun reopenSaved(pageUrl: String, title: String, cover: String? = null) {
        if (!youth.canPlay()) { emitMessage("今日观看已满 40 分钟，请休息"); return }
        if (reuseAudioPlayer(pageUrl)) return
        val episode = user.continueEpisode(pageUrl)
        startPlayer(PlayerRequest(page = pageUrl, title = title, episode = episode, resumeHistory = true), cover = cover)
    }
    /**
     * Retains the session for reopening. Audio mode continues; video pauses without visible PiP.
     *
     * @param playerSession Session leaving the screen; may differ from a newly opened one.
     */
    fun minimizePlayer(playerSession: PlaybackSession? = session) {
        if (platform.pictureInPictureActive.value || playerSession?.state?.value?.audioOnly == true)
            playerSession?.saveOnExit()
        playerSession?.setPlaybackVisible(platform.pictureInPictureActive.value)
        rememberPlayerLevels()
        playerLevelsActive = false
        platform.resetBrightness()
        platform.fullscreen(false)
    }

    /** Explicitly closes playback and cancels the sleep countdown. */
    fun closePlayer() {
        setSleepTimer(0)
        releasePlayer()
    }

    // Opening/replacing a title releases its old session without cancelling an
    // app-wide sleep timer that the user may have already armed in Settings.
    private fun releasePlayer() {
        sketchOpenJob?.cancel(); sketchOpenJob = null
        retryPrompts.clear("sketch")
        playerErrorJob?.cancel()
        artworkJob?.cancel(); artworkJob = null
        retryPrompts.clear("player")
        if (session != null) rememberPlayerLevels()
        session?.close(); session = null; playingCatalog = null; playingSearch = null; playingCover = null
        resolvedCastActors.clear()
        playingRecommendations = null
        platform.resetBrightness()
        platform.fullscreen(false)
    }
    fun close() { downloads.close(); serviceJob.cancel(); closePlayer(); api.close() }

    companion object {
        /** Positions under 10s reopen from the start instead of showing a resume marker. */
        private const val RESUME_MIN_POSITION_MS = 10_000L
        private const val RESUME_MIN_FRACTION = 0.03f
    }
}
