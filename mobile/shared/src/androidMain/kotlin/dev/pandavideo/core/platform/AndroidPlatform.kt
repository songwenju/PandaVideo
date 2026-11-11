package dev.pandavideo.core.platform

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlayerCapabilities
import dev.pandavideo.core.player.PlayerController
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.core.player.PreviewFrame
import dev.pandavideo.data.repository.isWebUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

class AndroidPlatform(private val activity: Activity) : PlatformServices {
    override fun offlineEngine(): dev.pandavideo.data.repository.OfflineEngine =
        AndroidOfflineEngine.get(activity.applicationContext)
    override fun avatarDirectory(): String =
        java.io.File(context.filesDir, "avatars").apply { mkdirs() }.absolutePath
    override fun deleteAvatar(filename: String) {
        if (avatarUri(filename) != null) java.io.File(avatarDirectory(), filename).delete()
    }
    override val dlnaSupported: Boolean = true
    override suspend fun discoverDlnaLocations(host: String?): List<String> = discoverAndroidDlna(context, host)
    private val context = activity.applicationContext
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activePlayer: AndroidPlayer? = null
    private var orientationBeforeFullscreen: Int? = null
    private val picoFullscreenWindow by lazy { PicoFullscreenWindow.create(activity) }
    private val pipMutable = MutableStateFlow(false)
    override val pictureInPictureActive: StateFlow<Boolean> = pipMutable.asStateFlow()
    override val pipResizesAppWindow: Boolean = true
    override val pipSupported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    override val haptics: Haptics = AndroidHaptics(activity)
    override fun httpClient() = HttpClient(OkHttp) { followRedirects = false }
    override fun player(): PlayerController = AndroidPlayer(context).also { activePlayer = it }
    override fun storage(): KeyValueStorage = SharedPrefsStorage(context)
    override fun catalogDatabasePath(): String =
        context.getDatabasePath("catalog-v1.db").also { it.parentFile?.mkdirs() }.absolutePath
    override fun posterCacheDirectory(): String = java.io.File(context.cacheDir, "posters").absolutePath
    override fun openLink(url: String) {
        if (isWebUrl(url)) activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    override fun fullscreen(enabled: Boolean) {
        // Request a fixed landscape orientation immediately, without depending on a
        // sensor-driven orientation policy that some window managers do not apply.
        // Preserve the original policy only on entry: repeated fullscreen calls must not
        // overwrite it with landscape. Restore it on exit, disposal, or PiP activation.
        if (enabled) {
            if (orientationBeforeFullscreen == null) {
                orientationBeforeFullscreen = activity.requestedOrientation
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            orientationBeforeFullscreen?.let { activity.requestedOrientation = it }
            orientationBeforeFullscreen = null
        }
        // PICO Spatial OS deliberately ignores display rotation requests. Resize its
        // application container as well, restoring the saved dimensions on exit/PiP.
        picoFullscreenWindow?.fullscreen(enabled)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun setSystemBarAppearance(lightStatusBarIcons: Boolean, darkTheme: Boolean) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !lightStatusBarIcons
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    override fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }
    override fun setVolume(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (value.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max),
            0,
        )
    }
    override fun currentBrightness(): Float {
        val override = activity.window.attributes.screenBrightness
        if (override in 0f..1f) return override
        return try {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }
    override fun setBrightness(value: Float) {
        val window = activity.window
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0f, 1f) }
    }
    override fun resetBrightness() {
        val window = activity.window
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
    override fun enterPictureInPicture(): Boolean {
        if (!pipSupported || activity.isInPictureInPictureMode) return false
        return try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .apply { if (Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(true) }
                .build()
            activity.enterPictureInPictureMode(params)
        } catch (_: Exception) {
            false
        }
    }
    /** Called by MainActivity when the PiP mode changes. */
    fun setPictureInPictureActive(active: Boolean) {
        // Restore the window policy synchronously; the Compose fullscreen state follows
        // the PiP flow asynchronously and must not leave a landscape request behind.
        if (active) fullscreen(false)
        pipMutable.value = active
    }
    /** Whether a media item is loaded and playable, used to gate auto-PiP on leave-home. */
    fun hasActivePlayback(): Boolean {
        val current = activePlayer?.state?.value ?: return false
        return current.mediaId != 0L && current.wantsPlay &&
            current.phase in setOf(PlayerPhase.Ready, PlayerPhase.Playing, PlayerPhase.Paused, PlayerPhase.Buffering)
    }
}

internal class SharedPrefsStorage(context: Context) : KeyValueStorage {
    private val prefs = context.getSharedPreferences("suixinkan_user", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()

/**
 * PlayerController backed by a MediaController bound to [PlaybackMediaSessionService]. The
 * service owns the ExoPlayer and publishes the system media notification; commands issued
 * before the controller connects are coalesced into a single pending media load.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidPlayer(context: Context) : PlayerController {
    private val appContext = context.applicationContext
    private val future = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackMediaSessionService::class.java)),
    ).buildAsync()
    // The service connects after the surface can already be composed. Publish the connection
    // as observable state so PlayerView binds immediately, even when playback state and the
    // surface's parameters have not changed. Otherwise the decoder stays on its dummy surface.
    private var native: Player? by mutableStateOf(null)
    private var pendingSource: MediaSource? = null

    private val mutable = MutableStateFlow(PlayerState())
    override val state = mutable.asStateFlow()
    override val capabilities = PlayerCapabilities(pictureInPicture = true, framePreview = true)
    private val seekPreview = AndroidSeekPreview(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var released = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (released) return
            val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: mutable.value.mediaId
            if (id != mutable.value.mediaId) return
            val phase = when {
                player.playerError != null -> PlayerPhase.Error
                player.playbackState == Player.STATE_ENDED -> PlayerPhase.Ended
                player.playbackState == Player.STATE_BUFFERING -> PlayerPhase.Buffering
                player.isPlaying -> PlayerPhase.Playing
                player.playbackState == Player.STATE_READY -> if (player.playWhenReady) PlayerPhase.Ready else PlayerPhase.Paused
                else -> PlayerPhase.Idle
            }
            mutable.value = mutable.value.copy(
                phase = phase, wantsPlay = player.playWhenReady, position = player.currentPosition.coerceAtLeast(0),
                duration = player.duration.takeIf { it > 0 } ?: 0,
                rate = player.playbackParameters.speed,
                error = player.playerError?.let { "视频无法播放，请重新解析或切换线路（${it.errorCodeName}）" },
            )
        }
    }
    init {
        future.addListener({
            if (released) return@addListener
            future.get()?.let { controller ->
                native = controller
                controller.addListener(listener)
                pendingSource?.let { applyLoad(it); pendingSource = null }
            }
        }, ContextCompat.getMainExecutor(appContext))
        scope.launch {
            while (isActive) {
                delay(500)
                val player = native ?: continue
                if (!released) mutable.value = mutable.value.copy(position = player.currentPosition.coerceAtLeast(0), duration = player.duration.takeIf { it > 0 } ?: 0)
            }
        }
    }
    private fun applyLoad(source: MediaSource) {
        val player = native ?: return
        mutable.value = PlayerState(mediaId = source.id, phase = PlayerPhase.Preparing, wantsPlay = source.autoplay)
        player.playWhenReady = source.autoplay
        player.setMediaItem(MediaItem.Builder().setUri(source.url).setMediaId(source.id.toString())
            .setMimeType(if (source.kind == "hls") MimeTypes.APPLICATION_M3U8 else null)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(source.title)
                    .setSubtitle(source.episodeLabel)
                    .setArtist(source.episodeLabel)
                    .build(),
            )
            .build())
        player.prepare()
    }
    override fun load(source: MediaSource) {
        if (released) return
        seekPreview.setSource(source)
        val player = native
        if (player == null) {
            pendingSource = source
        } else {
            applyLoad(source)
        }
    }
    override fun play() {
        if (released) return
        pendingSource = pendingSource?.copy(autoplay = true)
        mutable.value = mutable.value.copy(wantsPlay = true)
        native?.play()
    }
    override fun pause() {
        if (released) return
        // MediaController connects asynchronously; remember pause before it exists.
        pendingSource = pendingSource?.copy(autoplay = false)
        mutable.value = mutable.value.copy(wantsPlay = false)
        native?.pause()
    }
    override fun seekTo(position: Long) {
        val player = native ?: return
        if (!released && player.isCurrentMediaItemSeekable) player.seekTo(position.coerceIn(0, state.value.duration.coerceAtLeast(0)))
    }
    override fun setPlaybackRate(rate: Float) { if (!released && rate in 0.5f..2f) native?.setPlaybackSpeed(rate) }
    /** Extracts a bounded thumbnail with Media3 without issuing commands to the media session. */
    override suspend fun previewFrame(position: Long): PreviewFrame? =
        seekPreview.frame(state.value.mediaId, position)
    override fun stop() {
        if (released) return
        seekPreview.close()
        pendingSource = null
        native?.let { it.stop(); it.clearMediaItems() }
        mutable.value = mutable.value.copy(phase = PlayerPhase.Idle, wantsPlay = false, error = null)
    }
    override fun setRemoteEpisodeActions(onNext: (() -> Unit)?, onPrevious: (() -> Unit)?) {
        PlaybackMediaSessionService.setEpisodeHandlers(onNext, onPrevious)
    }
    override fun close() {
        if (released) return
        released = true
        seekPreview.close()
        scope.cancel()
        PlaybackMediaSessionService.setEpisodeHandlers(onNext = null, onReplay = null)
        native?.removeListener(listener)
        MediaController.releaseFuture(future)
        native = null
        mutable.value = mutable.value.copy(phase = PlayerPhase.Released, wantsPlay = false)
    }
    /** The bound media3 player once connected, used by the Compose PlayerView host. */
    val nativePlayer: Player? get() = native
}

@Composable
actual fun PlayerSurface(controller: PlayerController, modifier: Modifier, zoom: Float) {
    val player = controller as AndroidPlayer
    val nativePlayer = player.nativePlayer
    AndroidView(factory = { context -> PlayerView(context).apply {
        keepScreenOn = true; useController = false
        clipChildren = true
        clipToPadding = true
    } },
        modifier = modifier,
        update = {
            it.player = nativePlayer
            // Transform Media3's native content frame, including its SurfaceView, rather than
            // a Compose graphics layer which does not reliably transform native video surfaces.
            it.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)?.apply {
                scaleX = zoom
                scaleY = zoom
            }
        },
        onRelease = { it.player = null })
}
