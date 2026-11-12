package dev.pandavideo.core.player

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.nextEpisode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

enum class PlayerPhase { Idle, Preparing, Ready, Playing, Paused, Buffering, Ended, Error, Released }
data class MediaSource(
    val id: Long, val url: String, val kind: String, val title: String,
    /** Label of the selected episode, used by platform now-playing metadata. */
    val episodeLabel: String = "",
    /** Whether loading this source may start playback before an explicit play action. */
    val autoplay: Boolean = true,
    /** Poster URL used by system now-playing artwork; never a video download URL. */
    val artworkUrl: String? = null,
)
data class PlayerCapabilities(
    val playbackRate: Boolean = true, val seeking: Boolean = true,
    val fullscreen: Boolean = true, val pictureInPicture: Boolean = false,
    val framePreview: Boolean = false,
    /** Whether the host can disable video tracks while retaining audio playback. */
    val audioOnly: Boolean = false,
)
/** A small encoded image of [position] milliseconds in the source identified by [mediaId]. */
data class PreviewFrame(val mediaId: Long, val position: Long, val bytes: ByteArray)
data class PlayerState(
    val mediaId: Long = 0, val phase: PlayerPhase = PlayerPhase.Idle,
    val position: Long = 0, val duration: Long = 0, val wantsPlay: Boolean = false,
    val rate: Float = 1f, val error: String? = null,
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
    /** True when the current episode ended and a next episode is available for auto-advance. */
    val ended: Boolean = false,
    val countdownSeconds: Int = 0,
    /** Explicit session-scoped permission to play audio without a visible video presentation. */
    val audioOnly: Boolean = false,
)

