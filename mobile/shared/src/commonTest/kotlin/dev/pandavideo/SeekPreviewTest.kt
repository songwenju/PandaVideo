package dev.pandavideo

import dev.pandavideo.core.player.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SeekPreviewTest {
    private class Controller : PlayerController {
        override val state = MutableStateFlow(PlayerState(
            mediaId = 1, phase = PlayerPhase.Playing, position = 3_000, duration = 60_000,
        ))
        override val capabilities = PlayerCapabilities(framePreview = true)
        val requests = mutableListOf<Long>()
        var extract: suspend (Long) -> PreviewFrame? = { PreviewFrame(1, it, byteArrayOf(1)) }
        override suspend fun previewFrame(position: Long): PreviewFrame? {
            requests += position
            return extract(position)
        }
        override fun load(source: MediaSource) = error("Preview must not load media")
        override fun play() = error("Preview must not change playback")
        override fun pause() = error("Preview must not change playback")
        override fun seekTo(position: Long) = error("Preview must not seek playback")
        override fun setPlaybackRate(rate: Float) = error("Preview must not change playback")
        override fun stop() {}
        override fun close() {}
    }

    @Test fun bucketsAreClampedAndReusedWithoutSeeking() = runTest {
        val controller = Controller()
        val state = controller.state.value
        val preview = SeekPreview(controller, 1)
        assertEquals(5_000, preview.frame(5_001)?.position)
        assertEquals(5_000, preview.frame(5_999)?.position)
        assertEquals(0, preview.frame(-100)?.position)
        assertEquals(59_000, preview.frame(100_000)?.position)
        assertEquals(listOf(5_000L, 0L, 59_000L), controller.requests)
        assertEquals(state, controller.state.value)
    }

    @Test fun smallMemoryCacheEvictsOldFrames() = runTest {
        val controller = Controller()
        val preview = SeekPreview(controller, 1)
        (0L..8L).forEach { preview.frame(it * 1_000) }
        preview.frame(8_500)
        assertEquals(9, controller.requests.size)
        preview.frame(0)
        assertEquals(10, controller.requests.size)
    }

    @Test fun timedOutAndFailedRequestsRemainRetryable() = runTest {
        val controller = Controller()
        val preview = SeekPreview(controller, 1)
        controller.extract = { delay(3_000); PreviewFrame(1, it, byteArrayOf(1)) }
        assertNull(preview.frame(5_000))
        controller.extract = { error("Unsupported stream") }
        assertNull(preview.frame(5_000))
        controller.extract = { PreviewFrame(1, it, byteArrayOf(1)) }
        assertNotNull(preview.frame(5_000))
        assertEquals(3, controller.requests.size)
    }

    @Test fun oldSourceCompletionsAndEmptyImagesAreDiscarded() = runTest {
        val controller = Controller()
        val preview = SeekPreview(controller, 1)
        controller.extract = { PreviewFrame(2, it, byteArrayOf(1)) }
        assertNull(preview.frame(5_000))
        controller.extract = { PreviewFrame(1, it, byteArrayOf()) }
        assertNull(preview.frame(5_000))
        controller.extract = {
            delay(100)
            controller.state.value = controller.state.value.copy(mediaId = 2)
            PreviewFrame(1, it, byteArrayOf(1))
        }
        assertNull(preview.frame(5_000))
        assertNull(preview.frame(5_000))
        assertEquals(3, controller.requests.size)
    }

    @Test fun cancelledRequestCannotPublishOrPoisonTheNextRequest() = runTest {
        val controller = Controller()
        val preview = SeekPreview(controller, 1)
        var cancelled = false
        controller.extract = {
            try { delay(10_000); PreviewFrame(1, it, byteArrayOf(1)) }
            finally { cancelled = true }
        }
        val job = launch { preview.frame(5_000); fail("Cancelled preview was published") }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(cancelled)
        controller.extract = { PreviewFrame(1, it, byteArrayOf(1)) }
        assertNotNull(preview.frame(5_000))
    }
}
