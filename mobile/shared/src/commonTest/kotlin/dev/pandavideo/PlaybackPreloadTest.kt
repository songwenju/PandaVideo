package dev.pandavideo

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPreloadTest {
    private val episodes = listOf(Episode("one", "1", 1), Episode("two", "2", 1))
    private fun info(episode: String = "one") = PlaybackInfo("fixture", "https://fixture/$episode.mp4",
        "video", episodes, episode, "https://page")
    private class Controller : PlayerController {
        override val state = MutableStateFlow(PlayerState())
        override val capabilities = PlayerCapabilities(initialPosition = true)
        val candidates = mutableListOf<MediaSource?>()
        val loads = mutableListOf<MediaSource>()
        override fun preload(source: MediaSource?) { candidates += source }
        override fun load(source: MediaSource) {
            loads += source
            state.value = PlayerState(source.id, PlayerPhase.Playing, wantsPlay = source.autoplay,
                firstFrameRendered = true)
        }
        override fun play() {}
        override fun pause() { state.value = state.value.copy(phase = PlayerPhase.Paused, wantsPlay = false) }
        override fun seekTo(position: Long) {}
        override fun setPlaybackRate(rate: Float) {}
        override fun stop() { state.value = state.value.copy(phase = PlayerPhase.Idle) }
        override fun close() {}
    }

    @Test fun resolvedNextEpisodePreparesDataWithoutAutoplayAndCancelsWhenHidden() = runTest {
        val controller = Controller()
        val session = PlaybackSession({ info(it.episode ?: "one") }, controller, backgroundScope)
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        val candidate = assertNotNull(controller.candidates.last())
        assertEquals("https://fixture/two.mp4", candidate.url)
        assertFalse(candidate.autoplay)
        assertEquals(1, controller.loads.size)
        session.setPlaybackVisible(false); runCurrent()
        assertNull(controller.candidates.last())
        session.close()
    }

    @Test fun addressSurvivesMoreThanOneMinuteAndIsConsumedWithoutReresolution() = runTest {
        var count = 0
        val controller = Controller()
        val session = PlaybackSession({ count++; info(it.episode ?: "one") }, controller,
            backgroundScope, nowMs = { testScheduler.currentTime })
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        advanceTimeBy(90_000)
        session.select(episodes[1]); runCurrent()
        assertEquals(2, count)
        assertTrue(session.startupSamples.value.last().addressPreloadHit)
        session.close()
    }

    @Test fun preparedDataIsConsumedImmediatelyAtEndAndCloseCancelsPreload() = runTest {
        val controller = Controller()
        val session = PlaybackSession({ info(it.episode ?: "one") }, controller, backgroundScope)
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        controller.state.value = controller.state.value.copy(phase = PlayerPhase.Ended, wantsPlay = false)
        runCurrent()
        assertEquals("two", session.state.value.info?.selected)
        assertEquals(2, controller.loads.size)
        assertTrue(session.startupSamples.value.last().addressPreloadHit)
        assertFalse(session.state.value.ended)
        session.close()
        assertNull(controller.candidates.last())
    }

    @Test fun nearEndRefreshesOldAddressAndPreloadsNewData() = runTest {
        var nextCount = 0
        val requests = mutableListOf<PlayerRequest>()
        val controller = Controller()
        val session = PlaybackSession({
            requests += it
            if (it.episode == "two") nextCount++
            info(it.episode ?: "one").let { result ->
                if (it.episode == "two") result.copy(url = "https://fixture/two-$nextCount.mp4") else result
            }
        }, controller, backgroundScope, nowMs = { testScheduler.currentTime })
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        assertTrue(requests.last().reuseSourceInfo)
        assertFalse(requests.last().preferFastestLine)
        advanceTimeBy(80_000)
        controller.state.value = controller.state.value.copy(duration = 120_000, position = 80_000)
        runCurrent()
        assertEquals(2, nextCount)
        assertFalse(requests.last().reuseSourceInfo, "Near-end refresh must bypass the media cache")
        assertEquals("https://fixture/two-2.mp4", controller.candidates.last()?.url)
        session.select(episodes[1]); runCurrent()
        assertEquals("https://fixture/two-2.mp4", controller.loads.last().url)
        assertEquals(2, nextCount, "Freshly prepared URL must still match the episode navigation request")
        session.close()
    }

    @Test fun expiredAddressFallsBackToResolutionAndInitialPositionPrecedesBuffering() = runTest {
        var nextCount = 0
        val controller = Controller()
        val session = PlaybackSession({
            if (it.episode == "two") nextCount++
            info(it.episode ?: "one")
        }, controller, backgroundScope, resumePosition = { 42_000 },
            nowMs = { testScheduler.currentTime })
        session.load(PlayerRequest(page = "https://page")); runCurrent()
        assertEquals(42_000L, controller.loads.first().startPosition)
        advanceTimeBy(310_000)
        session.select(episodes[1]); runCurrent()
        assertEquals(2, nextCount)
        assertFalse(session.startupSamples.value.last().addressPreloadHit)
        session.close()
    }

    @Test fun telemetrySeparatesReadyFromRenderedFrameAndRejectsStaleEvents() = runTest {
        val telemetry = PlaybackTelemetry { testScheduler.currentTime }
        telemetry.begin(1, "episode")
        advanceTimeBy(100); telemetry.resolved("hls", true)
        advanceTimeBy(200); telemetry.observe(PlayerState(1, PlayerPhase.Ready), false)
        assertEquals(300L, telemetry.samples.value.single().readyMs)
        assertNull(telemetry.samples.value.single().firstFrameMs)
        advanceTimeBy(100)
        telemetry.observe(PlayerState(0, PlayerPhase.Playing, position = 1, firstFrameRendered = true), false)
        assertNull(telemetry.samples.value.single().firstFrameMs)
        telemetry.observe(PlayerState(1, PlayerPhase.Playing, position = 1, wantsPlay = true,
            firstFrameRendered = true, dataPreloadHit = true), false)
        val sample = telemetry.samples.value.single()
        assertEquals(400L, sample.firstFrameMs)
        assertEquals(300L, sample.nativeStartupMs)
        assertTrue(sample.dataPreloadHit)
        assertEquals(StartupOutcome.Started, sample.outcome)
    }

    @Test fun failuresCancellationAndAudioDoNotBecomeSuccessfulVideoSamples() = runTest {
        val telemetry = PlaybackTelemetry { testScheduler.currentTime }
        telemetry.begin(1, "load"); telemetry.finish(StartupOutcome.Failed)
        telemetry.begin(2, "load")
        telemetry.observe(PlayerState(2, PlayerPhase.Playing, position = 5, firstFrameRendered = true), true)
        advanceTimeBy(5_000)
        telemetry.observe(PlayerState(2, PlayerPhase.Playing, position = 10, firstFrameRendered = true), false)
        assertNull(telemetry.samples.value.last().firstFrameMs)
        telemetry.begin(3, "load"); telemetry.begin(4, "load")
        assertEquals(listOf(StartupOutcome.Failed, StartupOutcome.AudioOnly, StartupOutcome.Cancelled,
            StartupOutcome.Pending), telemetry.samples.value.map { it.outcome })
        assertTrue(telemetry.samples.value.all { it.firstFrameMs == null })
        repeat(60) { telemetry.begin(it + 5L, "load") }
        assertEquals(50, telemetry.samples.value.size)
    }
}
