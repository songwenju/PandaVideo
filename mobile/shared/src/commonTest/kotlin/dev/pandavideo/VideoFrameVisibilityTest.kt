package dev.pandavideo

import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.ui.player.VideoFrameVisibility
import kotlin.test.*

class VideoFrameVisibilityTest {
    @Test fun rebufferingDoesNotBlackOutAnAlreadyVisiblePresentationWhenTheCallbackIsMissing() {
        val visibility = VideoFrameVisibility()
        val playing = PlayerState(mediaId = 1, phase = PlayerPhase.Playing, position = 48_000)
        assertFalse(visibility.cover(playing, false, false, null))
        assertFalse(visibility.cover(playing.copy(phase = PlayerPhase.Buffering), false, false, null))
        assertFalse(visibility.cover(playing.copy(phase = PlayerPhase.Preparing), false, false, null))
    }

    @Test fun newEpisodeAndResolutionStillHideThePreviousEpisode() {
        val visibility = VideoFrameVisibility()
        val playing = PlayerState(mediaId = 1, phase = PlayerPhase.Playing, firstFrameRendered = true)
        assertFalse(visibility.cover(playing, false, false, null))
        assertTrue(visibility.cover(playing, true, false, null))
        val next = PlayerState(mediaId = 2, phase = PlayerPhase.Buffering, position = 60_000)
        assertTrue(visibility.cover(next, false, false, null))
        assertFalse(visibility.cover(next.copy(firstFrameRendered = true), false, false, null))
    }

    @Test fun resumePositionDoesNotCountAsAVisibleFrameAndAudioAndErrorsRemainUncovered() {
        val visibility = VideoFrameVisibility()
        val starting = PlayerState(mediaId = 1, phase = PlayerPhase.Buffering, position = 60_000)
        assertTrue(visibility.cover(starting, false, false, null))
        assertFalse(visibility.cover(starting, true, true, null))
        assertFalse(visibility.cover(starting, true, false, "播放失败"))
    }
}
