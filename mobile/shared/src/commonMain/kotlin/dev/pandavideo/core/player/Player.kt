package dev.pandavideo.core.player

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.LineProbe
import dev.pandavideo.core.model.nextEpisode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class PlayerPhase { Idle, Preparing, Ready, Playing, Paused, Buffering, Ended, Error, Released }
data class MediaSource(
    val id: Long, val url: String, val kind: String, val title: String,
    /** Label of the selected episode, used by platform now-playing metadata. */
    val episodeLabel: String = "",
    /** Whether loading this source may start playback before an explicit play action. */
    val autoplay: Boolean = true,
    /** Poster URL used by system now-playing artwork; never a video download URL. */
    val artworkUrl: String? = null,
    /** Initial position in milliseconds; applied before buffering on supporting hosts. */
    val startPosition: Long = 0,
    /** Opening policy evaluated against native duration before the first playback frame. */
    val startupSkip: SkipDurations = SkipDurations(),
    /** Ordered assets sharing one full-program timeline; [url] is the single-URL fallback. */
    val segments: List<dev.pandavideo.core.model.MediaSegment> = emptyList(),
)
data class PlayerCapabilities(
    val playbackRate: Boolean = true, val seeking: Boolean = true,
    val fullscreen: Boolean = true, val pictureInPicture: Boolean = false,
    val framePreview: Boolean = false,
    /** Whether the host can disable video tracks while retaining audio playback. */
    val audioOnly: Boolean = false,
    /** Whether load applies [MediaSource.startPosition] before initial buffering. */
    val initialPosition: Boolean = false,
    /** Whether load combines history and intro skipping before starting native playback. */
    val initialSkipping: Boolean = false,
)

/** Combines history and opening credits into one bounded target; unknown/live timelines stay at zero. */
fun MediaSource.startupPosition(duration: Long): Long {
    if (duration <= 0) return 0
    val intro = if (startupSkip.fits(duration)) startupSkip.introSeconds * 1_000L else 0L
    return maxOf(startPosition, intro).coerceIn(0, (duration - 1_000).coerceAtLeast(0))
}
/** A small encoded image of [position] milliseconds in the source identified by [mediaId]. */
data class PreviewFrame(val mediaId: Long, val position: Long, val bytes: ByteArray)
data class PlayerState(
    val mediaId: Long = 0, val phase: PlayerPhase = PlayerPhase.Idle,
    val position: Long = 0, val duration: Long = 0, val wantsPlay: Boolean = false,
    val rate: Float = 1f, val error: String? = null,
    /** True after the video output reports a first frame for this media ID. */
    val firstFrameRendered: Boolean = false,
    /** Whether a completed native media-data preload was reused by this load. */
    val dataPreloadHit: Boolean = false,
)
interface PlayerController {
    val state: StateFlow<PlayerState>
    val capabilities: PlayerCapabilities get() = PlayerCapabilities()
    fun load(source: MediaSource)
    fun play()
    fun pause()
    fun seekTo(position: Long)
    fun setPlaybackRate(rate: Float)
    /** Enables audio-only output without reloading, seeking, or changing play intent. */
    fun setAudioOnly(enabled: Boolean) {}
    fun stop()
    fun close()
    /**
     * Prepares one candidate without playing it or changing current/system playback state.
     * Null cancels the candidate. Unsupported hosts ignore this optional optimization.
     */
    fun preload(source: MediaSource?) {}
    /**
     * Extracts a small image near [position] milliseconds without seeking the playback player.
     * Returns null for unsupported sources or failures. Cancellation must prevent stale delivery;
     * implementations stop native work where supported and bound outstanding work otherwise.
     * Callers must discard frames belonging to an earlier media ID.
     */
    suspend fun previewFrame(position: Long): PreviewFrame? = null
    /**
     * Installs handlers for external transport events (lock screen, notification, headset).
     * Pass null handlers on teardown. Platforms without remote controls ignore this.
     */
    fun setRemoteEpisodeActions(onNext: (() -> Unit)?, onPrevious: (() -> Unit)?) {}
}
data class PlaybackState(
    val info: PlaybackInfo? = null, val loading: Boolean = false,
    val error: String? = null, val mediaId: Long = 0, val refreshing: Boolean = false,
    /** Transient completion marker while handing the next episode to the resolver. */
    val ended: Boolean = false,
    /** Explicit session-scoped permission to play audio without a visible video presentation. */
    val audioOnly: Boolean = false,
    /** True after a synthetic credits end, including the final episode. */
    val finished: Boolean = false,
)

