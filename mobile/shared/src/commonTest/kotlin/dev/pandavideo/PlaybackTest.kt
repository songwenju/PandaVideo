package dev.pandavideo

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.nextEpisode
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.core.player.PlayerController
import dev.pandavideo.core.player.PlayerCapabilities
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.ui.player.TapZone
import dev.pandavideo.ui.player.adjustedLevel
import dev.pandavideo.ui.player.dragSeekPosition
import dev.pandavideo.ui.player.quickSeekPosition
import dev.pandavideo.ui.player.tapZone
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTest {
    private val episodes = listOf(Episode("a1", "1", 1), Episode("b1", "1", 2), Episode("a2", "2", 1), Episode("b2", "2", 2))
    private fun info(selected: String = "a1") = PlaybackInfo("测试", "https://media/video.mp4", "video", episodes, selected, "https://page")
    private class FakePlayer(audioSupported: Boolean = true) : PlayerController {
        override val capabilities = PlayerCapabilities(audioOnly = audioSupported)
        override val state = MutableStateFlow(PlayerState())
        val loads = mutableListOf<MediaSource>()
        var closes = 0
        val rates = mutableListOf<Float>()
        val seeks = mutableListOf<Long>()
        val audioModes = mutableListOf<Boolean>()
        override fun setAudioOnly(enabled: Boolean) { audioModes += enabled }
        override fun load(source: MediaSource) { loads += source; state.value = PlayerState(source.id, if (source.autoplay) PlayerPhase.Playing else PlayerPhase.Paused, wantsPlay = source.autoplay) }
        override fun play() { state.value = state.value.copy(wantsPlay = true) }
        override fun pause() { state.value = state.value.copy(wantsPlay = false) }
        override fun seekTo(position: Long) { seeks += position }
        override fun setPlaybackRate(rate: Float) { rates += rate }
        override fun stop() { state.value = state.value.copy(phase = PlayerPhase.Idle) }
        override fun close() { closes++ }
    }
    @Test fun nextEpisodeNeverCrossesLine() {
        assertEquals("a2", nextEpisode(info())?.path)
        assertNull(nextEpisode(info("a2")))
        assertNull(nextEpisode(info("missing")))
    }
    @Test fun audioTogglePreservesTimelineRateAndPausedIntentWithoutReload() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info() }, player, backgroundScope, 1.5f)
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        player.state.value = player.state.value.copy(position = 42_000, duration = 120_000, rate = 1.5f)
        player.pause(); runCurrent()
        session.setAudioOnly(true)
        session.setAudioOnly(false)
        runCurrent()
        assertEquals(listOf(true, false), player.audioModes)
        assertEquals(1, player.loads.size)
        assertEquals(42_000L, player.state.value.position)
        assertEquals(1.5f, player.state.value.rate)
        assertFalse(player.state.value.wantsPlay)
        assertTrue(player.seeks.isEmpty())
        session.close()
    }
    @Test fun audioModeAllowsPendingLoadAndNextEpisodeWhileHidden() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ delay(1000); info(it.episode ?: "a1") }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        session.setAudioOnly(true)
        session.setPlaybackVisible(false)
        advanceTimeBy(1000); runCurrent()
        assertTrue(player.loads.single().autoplay)
        assertTrue(session.state.value.audioOnly)
        player.state.value = player.state.value.copy(phase = PlayerPhase.Ended, wantsPlay = false)
        runCurrent()
        assertTrue(session.state.value.ended)
        session.advanceToNext()
        advanceTimeBy(1000); runCurrent()
        assertEquals("a2", session.state.value.info?.selected)
        assertTrue(session.state.value.audioOnly)
        assertTrue(player.loads.last().autoplay)
        session.close()
    }
    @Test fun switchingBackToVideoWhileHiddenPausesAndRejectsRemotePlay() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info() }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        session.setAudioOnly(true)
        session.setPlaybackVisible(false); runCurrent()
        assertTrue(player.state.value.wantsPlay)
        session.setAudioOnly(false); runCurrent()
        assertFalse(player.state.value.wantsPlay)
        player.play(); runCurrent()
        assertFalse(player.state.value.wantsPlay)
        session.setPlaybackVisible(true); runCurrent()
        assertFalse(player.state.value.wantsPlay)
        session.close()
    }
    @Test fun hiddenAudioRecoversSourceAndUnsupportedHostsCannotEnableIt() = runTest {
        val unsupported = FakePlayer(audioSupported = false)
        val unsupportedSession = PlaybackSession({ info() }, unsupported, backgroundScope)
        unsupportedSession.setAudioOnly(true)
        assertFalse(unsupportedSession.state.value.audioOnly)
        assertTrue(unsupported.audioModes.isEmpty())
        unsupportedSession.close()
        val player = FakePlayer()
        val requests = mutableListOf<PlayerRequest>()
        val session = PlaybackSession({
            requests += it
            info().copy(url = if (it.rejectedMedia.isEmpty()) info().url else "https://media/backup.mp4")
        }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        session.setAudioOnly(true)
        session.setPlaybackVisible(false)
        player.state.value = player.state.value.copy(phase = PlayerPhase.Error, wantsPlay = true,
            position = 35_000, duration = 120_000)
        runCurrent()
        assertTrue(requests.any { it.rejectedMedia.isNotEmpty() })
        assertTrue(session.state.value.audioOnly)
        assertTrue(player.loads.last().autoplay)
        session.close()
    }
    @Test fun nativeFailureSwitchesSameEpisodeAndPreservesPositionAndPause() = runTest {
        val player = FakePlayer()
        val requests = mutableListOf<PlayerRequest>()
        val session = PlaybackSession({
            requests += it
            if (it.rejectedMedia.isEmpty()) info() else info().copy(url = "https://media/backup.mp4")
        }, player, backgroundScope, resumePosition = { 12_000 })
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        player.pause()
        runCurrent()
        player.state.value = player.state.value.copy(phase = PlayerPhase.Error,
            position = 35_000, duration = 120_000, wantsPlay = false)
        runCurrent()
        val recovery = requests.single { it.rejectedMedia.isNotEmpty() }
        assertEquals("a1", recovery.episode)
        assertEquals(setOf(info().url), recovery.rejectedMedia)
        assertFalse(player.loads.last().autoplay)
        player.state.value = player.state.value.copy(duration = 120_000)
        runCurrent()
        assertEquals(35_000L, player.seeks.last())
        session.close()
    }
    @Test fun nativeRecoveryIsBoundedAndManualRetryClearsRejectedUrls() = runTest {
        val player = FakePlayer()
        val requests = mutableListOf<PlayerRequest>()
        val session = PlaybackSession({
            requests += it
            info().copy(url = "https://media/${it.rejectedMedia.size}.mp4")
        }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        repeat(3) {
            player.state.value = player.state.value.copy(phase = PlayerPhase.Error, wantsPlay = true)
            runCurrent()
        }
        assertEquals(3, player.loads.size)
        assertNotNull(session.state.value.error)
        session.retry(); runCurrent()
        assertTrue(requests.last().rejectedMedia.isEmpty())
        assertEquals(4, player.loads.size)
        session.close()
    }
    @Test fun nativeErrorClearingPlayIntentStillResumesActivePlayback() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({
            info().copy(url = if (it.rejectedMedia.isEmpty()) info().url else "https://media/backup.mp4")
        }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        player.state.value = player.state.value.copy(phase = PlayerPhase.Error, wantsPlay = false)
        runCurrent()
        assertTrue(player.loads.last().autoplay)
        session.close()
    }
    @Test fun hiddenAndStaleNativeErrorsNeverResolveAnotherSource() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info() }, player, backgroundScope)
        session.load(PlayerRequest()); runCurrent()
        val oldId = player.state.value.mediaId
        session.load(PlayerRequest()); runCurrent()
        player.state.value = player.state.value.copy(mediaId = oldId, phase = PlayerPhase.Error)
        runCurrent()
        assertEquals(2, player.loads.size)
        session.setPlaybackVisible(false)
        player.state.value = player.state.value.copy(mediaId = session.state.value.mediaId)
        runCurrent()
        assertEquals(2, player.loads.size)
        session.close()
    }
    @Test fun systemArtworkFollowsResolvedSourceAcrossEpisodeLoads() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, player, backgroundScope,
            artworkUrl = { "https://image/${it.selected}.jpg" })
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        assertEquals("https://image/a1.jpg", player.loads.single().artworkUrl)
        session.select(episodes.first { it.path == "a2" })
        runCurrent()
        assertEquals("https://image/a2.jpg", player.loads.last().artworkUrl)
        session.close()
    }
    @Test fun missingSystemArtworkDoesNotPreventPlayback() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info() }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        assertNull(player.loads.single().artworkUrl)
        assertTrue(player.state.value.wantsPlay)
        session.close()
    }
    @Test fun hidingDuringResolutionDisablesAutoplayAndShowingDoesNotResume() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ delay(1000); info() }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        session.setPlaybackVisible(false)
        advanceTimeBy(1000); runCurrent()
        assertFalse(player.loads.single().autoplay)
        assertFalse(player.state.value.wantsPlay)
        session.setPlaybackVisible(true)
        runCurrent()
        assertFalse(player.state.value.wantsPlay)
        session.close()
    }
    @Test fun hidingCancelsCountdownAndRejectsHiddenRemotePlay() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info() }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        player.state.value = player.state.value.copy(phase = PlayerPhase.Ended, wantsPlay = false)
        runCurrent()
        assertTrue(session.state.value.ended)
        session.setPlaybackVisible(false)
        player.play()
        runCurrent()
        assertFalse(player.state.value.wantsPlay)
        advanceTimeBy(6000); runCurrent()
        assertEquals(1, player.loads.size)
        assertFalse(session.state.value.ended)
        session.close()
    }
    @Test fun hidingBeforeEndNeverStartsAutoAdvance() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info() }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        session.setPlaybackVisible(false)
        player.state.value = player.state.value.copy(phase = PlayerPhase.Ended, wantsPlay = false)
        runCurrent()
        advanceTimeBy(6000); runCurrent()
        assertFalse(session.state.value.ended)
        assertEquals(1, player.loads.size)
        session.close()
    }
    @Test fun sessionPreservesSelectedRateAcrossEpisodes() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, player, backgroundScope, 1.5f)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        assertEquals(1.5f, player.rates.last())
        session.setPlaybackRate(1.25f)
        session.select(episodes[2])
        runCurrent()
        assertEquals(1.25f, player.rates.last())
        session.close()
    }
    @Test fun progressAndResumeWorkWithoutAPlayerScreen() = runTest {
        val player = FakePlayer()
        val saves = mutableListOf<Pair<String, Long>>()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, player, backgroundScope,
            checkpoint = { info, state -> saves += info.selected to state.position },
            resumePosition = { 12_000 })
        session.load(PlayerRequest())
        runCurrent()
        player.state.value = player.state.value.copy(duration = 120_000, position = 15_000)
        runCurrent()
        assertEquals(listOf(12_000L), player.seeks)
        advanceTimeBy(5_000); runCurrent()
        assertEquals("a1" to 15_000L, saves.last())
        session.select(episodes[2])
        runCurrent()
        assertEquals(2, saves.size)
        assertTrue(saves.all { it.first == "a1" })
        session.close()
    }
    @Test fun countdownAdvancesWithoutUiAndCanBeCancelled() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, player, backgroundScope)
        session.load(PlayerRequest())
        runCurrent()
        player.state.value = player.state.value.copy(phase = PlayerPhase.Ended)
        runCurrent()
        assertEquals(5, session.state.value.countdownSeconds)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(3, session.state.value.countdownSeconds)
        session.dismissEnded()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, player.loads.size)
        session.retry(); runCurrent()
        player.state.value = player.state.value.copy(phase = PlayerPhase.Ended)
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals("a2", session.state.value.info?.selected)
        assertEquals(3, player.loads.size)
        session.close()
    }
    @Test fun verticalDragLevelsClampToRange() {
        assertEquals(0.7f, adjustedLevel(0.8f, 0.1f))
        assertEquals(0f, adjustedLevel(0.2f, 0.5f))
        assertEquals(1f, adjustedLevel(0.8f, -0.5f))
        assertEquals(0.5f, adjustedLevel(0.5f, 0f))
    }
    @Test fun adjustedLevelKeepsNeutralDragAndClampsExtremes() {
        assertEquals(0f, adjustedLevel(0f, 0f))
        assertEquals(1f, adjustedLevel(1f, 0f))
        assertEquals(0f, adjustedLevel(0.1f, 0.9f))
        assertEquals(1f, adjustedLevel(0.9f, -0.9f))
        // A drag larger than the gesture area must never overflow the 0f..1f range.
        assertEquals(0f, adjustedLevel(0.5f, 5f))
        assertEquals(1f, adjustedLevel(0.5f, -5f))
    }
    @Test fun doubleTapZonesSplitFrameIntoThirds() {
        assertEquals(TapZone.Left, tapZone(0f, 900))
        assertEquals(TapZone.Left, tapZone(299f, 900))
        assertEquals(TapZone.Center, tapZone(300f, 900))
        assertEquals(TapZone.Center, tapZone(599f, 900))
        assertEquals(TapZone.Right, tapZone(600f, 900))
        assertEquals(TapZone.Right, tapZone(899f, 900))
    }
    @Test fun fullscreenDoubleTapTogglesPlaybackAtEveryHorizontalPosition() {
        for (width in listOf(0, 390, 844, 1024)) {
            for (fraction in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
                assertEquals(TapZone.Center, tapZone(width * fraction, width, fullscreen = true))
            }
        }
    }
    @Test fun quickSeekMovesTenSecondsAndClamps() {
        assertEquals(30_000L, quickSeekPosition(20_000L, 120_000L, forward = true))
        assertEquals(10_000L, quickSeekPosition(20_000L, 120_000L, forward = false))
        assertEquals(0L, quickSeekPosition(5_000L, 120_000L, forward = false))
        assertEquals(120_000L, quickSeekPosition(115_000L, 120_000L, forward = true))
    }
    @Test fun horizontalDragSeekScalesWithFrameWidthAndClamps() {
        val duration = 100_000L
        val eps = 0.01f
        // A full-width rightward drag from the start advances by the whole duration.
        assertEquals(100_000f, dragSeekPosition(0L, null, duration, 1080f, 1080), eps)
        // A half-width drag moves half the duration.
        assertEquals(50_000f, dragSeekPosition(0L, null, duration, 540f, 1080), eps)
        // A leftward drag rewinds from a mid-video position.
        assertEquals(40_000f, dragSeekPosition(50_000L, null, duration, -108f, 1080), eps)
        // Rewind can never move below zero.
        assertEquals(0f, dragSeekPosition(5_000L, null, duration, -1080f, 1080), eps)
        // Forward can never exceed the duration.
        assertEquals(100_000f, dragSeekPosition(90_000L, null, duration, 1080f, 1080), eps)
        // Successive deltas accumulate from the live preview instead of restarting at base.
        val first = dragSeekPosition(20_000L, null, duration, 108f, 1080)
        assertEquals(30_000f, first, eps)
        assertEquals(40_000f, dragSeekPosition(20_000L, first, duration, 108f, 1080), eps)
        // A degenerate frame width must not divide by zero.
        assertEquals(100_000f, dragSeekPosition(0L, null, duration, 1f, 0), eps)
    }
    @Test fun duplicateAndStaleEndEventsDoNotSkipEpisodes() = runTest {
        val player = FakePlayer()
        val requests = mutableListOf<PlayerRequest>()
        val session = PlaybackSession({ requests += it; info(it.episode ?: "a1") }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        val first = player.loads.single().id
        player.state.value = PlayerState(first, PlayerPhase.Ended)
        runCurrent()
        assertEquals("a2", requests.last().episode)
        assertEquals(2, requests.size)
        player.state.value = PlayerState(first, PlayerPhase.Ended)
        runCurrent()
        assertEquals(2, requests.size)
        session.close()
    }
    @Test fun oldResolutionCannotReplaceNewSelection() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ request ->
            if (request.episode == "a1") delay(1000)
            info(request.episode ?: "a1")
        }, player, backgroundScope)
        session.load(PlayerRequest(episode = "a1"))
        runCurrent()
        session.load(PlayerRequest(episode = "a2"))
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        assertEquals("a2", session.state.value.info?.selected)
        assertEquals(1, player.loads.size)
        session.close()
    }
    @Test fun closeIsIdempotentAndCancelsResolution() = runTest {
        val player = FakePlayer()
        val session = PlaybackSession({ delay(1000); info() }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        session.close(); session.close()
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, player.closes)
        assertTrue(player.loads.isEmpty())
        session.load(PlayerRequest())
        runCurrent()
        assertTrue(player.loads.isEmpty())
    }
    @Test fun nextEpisodeIsPreloadedAndReusedWithoutStartingPlayback() = runTest {
        val player = FakePlayer()
        val requests = mutableListOf<PlayerRequest>()
        val session = PlaybackSession({ requests += it; info(it.episode ?: "a1") }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        assertEquals(listOf(null, "a2"), requests.map { it.episode })
        assertEquals(1, player.loads.size)
        session.select(episodes.first { it.path == "a2" })
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals(2, player.loads.size)
        assertEquals("a2", session.state.value.info?.selected)
        session.close()
    }
    @Test fun failedPreloadIsRetriedWhenSelected() = runTest {
        val player = FakePlayer()
        var nextAttempts = 0
        val session = PlaybackSession({
            if (it.episode == "a2" && ++nextAttempts == 1) error("offline")
            info(it.episode ?: "a1")
        }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        assertNull(session.state.value.error)
        assertEquals(1, player.loads.size)
        session.select(episodes.first { it.path == "a2" })
        runCurrent()
        assertEquals(2, nextAttempts)
        assertEquals("a2", session.state.value.info?.selected)
        session.close()
    }
    @Test fun refreshKeepsSelectedEpisodeAndPausedPlaybackPosition() = runTest {
        val player = FakePlayer()
        var refresh = false
        val requests = mutableListOf<PlayerRequest>()
        val session = PlaybackSession({
            requests += it
            if (refresh) delay(1000)
            info(it.episode ?: "a1")
        }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page", episode = "a2"))
        runCurrent()
        player.state.value = player.state.value.copy(position = 42_000, wantsPlay = false, phase = PlayerPhase.Paused)
        val before = player.state.value
        refresh = true
        session.refresh(); session.refresh()
        runCurrent()
        assertTrue(session.state.value.refreshing)
        assertEquals(2, requests.size)
        assertEquals("a2", requests.last().episode)
        advanceTimeBy(1000); runCurrent()
        assertFalse(session.state.value.refreshing)
        assertEquals(before, player.state.value)
        assertEquals(1, player.loads.size)
        session.close()
    }
    @Test fun closeCancelsPreloadEvenWhenSelectionIsWaitingForIt() = runTest {
        val player = FakePlayer()
        var cancelled = false
        val session = PlaybackSession({
            if (it.episode == "a2") {
                try { delay(5000) } finally { cancelled = true }
            }
            info(it.episode ?: "a1")
        }, player, backgroundScope)
        session.load(PlayerRequest(page = "https://page"))
        runCurrent()
        session.select(episodes.first { it.path == "a2" })
        runCurrent()
        session.close()
        runCurrent()
        assertTrue(cancelled)
        advanceTimeBy(6000); runCurrent()
        assertEquals(1, player.loads.size)
    }
}
