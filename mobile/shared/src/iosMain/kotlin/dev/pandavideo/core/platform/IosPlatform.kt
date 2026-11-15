@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo.core.platform

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import dev.pandavideo.app.PandaVideoApp
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlayerCapabilities
import dev.pandavideo.core.player.PlayerController
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.core.player.PreviewFrame
import dev.pandavideo.core.player.PendingSeek
import dev.pandavideo.core.player.startupPosition
import dev.pandavideo.core.player.ScreenWakeLock
import dev.pandavideo.data.repository.isWebUrl
import dev.pandavideo.data.repository.BundledPosters
import dev.pandavideo.data.repository.OnlineOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.*
import platform.Foundation.*
import platform.MediaPlayer.*
import platform.QuartzCore.CATransaction
import platform.UIKit.*
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

/** Swift host entry point. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    PandaVideoApp(remember { IosPlatform() }, onReady = {
        NSNotificationCenter.defaultCenter.postNotificationName("PandaVideoContentReady", null)
    })
}

class IosPlatform : PlatformServices {
    private val douban by lazy { IosDoubanSession() }
    override fun doubanSession(): DoubanSession = douban
    private val offline by lazy { IosOfflineEngine() }
    override fun offlineEngine(): dev.pandavideo.data.repository.OfflineEngine = offline
    override fun avatarDirectory(): String =
        (localDirectory(NSApplicationSupportDirectory) + "/avatars").also {
            NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null)
        }
    override fun deleteAvatar(filename: String) {
        if (avatarUri(filename) != null)
            NSFileManager.defaultManager.removeItemAtPath(avatarDirectory() + "/" + filename, null)
    }
    override val dlnaSupported: Boolean = true
    override val nativeCastingSupported: Boolean = true
    override suspend fun discoverDlnaLocations(host: String?): List<String> = discoverIosDlna(host)
    @Composable
    override fun CastingButton(modifier: Modifier, tint: Color) {
        UIKitView(
            factory = {
                AVRoutePickerView(frame = CGRectZero.readValue()).apply {
                    // UIKit interop views are composited above the Compose dialog layer. Without
                    // an opaque backdrop the page behind the sheet showed through the transparent
                    // route button, which looked like a stray portrait during discovery.
                    backgroundColor = UIColor.secondarySystemBackgroundColor
                    tintColor = UIColor(red = tint.red.toDouble(), green = tint.green.toDouble(),
                        blue = tint.blue.toDouble(), alpha = tint.alpha.toDouble())
                    activeTintColor = tintColor
                    prioritizesVideoDevices = true
                    accessibilityLabel = "AirPlay 投屏"
                }
            },
            modifier = modifier,
            update = { view ->
                view.tintColor = UIColor(red = tint.red.toDouble(), green = tint.green.toDouble(),
                    blue = tint.blue.toDouble(), alpha = tint.alpha.toDouble())
                view.activeTintColor = view.tintColor
            },
            properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true),
        )
    }
    private var originalBrightness: Double? = null
    private var activePlayer: IosPlayer? = null
    private val pipMutable = MutableStateFlow(false)
    override val pictureInPictureActive: StateFlow<Boolean> = pipMutable.asStateFlow()
    private val fullscreenBlockedMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val fullscreenBlocked: SharedFlow<Unit> = fullscreenBlockedMutable.asSharedFlow()
    override val pipSupported: Boolean = AVPictureInPictureController.isPictureInPictureSupported()
    override val haptics: Haptics = IosHaptics

    init {
        // The Swift host posts this when UIWindowScene rejects the landscape geometry request,
        // typically because the portrait orientation lock is enabled in Control Center.
        NSNotificationCenter.defaultCenter.addObserverForName(
            "SuiXinKanFullscreenDenied", null, NSOperationQueue.mainQueue,
        ) { _ -> fullscreenBlockedMutable.tryEmit(Unit) }
    }
    override fun httpClient() = HttpClient(Darwin) {
        followRedirects = false
        // Session cookies are attached explicitly after origin checks in ContentRepository.
        // Disable NSURLSession's ambient cookie handling, including on redirected requests.
        engine { configureRequest { setHTTPShouldHandleCookies(false) } }
    }
    override fun player(): PlayerController = IosPlayer(
        onPipChanged = { active -> pipMutable.value = active },
    ).also { activePlayer = it }
    override fun storage(): KeyValueStorage = IosStorage
    override fun catalogDatabasePath(): String = localDirectory(NSApplicationSupportDirectory) + "/catalog-v1.db"
    override fun posterCacheDirectory(): String = localDirectory(NSCachesDirectory) + "/posters"
    private fun localDirectory(directory: NSSearchPathDirectory): String {
        val root = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).first() as String
        val path = "$root/PandaVideo"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        return path
    }
    override fun openLink(url: String) {
        if (isWebUrl(url)) NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null) }
    }
    override fun openAppSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null)
        }
    }
    override fun fullscreen(enabled: Boolean) {
        // The Swift host owns the window scene and its supported orientation mask.
        // Modern iOS requires a scene geometry request rather than changing UIDevice orientation.
        NSNotificationCenter.defaultCenter.postNotificationName(
            "SuiXinKanFullscreenChanged", `object` = NSNumber(bool = enabled),
        )
    }
    override fun setSystemBarAppearance(lightStatusBarIcons: Boolean, darkTheme: Boolean) {
        // The UIKit host owns preferredStatusBarStyle; Compose only supplies backdrop contrast.
        NSNotificationCenter.defaultCenter.postNotificationName(
            "SuiXinKanStatusBarChanged", `object` = NSNumber(bool = lightStatusBarIcons),
        )
    }
    override fun enterPictureInPicture(): Boolean = activePlayer?.startPictureInPicture() ?: false
    /**
     * iOS does not allow setting system volume directly. A hidden MPVolumeView hosts the
     * system volume slider, whose actions route the change through the official media API.
     */
    private val volumeView = MPVolumeView().apply {
        setFrame(CGRectMake(-1000.0, -1000.0, 150.0, 24.0))
        showsRouteButton = false
        showsVolumeSlider = true
        userInteractionEnabled = false
        alpha = 0.01
    }
    private var volumeViewInstalled = false
    private fun ensureVolumeView() {
        if (volumeViewInstalled) return
        val scenes = UIApplication.sharedApplication.connectedScenes
        val activeScene = scenes.firstNotNullOfOrNull { candidate ->
            val windowScene = candidate as? UIWindowScene ?: return@firstNotNullOfOrNull null
            windowScene.takeIf { it.activationState == UISceneActivationStateForegroundActive }
        }
        val window = activeScene?.windows?.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
        if (window != null) {
            window.addSubview(volumeView)
            volumeViewInstalled = true
        }
    }
    private val volumeSlider: UISlider?
        get() = volumeView.subviews.firstOrNull { it is UISlider } as? UISlider
    override fun currentVolume(): Float = AVAudioSession.sharedInstance().outputVolume
    override fun setVolume(value: Float) {
        ensureVolumeView()
        volumeSlider?.let { slider ->
            slider.setValue(value.coerceIn(0f, 1f), animated = false)
            slider.sendActionsForControlEvents(UIControlEventTouchUpInside)
        }
    }
    override fun currentBrightness(): Float = UIScreen.mainScreen.brightness.toFloat()
    override fun setBrightness(value: Float) {
        if (originalBrightness == null) originalBrightness = UIScreen.mainScreen.brightness
        UIScreen.mainScreen.brightness = value.coerceIn(0f, 1f).toDouble()
    }
    override fun resetBrightness() {
        originalBrightness?.let { UIScreen.mainScreen.brightness = it }
        originalBrightness = null
    }
}

