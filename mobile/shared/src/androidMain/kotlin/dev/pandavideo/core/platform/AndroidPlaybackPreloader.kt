package dev.pandavideo.core.platform

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.SkipDurations
import dev.pandavideo.core.player.startupPosition
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl

/**
 * Owns one upcoming media-data preload and the service player on its application thread.
 * Both share Media3's load control so downloading for active playback takes precedence.
 */
@UnstableApi
internal class AndroidPlaybackPreloader(context: Context) {
    private val builder = DefaultPreloadManager.Builder(context,
        TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(PRELOAD_MS)
        })
        .setMediaSourceFactory(OfflineMediaSourceFactory(context))
        .setLoadControl(DefaultLoadControl.Builder()
            .setPlayerTargetBufferBytes("preload", 8 * 1024 * 1024).build())
    private val manager = builder.build()
    val player: ExoPlayer = builder.buildExoPlayer(ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000))
    private var candidate: MediaItem? = null
    private var completed = false
    private var consumed: MediaItem? = null
    private var startupSource: MediaSource? = null
    val isReady: Boolean get() = candidate != null && completed

    init {
        manager.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                if (candidate == mediaItem) completed = true
            }
        })
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                applyStartupPosition()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) applyStartupPosition(ready = true)
                if (playbackState == Player.STATE_IDLE) startupSource = null
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_IDLE) {
                    // Keep the consumed preload registered until ExoPlayer has acquired its
                    // period. Resetting it just after prepare() would race the playback thread
                    // and discard precisely the buffered data this optimization should reuse.
                    consumed?.let(manager::remove)
                    consumed = null
                }
            }
        })
    }

    fun preload(item: MediaItem?) {
        if (item == candidate) return
        candidate?.let(manager::remove)
        candidate = null
        completed = false
        if (item == null || item.localConfiguration?.uri?.scheme !in setOf("https", "http")) return
        candidate = item
        manager.add(item, 1)
        manager.invalidate()
    }

    private fun applyStartupPosition(ready: Boolean = false) {
        val source = startupSource ?: return
        if (player.currentMediaItem?.mediaId != source.id.toString()) return
        if (!ready && (player.currentTimeline.isEmpty ||
            player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).isPlaceholder ||
            player.duration <= 0)) return
        startupSource = null
        val target = source.startupPosition(player.duration)
        if (target > 0 && target != player.currentPosition) player.seekTo(target)
    }

    fun load(item: MediaItem, startPosition: Long, autoplay: Boolean,
        startupSkip: SkipDurations = SkipDurations()): Boolean {
        val upcoming = candidate
        val source = upcoming?.takeIf {
            startPosition == 0L && it.localConfiguration?.uri == item.localConfiguration?.uri &&
                it.localConfiguration?.mimeType == item.localConfiguration?.mimeType
        }?.let(manager::getMediaSource)
        val reusable = source?.takeIf { it.canUpdateMediaItem(item) }
        val hit = reusable != null && completed
        consumed?.let(manager::remove)
        consumed = null
        player.playWhenReady = autoplay
        if (reusable != null) {
            reusable.updateMediaItem(item)
            consumed = upcoming
            candidate = null
            completed = false
            player.setMediaSource(reusable, startPosition)
        } else {
            preload(null)
            player.setMediaItem(item, startPosition)
        }
        // setMediaItem may emit IDLE while replacing the old source. Install the new
        // policy afterwards so that transition cannot discard its opening target.
        startupSource = MediaSource(item.mediaId.toLong(), "", "", "", startPosition = startPosition,
            startupSkip = startupSkip)
        applyStartupPosition()
        player.prepare()
        return hit
    }

    fun release() {
        player.release()
        manager.release()
        candidate = null
        consumed = null
    }

    private companion object { const val PRELOAD_MS = 3_000L }
}
