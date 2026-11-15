package dev.pandavideo.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.TimeSource

/** Outcome of an attempt; only [StartupOutcome.Started] samples contain a video first-frame latency. */
enum class StartupOutcome { Pending, Started, Cancelled, Failed, AudioOnly }

/**
 * A bounded, local playback measurement with no media URL, title, query, or user identifier.
 * Latencies are milliseconds from the session's load request. [nativeStartupMs] excludes
 * address resolution. Readiness is not evidence that a video frame reached its output surface.
 */
data class PlaybackStartupSample(
    val mediaId: Long,
    val reason: String,
    val kind: String = "",
    val outcome: StartupOutcome = StartupOutcome.Pending,
    val resolutionMs: Long? = null,
    val readyMs: Long? = null,
    val firstFrameMs: Long? = null,
    val nativeStartupMs: Long? = null,
    val addressPreloadHit: Boolean = false,
    val dataPreloadHit: Boolean = false,
    val watchMs: Long = 0,
    val rebufferMs: Long = 0,
)

/** Collects at most 50 attempts in memory; observation never sends or logs playback data. */
internal class PlaybackTelemetry(private val nowMs: () -> Long = monotonicClock()) {
    private val mutable = MutableStateFlow<List<PlaybackStartupSample>>(emptyList())
    val samples = mutable.asStateFlow()
    private var started = 0L
    private var observed = 0L
    private var previous: PlayerState? = null
    private var collecting = false

    fun begin(id: Long, reason: String) {
        finish(StartupOutcome.Cancelled)
        started = nowMs()
        observed = started
        previous = null
        collecting = true
        mutable.value = (mutable.value + PlaybackStartupSample(id, reason)).takeLast(50)
    }

    fun resolved(kind: String, addressHit: Boolean) {
        update { it.copy(kind = kind, resolutionMs = elapsed(), addressPreloadHit = addressHit) }
    }

    fun observe(player: PlayerState, audioOnly: Boolean) {
        val sample = mutable.value.lastOrNull() ?: return
        if (!collecting || player.mediaId != sample.mediaId) return
        val now = nowMs()
        val delta = (now - observed).coerceAtLeast(0)
        val last = previous
        update {
            val ready = it.readyMs ?: if (player.phase in setOf(
                    PlayerPhase.Ready, PlayerPhase.Playing, PlayerPhase.Paused,
                )) now - started else null
            val first = it.firstFrameMs ?: if (it.outcome != StartupOutcome.AudioOnly &&
                !audioOnly && player.firstFrameRendered &&
                player.phase == PlayerPhase.Playing && player.position > 0) now - started else null
            it.copy(
                readyMs = ready,
                firstFrameMs = first,
                nativeStartupMs = first?.let { firstMs -> firstMs - (it.resolutionMs ?: 0) },
                outcome = when {
                    first != null -> StartupOutcome.Started
                    audioOnly && player.phase == PlayerPhase.Playing -> StartupOutcome.AudioOnly
                    else -> it.outcome
                },
                dataPreloadHit = player.dataPreloadHit,
                watchMs = it.watchMs + if (last?.phase == PlayerPhase.Playing && last.wantsPlay) delta else 0,
                // Initial loading and paused waits are not rebuffering. Seek waits cannot be
                // distinguished here, so this is a diagnostic stall counter, not a Mux ratio.
                rebufferMs = it.rebufferMs + if (it.outcome == StartupOutcome.Started &&
                    last?.phase == PlayerPhase.Buffering && last.wantsPlay) delta else 0,
            )
        }
        observed = now
        previous = player
        if (player.phase == PlayerPhase.Error) finish(StartupOutcome.Failed)
        if (player.phase == PlayerPhase.Paused && !player.wantsPlay &&
            mutable.value.last().outcome == StartupOutcome.Pending) finish(StartupOutcome.Cancelled)
    }

    fun finish(outcome: StartupOutcome) {
        if (!collecting) return
        update { if (it.outcome == StartupOutcome.Pending) it.copy(outcome = outcome) else it }
        collecting = false
        previous = null
    }

    private fun elapsed() = (nowMs() - started).coerceAtLeast(0)
    private inline fun update(transform: (PlaybackStartupSample) -> PlaybackStartupSample) {
        if (collecting && mutable.value.isNotEmpty())
            mutable.value = mutable.value.dropLast(1) + transform(mutable.value.last())
    }
}

/** Creates a monotonic clock whose epoch is local to the owning session. */
internal fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
