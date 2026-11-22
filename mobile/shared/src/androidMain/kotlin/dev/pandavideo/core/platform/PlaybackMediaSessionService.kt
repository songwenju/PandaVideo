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
    private var systemMute: AndroidSystemMediaMute? = null

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
        if (packageName == "dev.pandavideo.tv") systemMute = AndroidSystemMediaMute(this, player)
        mediaSession = MediaSession.Builder(this, player).setCallback(SessionCallback()).build().also {
            activeSession = it
            publishCommands()
        }
        activeService = this
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
        releasePlayback()
        // Bootstrap files outlive MediaControllers so background playback keeps working.
        // The service owns the final readers; purge only after its player/preloader release.
        java.io.File(cacheDir, "hls-bootstrap").deleteRecursively()
        super.onDestroy()
    }

    private fun releasePlayback() {
        if (activeService === this) {
            activeService = null
            activeSession = null
        }
        systemMute?.close()
        systemMute = null
        val session = mediaSession
        mediaSession = null
        if (session != null) {
            // ExoPlayer.release() waits for its playback thread to release native codecs.
            // A controller disconnect or pause leaves the service's decoder allocated;
            // receiver playback must start only after this owner has released it.
            preloader.release()
            session.release()
        }
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
                    args.getBoolean("autoplay"), SkipDurations(args.getInt("introSeconds"), args.getInt("outroSeconds")),
                    segments(args))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS,
                    Bundle().apply { putBoolean("preloadHit", hit) }))
            }
            if (controller.packageName == packageName && customCommand.customAction == ACTION_PRELOAD) {
                preloader.preload(if (segments(args).isEmpty()) mediaItem(args) else null)
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

        private fun segments(args: Bundle): List<dev.pandavideo.core.model.MediaSegment> {
            val urls = args.getStringArrayList("segmentUrls") ?: return emptyList()
            val durations = args.getLongArray("segmentDurations") ?: return emptyList()
            if (urls.size != durations.size || durations.any { it <= 0 }) return emptyList()
            return urls.mapIndexed { index, url -> dev.pandavideo.core.model.MediaSegment(url, durations[index]) }
        }

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

        private var activeService: PlaybackMediaSessionService? = null

        /**
         * Seeks once and acknowledges native command processing followed by readiness.
         * A playback-thread message is a FIFO barrier behind seekTo: the controller's
         * optimistic position discontinuity alone is not evidence of decoder readiness.
         * Call and cancel on the main thread. Errors, release and media replacement also
         * settle the request so a failed seek cannot permanently suspend UI timers.
         */
        internal fun seekToReady(position: Long, settled: () -> Unit): (() -> Unit)? {
            val player = activeService?.preloader?.player ?: return null
            val media = player.currentMediaItem?.mediaId
            var processed = false
            var finished = false
            lateinit var observer: androidx.media3.common.Player.Listener
            fun finish() {
                if (finished) return
                finished = true
                player.removeListener(observer)
                settled()
            }
            fun checkReady() {
                if (processed && (player.playbackState != androidx.media3.common.Player.STATE_BUFFERING ||
                        player.playerError != null || player.currentMediaItem?.mediaId != media)) finish()
            }
            observer = object : androidx.media3.common.Player.Listener {
                override fun onEvents(observed: androidx.media3.common.Player,
                    events: androidx.media3.common.Player.Events) { checkReady() }
            }
            player.addListener(observer)
            player.seekTo(position)
            val barrier = player.createMessage { _, _ ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    processed = true
                    checkReady()
                }
            }.send()
            return {
                finished = true
                barrier.cancel()
                player.removeListener(observer)
            }
        }

        /**
         * Releases local playback and preload resources before the integrated TV receiver
         * creates its player. Call on the main thread; completion means the native player
         * has been released, rather than merely scheduling service destruction.
         * Repeated calls without an active service are harmless.
         */
        @JvmStatic
        fun releaseForCasting() {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            val service = activeService ?: return
            // Background playback on phones keeps its ordinary lifetime.
            check(service.packageName == "dev.pandavideo.tv")
            service.releasePlayback()
            service.stopSelf()
        }

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
