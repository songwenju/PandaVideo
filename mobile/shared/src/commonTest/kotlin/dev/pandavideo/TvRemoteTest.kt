package dev.pandavideo

import dev.pandavideo.core.player.tvSeekTarget
import dev.pandavideo.core.player.TvConfirmPress
import dev.pandavideo.core.player.TvSeekGesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvRemoteTest {
    @Test fun shortPressAndRepeatEventsCommitOnlyOnce() {
        val gesture = TvSeekGesture()
        gesture.press(0, 35_000, 90_000, true)
        gesture.press(300, 35_000, 90_000, true)
        gesture.tick(600)
        assertFalse(gesture.longPress)
        assertEquals(45_000, gesture.release(600))
        assertEquals(null, gesture.release(700))
    }

    @Test fun heldPreviewUsesElapsedTimeAndNeverKeyRepeatCount() {
        val gesture = TvSeekGesture()
        gesture.press(0, 35_000, 900_000, true)
        gesture.tick(601)
        assertTrue(gesture.longPress)
        assertEquals(45_000, gesture.target)
        gesture.tick(720)
        assertEquals(55_000, gesture.target)
        gesture.tick(960)
        assertEquals(75_000, gesture.target)
        assertEquals(85_000, gesture.release(1080))
    }

    @Test fun dismissKeepsAccumulatingUntilReleaseAndNextGestureShowsAgain() {
        val gesture = TvSeekGesture()
        gesture.press(0, 35_000, 900_000, true)
        gesture.dismiss()
        gesture.tick(840)
        assertTrue(gesture.suppressed)
        assertEquals(65_000, gesture.release(840))
        gesture.press(900, 65_000, 900_000, false)
        assertFalse(gesture.suppressed)
        assertEquals(55_000, gesture.release(950))
    }

    @Test fun cancellationAndUnknownDurationCannotCommitAnOldMediaSeek() {
        val gesture = TvSeekGesture()
        gesture.press(0, 35_000, 90_000, true)
        gesture.cancel()
        assertEquals(null, gesture.release(960))
        gesture.press(1000, 35_000, 0, true)
        assertEquals(null, gesture.release(2000))
    }

    @Test fun holdReversesFromPreviewAndClampsVeryLongPresses() {
        val gesture = TvSeekGesture()
        gesture.press(0, 85_000, 90_000, true)
        gesture.tick(720)
        assertEquals(90_000, gesture.target)
        gesture.press(720, 85_000, 90_000, false)
        assertEquals(80_000, gesture.target)
        assertEquals(70_000, gesture.release(840))
        gesture.press(1000, 70_000, 90_000, false)
        assertEquals(0, gesture.release(Long.MAX_VALUE))
    }
    @Test fun revealingControlsConsumesRepeatsAndReleaseWithoutPausingPlayback() {
        for (initialWantsPlay in listOf(true, false)) {
            val press = TvConfirmPress()
            var controls = false
            var wantsPlay = initialWantsPlay
            var reveals = 0
            fun dispatch(down: Boolean, repeat: Int = 0) {
                val consumed = press.handle(23, down, repeat, controls) { controls = true; reveals++ }
                // TV Material invokes the newly focused button on key-up, even if
                // that button did not receive key-down before the focus transition.
                if (!consumed && !down && controls) wantsPlay = !wantsPlay
            }
            dispatch(true)
            dispatch(true, 1)
            dispatch(true, 2)
            dispatch(false)
            assertEquals(1, reveals)
            assertEquals(initialWantsPlay, wantsPlay)
            dispatch(true)
            assertEquals(initialWantsPlay, wantsPlay)
            dispatch(false)
            assertEquals(!initialWantsPlay, wantsPlay)
        }
    }

    @Test fun unrelatedConfirmationReleaseDoesNotUnlockPress() {
        val press = TvConfirmPress()
        var controls = false
        assertTrue(press.handle(66, true, 0, controls) { controls = true })
        assertFalse(press.handle(23, false, 0, controls) { error("Unexpected reveal") })
        assertTrue(press.handle(66, false, 0, controls) { error("Unexpected reveal") })
        assertFalse(press.handle(66, true, 0, controls) { error("Unexpected reveal") })
    }

    @Test fun orphanedConfirmationRepeatDoesNotRevealControls() {
        val press = TvConfirmPress()
        assertTrue(press.handle(23, true, 1, false) { error("Repeat must not reveal") })
        assertTrue(press.handle(23, false, 0, false) { error("Release must not reveal") })
        assertTrue(press.handle(23, true, 0, false) {})
    }

    @Test fun remoteSeekMovesTenSecondsAndBoundsBothEnds() {
        assertEquals(45_000, tvSeekTarget(35_000, 90_000, true))
        assertEquals(25_000, tvSeekTarget(35_000, 90_000, false))
        assertEquals(0, tvSeekTarget(3_000, 90_000, false))
        assertEquals(90_000, tvSeekTarget(85_000, 90_000, true))
        assertEquals(Long.MAX_VALUE, tvSeekTarget(Long.MAX_VALUE - 1, Long.MAX_VALUE, true))
    }

    @Test fun unknownTimelineDoesNotResetPlaybackOnRemoteSeek() {
        assertEquals(35_000, tvSeekTarget(35_000, 0, true))
        assertEquals(35_000, tvSeekTarget(35_000, 0, false))
    }

    @Test fun heldSeekingAccumulatesTenSecondStepsAndReversesAtTimelineBounds() {
        var target = 35_000L
        target = tvSeekTarget(target, 90_000, true)
        assertEquals(45_000, target)
        repeat(8) { target = tvSeekTarget(target, 90_000, true) }
        assertEquals(90_000, target)
        // A direction change must continue from the preview, rather than the old
        // decoder position, including while the previous seek is still buffering.
        target = tvSeekTarget(target, 90_000, false)
        assertEquals(80_000, target)
        repeat(10) { target = tvSeekTarget(target, 90_000, false) }
        assertEquals(0, target)
        assertEquals(10_000, tvSeekTarget(target, 90_000, true))
    }
}
