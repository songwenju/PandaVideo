package dev.pandavideo.core.platform

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PreviewFrame
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Lazily owns a Media3 thumbnail decoder, isolated from the audible media-session player.
 * All extractor lifecycle operations run on Main. Only one native request can be in flight;
 * cancelled UI requests detach from its result instead of queueing more native seeks.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AndroidSeekPreview(context: Context) {
    private val context = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(this.context)
    private var source: MediaSource? = null
    private var extractor: FrameExtractor? = null
    private var pending: Request? = null
    private data class Request(val position: Long, val future: ListenableFuture<FrameExtractor.Frame>)

    /** Invalidates the previous source before a new playback load, without preloading video. */
    fun setSource(source: MediaSource) {
        close()
        this.source = source
    }

    /** Returns the nearest keyframe as a small PNG, or null when the source was replaced. */
    suspend fun frame(mediaId: Long, position: Long): PreviewFrame? = withContext(Dispatchers.Main.immediate) {
        val loaded = source?.takeIf { it.id == mediaId } ?: return@withContext null
        val target = position.coerceAtLeast(0)
        // Media3 1.9.3 serializes extraction internally. Cancelling a running getFrame future
        // does not interrupt its decoder. Keep that one request and await it before submitting
        // the latest target, so rapid scrubbing cannot accumulate an unbounded native queue.
        val previous = pending
        var frame = previous?.future?.awaitFrame()
        currentCoroutineContext().ensureActive()
        if (source !== loaded) return@withContext null
        if (previous == null || previous.position != target) {
            val decoder = extractor ?: FrameExtractor.Builder(context,
                MediaItem.Builder().setUri(loaded.url)
                    .setMimeType(if (loaded.kind == "hls") MimeTypes.APPLICATION_M3U8 else null)
                    .build())
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .setEffects(listOf(Presentation.createForWidthAndHeight(
                    320, 180, Presentation.LAYOUT_SCALE_TO_FIT)))
                .build().also { extractor = it }
            val request = Request(target, decoder.getFrame(target))
            pending = request
            frame = request.future.awaitFrame()
        }
        currentCoroutineContext().ensureActive()
        if (source !== loaded || frame == null) return@withContext null
        val image = frame
        val bytes = withContext(Dispatchers.Default) {
            // Media3 retains the bitmap for seek deduplication; it owns recycling. Encode a
            // copy for the shared UI and never persist video segments or thumbnails to disk.
            ByteArrayOutputStream().use { output ->
                if (image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray()
                else byteArrayOf()
            }
        }
        if (source !== loaded || bytes.isEmpty()) null
        else PreviewFrame(mediaId, image.presentationTimeMs, bytes)
    }

    private suspend fun ListenableFuture<FrameExtractor.Frame>.awaitFrame(): FrameExtractor.Frame =
        suspendCancellableCoroutine { continuation ->
            addListener({
                if (continuation.isActive) {
                    val result = runCatching { get() }
                    if (result.isFailure && pending?.future === this) pending = null
                    continuation.resumeWith(result)
                }
            }, mainExecutor)
            // Do not cancel the future: retain the one native request until it completes or
            // fails. A cancelled continuation cannot publish a stale image or start another seek.
        }

    /** Invalidates delivery immediately; Media3 closes its decoder after pending extraction drains. */
    fun close() {
        source = null
        pending = null
        extractor?.close()
        extractor = null
    }
}
