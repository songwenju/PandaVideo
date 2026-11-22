package dev.pandavideo.core.player

import dev.pandavideo.core.model.MediaSegment

/** Maps a full-program position to an ordered MP4 asset without exposing segment boundaries to UI. */
internal class SegmentedTimeline(val segments: List<MediaSegment>) {
    init {
        require(segments.isNotEmpty() && segments.all { it.url.isNotBlank() && it.durationMs > 0 })
        require(segments.fold(0L) { total, segment ->
            require(total <= Long.MAX_VALUE - segment.durationMs)
            total + segment.durationMs
        } > 0)
    }
    val duration: Long = segments.sumOf { it.durationMs }
    fun offset(index: Int): Long = segments.take(index).sumOf { it.durationMs }

    /** Exact boundaries belong to the next asset; the final position belongs to the last asset. */
    fun locate(position: Long): SegmentPosition {
        val bounded = position.coerceIn(0, duration)
        var offset = 0L
        segments.forEachIndexed { index, segment ->
            if (bounded < offset + segment.durationMs || index == segments.lastIndex)
                return SegmentPosition(index, bounded - offset, offset)
            offset += segment.durationMs
        }
        error("Missing segment")
    }
}

internal data class SegmentPosition(val index: Int, val local: Long, val offset: Long)
