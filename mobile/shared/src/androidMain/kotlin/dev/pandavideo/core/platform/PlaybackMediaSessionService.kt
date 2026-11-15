package dev.pandavideo.core.platform

import android.content.Intent
import android.os.Bundle
import dev.pandavideo.core.player.SkipDurations
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
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
    private lateinit var preloader: AndroidPlaybackPreloader

    override fun onCreate() {
        super.onCreate()
        preloader = AndroidPlaybackPreloader(this)
        val player = preloader.player.apply {
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
            preloader.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession, controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_NEXT_EPISODE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_REPLAY, Bundle.EMPTY))
            // URLs travel only through the app's own controller; external transports retain
            // the ordinary MediaSession command set and cannot request speculative downloads.
            if (controller.packageName == packageName) {
                commands.add(SessionCommand(ACTION_LOAD, Bundle.EMPTY))
                commands.add(SessionCommand(ACTION_PRELOAD, Bundle.EMPTY))
                commands.add(SessionCommand(ACTION_PRELOAD_STATUS, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build()).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.packageName == packageName && customCommand.customAction == ACTION_LOAD) {
                val item = mediaItem(args) ?: return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                val hit = preloader.load(item, args.getLong("position").coerceAtLeast(0),
                    args.getBoolean("autoplay"), SkipDurations(args.getInt("introSeconds"), args.getInt("outroSeconds")))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS,
                    Bundle().apply { putBoolean("preloadHit", hit) }))
            }
            if (controller.packageName == packageName && customCommand.customAction == ACTION_PRELOAD) {
                preloader.preload(mediaItem(args))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (controller.packageName == packageName && customCommand.customAction == ACTION_PRELOAD_STATUS)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS,
                    Bundle().apply { putBoolean("ready", preloader.isReady) }))
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
        internal const val ACTION_LOAD = "dev.pandavideo.action.LOAD_PREPARED"
        internal const val ACTION_PRELOAD = "dev.pandavideo.action.PRELOAD"
        internal const val ACTION_PRELOAD_STATUS = "dev.pandavideo.action.PRELOAD_STATUS"

        private fun mediaItem(args: Bundle): MediaItem? {
            val url = args.getString("url") ?: return null
            return MediaItem.Builder().setUri(url).setMediaId(args.getLong("id").toString())
                .setMimeType(args.getString("mime"))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(args.getString("title"))
                    .setSubtitle(args.getString("episode")).setArtist(args.getString("episode")).build())
                .build()
        }

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
