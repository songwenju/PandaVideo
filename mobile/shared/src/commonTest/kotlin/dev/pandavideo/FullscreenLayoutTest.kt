package dev.pandavideo

import dev.pandavideo.ui.player.FullscreenLayoutState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullscreenLayoutTest {
    @Test fun delayedPortraitRestorationNeverShowsLandscapeEpisodeDetails() {
        val state = FullscreenLayoutState()
        state.request(true, landscape = false)
        state.windowChanged(landscape = true)
        state.request(false, landscape = true)
        assertFalse(state.requested, "The host must receive the exit request immediately")
        repeat(30) {
            state.windowChanged(landscape = true)
            assertTrue(state.usesFullscreenLayout(landscape = true),
                "Pending landscape frames must remain video-only")
        }
        assertFalse(state.usesFullscreenLayout(landscape = false),
            "Inline details appear on the first restored portrait frame")
        state.windowChanged(landscape = false)
        assertFalse(state.usesFullscreenLayout(landscape = true),
            "Later manual rotation must not restore a stale fullscreen exit")
    }

    @Test fun landscapeTabletAndDeniedEntryDoNotWaitForAnUnneededRotation() {
        for (landscape in listOf(true, false)) {
            val state = FullscreenLayoutState()
            state.request(true, landscape)
            state.request(false, landscape)
            assertFalse(state.usesFullscreenLayout(landscape))
        }
    }

    @Test fun pictureInPictureCancelsPendingRestoration() {
        val state = FullscreenLayoutState()
        state.request(true, landscape = false)
        state.request(false, landscape = true)
        state.enterPictureInPicture()
        assertFalse(state.requested)
        assertFalse(state.usesFullscreenLayout(landscape = true))
        assertFalse(state.usesFullscreenLayout(landscape = false))
    }

    @Test fun reenterDuringRotationKeepsTheOriginalInlineOrientation() {
        val state = FullscreenLayoutState()
        state.request(true, landscape = false)
        state.request(false, landscape = true)
        state.request(true, landscape = true)
        state.request(false, landscape = true)
        assertTrue(state.usesFullscreenLayout(landscape = true))
        assertFalse(state.usesFullscreenLayout(landscape = false))
    }

    @Test fun hostKeepingLandscapeCanFinishWithoutAResizeCallback() {
        val state = FullscreenLayoutState()
        state.request(true, landscape = false)
        state.request(false, landscape = true)
        assertTrue(state.restoring)
        state.finishUnchangedWindow()
        assertFalse(state.restoring)
        assertFalse(state.usesFullscreenLayout(landscape = true))
    }
}