/** Serializes resolution and ignores stale or duplicate completion events. */
class PlaybackSession(
    private val resolve: suspend (PlayerRequest) -> PlaybackInfo,
    val controller: PlayerController, private val scope: CoroutineScope,
    initialRate: Float = 1f,
    private val checkpoint: (PlaybackInfo, PlayerState) -> Unit = { _, _ -> },
    private val resumePosition: (PlaybackInfo) -> Long? = { null },
    private val artworkUrl: (PlaybackInfo) -> String? = { null },
    private val nowMs: () -> Long = monotonicClock(),
    private val onPauseAtEnd: () -> Unit = {},
    /**
     * Dispatcher for source resolution, or null to use the session's own dispatcher.
     * Hosts pass a background dispatcher: detail pages are large and episode lists can hold
     * hundreds of entries, so resolving on the UI dispatcher delays the first composition.
     */
    private val resolveDispatcher: CoroutineDispatcher? = null,
    /** Manual advertising reports rank automatic recovery without overriding explicit line taps. */
    private val lineHasAds: (PlaybackInfo, Episode) -> Boolean = { _, _ -> false },
    private val skipDurations: (PlaybackInfo) -> SkipDurations = { SkipDurations() },
    /** Optional background mirror discovery; it must return metadata without loading media. */
    private val supplement: (suspend (PlaybackInfo) -> PlaybackInfo)? = null,
) {
    private var activeSkip = SkipDurations()
    private var introConsumed = false
    private var outroExempt = false
    /**
     * Seeks on behalf of the viewer, exempting intentional intro/credits replay from skipping.
     * This explicit boundary avoids confusing a native buffer jump with a user gesture.
     */
    fun seekTo(position: Long) {
        if (closed || !controller.capabilities.seeking || mutable.value.loading) return
        val player = controller.state.value
        if (player.mediaId != mutable.value.mediaId) return
        introConsumed = true
        // Entering credits by fast-forward is still normal episode completion. Only
        // moving back within credits or reopening a completed episode requests replay.
        // Unknown duration cannot establish a credits boundary and must not disable the
        // outro policy for the rest of the episode once the native timeline arrives.
        if (mutable.value.ended || mutable.value.finished ||
            (player.duration > 0 && position < player.position &&
                position >= player.duration - activeSkip.outroSeconds * 1_000L)) outroExempt = true
        dismissEnded()
        mutable.value = mutable.value.copy(finished = false)
        controller.seekTo(position.coerceAtLeast(0))
    }
    private val telemetry = PlaybackTelemetry(nowMs)
    /** Bounded local samples, without URLs or user data; readiness is distinct from first frame. */
    val startupSamples: StateFlow<List<PlaybackStartupSample>> = telemetry.samples
    private var playbackRate = initialRate
    /** Sets the session speed, preserving it across episode loads. */
    fun setPlaybackRate(rate: Float) {
        if (!controller.capabilities.playbackRate) return
        playbackRate = rate
        controller.setPlaybackRate(rate)
    }
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    private var request: PlayerRequest? = null
    private var resolution: Job? = null
    private var refreshJob: Job? = null
    private var supplementJob: Job? = null
    private data class Preload(
        val request: PlayerRequest, val result: Deferred<PlaybackInfo?>,
        var completedMs: Long? = null, var info: PlaybackInfo? = null,
    )
    private var preload: Preload? = null
    private var nativePreload: Preload? = null
    private var generation = 0L
    private var endedId = -1L
    private var closed = false
    private var pauseAtEnd = false
    private var playbackVisible = true
    private var recoveryAttempts = 0
    private var manualLine = false
    private var bufferingJob: Job? = null
    private var recoveryPosition: Long? = null
    // Keep the initial target through native readiness and retries, including an explicit zero.
    private var resumeTarget: Long? = null
    private var intendedPlay = true
    private val playbackAllowed: Boolean get() = playbackVisible || mutable.value.audioOnly
    /**
     * Switches output mode for this session and preserves it across episode changes.
     * Disabling audio mode while hidden checkpoints and pauses; enabling never resumes a pause.
     */
    fun setAudioOnly(enabled: Boolean) {
        if (closed || !controller.capabilities.audioOnly || mutable.value.audioOnly == enabled) return
        controller.setAudioOnly(enabled)
        mutable.value = mutable.value.copy(audioOnly = enabled)
        if (!playbackAllowed) pauseHiddenPlayback()
    }
    /**
     * Arms a one-shot pause at the end of the currently playing episode, before auto-advance.
     *
     * @param enabled Whether the next valid end event should pause instead of loading the next episode.
     */
    fun setPauseAtEnd(enabled: Boolean) {
        if (closed) return
        pauseAtEnd = enabled
        val player = controller.state.value
        if (enabled && !mutable.value.loading && player.mediaId == mutable.value.mediaId &&
            player.phase == PlayerPhase.Ended) finishPauseAtEnd()
    }
    /** Pauses playback and pending autoplay without releasing the session or its timeline. */
    fun pauseForSleepTimer() {
        if (closed) return
        intendedPlay = false
        savePosition()
        dismissEnded()
        controller.pause()
    }
    private fun finishPauseAtEnd() {
        pauseAtEnd = false
        pauseForSleepTimer()
        onPauseAtEnd()
    }
    private fun pauseHiddenPlayback() {
        intendedPlay = false
        savePosition()
        dismissEnded()
        controller.pause()
    }
    /**
     * Records whether the page or an explicit PiP window is visible.
     * Hiding checkpoints and pauses even pending loads unless audio mode is enabled.
     * Showing never resumes by itself.
     *
     * @param visible Whether the video currently has a visible presentation.
     */
    fun setPlaybackVisible(visible: Boolean) {
        if (closed || playbackVisible == visible) return
        playbackVisible = visible
        if (!playbackAllowed) pauseHiddenPlayback()
    }
    private var resumedId = -1L
    /** Checkpoints a page exit even when audio or PiP keeps the native session alive. */
    fun saveOnExit() { if (!closed) savePosition() }
    private fun savePosition() {
        val current = mutable.value
        val player = controller.state.value
        if (!current.loading && player.mediaId == current.mediaId && player.duration >= 20_000) {
            current.info?.let {
                // Credits skipping and sleep-at-end may pause the native player before it
                // reports Ended. Preserve completion through episode switching and teardown.
                checkpoint(it, if (current.finished || current.ended)
                    player.copy(phase = PlayerPhase.Ended, position = player.duration) else player)
            }
        }
    }
    private val observer = scope.launch {
        controller.state.collect { player ->
            telemetry.observe(player, mutable.value.audioOnly)
            updateNativePreload()
            refreshPreloadNearEnd()
            // Native readiness and remote transport events can arrive after navigation.
            // Enforce the same policy for these events as for asynchronous resolution.
            if (!closed && !playbackAllowed && player.wantsPlay) controller.pause()
            val current = mutable.value
            // AVPlayer and JavaFX clear wantsPlay when reporting an error. Preserve the
            // last non-error intent so recovery continues active playback but respects pause.
            if (!closed && !current.loading && player.mediaId == current.mediaId &&
                player.phase != PlayerPhase.Error) intendedPlay = player.wantsPlay
            watchBuffering(player)
            if (!closed && !current.loading && player.mediaId == current.mediaId &&
                player.mediaId != resumedId && player.duration > 0 &&
                player.phase in setOf(PlayerPhase.Ready, PlayerPhase.Playing, PlayerPhase.Paused, PlayerPhase.Buffering)) {
                resumedId = player.mediaId
                val intro = if (!introConsumed && controller.capabilities.seeking &&
                    activeSkip.fits(player.duration)) activeSkip.introSeconds * 1_000L else 0L
                if (intro > 0) introConsumed = true
                val position = (recoveryPosition ?: resumeTarget) ?: 0L
                val target = maxOf(position, intro)
                if (target > 0 && target != player.position)
                    controller.seekTo(target.coerceIn(0, (player.duration - 1_000).coerceAtLeast(0)))
                recoveryPosition = null
            }
            if (!closed && playbackAllowed && !current.loading && current.error == null &&
                player.mediaId == current.mediaId && controller.capabilities.seeking &&
                activeSkip.fits(player.duration) && player.phase == PlayerPhase.Playing &&
                player.wantsPlay && !current.ended && !current.finished) {
                if (!introConsumed) {
                    introConsumed = true
                    // A deferred resume seek may still be awaiting its native position event.
                    // Never replace a later resume target with the intro boundary.
                    val resume = resumeTarget ?: 0L
                    if (activeSkip.introSeconds > 0 && player.position < activeSkip.introSeconds * 1_000L &&
                        resume < activeSkip.introSeconds * 1_000L) {
                        controller.seekTo(activeSkip.introSeconds * 1_000L)
                        return@collect
                    }
                }
                if (!outroExempt && activeSkip.outroSeconds > 0 &&
                    player.position >= player.duration - activeSkip.outroSeconds * 1_000L &&
                    player.mediaId != endedId) {
                    finishEpisode(player, synthetic = true)
                    return@collect
                }
            }
            if (!closed && playbackAllowed && !current.loading && current.error == null &&
                player.mediaId == current.mediaId && player.phase == PlayerPhase.Error) {
                if (!recoverSource(player)) {
                    mutable.value = current.copy(error = player.error ?: "当前播放线路无法播放，请重试或切换线路")
                }
            }
            if (!closed && playbackAllowed && player.phase == PlayerPhase.Ended && player.mediaId == current.mediaId &&
                player.mediaId != endedId && !current.loading && !current.ended) {
                finishEpisode(player, synthetic = false)
            }
        }
    }
    private fun watchBuffering(player: PlayerState) {
        val current = mutable.value
        if (closed || manualLine || !playbackAllowed || current.loading || current.error != null ||
            player.mediaId != current.mediaId || !player.wantsPlay || player.phase != PlayerPhase.Buffering) {
            bufferingJob?.cancel()
            bufferingJob = null
            return
        }
        // Native buffered-position updates must not restart the deadline. Leaving buffering,
        // pausing or changing media cancels it, so a healthy seek never triggers a later switch.
        if (bufferingJob != null) return
        val id = player.mediaId
        bufferingJob = scope.launch {
            delay(AUTO_LINE_BUFFER_TIMEOUT_MS)
            val latest = controller.state.value
            if (!closed && !manualLine && playbackAllowed && !mutable.value.loading &&
                mutable.value.error == null && latest.mediaId == id && latest.wantsPlay &&
                latest.phase == PlayerPhase.Buffering) {
                if (!recoverSource(latest))
                    mutable.value = mutable.value.copy(error = "播放持续缓冲，请重试或切换线路")
            }
        }
    }
    private fun recoverSource(player: PlayerState): Boolean {
        val info = mutable.value.info ?: return false
        if (manualLine) return false
        if (recoveryAttempts >= 2) return false
        // A segmented route and its complete HLS fallback share one PID. Reject the failing
        // MP4 tier, retaining that stable episode and HLS so recovery can replay the whole program.
        val rejected = request?.rejectedMedia.orEmpty() +
            if (info.segments.isEmpty()) setOf(info.url) else info.segments.map { it.url }.toSet()
        val rejectedEpisodes = request?.rejectedEpisodes.orEmpty() +
            if (info.segments.isEmpty()) setOf(info.selected) else emptySet()
        val selected = info.episodes.find { it.path == info.selected }
        val index = info.episodes.filter { it.line == selected?.line }.indexOf(selected)
        // Only a corresponding episode can be an automatic target. Unlike a manual tap,
        // recovery must never fall back to episode one of a shorter or unrelated line.
        val target = info.episodes.groupBy { it.line }.values.mapNotNull { line ->
            selected?.label?.takeIf { it.isNotBlank() }?.let { label -> line.find { it.label == label } }
                ?: line.getOrNull(index)
        }.filter { it.path !in rejectedEpisodes && info.lineProbes[it.line]?.available == true &&
            info.lineProbes[it.line]?.elapsedMs?.let { elapsed -> elapsed >= 0 } == true }
            .minWithOrNull(compareBy<Episode> { lineHasAds(info, it) }
                .thenBy { info.lineProbes.getValue(it.line).elapsedMs!! })
        recoveryAttempts++
        // Fresh measurements rank recovery; absent measurements retain the resolver's
        // existing same-episode fallback. Failed stable paths and URLs cannot cycle back.
        loadRequest(PlayerRequest(page = info.pageUrl, title = info.title,
            episode = target?.path ?: info.selected, rejectedMedia = rejected,
            rejectedEpisodes = rejectedEpisodes, preferFastestLine = target == null),
            recoveryPosition ?: if (player.mediaId != resumedId && player.position == 0L)
                resumeTarget ?: 0L else player.position, intendedPlay, reason = "recovery", preserveSkip = true)
        return true
    }
    private fun finishEpisode(player: PlayerState, synthetic: Boolean) {
        val current = mutable.value
        endedId = player.mediaId
        introConsumed = true
        outroExempt = true
        mutable.value = current.copy(finished = true)
        savePosition()
        if (synthetic) controller.pause()
        // Sleep-after-current is consumed before scheduling any automatic episode load.
        if (pauseAtEnd) {
            finishPauseAtEnd()
            return
        }
        if (nextEpisode(current.info ?: return) == null) {
            if (synthetic) {
                mutable.value = current.copy(finished = true)
                controller.seekTo(player.duration)
            }
            return
        }
        // Completion is already checkpointed. Hand off immediately, including a prepared
        // next source; no interstitial UI or timer should delay continuous playback.
        mutable.value = current.copy(ended = true, finished = true)
        advanceToNext()
    }
    /** Immediately loads the same-line successor after a valid episode completion. */
    fun advanceToNext() {
        val current = mutable.value
        if (!playbackAllowed || !current.ended) return
        nextEpisode(current.info ?: return)?.let { select(it) }
    }
    /** Clears a transient completion marker when pausing or explicitly seeking. */
    fun dismissEnded() {
        if (mutable.value.ended) mutable.value = mutable.value.copy(ended = false)
    }
    fun load(value: PlayerRequest) {
        // Explicit route choices disable fallback. An episode path alone can also come
        // from automatic history/preloading and must not be mistaken for a manual line tap.
        manualLine = !value.allowLineFallback
        recoveryAttempts = 0
        loadRequest(value.copy(preferFastestLine = if (supplement != null) false else !manualLine,
            reuseSourceInfo = supplement != null || value.reuseSourceInfo), null, true)
    }
    private fun loadRequest(value: PlayerRequest, position: Long?, autoplay: Boolean, reason: String = "load",
        preserveSkip: Boolean = false) {
        if (closed) return
        bufferingJob?.cancel()
        bufferingJob = null
        recoveryPosition = position
        intendedPlay = playbackAllowed && autoplay
        savePosition()
        // A slow resolver must not consume the address's lifetime before it finishes.
        val cached = preload?.takeIf { it.request.copy(reuseSourceInfo = value.reuseSourceInfo) == value &&
            (it.completedMs == null || nowMs() - it.completedMs!! < PRELOAD_VALID_MS) }
        if (cached == null) preload?.result?.cancel()
        if (cached == null) controller.preload(null)
        preload = null
        nativePreload = null
        request = value
        resolution?.cancel()
        refreshJob?.cancel()
        supplementJob?.cancel()
        val id = ++generation
        telemetry.begin(id, reason)
        controller.stop()
        mutable.value = mutable.value.copy(loading = true, refreshing = false, error = null, mediaId = id, ended = false)
        resolution = scope.launch {
            try {
                val prepared = cached?.result?.await()
                val info = prepared ?: resolveSource(value)
                ensureActive()
                if (info.url in value.rejectedMedia) throw IllegalStateException("备用线路不可用，请重试")
                if (!closed && id == generation) {
                    val stored = resumePosition(info)
                    val target = position ?: (if (preserveSkip) resumeTarget else null) ?: stored
                    ensureActive()
                    if (closed || id != generation) return@launch
                    resumeTarget = target
                    // History continuation is a one-time entry policy. Retry/replay must
                    // resolve the episode actually opened, without advancing it again.
                    if (value.resumeHistory || value.episode == null) request = value.copy(
                        page = info.pageUrl, episode = info.selected, resumeHistory = false)
                    if (!preserveSkip) {
                        activeSkip = skipDurations(info).takeIf { it.valid && info.supportsAutomaticSkipping() }
                            ?: SkipDurations()
                        introConsumed = false
                        outroExempt = false
                    }
                    if (position != null) introConsumed = true
                    mutable.value = PlaybackState(info = info, mediaId = id, audioOnly = mutable.value.audioOnly)
                    telemetry.resolved(info.kind, prepared != null)
                    val start = (target ?: 0L).coerceAtLeast(0)
                    val startupSkip = if (!introConsumed && controller.capabilities.seeking) activeSkip else SkipDurations()
                    // Native hosts own the first timeline decision, before autoplay. Mark the
                    // policy consumed before load can synchronously publish readiness.
                    if (controller.capabilities.initialSkipping) introConsumed = true
                    if (controller.capabilities.initialPosition) {
                        resumedId = id
                        recoveryPosition = null
                    }
                    controller.load(MediaSource(id, info.url, info.kind, info.title,
                        episodeLabelOf(info), playbackAllowed && intendedPlay, artworkUrl(info), start, startupSkip,
                        segments = info.segments))
                    if (controller.capabilities.playbackRate) controller.setPlaybackRate(playbackRate)
                    installRemoteActions(info)
                    preloadNextAfterFirstFrame(info, id)
                    supplementJob = supplement?.let { discover ->
                        scope.launch {
                            try {
                                val updated = resolveDispatcher?.let { withContext(it) { discover(info) } }
                                    ?: discover(info)
                                ensureActive()
                                val current = mutable.value
                                if (!closed && id == generation && current.info?.pageUrl == info.pageUrl &&
                                    current.info.selected == info.selected && updated.pageUrl == info.pageUrl &&
                                    updated.episodes.any { it.path == current.info.selected }) {
                                    // Publish only the list. Native media, position, play intent and the
                                    // user's selected line remain untouched, including an intervening pause.
                                    mutable.value = current.copy(info = current.info.copy(episodes = updated.episodes))
                                    installRemoteActions(mutable.value.info!!)
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { /* Optional discovery cannot become a playback error. */ }
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!closed && id == generation) {
                    telemetry.finish(StartupOutcome.Failed)
                    mutable.value = mutable.value.copy(loading = false, error = error.message ?: "视频解析失败")
                    // A measured backup can fail while resolving. Continue with the remaining
                    // corresponding routes, retaining the original timeline and pause intent.
                    if (reason == "recovery") {
                        request = value.copy(rejectedEpisodes = value.rejectedEpisodes + listOfNotNull(value.episode))
                        recoverSource(controller.state.value)
                    }
                }
            }
        }
        resolution?.invokeOnCompletion { cached?.result?.cancel() }
    }
    fun retry() {
        preload?.result?.cancel()
        preload = null
        nativePreload = null
        controller.preload(null)
        request?.copy(rejectedEpisodes = emptySet())?.let {
            recoveryAttempts = 0
            loadRequest(it.copy(rejectedMedia = emptySet(), reuseSourceInfo = false), null, true,
                preserveSkip = !mutable.value.ended && !mutable.value.finished)
        }
    }
    /** Updates the current episode and source list without interrupting playback. */
    fun refresh() {
        if (closed || mutable.value.loading || mutable.value.refreshing) return
        val current = mutable.value.info ?: run { retry(); return }
        val value = PlayerRequest(page = current.pageUrl, title = current.title, episode = current.selected,
            allowLineFallback = !manualLine)
        val id = generation
        preload?.result?.cancel()
        preload = null
        nativePreload = null
        controller.preload(null)
        mutable.value = mutable.value.copy(refreshing = true, error = null)
        refreshJob = scope.launch {
            try {
                val info = resolveSource(value)
                ensureActive()
                if (!closed && id == generation) {
                    request = value
                    mutable.value = mutable.value.copy(info = info, refreshing = false)
                    installRemoteActions(info)
                    preloadNext(info)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!closed && id == generation) mutable.value = mutable.value.copy(
                    refreshing = false, error = error.message ?: "刷新失败",
                )
            }
        }
    }
    private suspend fun resolveSource(value: PlayerRequest): PlaybackInfo =
        resolveDispatcher?.let { dispatcher -> withContext(dispatcher) { resolve(value) } }
            ?: resolve(value)

    /**
     * Speculative next-episode resolution starts only after the current media paints a frame.
     * Starting it alongside the initial load duplicates the detail page and competes with the
     * manifest and first segment requests, which are the critical path to the first frame.
     */
    private fun preloadNextAfterFirstFrame(info: PlaybackInfo, id: Long) {
        if (nextEpisode(info) == null) return
        scope.launch {
            withTimeoutOrNull(PRELOAD_AFTER_FIRST_FRAME_TIMEOUT_MS) {
                controller.state.first { it.mediaId == id && it.firstFrameRendered }
            } ?: return@launch
            if (!closed && id == generation) preloadNext(info)
        }
    }

    /**
     * Discards speculative next-episode choices after a manual line report changes.
     * Active playback stays untouched; the next load resolves using the new preferences.
     */
    fun invalidateLinePreload() {
        if (closed || manualLine) return
        preload?.result?.cancel()
        preload = null
        nativePreload = null
        controller.preload(null)
    }

    private fun preloadNext(info: PlaybackInfo, reuseSourceInfo: Boolean = true) {
        preload?.result?.cancel()
        preload = null
        nativePreload = null
        controller.preload(null)
        val next = nextEpisode(info) ?: return
        val value = PlayerRequest(page = info.pageUrl, title = info.title, episode = next.path,
            allowLineFallback = !manualLine, reuseSourceInfo = reuseSourceInfo)
        lateinit var candidate: Preload
        val result = scope.async(start = CoroutineStart.LAZY) {
            try {
                resolveSource(value).also { resolved ->
                    ensureActive()
                    if (preload === candidate && !closed) {
                        candidate.completedMs = nowMs()
                        candidate.info = resolved
                        updateNativePreload()
                    }
                }
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        }
        candidate = Preload(value, result)
        preload = candidate
        result.start()
    }
    private fun updateNativePreload() {
        val candidate = preload
        val player = controller.state.value
        val allowed = !closed && playbackAllowed && !mutable.value.loading &&
            player.mediaId == mutable.value.mediaId &&
            player.phase in setOf(PlayerPhase.Playing, PlayerPhase.Ended)
        if (!allowed) {
            if (nativePreload != null) controller.preload(null)
            nativePreload = null
            return
        }
        val info = candidate?.info ?: return
        if (nativePreload === candidate) return
        nativePreload = candidate
        controller.preload(MediaSource(generation + 1, info.url, info.kind, info.title,
            episodeLabelOf(info), autoplay = false, segments = info.segments))
    }
    private fun refreshPreloadNearEnd() {
        if (closed || !playbackAllowed || mutable.value.loading) return
        val candidate = preload ?: return
        val completed = candidate.completedMs ?: return
        val player = controller.state.value
        if (player.mediaId != mutable.value.mediaId || player.phase != PlayerPhase.Playing ||
            player.duration <= 0 || player.duration - player.position > 60_000 ||
            nowMs() - completed < 45_000) return
        mutable.value.info?.let { preloadNext(it, reuseSourceInfo = false) }
    }
    fun select(episode: Episode) {
        val info = mutable.value.info ?: return
        recoveryAttempts = 0
        loadRequest(PlayerRequest(page = info.pageUrl, title = info.title, episode = episode.path,
            allowLineFallback = !manualLine, reuseSourceInfo = true),
            null, true, reason = "episode")
    }
    /** Applies optional measurements only while the same resolved episode is still active. */
    fun updateLineProbes(info: PlaybackInfo, probes: Map<Int, LineProbe>) {
        val current = mutable.value
        if (closed || current.loading || current.info?.pageUrl != info.pageUrl ||
            current.info.selected != info.selected || current.info.url != info.url) return
        mutable.value = current.copy(info = current.info.copy(lineProbes = current.info.lineProbes + probes))
    }
    /**
     * Immediately plays the corresponding episode on [line], preserving its position.
     * Matches an episode label first, then its index within the current line. If neither exists,
     * starts the first available episode on the target line without carrying unrelated progress.
     * Unknown lines, an already active line, and identical pending requests are ignored.
     */
    fun selectLine(line: Int) {
        if (closed) return
        val current = mutable.value
        val info = current.info ?: return
        val selected = info.episodes.find { it.path == info.selected }
        if (info.episodes.none { it.line == line }) return
        manualLine = true
        bufferingJob?.cancel()
        bufferingJob = null
        if (selected?.line == line && !current.loading && current.error == null) {
            request = request?.copy(page = info.pageUrl, episode = info.selected,
                preferFastestLine = false, allowLineFallback = false)
            return
        }
        val targets = info.episodes.filter { it.line == line }
        if (targets.isEmpty()) return
        // Providers can use different language/quality labels for the same episode. Preserve
        // server order as a fallback, but never carry a later episode's timestamp into episode one.
        val index = info.episodes.filter { it.line == selected?.line }.indexOf(selected)
        val corresponding = selected?.label?.takeIf { it.isNotBlank() }?.let { label ->
            targets.find { it.label == label }
        } ?: targets.getOrNull(index)
        val target = corresponding ?: targets.first()
        val value = PlayerRequest(page = info.pageUrl, title = info.title, episode = target.path,
            allowLineFallback = false, reuseSourceInfo = true)
        // Loading still exposes the previous PlaybackInfo. Compare with the actual pending
        // request so repeated taps cannot restart its timeout or cancel useful resolution.
        // Failed loads remain retryable, and a different target still supersedes this request.
        if (current.loading && request == value) return
        val player = controller.state.value
        val position = if (corresponding != null)
            recoveryPosition ?: player.position.takeIf { player.mediaId == current.mediaId }
        else null
        recoveryAttempts = 0
        loadRequest(value, position, true, reason = "line", preserveSkip = corresponding != null)
    }
    /** Exposes episode switching to platform lock-screen / notification transport controls. */
    private fun installRemoteActions(info: PlaybackInfo) {
        controller.setRemoteEpisodeActions(
            onNext = { nextEpisode(info)?.let { select(it) } },
            onPrevious = { seekTo(0L) },
        )
    }
    private fun episodeLabelOf(info: PlaybackInfo): String =
        info.episodes.find { it.path == info.selected }?.label.orEmpty()
    fun close() {
        if (closed) return
        savePosition()
        closed = true
        telemetry.finish(StartupOutcome.Cancelled)
        generation++
        resolution?.cancel()
        refreshJob?.cancel()
        supplementJob?.cancel()
        bufferingJob?.cancel()
        preload?.result?.cancel()
        preload = null
        nativePreload = null
        controller.preload(null)
        observer.cancel()
        controller.setRemoteEpisodeActions(onNext = null, onPrevious = null)
        controller.close()
    }
    private companion object {
        const val PRELOAD_VALID_MS = 5 * 60_000L
        const val PRELOAD_AFTER_FIRST_FRAME_TIMEOUT_MS = 15_000L
        const val AUTO_LINE_BUFFER_TIMEOUT_MS = 15_000L
    }
}
