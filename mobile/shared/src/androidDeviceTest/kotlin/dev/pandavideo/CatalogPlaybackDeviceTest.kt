package dev.pandavideo

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.platform.AndroidPlayer
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.data.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Opt-in check of catalog matching, source resolution and actual video decoding on Android. */
@RunWith(AndroidJUnit4::class)
class CatalogPlaybackDeviceTest {
    @Test fun neZhaCatalogSelectionRendersAndAdvances() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSources") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = ContentRepository(HttpClient(OkHttp) { followRedirects = false })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val firstFrame = CompletableDeferred<Unit>()
        var controller: AndroidPlayer? = null
        var session: PlaybackSession? = null
        try {
            val item = CatalogItem("哪吒之魔童闹海", "https://movie.douban.com/subject/34780991/",
                casts = listOf("吕艳婷", "囧森瑟夫", "瀚墨"), kind = "movie")
            withContext(Dispatchers.Main) {
                val player = AndroidPlayer(context)
                controller = player
                session = PlaybackSession({ request ->
                    val match = repo.resolve(item)
                    assertTrue(match.playable)
                    repo.player(request.copy(page = match.pageUrl))
                }, player, scope)
                session!!.load(PlayerRequest(title = item.title))
            }
            withTimeout(15_000) {
                while (!withContext(Dispatchers.Main) { controller!!.nativePlayer != null }) delay(50)
            }
            withContext(Dispatchers.Main) {
                controller!!.nativePlayer!!.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.complete(Unit) }
                })
                controller!!.nativePlayer!!.setVideoSurface(surface)
            }
            withTimeout(60_000) { firstFrame.await() }
            withTimeout(15_000) {
                while (!withContext(Dispatchers.Main) {
                        val player = controller!!.nativePlayer!!
                        assertNull(player.playerError)
                        assertEquals(item.title, session!!.state.value.info?.title)
                        player.isPlaying && player.currentPosition >= 1_000
                    }) delay(50)
            }
        } finally {
            withContext(Dispatchers.Main) {
                controller?.nativePlayer?.clearVideoSurface()
                controller?.stop()
                session?.close()
                if (session == null) controller?.close()
            }
            scope.cancel()
            surface.release()
            texture.release()
            repo.close()
        }
    }
}
