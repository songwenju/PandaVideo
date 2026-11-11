package dev.pandavideo.core.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** One-second preview buckets avoid redundant extraction while preserving useful scrub accuracy. */
internal fun previewPosition(position: Long, duration: Long): Long =
    (position.coerceIn(0, (duration - 1).coerceAtLeast(0)) / 1_000) * 1_000

/**
 * Holds at most eight small images for one media source, only while its preview UI is mounted.
 * Frames never go to disk. A failed or stale request cannot disturb playback or poison the cache.
 * Requests are serialized by the UI's collectLatest collector.
 */
internal class SeekPreview(private val controller: PlayerController, private val mediaId: Long) {
    private val frames = linkedMapOf<Long, PreviewFrame>()

    suspend fun frame(position: Long): PreviewFrame? {
        val state = controller.state.value
        if (!controller.capabilities.framePreview || state.mediaId != mediaId || state.duration <= 0)
            return null
        val target = previewPosition(position, state.duration)
        frames[target]?.let { return it }
        val frame = try {
            withTimeoutOrNull(2_000) { controller.previewFrame(target) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (controller.state.value.mediaId != mediaId || frame?.mediaId != mediaId || frame.bytes.isEmpty())
            return null
        frames[target] = frame
        if (frames.size > 8) frames.remove(frames.keys.first())
        return frame
    }
}
