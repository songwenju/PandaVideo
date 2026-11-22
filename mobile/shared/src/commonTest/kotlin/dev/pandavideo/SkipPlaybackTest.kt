package dev.pandavideo

import dev.pandavideo.core.model.*
import dev.pandavideo.core.player.*
import dev.pandavideo.data.user.SkipDurationsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SkipPlaybackTest {
    private val episodes = listOf(Episode("a1", "1", 1), Episode("a2", "2", 1),
        Episode("b1", "1", 2), Episode("b2", "2", 2))
    private fun info(selected: String = "a1") =
        PlaybackInfo("测试剧", "https://media.test/$selected", "video", episodes, selected, "https://page.test/series")
    private class Host(seeking: Boolean = true, initial: Boolean = false, nativeSkip: Boolean = false) : PlayerController {
        override val capabilities = PlayerCapabilities(seeking = seeking, initialPosition = initial,
            initialSkipping = nativeSkip, audioOnly = true)
        override val state = MutableStateFlow(PlayerState())
        val loads = mutableListOf<MediaSource>()
        val seeks = mutableListOf<Long>()
        override fun load(source: MediaSource) {
            loads += source
            state.value = PlayerState(source.id, if (source.autoplay) PlayerPhase.Playing else PlayerPhase.Paused,
                position = if (capabilities.initialPosition) source.startPosition else 0,
                wantsPlay = source.autoplay, firstFrameRendered = true)
        }
        fun tick(position: Long, duration: Long = 120_000) {
            val target = if (capabilities.initialSkipping && state.value.duration == 0L && duration > 0)
                loads.last().startupPosition(duration) else position
            state.value = state.value.copy(position = target, duration = duration)
        }
        override fun seekTo(position: Long) { seeks += position; tick(position, state.value.duration) }
        override fun play() { state.value = state.value.copy(phase = PlayerPhase.Playing, wantsPlay = true) }
        override fun pause() { state.value = state.value.copy(phase = PlayerPhase.Paused, wantsPlay = false) }
        override fun stop() { state.value = state.value.copy(phase = PlayerPhase.Idle) }
        override fun setPlaybackRate(rate: Float) {}
        override fun close() {}
    }

    @Test fun seriesSettingsPersistCanonicalIdentityAndRejectInvalidInput() {
        val storage = FakeStorage()
        val store = SkipDurationsStore(storage)
        val page = "https://pomo.mom/content/plugins/plyr_player/api.php?type=auto_detail&site=test&id=1"
        assertEquals(SkipDurations(90, 90), store.get(page))
        assertTrue(store.save("$page#1-0", SkipDurations(60, 90)))
        assertEquals(SkipDurations(60, 90), SkipDurationsStore(storage).get("$page#1-1"))
        assertEquals(SkipDurations(90, 90), store.get("https://other.test/v/1"))
        assertFalse(store.save(page, SkipDurations(-1, 301)))
        assertEquals(SkipDurations(60, 90), store.get(page))
        assertTrue(store.save(page, SkipDurations()))
        assertEquals(SkipDurations(), SkipDurationsStore(storage).get(page))
    }

    @Test fun enableSwitchFillsMissingEdgesWithRegionalFallback() {
        val storage = FakeStorage()
        val store = SkipDurationsStore(storage)
        val page = "https://page.test/series"
        // US series use the short 30-second reference on both edges.
        val us = SkipDurations.defaultSecondsFor("美国")
        assertEquals(SkipDurations.UsSeriesSeconds, us)
        assertTrue(store.setEnabled(page, true, us))
        assertEquals(SkipDurations(30, 30), SkipDurationsStore(storage).get(page))
        assertTrue(store.setEnabled(page, false))
        assertEquals(SkipDurations(), SkipDurationsStore(storage).get(page))
        // Every other region keeps the 90-second reference.
        assertEquals(SkipDurations.DefaultSeconds, SkipDurations.defaultSecondsFor("中国大陆"))
        assertTrue(store.setEnabled(page, true))
        assertEquals(SkipDurations(90, 90), SkipDurationsStore(storage).get(page))
        // An already configured edge is kept; only the missing one receives the fallback.
        assertTrue(store.save(page, SkipDurations(60, 0)))
        assertTrue(store.setEnabled(page, true))
        assertEquals(SkipDurations(60, 90), store.get(page))
        // Turning the switch off persists an explicit opt-out.
        assertTrue(store.setEnabled(page, false))
        assertEquals(SkipDurations(), SkipDurationsStore(storage).get(page))
    }

    @Test fun corruptSettingsUseSafeDefaults() {
        val storage = FakeStorage()
        for (data in listOf("broken", """{"https://page":{"introSeconds":999}}""")) {
            storage.putString("series_skip_durations", data)
            assertEquals(SkipDurations(90, 90), SkipDurationsStore(storage).get("https://page"))
        }
    }

    @Test fun skippingDefaultsOnByRegionAndExplicitOffSurvivesRestart() {
        val storage = FakeStorage()
        val store = SkipDurationsStore(storage)
        val page = "https://pomo.mom/content/plugins/plyr_player/api.php?type=auto_detail&site=test&id=1"
        assertEquals(SkipDurations(90, 90), store.get(page))
        assertEquals(SkipDurations(30, 30), store.get(page, SkipDurations.defaultSecondsFor("美国")))
        assertTrue(store.setEnabled(page, false))
        val restored = SkipDurationsStore(storage)
        assertEquals(SkipDurations(), restored.get(page, 30))
        assertEquals(SkipDurations(), restored.get("$page#episode"))
        assertEquals(SkipDurations(90, 90), restored.get("https://page.test/other-series"))
        assertTrue(restored.save(page, SkipDurations(60, 0)))
        assertEquals(SkipDurations(60, 0), SkipDurationsStore(storage).get(page, 30))
    }

    @Test fun defaultSkippingSeeksIntroAndAdvancesPastOutroWithoutOpeningSettings() = runTest {
        val store = SkipDurationsStore(FakeStorage())
        val host = Host()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, host, backgroundScope,
            skipDurations = { store.get(it.pageUrl) })
        session.load(PlayerRequest()); runCurrent()
        host.tick(0, 600_000); runCurrent()
        assertEquals(listOf(90_000L), host.seeks)
        host.tick(510_000, 600_000); runCurrent()
        assertEquals("a2", session.state.value.info?.selected)
        session.close()
    }

    @Test fun introWaitsForDurationAndPreservesLaterResumeOnBothHosts() = runTest {
        for (initial in listOf(false, true)) for (resume in listOf(0L, 5_000L, 45_000L)) {
            val host = Host(initial = initial)
            val session = PlaybackSession({ info() }, host, backgroundScope,
                resumePosition = { resume }, skipDurations = { SkipDurations(10, 20) })
            session.load(PlayerRequest()); runCurrent()
            assertTrue(host.seeks.isEmpty(), "Unknown duration must not cause an intro seek")
            host.tick(host.state.value.position); runCurrent()
            assertEquals(if (resume < 10_000) 10_000L else 45_000L, host.state.value.position)
            session.seekTo(0); runCurrent()
            assertEquals(0L, host.state.value.position)
            host.tick(1_000); runCurrent()
            assertEquals(1_000L, host.state.value.position)
            session.close()
        }
    }

    @Test fun readinessCombinesHistoryAndIntroIntoOneSeekBeforePlayback() = runTest {
        for (resume in listOf(0L, 5_000L, 45_000L)) {
            val host = Host()
            val session = PlaybackSession({ info() }, host, backgroundScope,
                resumePosition = { resume }, skipDurations = { SkipDurations(10, 20) })
            session.load(PlayerRequest()); runCurrent()
            host.state.value = host.state.value.copy(phase = PlayerPhase.Ready,
                firstFrameRendered = false, duration = 120_000)
            runCurrent()
            val target = maxOf(resume, 10_000L)
            assertEquals(listOf(target), host.seeks)
            host.play(); runCurrent()
            assertEquals(listOf(target), host.seeks, "First playback must not trigger a second seek")
            session.close()
        }
    }

    @Test fun nativeStartupPolicyIsPassedOnceAndNeverSeeksAfterFirstFrame() = runTest {
        for (resume in listOf(0L, 5_000L, 45_000L)) {
            val host = Host(initial = true, nativeSkip = true)
            val session = PlaybackSession({ info() }, host, backgroundScope,
                resumePosition = { resume }, skipDurations = { SkipDurations(10, 20) })
            session.load(PlayerRequest()); runCurrent()
            assertEquals(SkipDurations(10, 20), host.loads.last().startupSkip)
            assertEquals(resume, host.loads.last().startPosition)
            host.tick(0); runCurrent()
            assertEquals(maxOf(resume, 10_000L), host.state.value.position)
            assertTrue(host.seeks.isEmpty(), "Native positioning must not be repeated by the session")
            session.seekTo(0); runCurrent()
            host.tick(1_000); runCurrent()
            assertEquals(1_000L, host.state.value.position)
            session.close()
        }
    }

    @Test fun startupTargetPreservesShortLiveAndLaterHistoryTimelines() {
        val source = MediaSource(1, "", "", "", startPosition = 5_000, startupSkip = SkipDurations(10, 20))
        assertEquals(0L, source.startupPosition(0))
        assertEquals(5_000L, source.startupPosition(30_000))
        assertEquals(10_000L, source.startupPosition(120_000))
        assertEquals(45_000L, source.copy(startPosition = 45_000).startupPosition(120_000))
        assertEquals(119_000L, source.copy(startPosition = 200_000).startupPosition(120_000))
        assertEquals(5_000L, source.copy(startupSkip = SkipDurations()).startupPosition(120_000))
    }

    @Test fun invalidTimelineUnseekableAndMoviesDoNotSkip() = runTest {
        for (duration in listOf(0L, 20_000L, 30_000L)) {
            val host = Host()
            val session = PlaybackSession({ info() }, host, backgroundScope, skipDurations = { SkipDurations(10, 20) })
            session.load(PlayerRequest()); runCurrent()
            host.tick(0, duration); runCurrent()
            assertTrue(host.seeks.isEmpty())
            assertFalse(session.state.value.ended)
            session.close()
        }
        for (movie in listOf(false, true)) {
            val host = Host(seeking = movie)
            val session = PlaybackSession({ if (movie) info().copy(episodes = listOf(episodes[0], episodes[2])) else info() },
                host, backgroundScope, skipDurations = { SkipDurations(10, 20) })
            session.load(PlayerRequest()); runCurrent()
            host.tick(110_000); runCurrent()
            assertTrue(host.seeks.isEmpty())
            assertFalse(session.state.value.ended)
            session.close()
        }
    }

    @Test fun outroImmediatelyAdvancesInSameLineAndNextLoadReadsNewSettings() = runTest {
        var config = SkipDurations(10, 20)
        val host = Host()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, host, backgroundScope, skipDurations = { config })
        session.load(PlayerRequest()); runCurrent()
        host.tick(0); runCurrent()
        config = SkipDurations(15, 25)
        host.tick(99_000); runCurrent()
        assertFalse(session.state.value.ended, "Editing must not change the current threshold")
        host.tick(100_000); runCurrent()
        assertEquals(2, host.loads.size)
        assertEquals("a2", session.state.value.info?.selected)
        assertTrue(host.state.value.wantsPlay)
        assertFalse(session.state.value.ended)
        host.tick(0); runCurrent()
        assertEquals(15_000L, host.state.value.position)
        session.close()
    }

