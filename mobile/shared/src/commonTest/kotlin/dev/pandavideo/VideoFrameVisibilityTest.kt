package dev.pandavideo

import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.ui.player.VideoFrameVisibility
import kotlin.test.*

class VideoFrameVisibilityTest {
    @Test fun readyPlayingAndPausedDoNotExposeAnUnrenderedReplacement() {
        val visibility = VideoFrameVisibility()
        assertFalse(visibility.cover(PlayerState(mediaId = 1, phase = PlayerPhase.Playing,
            firstFrameRendered = true), false, false, null))
        for (phase in listOf(PlayerPhase.Ready, PlayerPhase.Playing, PlayerPhase.Paused)) {
            assertTrue(visibility.cover(PlayerState(mediaId = 2, phase = phase,
                position = 60_000), false, false, null),
                "Playback state and a resumed position cannot prove the replacement was painted")
        }
        assertFalse(visibility.cover(PlayerState(mediaId = 2, phase = PlayerPhase.Paused,
            firstFrameRendered = true), false, false, null))
    }

    @Test fun rebufferingDoesNotBlackOutAnAlreadyRenderedPresentation() {
        val visibility = VideoFrameVisibility()
        val playing = PlayerState(mediaId = 1, phase = PlayerPhase.Playing, position = 48_000,
            firstFrameRendered = true)
        assertFalse(visibility.cover(playing, false, false, null))
        assertFalse(visibility.cover(playing.copy(phase = PlayerPhase.Buffering, firstFrameRendered = false), false, false, null))
        assertFalse(visibility.cover(playing.copy(phase = PlayerPhase.Preparing, firstFrameRendered = false), false, false, null))
    }

    @Test fun resolvedRequestCannotAcknowledgeThePreviousPlayersFirstFrame() {
        val visibility = VideoFrameVisibility()
        val previous = PlayerState(mediaId = 1, phase = PlayerPhase.Playing, firstFrameRendered = true)
        assertFalse(visibility.cover(previous, false, false, null, requestedMediaId = 1))
        assertTrue(visibility.cover(previous, true, false, null, requestedMediaId = 2))
        assertTrue(visibility.cover(previous, false, false, null, requestedMediaId = 2))
        assertTrue(visibility.cover(PlayerState(mediaId = 2, phase = PlayerPhase.Playing),
            false, false, null, requestedMediaId = 2))
        assertFalse(visibility.cover(previous.copy(mediaId = 2), false, false, null, requestedMediaId = 2))
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
