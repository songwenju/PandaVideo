package dev.pandavideo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.platform.AndroidPlatform
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Minimal window for verifying real fullscreen geometry without loading online media. */
class FullscreenTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Fullscreen window verification" })
    }
}

/** Verifies actual window dimensions, rather than only the accepted orientation request. */
@RunWith(AndroidJUnit4::class)
class AndroidFullscreenDeviceTest {
    @Test fun fullscreenResizesAndRestoresTheWindowIncludingPip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, FullscreenTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        lateinit var platform: AndroidPlatform
        var platformInitialized = false
        var originalSize = 0 to 0
        var originalOrientation = 0
        fun size(): Pair<Int, Int> {
            var result = 0 to 0
            instrumentation.runOnMainSync {
                result = activity.window.decorView.width to activity.window.decorView.height
            }
            return result
        }
        fun awaitSize(description: String, predicate: (Pair<Int, Int>) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (!predicate(size()) && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue(predicate(size()), "$description: actual window ${size()}")
        }
        try {
            awaitSize("Initial window must be laid out") { it.first > 0 && it.second > 0 }
            originalSize = size()
            instrumentation.runOnMainSync {
                platform = AndroidPlatform(activity)
                platformInitialized = true
                originalOrientation = activity.requestedOrientation
                platform.fullscreen(true)
                // Repeated entry must retain the original portrait window for restoration.
                platform.fullscreen(true)
            }
            awaitSize("Fullscreen must have landscape dimensions") { it.first > it.second }
            instrumentation.runOnMainSync { platform.fullscreen(false) }
            awaitSize("Exit must restore the original window") { it == originalSize }
            instrumentation.runOnMainSync {
                assertEquals(originalOrientation, activity.requestedOrientation)
                platform.fullscreen(true)
            }
            awaitSize("Second entry must also become landscape") { it.first > it.second }
            instrumentation.runOnMainSync { platform.setPictureInPictureActive(true) }
            awaitSize("PiP activation must restore the original window") { it == originalSize }
            instrumentation.runOnMainSync {
                assertEquals(originalOrientation, activity.requestedOrientation)
            }
        } finally {
            instrumentation.runOnMainSync {
                if (platformInitialized) platform.fullscreen(false)
                activity.finish()
            }
        }
    }
}
