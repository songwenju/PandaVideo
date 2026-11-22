package dev.pandavideo.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import dev.pandavideo.core.player.PlayerState
import kotlinx.coroutines.flow.StateFlow

/** Replaces playback subscriptions without composing the previous session's first-frame flag. */
@Composable
internal fun <T> StateFlow<T>.collectPlaybackState(): State<T> = key(this) { collectAsState() }

/** Keeps replacement video covered until the requested media reports a rendered frame. */
internal class VideoFrameVisibility {
    private var mediaId: Long? = null
    private var presented = false

    fun cover(player: PlayerState, loading: Boolean, audioOnly: Boolean, error: String?,
        requestedMediaId: Long = player.mediaId): Boolean {
        if (mediaId != requestedMediaId) {
            mediaId = requestedMediaId
            presented = false
        }
        // Resolution and native state are collected independently. An old player's rendered
        // flag cannot acknowledge the new request, and Ready/Playing may precede video output.
        // After a verified presentation, preserve it through rebuffering and output resets.
        if (!loading && !audioOnly && player.mediaId == requestedMediaId && player.firstFrameRendered)
            presented = true
        return !audioOnly && error == null && (loading || player.mediaId != requestedMediaId || !presented)
    }
}
