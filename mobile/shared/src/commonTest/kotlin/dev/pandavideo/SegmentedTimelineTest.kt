package dev.pandavideo

import dev.pandavideo.core.model.MediaSegment
import dev.pandavideo.core.player.SegmentedTimeline
import kotlin.test.*

class SegmentedTimelineTest {
    private val timeline = SegmentedTimeline(listOf(
        MediaSegment("first.mp4", 120_000), MediaSegment("second.mp4", 120_000),
        MediaSegment("last.mp4", 59_160)))

    @Test fun wholeProgramBoundsAndBothDirectionsCrossSegmentEdges() {
        assertEquals(299_160L, timeline.duration)
        assertEquals(0L, timeline.locate(-1).local)
        assertEquals(1, timeline.locate(120_000).index)
        assertEquals(0L, timeline.locate(120_000).local)
        assertEquals(5_000L, timeline.locate(125_000).local)
        assertEquals(120_000L, timeline.locate(125_000).offset)
        assertEquals(0, timeline.locate(115_000).index)
        assertEquals(115_000L, timeline.locate(115_000).local)
        assertEquals(59_160L, timeline.locate(Long.MAX_VALUE).local)
        for (position in listOf(0L, 119_999, 120_000, 120_001, 239_999, 240_000, 299_160)) {
            val mapped = timeline.locate(position)
            assertEquals(position, mapped.offset + mapped.local)
        }
    }

    @Test fun invalidAndOverflowingTimelinesAreRejected() {
        assertFailsWith<IllegalArgumentException> { SegmentedTimeline(emptyList()) }
        assertFailsWith<IllegalArgumentException> { SegmentedTimeline(listOf(MediaSegment("x", 0))) }
        assertFailsWith<IllegalArgumentException> {
            SegmentedTimeline(listOf(MediaSegment("x", Long.MAX_VALUE), MediaSegment("y", 1)))
        }
    }
}