internal object IosStorage : KeyValueStorage {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun getString(key: String): String? = defaults.stringForKey(key)
    override fun putString(key: String, value: String) { defaults.setObject(value, forKey = key) }
    override fun remove(key: String) { defaults.removeObjectForKey(key) }
}

internal actual fun currentEpochMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

class IosPlayer(private val onPipChanged: (Boolean) -> Unit = {}) : PlayerController {
    /**
     * The only AVPlayer for this session. It stays bound to [PlayerContainerView.videoLayer] and
     * the PiP controller; preloaded media is adopted as an item, never as another player instance.
     */
    val native: AVPlayer = AVPlayer()
    private data class Prepared(
        val source: MediaSource, val item: AVPlayerItem, var ready: Boolean = false,
    )
    private var prepared: Prepared? = null
    private var prepareJob: Job? = null
    internal var displayLayer: AVPlayerLayer? = null
    private val mutable = MutableStateFlow(PlayerState())
    private val pendingSeek = PendingSeek()
    private var startupSource: MediaSource? = null
    private var startupSeeking = false
    override val state = mutable.asStateFlow()
    override val capabilities = PlayerCapabilities(pictureInPicture = true, framePreview = true,
        audioOnly = true, initialPosition = true, initialSkipping = true)
    private var audioOnly = false
    private var previewGenerator: AVAssetImageGenerator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var released = false
    private var endObserver: NSObjectProtocol? = null
    private var remoteNext: (() -> Unit)? = null
    private var currentTitle = ""
    private var currentEpisode = ""
    private var nowPlayingLastUpdate = 0.0
    private var nowPlayingPhase: PlayerPhase? = null
    private var currentArtwork: MPMediaItemArtwork? = null
    private var artworkJob: Job? = null
    private val artworkClient = HttpClient(Darwin) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }
    private val remoteTokens = mutableListOf<Pair<MPRemoteCommand, Any>>()

    private fun MPRemoteCommand.target(handler: (MPRemoteCommandEvent?) -> MPRemoteCommandHandlerStatus) {
        remoteTokens += this to addTargetWithHandler(handler)
    }

    init {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)
        installRemoteCommands()
        scope.launch {
            while (isActive) {
                delay(if (!mutable.value.firstFrameRendered && mutable.value.wantsPlay) 50 else 250)
                val item = native.currentItem ?: continue
                // HLS tracks can arrive after load. Apply the current mode when they become
                // available as well, without replacing the item or disturbing its timeline.
                applyAudioOnly(item)
                val duration = CMTimeGetSeconds(item.duration).takeIf { it.isFinite() && it > 0 } ?: 0.0
                // Keep autoplay behind the single history/intro seek. Publishing a Playing
                // sample first used to paint the opening and immediately show loading again.
                val startup = startupSource
                if (startup != null && !startupSeeking && item.status == AVPlayerItemStatusReadyToPlay) {
                    val target = startup.startupPosition((duration * 1000).toLong())
                    if (target > 0) {
                        startupSeeking = true
                        native.seekToTime(CMTimeMakeWithSeconds(target / 1000.0, 600),
                            toleranceBefore = CMTimeMake(0, 1), toleranceAfter = CMTimeMake(0, 1),
                            completionHandler = { _ ->
                                scope.launch {
                                    if (released || startupSource !== startup || native.currentItem != item) return@launch
                                    startupSource = null
                                    startupSeeking = false
                                    if (mutable.value.wantsPlay) native.playImmediatelyAtRate(mutable.value.rate)
                                }
                            })
                    } else {
                        // A ready indefinite timeline is live; do not wait for a VOD duration.
                        startupSource = null
                        if (mutable.value.wantsPlay) native.playImmediatelyAtRate(mutable.value.rate)
                    }
                }
                val position = CMTimeGetSeconds(native.currentTime()).takeIf { it.isFinite() && it > 0 } ?: 0.0
                val previous = mutable.value
                val seeking = pendingSeek.active(previous.mediaId)
                val displayedPosition = pendingSeek.position(previous.mediaId, (position * 1000).toLong())
                val phase = when {
                    item.status == AVPlayerItemStatusFailed -> PlayerPhase.Error
                    startupSource != null -> PlayerPhase.Preparing
                    seeking -> if (previous.wantsPlay) PlayerPhase.Buffering else PlayerPhase.Paused
                    previous.phase == PlayerPhase.Ended -> PlayerPhase.Ended
                    item.status == AVPlayerItemStatusUnknown -> PlayerPhase.Preparing
                    native.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> PlayerPhase.Buffering
                    native.timeControlStatus == AVPlayerTimeControlStatusPlaying -> PlayerPhase.Playing
                    else -> PlayerPhase.Paused
                }
                val wants = if (seeking && phase != PlayerPhase.Error) previous.wantsPlay else when (phase) {
                    PlayerPhase.Playing -> true
                    PlayerPhase.Paused, PlayerPhase.Ended, PlayerPhase.Error -> false
                    else -> previous.wantsPlay
                }
                mutable.value = previous.copy(
                    phase = phase, wantsPlay = wants, duration = (duration * 1000).toLong(), position = displayedPosition,
                    error = if (phase == PlayerPhase.Error) "视频无法播放，请重新解析或切换线路" else null,
                    // A ready AVPlayerItem is not a rendered frame. Require the bound video
                    // layer's display readiness and a progressing active player as well.
                    firstFrameRendered = previous.firstFrameRendered || (phase == PlayerPhase.Playing &&
                        position > 0 && displayLayer?.player == native &&
                        displayLayer?.isReadyForDisplay() == true),
                )
                publishNowPlaying(phase, displayedPosition, (duration * 1000).toLong())
            }
        }
    }

    // MARK: - Lock screen / external transport

    private fun installRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.setEnabled(true)
        center.pauseCommand.setEnabled(true)
        center.togglePlayPauseCommand.setEnabled(true)
        center.nextTrackCommand.setEnabled(true)
        center.previousTrackCommand.setEnabled(true)
        center.changePlaybackPositionCommand.setEnabled(true)
        center.seekForwardCommand.setEnabled(true)
        center.seekBackwardCommand.setEnabled(true)
        // Default seek intervals are 10s, matching the in-player double-tap step.
        center.playCommand.target {
            scope.launch { play() }; MPRemoteCommandHandlerStatusSuccess
        }
        center.pauseCommand.target {
            scope.launch { pause() }; MPRemoteCommandHandlerStatusSuccess
        }
        center.togglePlayPauseCommand.target {
            scope.launch { if (mutable.value.wantsPlay) pause() else play() }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.nextTrackCommand.target {
            scope.launch { remoteNext?.invoke() }; MPRemoteCommandHandlerStatusSuccess
        }
        center.previousTrackCommand.target {
            scope.launch { seekTo(0L) }; MPRemoteCommandHandlerStatusSuccess
        }
        center.changePlaybackPositionCommand.target { event ->
            val seconds = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: 0.0
            scope.launch { seekTo((seconds * 1000).toLong()) }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.seekForwardCommand.target {
            scope.launch { seekTo(mutable.value.position + 10_000L) }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.seekBackwardCommand.target {
            scope.launch { seekTo(mutable.value.position - 10_000L) }
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun publishNowPlaying(phase: PlayerPhase, positionMs: Long, durationMs: Long) {
        if (currentTitle.isBlank()) return
        val now = NSDate().timeIntervalSince1970
        val playing = phase == PlayerPhase.Playing
        val phaseChanged = phase != nowPlayingPhase
        val stale = now - nowPlayingLastUpdate >= 2.0
        if (!phaseChanged && !(playing && stale)) return
        nowPlayingPhase = phase
        nowPlayingLastUpdate = now
        val info = mutableMapOf<Any?, Any?>(MPMediaItemPropertyTitle to currentTitle)
        currentArtwork?.let { info[MPMediaItemPropertyArtwork] = it }
        if (currentEpisode.isNotBlank()) info[MPMediaItemPropertyArtist] = currentEpisode
        if (durationMs > 0) {
            info[MPMediaItemPropertyPlaybackDuration] = durationMs / 1000.0
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionMs.coerceAtLeast(0L) / 1000.0
            info[MPNowPlayingInfoPropertyPlaybackRate] = if (playing) mutable.value.rate.toDouble() else 0.0
        }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    @OptIn(BetaInteropApi::class)
    private fun loadArtwork(source: MediaSource) {
        artworkJob?.cancel()
        currentArtwork = null
        val url = source.artworkUrl?.takeIf(::isWebUrl) ?: return
        artworkJob = scope.launch {
            try {
                // Prefer the same bundled poster as the app so system artwork also works offline.
                // Remote posters use the app's image headers to satisfy upstream hotlink checks.
                val bytes = BundledPosters.read(url) ?: artworkClient.get(url) {
                    header("User-Agent", "Mozilla/5.0")
                    header("Referer", if (NSURL.URLWithString(url)?.host?.endsWith("doubanio.com") == true)
                        "https://movie.douban.com/" else "$OnlineOrigin/")
                }.let { response ->
                    if (!response.status.isSuccess()) return@launch
                    response.bodyAsBytes()
                }
                ensureActive()
                if (released || mutable.value.mediaId != source.id || bytes.isEmpty()) return@launch
                val data = bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                }
                val image = UIImage.imageWithData(data) ?: return@launch
                currentArtwork = MPMediaItemArtwork(boundsSize = image.size, requestHandler = { image })
                // Force a refresh even when paused; subsequent progress updates retain the artwork.
                nowPlayingPhase = null
                val state = mutable.value
                publishNowPlaying(state.phase, state.position, state.duration)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Artwork is optional; an image failure must never interrupt media playback.
            }
        }
    }

    override fun load(source: MediaSource) {
        if (released) return
        pendingSeek.clear()
        startupSource = source.takeIf { it.startPosition > 0 || it.startupSkip.introSeconds > 0 }
        startupSeeking = false
        cancelPreview()
        native.pause()
        removeEndObserver()
        val url = NSURL.URLWithString(source.url) ?: return
        val upcoming = prepared?.takeIf { it.ready && it.source.url == source.url &&
            it.source.kind == source.kind && source.startPosition == 0L &&
            pipController?.isPictureInPictureActive() != true }
        val item: AVPlayerItem
        if (upcoming != null) {
            prepared = null
            prepareJob?.cancel()
            prepareJob = null
            item = upcoming.item
        } else {
            preload(null)
            item = playbackItem(url, source.url)
        }
        native.replaceCurrentItemWithPlayerItem(item)
        currentTitle = source.title
        currentEpisode = source.episodeLabel
        nowPlayingPhase = null
        mutable.value = PlayerState(mediaId = source.id, phase = PlayerPhase.Preparing,
            wantsPlay = source.autoplay, dataPreloadHit = upcoming != null)
        loadArtwork(source)
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
        ) {
            if (!released && mutable.value.mediaId == source.id && native.currentItem == item) {
                mutable.value = mutable.value.copy(phase = PlayerPhase.Ended, wantsPlay = false)
            }
        }
        applyAudioOnly(item)
        if (source.autoplay && startupSource == null) native.play()
    }
    /**
     * Warms one upcoming item without audio output or Now Playing changes.
     * The item is adopted on load only when its asset is playable and no active PiP controller
     * uses the current player.
     */
    override fun preload(source: MediaSource?) {
        if (released) return
        if (source != null && prepared?.source?.url == source.url &&
            prepared?.source?.kind == source.kind) return
        prepareJob?.cancel()
        prepareJob = null
        prepared = null
        if (source == null || !isWebUrl(source.url)) return
        val url = NSURL.URLWithString(source.url) ?: return
        val item = playbackItem(url, source.url).apply { preferredForwardBufferDuration = 3.0 }
        val candidate = Prepared(source, item)
        prepared = candidate
        prepareJob = scope.launch {
            // An AVPlayerItem can belong to only one AVPlayer, so the candidate is never attached
            // to a standby player. Loading the asset keys warms the playlist and transport while
            // leaving the item free to be adopted by the single session player on load.
            val ready = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    item.asset.loadValuesAsynchronouslyForKeys(listOf("playable", "duration")) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(
                                item.asset.statusOfValueForKey("playable", null) ==
                                    AVKeyValueStatusLoaded,
                            ))
                        }
                    }
                }
            } == true
            if (!released && prepared === candidate) {
                if (ready) candidate.ready = true else prepared = null
            }
        }
    }
    /** Uses the documented asset User-Agent option for official public MP4 transport. */
    private fun playbackItem(url: NSURL, address: String): AVPlayerItem =
        if (dev.pandavideo.core.player.BilibiliMedia.owns(address)) AVPlayerItem(asset = AVURLAsset(
            uRL = url, options = mapOf(AVURLAssetHTTPUserAgentKey to dev.pandavideo.core.player.BilibiliMedia.userAgent)))
        else AVPlayerItem(uRL = url)

    /** Disables video tracks on the existing AVPlayerItem while retaining audio and time. */
    override fun setAudioOnly(enabled: Boolean) {
        if (released) return
        audioOnly = enabled
        if (enabled) cancelPreview()
        native.currentItem?.let(::applyAudioOnly)
    }
    private fun applyAudioOnly(item: AVPlayerItem) {
        item.tracks.filterIsInstance<AVPlayerItemTrack>().forEach { track ->
            if (track.assetTrack?.mediaType == AVMediaTypeVideo && track.enabled == audioOnly)
                track.enabled = !audioOnly
        }
    }
    override fun play() {
        if (!released) {
            mutable.value = mutable.value.copy(wantsPlay = true)
            if (startupSource == null) native.playImmediatelyAtRate(mutable.value.rate)
        }
    }
    override fun pause() {
        if (!released) { native.pause(); mutable.value = mutable.value.copy(wantsPlay = false, phase = PlayerPhase.Paused) }
    }
    override fun seekTo(position: Long) {
        val player = native
        val item = player.currentItem ?: return
        val previous = mutable.value
        if (released || previous.duration <= 0 || item.seekableTimeRanges.isEmpty()) return
        val wasStarting = startupSource != null
        if (wasStarting) {
            // An explicit scrub overrides the opening policy even while its native seek
            // is pending. Its obsolete completion must not restore autoplay or position.
            startupSource = null
            startupSeeking = false
            item.cancelPendingSeeks()
        }
        val target = position.coerceIn(0, previous.duration)
        val request = pendingSeek.begin(previous.mediaId, target)
        // Release the drag preview into an optimistic timeline, rather than a stale native
        // sample. AVPlayer temporarily pauses/waits during seek; preserve explicit play intent.
        mutable.value = previous.copy(position = target,
            phase = if (previous.wantsPlay) PlayerPhase.Buffering else PlayerPhase.Paused)
        player.seekToTime(CMTimeMakeWithSeconds(target.toDouble() / 1000, 600),
            // kCMTimeZero bridges as a plain struct here, so build a CValue<CMTime> explicitly.
            toleranceBefore = CMTimeMake(0, 1), toleranceAfter = CMTimeMake(0, 1),
            completionHandler = { finished ->
                scope.launch {
                    if (released || native !== player || player.currentItem != item ||
                        mutable.value.mediaId != request.mediaId || !pendingSeek.complete(request)) return@launch
                    val seconds = CMTimeGetSeconds(player.currentTime())
                    val actual = seconds.takeIf { it.isFinite() && it >= 0 }
                        ?.let { (it * 1000).toLong() } ?: if (finished) target else previous.position
                    mutable.value = mutable.value.copy(position = actual.coerceIn(0, mutable.value.duration))
                    if (wasStarting && mutable.value.wantsPlay) player.playImmediatelyAtRate(mutable.value.rate)
                }
            })
    }
    override suspend fun previewFrame(position: Long): PreviewFrame? = withContext(Dispatchers.Main) {
        val item = native.currentItem ?: return@withContext null
        val id = mutable.value.mediaId
        if (released || mutable.value.duration <= 0) return@withContext null
        cancelPreview()
        // Reuse the current asset's transport configuration. A separate image generator never
        // seeks AVPlayer, so scrubbing cannot change playback position or interrupt PiP/audio.
        val generator = AVAssetImageGenerator(asset = item.asset).apply {
            appliesPreferredTrackTransform = true
            maximumSize = CGSizeMake(320.0, 180.0)
            requestedTimeToleranceBefore = CMTimeMakeWithSeconds(1.0, 600)
            requestedTimeToleranceAfter = CMTimeMakeWithSeconds(1.0, 600)
        }
        previewGenerator = generator
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { generator.cancelAllCGImageGeneration() }
                val time = CMTimeMakeWithSeconds(position / 1000.0, 600)
                generator.generateCGImagesAsynchronouslyForTimes(listOf(NSValue.valueWithCMTime(time))) {
                        _, image, actualTime, result, _ ->
                    if (continuation.isActive) {
                        val frame = if (result == AVAssetImageGeneratorSucceeded && image != null &&
                            mutable.value.mediaId == id) {
                            val data = UIImagePNGRepresentation(UIImage.imageWithCGImage(image))
                            data?.let {
                                val bytes = ByteArray(it.length.toInt())
                                if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
                                    it.getBytes(pinned.addressOf(0), it.length)
                                }
                                PreviewFrame(id, (CMTimeGetSeconds(actualTime) * 1000).toLong(), bytes)
                            }
                        } else null
                        continuation.resumeWith(Result.success(frame))
                    }
                }
            }
        } finally {
            generator.cancelAllCGImageGeneration()
            if (previewGenerator === generator) previewGenerator = null
        }
    }

    private fun cancelPreview() {
        previewGenerator?.cancelAllCGImageGeneration()
        previewGenerator = null
    }
    override fun setPlaybackRate(rate: Float) {
        if (!released && rate in 0.5f..2f) {
            mutable.value = mutable.value.copy(rate = rate)
            if (mutable.value.wantsPlay && startupSource == null) native.playImmediatelyAtRate(rate)
        }
    }
    override fun setRemoteEpisodeActions(onNext: (() -> Unit)?, onPrevious: (() -> Unit)?) {
        remoteNext = onNext
    }

    // MARK: - Picture in picture

    var pipController: AVPictureInPictureController? = null

    fun startPictureInPicture(): Boolean {
        val controller = pipController ?: return false
        return if (controller.isPictureInPicturePossible()) {
            controller.startPictureInPicture()
            true
        } else {
            false
        }
    }

    fun attachPipController(controller: AVPictureInPictureController) {
        pipController = controller
    }

    fun notifyPipActive(active: Boolean) = onPipChanged(active)

    private fun removeEndObserver() {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
    }
    override fun stop() {
        if (released) return
        pendingSeek.clear()
        startupSource = null
        startupSeeking = false
        cancelPreview()
        artworkJob?.cancel()
        artworkJob = null
        currentArtwork = null
        currentTitle = ""
        currentEpisode = ""
        nowPlayingPhase = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        native.pause(); removeEndObserver(); native.replaceCurrentItemWithPlayerItem(null)
        mutable.value = mutable.value.copy(phase = PlayerPhase.Idle, wantsPlay = false, error = null)
    }
    override fun close() {
        if (released) return
        preload(null)
        stop(); released = true; scope.cancel()
        artworkClient.close()
        remoteTokens.forEach { (command, token) -> command.removeTarget(token) }
        remoteTokens.clear()
        remoteNext = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
        mutable.value = mutable.value.copy(phase = PlayerPhase.Released)
    }
}

