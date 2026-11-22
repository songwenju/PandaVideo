@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo

import dev.pandavideo.core.platform.IosPlayer
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.model.MediaSegment
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.*
import kotlin.test.*

/** Opt-in public decoder test with real segment transitions in the same AVQueuePlayer. */
class IosSegmentedPlaybackTest {
    @Test fun publicMp4ContinuousPlaybackAndBidirectionalSeekPreserveWholeProgramState() {
        if (NSProcessInfo.processInfo.environment["PANDAVIDEO_CCTV_SEGMENTS"] != "1") return
        verify(cctvSegmentFixture(), "public")
    }

    @Test fun queuedLocalAssetsPreserveTimelineSeekPauseAndFinalCompletion() {
        val path = NSProcessInfo.processInfo.environment["PANDAVIDEO_SEGMENT_FIXTURE"] as? String ?: return
        val address = NSURL.fileURLWithPath(path).absoluteString!!
        verify(MediaSource(100, address, "video", "Controlled segmented fixture", startPosition = 118_500,
            segments = List(3) { MediaSegment(address, 120_000) }), "controlled")
    }

    @Test fun singleAssetEndRetainsItemForBackwardSeek() {
        val path = NSProcessInfo.processInfo.environment["PANDAVIDEO_SEGMENT_FIXTURE"] as? String ?: return
        val player = IosPlayer()
        fun await(condition: () -> Boolean) {
            val deadline = NSDate.timeIntervalSinceReferenceDate + 15
            while (!condition() && NSDate.timeIntervalSinceReferenceDate < deadline)
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.025))
            assertTrue(condition(), "Single-asset replay did not reach the expected state: " +
                "phase=${player.state.value.phase} position=${player.state.value.position} " +
                "native_seconds=${CMTimeGetSeconds(player.native.currentTime())} " +
                "seekable_ranges=${player.native.currentItem?.seekableTimeRanges?.size}")
        }
        try {
            player.load(MediaSource(101, NSURL.fileURLWithPath(path).absoluteString!!, "video",
                "Single-asset fixture", startPosition = 118_500))
            await { player.state.value.phase == PlayerPhase.Ended }
            assertNotNull(player.native.currentItem)
            player.seekTo(5_000)
            await { player.state.value.phase == PlayerPhase.Paused && player.state.value.position in 4_750..5_250 }
            player.play()
            await { player.state.value.phase == PlayerPhase.Playing && player.state.value.position > 5_500 }
            println("CCTV_SEGMENTS ios single_asset_replay=true")
        } finally { player.close() }
    }

    private fun verify(source: MediaSource, mode: String) {
        val player = IosPlayer()
        fun await(condition: () -> Boolean) {
            val deadline = NSDate.timeIntervalSinceReferenceDate + 45
            while (!condition() && NSDate.timeIntervalSinceReferenceDate < deadline) {
                val error = player.native.currentItem?.error
                val underlying = error?.userInfo?.get(NSUnderlyingErrorKey) as? NSError
                assertNotEquals(PlayerPhase.Error, player.state.value.phase,
                    "phase=${player.state.value.phase} position=${player.state.value.position} " +
                        "native_domain=${error?.domain} native_code=${error?.code} " +
                        "underlying_domain=${underlying?.domain} underlying_code=${underlying?.code}")
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.025))
            }
            assertTrue(condition(), "Native segmented playback timed out: phase=${player.state.value.phase} " +
                "position=${player.state.value.position} duration=${player.state.value.duration} " +
                "native_seconds=${CMTimeGetSeconds(player.native.currentTime())} rate=${player.native.rate} " +
                "queued=${(player.native as AVQueuePlayer).items().size} item_status=${player.native.currentItem?.status}")
        }
        try {
            player.load(source)
            await { player.state.value.phase == PlayerPhase.Playing && player.state.value.position >= 120_500 }
            assertEquals(source.segments.sumOf { it.durationMs }, player.state.value.duration)
            println("CCTV_SEGMENTS ios mode=$mode natural_transition_ms=${player.state.value.position} duration_ms=${player.state.value.duration}")
            player.pause()
            player.setPlaybackRate(1.5f)
            player.seekTo(245_000)
            await { player.state.value.phase == PlayerPhase.Paused && player.state.value.position in 244_750..245_250 }
            assertFalse(player.state.value.wantsPlay)
            player.seekTo(115_000)
            await { player.state.value.phase == PlayerPhase.Paused && player.state.value.position in 114_750..115_250 }
            assertFalse(player.state.value.wantsPlay)
            assertEquals(1.5f, player.state.value.rate)
            println("CCTV_SEGMENTS ios mode=$mode forward_ms=245000 backward_ms=115000 paused=true rate=1.5")
            player.play()
            await { player.state.value.phase == PlayerPhase.Playing && player.state.value.position > 120_500 }
            player.seekTo(player.state.value.duration - 1_000)
            await { player.state.value.phase == PlayerPhase.Ended }
            println("CCTV_SEGMENTS ios mode=$mode final_segment_ended=true")
            player.seekTo(115_000)
            await { player.state.value.phase == PlayerPhase.Paused && player.state.value.position in 114_750..115_250 }
            assertFalse(player.state.value.wantsPlay)
            println("CCTV_SEGMENTS ios mode=$mode backward_after_end=true")
        } finally { player.close() }
    }
}
