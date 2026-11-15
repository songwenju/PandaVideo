@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo

import dev.pandavideo.core.platform.IosPlayer
import dev.pandavideo.core.player.*
import platform.Foundation.*
import kotlin.test.*

/** Opt-in native transport checks using a local 120-second MP4, without public network dependencies. */
class IosStartupPlaybackTest {
    private fun fixture(): String? =
        (NSProcessInfo.processInfo.environment["PANDAVIDEO_STARTUP_FIXTURE"] as? String)
            ?.let { NSURL.fileURLWithPath(it).absoluteString }

    private fun await(timeout: Double = 10.0, condition: () -> Boolean) {
        val deadline = NSDate.timeIntervalSinceReferenceDate + timeout
        while (!condition() && NSDate.timeIntervalSinceReferenceDate < deadline)
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.025))
        assertTrue(condition(), "Native player did not reach the expected state")
    }

    @Test fun initialResumeAndIntroCompleteBeforeNativePlayback() {
        val address = fixture() ?: return
        for (resume in listOf(0L, 5_000L, 45_000L)) {
            val player = IosPlayer()
            val target = maxOf(resume, 10_000L)
            try {
                player.load(MediaSource(1, address, "video", "Startup fixture",
                    startPosition = resume, startupSkip = SkipDurations(10, 20)))
                // Session speed is normally applied immediately after load; it must not
                // bypass the pending initial seek by starting AVPlayer at zero.
                player.setPlaybackRate(1f)
                await {
                    val state = player.state.value
                    if (state.phase == PlayerPhase.Playing)
                        assertTrue(state.position >= target - 250, "Playback started before initial positioning")
                    state.phase == PlayerPhase.Playing && state.position >= target
                }
                val first = player.state.value.position
                await { player.state.value.position >= first + 1_000 }
                assertEquals(PlayerPhase.Playing, player.state.value.phase)
                println("IOS_STARTUP resume_ms=$resume target_ms=$target progressed=true")
            } finally { player.close() }
        }
    }

    @Test fun pauseDuringInitialSeekIsPreservedAndReplacementDiscardsOldTarget() {
        val address = fixture() ?: return
        val player = IosPlayer()
        try {
            player.load(MediaSource(1, address, "video", "Paused fixture",
                startPosition = 45_000, startupSkip = SkipDurations(10, 20)))
            player.pause()
            await { player.state.value.duration > 0 && player.state.value.position >= 44_750 }
            assertFalse(player.state.value.wantsPlay)
            assertEquals(PlayerPhase.Paused, player.state.value.phase)
            player.load(MediaSource(2, address, "video", "Replacement fixture"))
            await { player.state.value.phase == PlayerPhase.Playing && player.state.value.position > 0 }
            assertEquals(2L, player.state.value.mediaId)
            assertTrue(player.state.value.position < 10_000)
            println("IOS_STARTUP paused_intent=true replacement_target_cleared=true")
        } finally { player.close() }
    }
}
