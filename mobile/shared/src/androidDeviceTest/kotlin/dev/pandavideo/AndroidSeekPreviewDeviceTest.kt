package dev.pandavideo

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.platform.AndroidSeekPreview
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PreviewFrame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the production Media3 decoder on a real GPU, using small, silent red/blue fixtures. */
@RunWith(AndroidJUnit4::class)
class AndroidSeekPreviewDeviceTest {
    private suspend fun exercise(kind: String, extension: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "seek-preview-test").apply { mkdirs() }
        val assets = instrumentation.context.assets
        for (name in assets.list("seek-preview").orEmpty()) {
            assets.open("seek-preview/$name").use { input ->
                File(directory, name).outputStream().use { input.copyTo(it) }
            }
        }
        val source = MediaSource(41, File(directory, "colors.$extension").toURI().toString(), kind, "Fixture")
        withContext(Dispatchers.Main) {
            val preview = AndroidSeekPreview(context)
            try {
                preview.setSource(source)
                val red = withTimeout(15_000) { assertNotNull(preview.frame(41, 0)) }
                val blue = withTimeout(15_000) { assertNotNull(preview.frame(41, 3_000)) }
                assertEquals(41, red.mediaId)
                assertTrue(blue.position >= 2_000, "Extractor returned an old frame: ${blue.position}")
                assertColor(red, red = true)
                assertColor(blue, red = false)
                preview.close()
                assertNull(preview.frame(41, 0))
                preview.setSource(source.copy(id = 42))
                assertNull(preview.frame(41, 0))
                assertEquals(42, withTimeout(15_000) { preview.frame(42, 0) }?.mediaId)
            } finally {
                preview.close()
                directory.deleteRecursively()
            }
        }
    }

    private fun assertColor(frame: PreviewFrame, red: Boolean) {
        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size))
        try {
            assertTrue(bitmap.width <= 320 && bitmap.height <= 180)
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue(if (red) Color.red(pixel) > 200 && Color.blue(pixel) < 40
                else Color.blue(pixel) > 200 && Color.red(pixel) < 40, "Wrong preview color")
        } finally { bitmap.recycle() }
    }

    @Test fun mp4FramesFollowTargetTimeAndInvalidateOnSourceChange() = runBlocking {
        exercise("mp4", "mp4")
    }

    @Test fun hlsFramesFollowTargetTimeAndInvalidateOnSourceChange() = runBlocking {
        exercise("hls", "m3u8")
    }
}