/** Serializes resolution and ignores stale or duplicate completion events. */
class PlaybackSession(
    private val resolve: suspend (PlayerRequest) -> PlaybackInfo,
    val controller: PlayerController, private val scope: CoroutineScope,
    initialRate: Float = 1f,
    private val checkpoint: (PlaybackInfo, PlayerState) -> Unit = { _, _ -> },
    private val resumePosition: (PlaybackInfo) -> Long? = { null },
    private val artworkUrl: (PlaybackInfo) -> String? = { null },
) {
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
    private data class Preload(
        val request: PlayerRequest, val result: Deferred<PlaybackInfo?>, val started: TimeMark,
    )
    private var preload: Preload? = null
    private var generation = 0L
    private var endedId = -1L
    private var closed = false
    private var playbackVisible = true
    private var recoveryAttempts = 0
    private var recoveryPosition: Long? = null
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
    private var countdown: Job? = null
    private val persistence = scope.launch {
        while (true) {
            delay(5_000)
            savePosition()
        }
    }
    private fun savePosition() {
        val current = mutable.value
        val player = controller.state.value
        if (!current.loading && player.mediaId == current.mediaId && player.duration >= 20_000) {
            current.info?.let { checkpoint(it, player) }
        }
    }
    private val observer = scope.launch {
        controller.state.collect { player ->
            // Native readiness and remote transport events can arrive after navigation.
            // Enforce the same policy for these events as for asynchronous resolution.
            if (!closed && !playbackAllowed && player.wantsPlay) controller.pause()
            val current = mutable.value
            // AVPlayer and JavaFX clear wantsPlay when reporting an error. Preserve the
            // last non-error intent so recovery continues active playback but respects pause.
            if (!closed && !current.loading && player.mediaId == current.mediaId &&
                player.phase != PlayerPhase.Error) intendedPlay = player.wantsPlay
            if (!closed && !current.loading && player.mediaId == current.mediaId &&
                player.mediaId != resumedId && player.duration > 0 &&
                player.phase in setOf(PlayerPhase.Ready, PlayerPhase.Playing, PlayerPhase.Paused, PlayerPhase.Buffering)) {
                resumedId = player.mediaId
                (recoveryPosition ?: current.info?.let(resumePosition))?.let {
                    controller.seekTo(it.coerceIn(0, (player.duration - 1_000).coerceAtLeast(0)))
                }
                recoveryPosition = null
            }
            if (!closed && playbackAllowed && !current.loading && current.error == null &&
                player.mediaId == current.mediaId && player.phase == PlayerPhase.Error) {
                val info = current.info ?: return@collect
                if (recoveryAttempts >= 2) {
                    mutable.value = current.copy(error = "播放失败，请重试")
                } else {
                    recoveryAttempts++
                    // Use the resolved page/episode after a provider switch, not the original
                    // request. Rejected URLs stay in memory and cannot be reused by preloading.
                    val rejected = request?.rejectedMedia.orEmpty() + info.url
                    loadRequest(PlayerRequest(page = info.pageUrl, title = info.title,
                        episode = info.selected, rejectedMedia = rejected),
                        recoveryPosition ?: if (player.mediaId != resumedId && player.position == 0L)
                            resumePosition(info) ?: 0L else player.position, intendedPlay)
                }
            }
            if (!closed && playbackAllowed && player.phase == PlayerPhase.Ended && player.mediaId == current.mediaId &&
                player.mediaId != endedId && !current.loading && !current.ended) {
                endedId = player.mediaId
                savePosition()
                if (nextEpisode(current.info ?: return@collect) != null) {
                    mutable.value = current.copy(ended = true, countdownSeconds = 5)
                    countdown?.cancel()
                    countdown = scope.launch {
                        repeat(5) {
                            delay(1_000)
                            mutable.value = mutable.value.copy(countdownSeconds = 4 - it)
                        }
                        advanceToNext()
                    }
                }
            }
        }
    }
    /** Continues to the next episode after the end-of-episode countdown or an explicit tap. */
    fun advanceToNext() {
        val current = mutable.value
        if (!playbackAllowed || !current.ended) return
        nextEpisode(current.info ?: return)?.let { select(it) }
    }
    /** Cancels auto-advance and stays on the finished episode for replay. */
    fun dismissEnded() {
        countdown?.cancel()
        if (mutable.value.ended) mutable.value = mutable.value.copy(ended = false, countdownSeconds = 0)
    }
    fun load(value: PlayerRequest) {
        recoveryAttempts = 0
        loadRequest(value, null, true)
    }
    private fun loadRequest(value: PlayerRequest, position: Long?, autoplay: Boolean) {
        if (closed) return
        recoveryPosition = position
        intendedPlay = playbackAllowed && autoplay
        savePosition()
        countdown?.cancel()
        val cached = preload?.takeIf { it.request == value && it.started.elapsedNow() < 60.seconds }
        if (cached == null) preload?.result?.cancel()
        preload = null
        request = value
        resolution?.cancel()
        refreshJob?.cancel()
        val id = ++generation
        controller.stop()
        mutable.value = mutable.value.copy(loading = true, refreshing = false, error = null, mediaId = id, ended = false, countdownSeconds = 0)
        resolution = scope.launch {
            try {
                val info = cached?.result?.await() ?: resolve(value)
                ensureActive()
                if (info.url in value.rejectedMedia) throw IllegalStateException("备用线路不可用，请重试")
                if (!closed && id == generation) {
                    mutable.value = PlaybackState(info = info, mediaId = id, audioOnly = mutable.value.audioOnly)
                    controller.load(MediaSource(id, info.url, info.kind, info.title,
                        episodeLabelOf(info), playbackAllowed && intendedPlay, artworkUrl(info)))
                    if (controller.capabilities.playbackRate) controller.setPlaybackRate(playbackRate)
                    installRemoteActions(info)
                    preloadNext(info)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!closed && id == generation) mutable.value = mutable.value.copy(loading = false, error = error.message ?: "视频解析失败")
            }
        }
        resolution?.invokeOnCompletion { cached?.result?.cancel() }
    }
    fun retry() {
        preload?.result?.cancel()
        preload = null
        request?.let { load(it.copy(rejectedMedia = emptySet())) }
    }
    /** Updates the current episode and source list without interrupting playback. */
    fun refresh() {
        if (closed || mutable.value.loading || mutable.value.refreshing) return
        val current = mutable.value.info ?: run { retry(); return }
        val value = PlayerRequest(page = current.pageUrl, title = current.title, episode = current.selected)
        val id = generation
        preload?.result?.cancel()
        preload = null
        mutable.value = mutable.value.copy(refreshing = true, error = null)
        refreshJob = scope.launch {
            try {
                val info = resolve(value)
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
    private fun preloadNext(info: PlaybackInfo) {
        preload?.result?.cancel()
        preload = null
        val next = nextEpisode(info) ?: return
        val value = PlayerRequest(page = info.pageUrl, title = info.title, episode = next.path)
        preload = Preload(value, scope.async {
            try { resolve(value) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        }, TimeSource.Monotonic.markNow())
    }
    fun select(episode: Episode) {
        val info = mutable.value.info ?: return
        load(PlayerRequest(page = info.pageUrl, title = info.title, episode = episode.path))
    }
    /** Exposes episode switching to platform lock-screen / notification transport controls. */
    private fun installRemoteActions(info: PlaybackInfo) {
        controller.setRemoteEpisodeActions(
            onNext = { nextEpisode(info)?.let { select(it) } },
            onPrevious = { controller.seekTo(0L) },
        )
    }
    private fun episodeLabelOf(info: PlaybackInfo): String =
        info.episodes.find { it.path == info.selected }?.label.orEmpty()
    fun close() {
        if (closed) return
        savePosition()
        closed = true
        persistence.cancel()
        countdown?.cancel()
        generation++
        resolution?.cancel()
        refreshJob?.cancel()
        preload?.result?.cancel()
        preload = null
        observer.cancel()
        controller.setRemoteEpisodeActions(onNext = null, onPrevious = null)
        controller.close()
    }
}
