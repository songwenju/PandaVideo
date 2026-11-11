package dev.pandavideo.core.platform

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Hosts the single [ExoPlayer] and its [MediaSession], so the system renders a media
 * notification and lock-screen transport controls. The app side talks to the player through a
 * MediaController bound to this service; custom commands bridge notification actions back to
 * the shared PlaybackSession (next episode / replay current).
 */
@UnstableApi
class PlaybackMediaSessionService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(OfflineMediaSourceFactory(this))
            // Notification gets ±10s seek buttons; matches the in-player double-tap step.
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    /* handleAudioFocus = */ true,
                )
                setHandleAudioBecomingNoisy(true)
            }
        mediaSession = MediaSession.Builder(this, player).setCallback(SessionCallback()).build().also {
            activeSession = it
            publishCommands()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        activeSession = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            val handled = when (customCommand.customAction) {
                ACTION_NEXT_EPISODE -> { nextEpisodeHandler?.invoke(); true }
                ACTION_REPLAY -> { replayHandler?.invoke(); true }
                else -> false
            }
            return Futures.immediateFuture(
                SessionResult(if (handled) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_NOT_SUPPORTED),
            )
        }
    }

    companion object {
        const val ACTION_NEXT_EPISODE = "dev.pandavideo.action.NEXT_EPISODE"
        const val ACTION_REPLAY = "dev.pandavideo.action.REPLAY"
        private const val SEEK_STEP_MS = 10_000L

        @Volatile
        private var activeSession: MediaSession? = null

        @Volatile
        private var nextEpisodeHandler: (() -> Unit)? = null

        @Volatile
        private var replayHandler: (() -> Unit)? = null

        /** Registers notification custom actions and refreshes the published button layout. */
        fun setEpisodeHandlers(onNext: (() -> Unit)?, onReplay: (() -> Unit)?) {
            nextEpisodeHandler = onNext
            replayHandler = onReplay
            publishCommands()
        }

        private fun publishCommands() {
            val session = activeSession ?: return
            val buttons = buildList {
                replayHandler?.let {
                    add(
                        CommandButton.Builder()
                            .setSessionCommand(SessionCommand(ACTION_REPLAY, Bundle.EMPTY))
                            .setDisplayName("重播本集")
                            .setIconResId(android.R.drawable.ic_media_rew)
                            .build(),
                    )
                }
                nextEpisodeHandler?.let {
                    add(
                        CommandButton.Builder()
                            .setSessionCommand(SessionCommand(ACTION_NEXT_EPISODE, Bundle.EMPTY))
                            .setDisplayName("下一集")
                            .setIconResId(android.R.drawable.ic_media_next)
                            .build(),
                    )
                }
            }
            session.setCustomLayout(ImmutableList.copyOf(buttons))
        }
    }
}