/**
 * Plain UIView hosting an AVPlayerLayer.
 *
 * AVPlayerViewController is designed for full-screen presentation and positions its private
 * video view through its own safe-area container layout. Embedded inside a Compose interop
 * node that layer is offset instead of tracking the assigned bounds. Keeping AVPlayerLayer as
 * a manually laid-out sublayer makes the framed video always match this view's bounds. The
 * layer also hosts the AVPictureInPictureController used by the in-player PiP button.
 */
private class PlayerContainerView(private val player: IosPlayer) : UIView(frame = CGRectZero.readValue()) {
    val videoLayer = AVPlayerLayer()
    var zoom: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            setNeedsLayout()
        }
    private var pipController: AVPictureInPictureController? = null
    private val pipDelegate = PipDelegate(onActive = player::notifyPipActive)

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        videoLayer.videoGravity = AVLayerVideoGravityResizeAspect
        videoLayer.player = player.native
        layer.addSublayer(videoLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        // Enlarge only the AVPlayerLayer. UIKit interop keeps the same bounds, preserving
        // Compose overlays and clipping; relayout also keeps the picture centered on rotation.
        bounds.useContents {
            val width = size.width * zoom
            val height = size.height * zoom
            videoLayer.setFrame(CGRectMake(
                (size.width - width) / 2.0, (size.height - height) / 2.0, width, height,
            ))
        }
        CATransaction.commit()
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        if (window != null && pipController == null && AVPictureInPictureController.isPictureInPictureSupported()) {
            val controller = AVPictureInPictureController(playerLayer = videoLayer)
            controller.delegate = pipDelegate
            controller.canStartPictureInPictureAutomaticallyFromInline = false
            pipController = controller
            player.attachPipController(controller)
        }
    }
}

