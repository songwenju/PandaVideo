package dev.pandavideo

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.LineProbe
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.visiblePlaybackLines
import dev.pandavideo.tvui.tvPlaybackLineLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class TvLineProbeTest {
    private val info = PlaybackInfo("剧集", "media", "tv", pageUrl = "detail", episodes = listOf(
        Episode("a1", "第1集", 0, "线路A"), Episode("a2", "第2集", 0, "线路A"),
        Episode("b1", "第1集", 1), Episode("c1", "第1集", 2, "线路C"),
        Episode("d1", "第1集", 3, "线路D"),
    ))

    @Test fun bothTvPickersShowPendingOrMeasuredLatencyWithoutEpisodeCount() {
        assertEquals("线路A · 待测速", tvPlaybackLineLabel(info, 0))
        assertEquals("线路 2 · 待测速", tvPlaybackLineLabel(info, 1))
        val measured = info.copy(lineProbes = mapOf(0 to LineProbe(123, true), 1 to LineProbe(0, true)))
        assertEquals("线路A · 123ms", tvPlaybackLineLabel(measured, 0))
        assertEquals("线路 2 · 0ms", tvPlaybackLineLabel(measured, 1))
    }

    @Test fun tvUsesPhoneOrderingAndHidesFailedRoutesAsResultsArrive() {
        val measured = info.copy(lineProbes = mapOf(
            0 to LineProbe(230, true), 1 to LineProbe(80, true), 2 to LineProbe(available = false),
        ))
        val lines = visiblePlaybackLines(info.episodes.map { it.line }.distinct(), measured.lineProbes)
        assertEquals(listOf(1, 0, 3), lines)
        assertEquals(listOf("线路 2 · 80ms", "线路A · 230ms", "线路D · 待测速"),
            lines.map { tvPlaybackLineLabel(measured, it) })
    }
}
