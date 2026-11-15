package dev.pandavideo

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.platform.AndroidPlayer
import dev.pandavideo.core.platform.createAndroidPlayerView
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlayerPhase
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Exercises the production surface and service, including transient output resets. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BufferingVideoDeviceTest {
    @Test fun renderedVideoSurvivesOutputResetWhileWaitingForTheNextFrame() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("preloadFixtureBase")
        assumeTrue("Provide preloadFixtureBase", base != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        lateinit var controller: AndroidPlayer
        lateinit var view: PlayerView
        lateinit var defaultView: PlayerView
        withContext(Dispatchers.Main) {
            controller = AndroidPlayer(context)
            view = createAndroidPlayerView(context)
            defaultView = PlayerView(context).apply { useController = false }
            controller.load(MediaSource(1, "$base/video.m3u8", "hls", "Buffer fixture"))
        }
        try {
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) {
                        val native = controller.nativePlayer ?: return@withContext false
                        view.player = native
                        defaultView.player = native
                        // A headless output allows this test to run on phones that disallow
                        // automated activity launches. The real decoder still emits first frame.
                        native.setVideoSurface(surface)
                        true
                    }) delay(25)
            }
            withTimeout(20_000) {
                while (!withContext(Dispatchers.Main) {
                        assertNull(controller.state.value.error)
                        controller.state.value.firstFrameRendered &&
                            controller.state.value.phase == PlayerPhase.Playing
                    }) delay(25)
            }
            withContext(Dispatchers.Main) {
                controller.pause()
                val shutter = assertNotNull(view.findViewById<View>(androidx.media3.ui.R.id.exo_shutter))
                assertEquals(View.INVISIBLE, shutter.visibility)
                assertTrue(view.keepScreenOn)
                val defaultShutter = assertNotNull(
                    defaultView.findViewById<View>(androidx.media3.ui.R.id.exo_shutter))
                assertEquals(View.INVISIBLE, defaultShutter.visibility)
                defaultView.player = null
                assertEquals(View.VISIBLE, defaultShutter.visibility, "Default Media3 reproduces black shutter")
                // Media3 resets can temporarily remove tracks/output. The last painted frame
                // must not acquire a black native shutter before its replacement arrives.
                val native = view.player
                view.player = null
                assertEquals(View.INVISIBLE, shutter.visibility, "Output reset must retain the last frame")
                view.player = native
                assertEquals(View.INVISIBLE, shutter.visibility, "Rebinding must not close the shutter")
            }
        } finally {
            withContext(Dispatchers.Main) {
                controller.stop()
                view.player = null
                defaultView.player = null
                controller.nativePlayer?.clearVideoSurface()
                controller.close()
            }
            surface.release()
            texture.release()
        }
    }
}
