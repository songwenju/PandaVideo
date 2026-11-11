package dev.pandavideo.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlayerController
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM/desktop player backed by JavaFX Media. On macOS the AVFoundation backend supports both
 * HLS (.m3u8) and MP4. All JavaFX objects are created and mutated on the JavaFX application
 * thread; Compose observes the thread-safe [state] flow.
 */
class DesktopPlayer(initialVolume: Float = 1f) : PlayerController {
    private val mutable = MutableStateFlow(PlayerState())
    override val state = mutable.asStateFlow()

    private var fx: MediaPlayer? = null
    private var mediaView: MediaView? = null
    private var released = false
    private var volumeLevel = initialVolume.coerceIn(0f, 1f)
    private var lastTick = 0L

    @Volatile
    private var fxStarted = false
    private val fxLock = Any()

    /**
     * Ensures the JavaFX runtime is bootstrapped before scheduling work. load() can arrive
     * before any JFXPanel exists, and Platform.startup throws if another component (such as
     * a JFXPanel) already started the toolkit, so the double-start is treated as success.
     */
    private fun fxRun(action: () -> Unit) {
        if (!fxStarted) {
            synchronized(fxLock) {
                if (!fxStarted) {
                    try {
                        Platform.startup { fxStarted = true }
                    } catch (_: IllegalStateException) {
                        fxStarted = true
                    }
                }
            }
        }
        Platform.runLater(action)
    }

    private val timer = object : AnimationTimer() {
        override fun handle(now: Long) {
            if (released) return
            if (now - lastTick < TICK_INTERVAL_NANOS) return
            lastTick = now
            val player = fx ?: return
            // javafx.util.Duration has no isFinite(); live/unknown streams report INDEFINITE/UNKNOWN.
            val duration = player.cycleDuration
                .takeIf { !it.isIndefinite && !it.isUnknown }
                ?.toMillis()?.toLong()?.takeIf { ms -> ms > 0L } ?: 0L
            val position = player.currentTime.toMillis().toLong().coerceAtLeast(0L)
            mutable.value = mutable.value.copy(
                position = position,
                duration = duration,
                rate = player.rate.toFloat(),
            )
        }
    }

    init {
        fxRun { timer.start() }
    }

    /** Lazily creates the heavyweight panel hosting the MediaView scene. */
    fun swingPanel(): JFXPanel {
        lateinit var panel: JFXPanel
        panel = JFXPanel()
        fxRun {
            val view = MediaView()
            mediaView = view
            val root = StackPane(view).apply { style = "-fx-background-color: black;" }
            // The native surface occupies only the area between the desktop toolbars.
            // Fit the movie to that area instead of allowing its intrinsic pixel dimensions
            // to overflow the panel when the window or fullscreen layout changes.
            view.fitWidthProperty().bind(root.widthProperty())
            view.fitHeightProperty().bind(root.heightProperty())
            view.isPreserveRatio = true
            panel.scene = Scene(root, javafx.scene.paint.Color.BLACK)
            fx?.let { view.mediaPlayer = it }
        }
        return panel
    }

    override fun load(source: MediaSource) {
        if (released) return
        mutable.value = PlayerState(mediaId = source.id, phase = PlayerPhase.Preparing, wantsPlay = source.autoplay)
        fxRun {
            if (released || mutable.value.mediaId != source.id) return@fxRun
            try {
                fx?.let { old ->
                    mediaView?.mediaPlayer = null
                    old.dispose()
                }
                val media = Media(source.url)
                val player = MediaPlayer(media)
                player.volume = volumeLevel.toDouble()
                player.setOnReady {
                    if (!released) mutable.value = mutable.value.copy(phase = PlayerPhase.Ready, error = null)
                }
                player.setOnPlaying {
                    if (!released) mutable.value = mutable.value.copy(
                        phase = PlayerPhase.Playing, wantsPlay = true, error = null,
                    )
                }
                player.setOnPaused {
                    if (!released) mutable.value = mutable.value.copy(phase = PlayerPhase.Paused, wantsPlay = false)
                }
                player.setOnStalled {
                    if (!released) mutable.value = mutable.value.copy(phase = PlayerPhase.Buffering)
                }
                player.setOnEndOfMedia {
                    if (!released) mutable.value = mutable.value.copy(phase = PlayerPhase.Ended, wantsPlay = false)
                }
                player.setOnHalted { reportError() }
                player.setOnError { reportError() }
                fx = player
                mediaView?.mediaPlayer = player
                // The queued creation may run after pause/navigation on the Compose thread.
                if (mutable.value.wantsPlay) player.play()
            } catch (_: Throwable) {
                reportError()
            }
        }
    }

    private fun reportError() {
        if (released) return
        // Keep the message generic; never surface media URLs or upstream error text.
        mutable.value = mutable.value.copy(
            phase = PlayerPhase.Error, wantsPlay = false,
            error = "视频无法播放，请重新解析或切换线路",
        )
    }

    override fun play() {
        if (released) return
        mutable.value = mutable.value.copy(wantsPlay = true)
        fxRun { fx?.play() }
    }

    override fun pause() {
        if (released) return
        mutable.value = mutable.value.copy(wantsPlay = false, phase = PlayerPhase.Paused)
        fxRun { fx?.pause() }
    }

    override fun seekTo(position: Long) {
        if (released) return
        val target = position.coerceIn(0L, mutable.value.duration.coerceAtLeast(0L))
        fxRun {
            fx?.let { player ->
                player.seek(Duration.millis(target.toDouble()))
                // AVFoundation may advance after a seek even while JavaFX reports PAUSED.
                // Reapply the transport intent rather than treating a seek as a play action.
                if (!mutable.value.wantsPlay) player.pause()
            }
        }
    }

    override fun setPlaybackRate(rate: Float) {
        if (released || rate !in 0.5f..2f) return
        mutable.value = mutable.value.copy(rate = rate)
        fxRun {
            fx?.let { player ->
                player.rate = rate.toDouble()
                // Changing the native rate must not silently resume a paused movie.
                if (!mutable.value.wantsPlay) player.pause()
            }
        }
    }

    fun setVolume(value: Float) {
        volumeLevel = value.coerceIn(0f, 1f)
        fxRun { fx?.volume = volumeLevel.toDouble() }
    }

    override fun stop() {
        if (released) return
        fxRun {
            fx?.let { old ->
                mediaView?.mediaPlayer = null
                old.dispose()
            }
            fx = null
        }
        mutable.value = mutable.value.copy(phase = PlayerPhase.Idle, wantsPlay = false, error = null)
    }

    override fun close() {
        if (released) return
        released = true
        fxRun {
            timer.stop()
            fx?.let { old ->
                mediaView?.mediaPlayer = null
                old.dispose()
            }
            fx = null
        }
        mutable.value = mutable.value.copy(phase = PlayerPhase.Released, wantsPlay = false)
    }

    private companion object {
        const val TICK_INTERVAL_NANOS = 250_000_000L
    }
}

@Composable
actual fun PlayerSurface(controller: PlayerController, modifier: Modifier, zoom: Float) {
    val player = controller as DesktopPlayer
    SwingPanel(
        factory = { player.swingPanel() },
        modifier = modifier,
        background = Color.Black,
    )
}