@Test fun pauseHiddenVideoAndAudioIntentAreRespected() = runTest {
        val host = Host()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, host, backgroundScope, skipDurations = { SkipDurations(0, 20) })
        session.load(PlayerRequest()); runCurrent()
        host.pause(); host.tick(105_000); runCurrent()
        assertFalse(session.state.value.ended)
        session.setPlaybackVisible(false); host.play(); runCurrent()
        assertFalse(session.state.value.ended)
        assertFalse(host.state.value.wantsPlay)
        session.setAudioOnly(true); runCurrent()
        assertFalse(host.state.value.wantsPlay)
        host.play(); runCurrent()
        assertEquals("a2", session.state.value.info?.selected)
        assertEquals(2, host.loads.size)
        session.close()
    }

    @Test fun manualCreditsReplayAndLineSwitchKeepExemption() = runTest {
        val host = Host()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, host, backgroundScope, skipDurations = { SkipDurations(10, 20) })
        session.load(PlayerRequest()); runCurrent()
        host.tick(0); runCurrent()
        host.pause(); host.tick(110_000); runCurrent()
        session.seekTo(105_000); host.play(); runCurrent()
        host.tick(106_000); runCurrent()
        assertFalse(session.state.value.ended)
        session.selectLine(2); runCurrent()
        host.tick(0); runCurrent()
        host.tick(108_000); runCurrent()
        assertFalse(session.state.value.ended)
        session.seekTo(0); runCurrent()
        assertEquals(0L, host.state.value.position)
        session.close()
    }

    @Test fun forwardSeekIntoCreditsAdvancesButPausedSeekWaitsForPlay() = runTest {
        for (paused in listOf(false, true)) {
            val host = Host()
            val session = PlaybackSession({ info(it.episode ?: "a1") }, host, backgroundScope,
                skipDurations = { SkipDurations(10, 20) })
            session.load(PlayerRequest()); runCurrent()
            host.tick(0); runCurrent()
            if (paused) host.pause()
            session.seekTo(105_000); runCurrent()
            if (paused) {
                assertEquals("a1", session.state.value.info?.selected)
                host.play(); runCurrent()
            }
            assertEquals("a2", session.state.value.info?.selected)
            assertEquals(2, host.loads.size)
            assertTrue(host.state.value.wantsPlay)
            session.close()
        }
    }

    @Test fun seekingBeforeDurationArrivesDoesNotDisableLaterOutro() = runTest {
        val host = Host()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, host, backgroundScope,
            skipDurations = { SkipDurations(10, 20) })
        session.load(PlayerRequest()); runCurrent()
        session.seekTo(45_000); runCurrent()
        host.tick(45_000); runCurrent()
        host.tick(100_000); runCurrent()
        assertEquals("a2", session.state.value.info?.selected)
        session.close()
    }

    @Test fun sleepAtCreditsIsOneShotAndNeverLoadsNext() = runTest {
        val host = Host()
        var pauses = 0
        val session = PlaybackSession({ info(it.episode ?: "a1") }, host, backgroundScope,
            onPauseAtEnd = { pauses++ }, skipDurations = { SkipDurations(0, 20) })
        session.load(PlayerRequest()); runCurrent()
        session.setPauseAtEnd(true)
        host.tick(100_000); runCurrent()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, pauses)
        assertEquals(1, host.loads.size)
        assertFalse(session.state.value.ended)
        assertFalse(host.state.value.wantsPlay)
        host.play(); host.tick(110_000); runCurrent()
        assertEquals(1, pauses)
        session.close()
    }

    @Test fun finalEpisodeStopsAtEndAndCanReplayCredits() = runTest {
        val host = Host()
        val session = PlaybackSession({ info("a2") }, host, backgroundScope, skipDurations = { SkipDurations(0, 20) })
        session.load(PlayerRequest()); runCurrent()
        host.tick(100_000); runCurrent()
        assertTrue(session.state.value.finished)
        assertFalse(session.state.value.ended)
        assertEquals(120_000L, host.state.value.position)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, host.loads.size)
        session.seekTo(105_000); host.play(); runCurrent()
        assertFalse(session.state.value.finished)
        assertFalse(session.state.value.ended)
        session.close()
    }

    @Test fun recoveryDoesNotRepeatIntroOrUndoViewerReplay() = runTest {
        val host = Host()
        val session = PlaybackSession({ info().copy(url = "https://media.test/${it.rejectedMedia.size}") },
            host, backgroundScope, skipDurations = { SkipDurations(10, 20) })
        session.load(PlayerRequest()); runCurrent()
        host.tick(0); runCurrent()
        session.seekTo(2_000); runCurrent()
        host.state.value = host.state.value.copy(phase = PlayerPhase.Error); runCurrent()
        host.tick(0); runCurrent()
        assertEquals(2_000L, host.state.value.position)
        assertEquals(1, host.seeks.count { it == 10_000L })
        session.close()
    }

    @Test fun syntheticEndCheckpointsBeforeImmediateAdvanceAndMinuteTimerPausesNext() = runTest {
        val host = Host()
        val checkpoints = mutableListOf<PlayerState>()
        val session = PlaybackSession({ info(it.episode ?: "a1") }, host, backgroundScope,
            checkpoint = { _, state -> checkpoints += state },
            skipDurations = { SkipDurations(0, 20) })
        session.load(PlayerRequest()); runCurrent()
        host.tick(100_000); runCurrent()
        assertEquals("a2", session.state.value.info?.selected)
        assertEquals(PlayerPhase.Ended, checkpoints.last().phase)
        assertEquals(120_000L, checkpoints.last().position)
        host.tick(0); runCurrent()
        session.pauseForSleepTimer()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(2, host.loads.size)
        assertFalse(session.state.value.ended)
        session.seekTo(70_000); runCurrent()
        session.close()
        assertEquals(70_000L, checkpoints.last().position)
    }
}