/** Bridges PiP lifecycle callbacks back to the platform state flow. */
private class PipDelegate(private val onActive: (Boolean) -> Unit) : NSObject(), AVPictureInPictureControllerDelegateProtocol {
    override fun pictureInPictureControllerDidStartPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        onActive(true)
    }
    override fun pictureInPictureControllerDidStopPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        onActive(false)
    }
    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError: NSError,
    ) {
        onActive(false)
    }
}

// AVPlayer's playback sleep prevention is not a presentation lifetime guarantee: during
// buffering there may be no active video output keeping iOS awake. Own the idle timer for
// mounted video surfaces, including startup/seek waits, and restore it for audio-only mode.
private val videoScreenWakeLock = ScreenWakeLock(
    readDisabled = { UIApplication.sharedApplication.idleTimerDisabled },
    writeDisabled = { UIApplication.sharedApplication.idleTimerDisabled = it },
)

@Composable
actual fun PlayerSurface(controller: PlayerController, modifier: Modifier, zoom: Float, audioOnly: Boolean, fillViewport: Boolean) {
    val player = controller as IosPlayer
    DisposableEffect(audioOnly) {
        val release = if (audioOnly) ({}) else videoScreenWakeLock.acquire()
        onDispose { release() }
    }
    UIKitView(
        factory = { PlayerContainerView(player) },
        modifier = modifier,
        // Rebind the existing layer when returning to video, without racing a disposed host
        // or replacing the AVPlayerItem. Playback position, speed and pause intent stay intact.
        update = {
            player.displayLayer = it.videoLayer
            it.videoLayer.player = if (audioOnly) null else player.native
            it.videoLayer.videoGravity = if (fillViewport) AVLayerVideoGravityResizeAspectFill
                else AVLayerVideoGravityResizeAspect
            it.zoom = zoom
        },
        onRelease = {
            if (player.displayLayer === it.videoLayer) player.displayLayer = null
            it.videoLayer.player = null
        },
        // The container only hosts an AVPlayerLayer and has no native touch handling. With the
        // default interactive mode the interop view owns touch sequences, so the Compose layers
        // above it never receive taps or horizontal/vertical drags. Disable native interaction so
        // every touch is delivered to the shared gesture detectors (seek, volume, brightness).
        properties = UIKitInteropProperties(
            isInteractive = false,
            isNativeAccessibilityEnabled = true,
        ),
    )
}
