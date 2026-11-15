package dev.pandavideo.ui.player

import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState

/** Tracks the visible presentation independently of the first-frame telemetry callback. */
internal class VideoFrameVisibility {
    private var mediaId: Long? = null
    private var presented = false

    fun cover(player: PlayerState, loading: Boolean, audioOnly: Boolean, error: String?): Boolean {
        if (mediaId != player.mediaId) {
            mediaId = player.mediaId
            presented = false
        }
        // Playing already exposes the native surface even if first-frame telemetry was missed.
        // Remember that presentation across subsequent buffering; a resume position alone must
        // never unmask startup. Reset on media ID changes so a new episode cannot show old video.
        if (!audioOnly && (player.firstFrameRendered || player.phase == PlayerPhase.Playing)) presented = true
        return !audioOnly && error == null &&
            (loading || (!presented &&
                player.phase in listOf(PlayerPhase.Idle, PlayerPhase.Preparing, PlayerPhase.Buffering)))
    }
}
