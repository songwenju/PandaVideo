package dev.pandavideo

import dev.pandavideo.core.player.PendingSeek
import kotlin.test.*

class PendingSeekTest {
    @Test fun oldNativeSamplesCannotPullTheReleasedTimelineBack() {
        val seek = PendingSeek()
        val request = seek.begin(1, 137_000)
        for (oldPosition in listOf(60_000L, 60_250L, 60_500L)) {
            assertEquals(137_000L, seek.position(1, oldPosition))
        }
        assertTrue(seek.complete(request))
        assertEquals(137_250L, seek.position(1, 137_250))
    }

    @Test fun interruptedSeekCompletionCannotOverwriteTheNewestTarget() {
        val seek = PendingSeek()
        val first = seek.begin(1, 137_000)
        val second = seek.begin(1, 200_000)
        assertFalse(seek.complete(first))
        assertTrue(seek.active(1))
        assertEquals(200_000L, seek.position(1, 137_000))
        assertTrue(seek.complete(second))
        assertEquals(200_250L, seek.position(1, 200_250))
    }

    @Test fun mediaReplacementAndTeardownDiscardPendingSeeks() {
        val seek = PendingSeek()
        val old = seek.begin(1, 137_000)
        assertEquals(0L, seek.position(2, 0))
        seek.clear()
        assertFalse(seek.complete(old))
        assertFalse(seek.active(1))
        val current = seek.begin(2, 10_000)
        assertFalse(seek.complete(old))
        assertEquals(10_000L, seek.position(2, 0))
        assertTrue(seek.complete(current))
    }

    @Test fun failedCurrentSeekCanReturnToActualNativePosition() {
        val seek = PendingSeek()
        val request = seek.begin(1, 137_000)
        assertTrue(seek.complete(request))
        assertEquals(60_000L, seek.position(1, 60_000))
        assertFalse(seek.complete(request))
    }
}
