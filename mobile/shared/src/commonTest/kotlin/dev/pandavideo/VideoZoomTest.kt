package dev.pandavideo

import dev.pandavideo.ui.player.videoZoom
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoZoomTest {
    @Test
    fun pinchCanEnlargeAndReturnToOriginalSize() {
        val enlarged = videoZoom(1f, 2f)
        assertEquals(2f, enlarged)
        assertEquals(1f, videoZoom(enlarged, 0.5f))
    }

    @Test
    fun pinchCanShrinkBelowOriginalAndReturnToOriginalSize() {
        val reduced = videoZoom(1f, 0.75f)
        assertEquals(0.75f, reduced)
        assertEquals(1f, videoZoom(reduced, 1f / 0.75f))
    }

    @Test
    fun boundsPreventOverZoomAndExcessiveShrinking() {
        assertEquals(4f, videoZoom(3f, 2f))
        assertEquals(0.5f, videoZoom(2f, 0.1f))
        assertEquals(0.5f, videoZoom(0.5f, 0.5f))
        assertEquals(2f, videoZoom(4f, 0.5f))
    }

    @Test
    fun coincidentOrInvalidTouchDistancesKeepCurrentPicture() {
        for (multiplier in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(2f, videoZoom(2f, multiplier))
        }
    }
}
