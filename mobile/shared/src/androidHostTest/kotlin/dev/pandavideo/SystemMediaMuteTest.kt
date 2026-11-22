package dev.pandavideo

import dev.pandavideo.core.platform.systemMediaGain
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemMediaMuteTest {
    @Test fun zeroIndexMutesEvenWhenVendorStreamIsNotMuted() {
        assertEquals(0f, systemMediaGain(0, false))
    }

    @Test fun explicitMuteOverridesPositiveSystemVolume() {
        assertEquals(0f, systemMediaGain(16, true))
    }

    @Test fun positiveVolumeRestoresGainWithoutReplacingTheSystemCurve() {
        for (index in listOf(1, 10, 50)) assertEquals(1f, systemMediaGain(index, false))
    }
}
