package dev.pandavideo

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.platform.AndroidPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Verifies the real service connection publishes the observable state consumed by the surface. */
@RunWith(AndroidJUnit4::class)
class AndroidPlayerConnectionDeviceTest {
    @Test fun connectingAndReleasingUpdateTheObservedNativePlayer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reads = mutableSetOf<Any>()
        lateinit var controller: AndroidPlayer
        lateinit var surfaceState: State<*>
        withContext(Dispatchers.Main) {
            controller = AndroidPlayer(context)
            // Track the same getter Compose reads before the asynchronous callback. A plain
            // backing field would record no state, leaving an already composed view unbound.
            Snapshot.observe(readObserver = { reads += it }) {
                assertNull(controller.nativePlayer)
            }
            surfaceState = assertIs<State<*>>(reads.single())
        }
        try {
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { controller.nativePlayer == null }) delay(25)
            }
            withContext(Dispatchers.Main) {
                assertSame(assertNotNull(controller.nativePlayer), surfaceState.value)
                controller.close()
                assertNull(surfaceState.value)
                assertNull(controller.nativePlayer)
                assertEquals(false, controller.state.value.wantsPlay)
            }
        } finally {
            withContext(Dispatchers.Main) {
                controller.close()
            }
        }
    }
}
