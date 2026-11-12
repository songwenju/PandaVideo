package dev.pandavideo.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.SearchResult
import dev.pandavideo.core.model.appendCatalog
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
    val user = UserStore(platform.storage())
    val settings = SettingsStore(platform.storage())
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
        onFailure = ::reportDownloadFailure, resolve = api::player,
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
    private var sleepJob: Job? = null
    private var playerLevelsActive = false
    private val sleepRemainingMutable = MutableStateFlow(0)
    /** Remaining sleep timer seconds; zero means disabled. Timers are never persisted. */
    val sleepRemaining = sleepRemainingMutable.asStateFlow()
    private val sleepTimerMinutesMutable = MutableStateFlow(0)
    /** Selected sleep duration in minutes; retained during countdown and cleared on cancellation or expiry. */
    val sleepTimerMinutes = sleepTimerMinutesMutable.asStateFlow()
    /**
     * Starts or replaces the app-wide sleep countdown, including before playback opens.
     *
     * @param minutes Duration clamped to 0–180 minutes; zero cancels the countdown.
     */
    fun setSleepTimer(minutes: Int) {
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
            active?.dismissEnded()
            active?.controller?.pause()
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
    var playingCatalog: CatalogItem? = null
        private set
    /** Catalog snapshot that opened this session; null allows unscoped search/history suggestions. */
    var playingRecommendations: List<CatalogItem>? by mutableStateOf(null)
        private set
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
        discoverJob?.cancel()
        retryPrompts.clear("discover")
        val previous = if (selected == kind && country == discoverCountry) discoverMutable.value else CatalogState()
        kind = selected
        discoverCountry = country
        discoverMutable.value = previous.copy(loading = true, loadingMore = more, refreshing = refresh && !more, error = null)
        discoverJob = serviceScope.launch {
            try {
                val page = active.list(selected, if (more) previous.page?.nextStart() ?: 0 else 0, country)
                ensureActive()
                publishCatalog(discoverMutable, previous, page, more)
                warmPosters(page.items.map { it.cover })
                if (!more && country == "all" && catalogWarmJobs[selected]?.isActive != true) {
                    catalogWarmJobs[selected] = serviceScope.launch { active.warmCatalog(selected, page) }
                }
                inspectCatalog(discoverMutable)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                discoverMutable.value = previous.copy(loading = false, loadingMore = false, refreshing = false,
                    error = "加载失败，请检查网络后重试")
                retryPrompts.offer("discover", "加载失败", "请检查网络连接后重试", "discover") {
                    loadDiscover(selected, more, country, refresh)
                }
            }
        }
    }
    fun loadBrowse(selected: BrowseFilters = filters, more: Boolean = false, refresh: Boolean = false) {
        val active = api
        if (more && (browseMutable.value.loading || browseMutable.value.page?.hasMore != true)) return
        browseJob?.cancel()
        retryPrompts.clear("browse")
        val previous = if (selected == filters) browseMutable.value else CatalogState()
        filters = selected
        browseMutable.value = previous.copy(loading = true, loadingMore = more, refreshing = refresh && !more, error = null)
        browseJob = serviceScope.launch {
            try {
                val page = active.browse(selected, if (more) previous.page?.nextStart() ?: 0 else 0)
                ensureActive()
                publishCatalog(browseMutable, previous, page, more)
                warmPosters(page.items.map { it.cover })
                inspectCatalog(browseMutable)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                browseMutable.value = previous.copy(loading = false, loadingMore = false, refreshing = false,
                    error = "加载失败，请检查网络后重试")
                retryPrompts.offer("browse", "加载失败", "请检查网络连接后重试", "browse") {
                    loadBrowse(selected, more, refresh)
                }
            }
        }
    }
    fun search(query: String, type: String) {
        searchJob?.cancel()
        retryPrompts.clear("search")
        if (youth.enabled.value) {
            searchMutable.value = SearchState(error = "青少年模式下已关闭自由搜索，请由家长选择影片")
            return
        }
        val active = api
        if (query.isBlank()) { searchMutable.value = SearchState(error = "请输入影视名称"); return }
        searchHistory.add(query)
        searchJob = serviceScope.launch {
            searchMutable.value = SearchState(loading = true)
            try {
                val result = active.search(query, type)
                ensureActive()
                searchMutable.value = SearchState(result)
                warmPosters(result.results.map { it.thumbnailUrl })
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                searchMutable.value = SearchState(error = "搜索失败，请检查网络后重试")
                retryPrompts.offer("search", "搜索失败", "请检查网络连接后重试", "search") { search(query, type) }
            }
        }
    }
    fun clearSearch() { searchJob?.cancel(); retryPrompts.clear("search"); searchMutable.value = SearchState() }
    fun loadHot(type: String = hotType, more: Boolean = false, refresh: Boolean = false) {
        val option = HotTypeOptions.firstOrNull { it.first == type } ?: return
        if (more && (hotMutable.value.loading || hotMutable.value.page?.hasMore != true)) return
        hotJob?.cancel()
        retryPrompts.clear("hot")
        val previous = if (type == hotType) hotMutable.value else CatalogState()
        hotType = type
        hotMutable.value = previous.copy(loading = true, loadingMore = more, refreshing = refresh && !more, error = null)
        hotJob = serviceScope.launch {
            try {
                val page = api.browse(BrowseFilters(type = type, sort = "hot"), if (more) previous.page?.nextStart() ?: 0 else 0)
                ensureActive()
                publishCatalog(hotMutable, previous,
                    page.copy(label = "最近热播 · ${option.second.removePrefix("热播").removePrefix("热门")}"), more)
                warmPosters(page.items.map { it.cover })
                inspectCatalog(hotMutable)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                hotMutable.value = previous.copy(loading = false, loadingMore = false, refreshing = false,
                    error = "加载失败，请检查网络后重试")
                retryPrompts.offer("hot", "加载失败", "请检查网络连接后重试", "hot") { loadHot(type, more, refresh) }
            }
        }
    }
    private suspend fun publishCatalog(
        target: MutableStateFlow<CatalogState>, previous: CatalogState, page: CatalogPage, more: Boolean,
    ) {
        val visible = api.visibleCatalog(page)
        currentCoroutineContext().ensureActive()
        target.value = CatalogState(
            if (more) appendCatalog(previous.items, visible.items) else visible.items, visible,
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
    /**
     * Opens a catalog title and retains the originating filter result for related selections.
     *
     * @param item Title to resolve.
     * @param recommendations Originating catalog snapshot; defaults to the active session's scope.
     * @param play Navigation callback invoked immediately while source matching proceeds.
     */
    fun match(item: CatalogItem, recommendations: List<CatalogItem>? = playingRecommendations, play: () -> Unit) {
        if (!youth.canPlay()) { emitMessage("今日观看已满 40 分钟，请休息"); return }
        val active = api
        releasePlayer()
        playingCatalog = item
        playingRecommendations = recommendations?.toList()
        playingCover = poster(item)
        session = PlaybackSession({ request ->
            val resolved = if (request.page == null) {
                val match = active.resolve(item)
                if (!match.playable || match.pageUrl.isBlank()) throw ApiError("未找到明确匹配的播放资源")
                request.copy(page = match.pageUrl)
            } else request
            downloads.offline(resolved) ?: active.player(resolved)
        }, platform.player(), serviceScope, settings.settings.value.defaultRate,
            ::checkpoint, ::resumePosition, ::playbackArtwork).also {
            it.load(PlayerRequest(title = item.title))
        }
        watchPlayerErrors(session!!)
        play()
    }
    fun startPlayer(
        request: PlayerRequest, catalog: CatalogItem? = null, search: SearchItem? = null,
        cover: String? = null, offlineOnly: Boolean = false,
    ) {
        if (!youth.canPlay()) { emitMessage("今日观看已满 40 分钟，请休息"); return }
        val active = api
        releasePlayer()
        playingCatalog = catalog
        playingSearch = search
        playingCover = catalog?.let(::poster) ?: search?.let(::poster) ?: cover?.takeIf(::isWebUrl)
        session = PlaybackSession({
            downloads.offline(it) ?: if (offlineOnly) throw ApiError("缓存文件无法播放，请重新缓存")
                else active.player(it)
        }, platform.player(), serviceScope,
            settings.settings.value.defaultRate, ::checkpoint, ::resumePosition,
            ::playbackArtwork).also { it.load(request) }
        watchPlayerErrors(session!!)
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
                        "请检查网络连接，或稍后重试", "player",
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
        rememberPlayed(info.title, info.pageUrl,
            playingCatalog?.cover ?: playingSearch?.thumbnailUrl ?: playingCover,
            playingCatalog?.year.orEmpty(),
            info.episodes.find { it.path == info.selected }?.label.orEmpty(), info.selected)
        return progressFor(info.pageUrl, info.selected)?.takeIf {
            !it.finished && it.position >= 5_000 && it.position < it.duration - 5_000
        }?.position
    }
    fun poster(item: CatalogItem) = item.cover.takeIf(::isWebUrl)
    fun poster(item: SearchItem) = item.thumbnailUrl.takeIf(::isWebUrl)

    /** In-memory pool of every catalog row currently held by the home/browse/hot rails. */
    private val catalogPool: List<CatalogItem>
        get() = (discover.value.items + browse.value.items + hot.value.items).distinctBy { it.url }

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
    ) {
        user.recordHistory(title, pageUrl, cover.orEmpty(), year, episode, episodePath)
    }

    /** Throttled playback-position persistence; returns when the media is long enough to track. */
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

    /** Reopens a saved/history title, resuming the unfinished episode when one is recorded. */
    fun reopenSaved(pageUrl: String, title: String, cover: String? = null) {
        val latest = user.latestProgress(pageUrl)
        val episode = latest?.episodePath?.takeIf { it.isNotBlank() && !latest.finished }
        startPlayer(PlayerRequest(page = pageUrl, title = title, episode = episode), cover = cover)
    }
    /**
     * Retains the session for reopening. Audio mode continues; video pauses without visible PiP.
     *
     * @param playerSession Session leaving the screen; may differ from a newly opened one.
     */
    fun minimizePlayer(playerSession: PlaybackSession? = session) {
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
        playerErrorJob?.cancel()
        retryPrompts.clear("player")
        if (session != null) rememberPlayerLevels()
        session?.close(); session = null; playingCatalog = null; playingSearch = null; playingCover = null
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
