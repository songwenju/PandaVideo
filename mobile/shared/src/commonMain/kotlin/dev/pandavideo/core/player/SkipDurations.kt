package dev.pandavideo.core.player

import dev.pandavideo.core.model.PlaybackInfo
import kotlinx.serialization.Serializable

/** Per-series durations; zero disables the corresponding automatic skip. */
@Serializable
data class SkipDurations(val introSeconds: Int = 0, val outroSeconds: Int = 0) {
    /** Whether both values can be safely persisted. */
    val valid: Boolean get() = introSeconds in 0..300 && outroSeconds in 0..300
    /** True when at least one edge is configured. */
    val enabled: Boolean get() = introSeconds > 0 || outroSeconds > 0
    /**
     * Sources expose no intro/outro markers, so the switch fills each unset edge with
     * the regional reference value instead of skipping nothing.
     */
    fun withDefaultFallback(seconds: Int = DefaultSeconds): SkipDurations = SkipDurations(
        introSeconds.takeIf { it > 0 } ?: seconds,
        outroSeconds.takeIf { it > 0 } ?: seconds,
    )
    /** Returns false until the timeline proves that some main content remains. */
    fun fits(duration: Long): Boolean =
        valid && duration > (introSeconds.toLong() + outroSeconds) * 1_000 &&
            duration > 0

    companion object {
        /** US series run a short title sequence, so both edges default to 30 seconds. */
        const val UsSeriesSeconds = 30
        /** Other dramas usually spend roughly 90 seconds on opening and closing credits. */
        const val DefaultSeconds = 90
        /** Maps a detail-page area label to the fallback both edges should use. */
        fun defaultSecondsFor(region: String): Int {
            val value = region.trim()
            val us = value.isNotEmpty() && listOf("美国", "欧美", "美剧").any(value::contains)
            return if (us) UsSeriesSeconds else DefaultSeconds
        }
    }
}

/** A series has multiple episodes in at least one source line, rather than multiple movie sources. */
internal fun PlaybackInfo.supportsAutomaticSkipping(): Boolean =
    episodes.groupingBy { it.line }.eachCount().values.any { it > 1 }
