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
    override fun httpClient() = HttpClient(Darwin) { followRedirects = false }
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
    val native = AVPlayer()
    private val mutable = MutableStateFlow(PlayerState())
    override val state = mutable.asStateFlow()
    override val capabilities = PlayerCapabilities(pictureInPicture = true, framePreview = true)
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
                delay(250)
                val item = native.currentItem ?: continue
                val duration = CMTimeGetSeconds(item.duration).takeIf { it.isFinite() && it > 0 } ?: 0.0
                val position = CMTimeGetSeconds(native.currentTime()).takeIf { it.isFinite() && it > 0 } ?: 0.0
                val previous = mutable.value
                val phase = when {
                    item.status == AVPlayerItemStatusFailed -> PlayerPhase.Error
                    previous.phase == PlayerPhase.Ended -> PlayerPhase.Ended
                    item.status == AVPlayerItemStatusUnknown -> PlayerPhase.Preparing
                    native.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> PlayerPhase.Buffering
                    native.timeControlStatus == AVPlayerTimeControlStatusPlaying -> PlayerPhase.Playing
                    else -> PlayerPhase.Paused
                }
                val wants = when (phase) {
                    PlayerPhase.Playing -> true
                    PlayerPhase.Paused, PlayerPhase.Ended, PlayerPhase.Error -> false
                    else -> previous.wantsPlay
                }
                mutable.value = previous.copy(
                    phase = phase, wantsPlay = wants, duration = (duration * 1000).toLong(), position = (position * 1000).toLong(),
                    error = if (phase == PlayerPhase.Error) "视频无法播放，请重新解析或切换线路" else null,
                )
                publishNowPlaying(phase, (position * 1000).toLong(), (duration * 1000).toLong())
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
        cancelPreview()
        native.pause()
        removeEndObserver()
        val url = NSURL.URLWithString(source.url) ?: return
        val item = AVPlayerItem(uRL = url)
        currentTitle = source.title
        currentEpisode = source.episodeLabel
        nowPlayingPhase = null
        mutable.value = PlayerState(mediaId = source.id, phase = PlayerPhase.Preparing, wantsPlay = source.autoplay)
        loadArtwork(source)
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
        ) {
            if (!released && mutable.value.mediaId == source.id && native.currentItem == item) {
                mutable.value = mutable.value.copy(phase = PlayerPhase.Ended, wantsPlay = false)
            }
        }
        native.replaceCurrentItemWithPlayerItem(item)
        if (source.autoplay) native.play()
    }
    override fun play() {
        if (!released) { mutable.value = mutable.value.copy(wantsPlay = true); native.playImmediatelyAtRate(mutable.value.rate) }
    }
    override fun pause() {
        if (!released) { native.pause(); mutable.value = mutable.value.copy(wantsPlay = false, phase = PlayerPhase.Paused) }
    }
    override fun seekTo(position: Long) {
        if (!released && mutable.value.duration > 0 && native.currentItem?.seekableTimeRanges?.isNotEmpty() == true) {
            native.seekToTime(CMTimeMakeWithSeconds(position.coerceIn(0, mutable.value.duration).toDouble() / 1000, 600))
        }
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
            if (mutable.value.wantsPlay) native.playImmediatelyAtRate(rate)
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

@Composable
actual fun PlayerSurface(controller: PlayerController, modifier: Modifier, zoom: Float) {
    val player = controller as IosPlayer
    UIKitView(
        factory = { PlayerContainerView(player) },
        modifier = modifier,
        update = { it.videoLayer.player = player.native; it.zoom = zoom },
        onRelease = { it.videoLayer.player = null },
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
