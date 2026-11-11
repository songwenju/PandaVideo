package dev.pandavideo

import dev.pandavideo.ui.common.playbackTime
import dev.pandavideo.ui.player.stripRate
import kotlin.test.*

class FormattingTest {
    @Test fun playbackTimeUsesMinuteSecondFormatAndNeverGoesNegative() {
        assertEquals("0:00", playbackTime(0))
        assertEquals("0:05", playbackTime(5_000))
        assertEquals("1:05", playbackTime(65_000))
        assertEquals("10:00", playbackTime(600_000))
        assertEquals("59:59", playbackTime(3_599_000))
        assertEquals("60:00", playbackTime(3_600_000))
        assertEquals("0:00", playbackTime(-12_000))
    }

    @Test fun stripRateRemovesRedundantTrailingZeros() {
        assertEquals("1", 1f.stripRate())
        assertEquals("2", 2f.stripRate())
        assertEquals("0.75", 0.75f.stripRate())
        assertEquals("1.25", 1.25f.stripRate())
        assertEquals("1.5", 1.5f.stripRate())
    }
}
