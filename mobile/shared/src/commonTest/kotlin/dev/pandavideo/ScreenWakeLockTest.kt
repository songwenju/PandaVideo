package dev.pandavideo

import dev.pandavideo.core.player.ScreenWakeLock
import kotlin.test.*

class ScreenWakeLockTest {
    @Test fun videoKeepsTheScreenAwakeUntilItsSurfaceLeavesIncludingBuffering() {
        var disabled = false
        val writes = mutableListOf<Boolean>()
        val wake = ScreenWakeLock({ disabled }, { disabled = it; writes += it })
        val leaveVideo = wake.acquire()
        assertTrue(disabled)
        // No player phase drives ownership: a buffering or preparing video retains its lease.
        assertEquals(listOf(true), writes)
        leaveVideo()
        assertFalse(disabled)
        leaveVideo()
        assertEquals(listOf(true, false), writes)
    }

    @Test fun fullscreenReplacementDoesNotRestoreAutoLockWhileStillVisible() {
        var disabled = false
        val wake = ScreenWakeLock({ disabled }, { disabled = it })
        val inline = wake.acquire()
        val fullscreen = wake.acquire()
        inline()
        assertTrue(disabled)
        fullscreen()
        assertFalse(disabled)
        val nextVideo = wake.acquire()
        assertTrue(disabled)
        nextVideo()
        assertFalse(disabled)
    }

    @Test fun releasingVideoPreservesAnAlreadyDisabledIdleTimer() {
        var disabled = true
        val wake = ScreenWakeLock({ disabled }, { disabled = it })
        val leaveVideo = wake.acquire()
        leaveVideo()
        assertTrue(disabled)
    }
}
