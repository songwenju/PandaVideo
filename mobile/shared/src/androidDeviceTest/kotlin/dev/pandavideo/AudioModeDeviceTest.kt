package dev.pandavideo

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.platform.AndroidPlayer
import dev.pandavideo.core.player.PlaybackSession
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Exercises audio/video track selection on the production service with no visible surface. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioModeDeviceTest {
    @Test fun hiddenAudioAdvancesAndReturningToVideoPreservesMediaAndPause() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("audioFixtureBase")
        assumeTrue("Provide audioFixtureBase with short HLS/MP4 audio-video fixtures", base != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((file, kind) in listOf("video.mp4" to "video", "video.m3u8" to "hls")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            lateinit var controller: AndroidPlayer
            lateinit var session: PlaybackSession
            var controllerCreated = false
            var sessionCreated = false
            try {
                withContext(Dispatchers.Main) {
                    controller = AndroidPlayer(context)
                    controllerCreated = true
                    session = PlaybackSession({
                        PlaybackInfo("Audio fixture", "$base/$file", kind, pageUrl = "https://fixture.test/$kind")
                    }, controller, scope)
                    sessionCreated = true
                    session.setAudioOnly(true)
                    session.load(PlayerRequest(page = "https://fixture.test/$kind"))
                    session.setPlaybackVisible(false)
                }
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) {
                            val native = controller.nativePlayer ?: return@withContext false
                            assertNull(native.playerError)
                            native.isPlaying && native.currentPosition >= 500 &&
                                native.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
                        }) delay(50)
                }
                withContext(Dispatchers.Main) {
                    val native = assertNotNull(controller.nativePlayer)
                    assertFalse(native.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                    assertTrue(native.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
                    controller.seekTo(2_000)
                }
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) {
                            assertNotNull(controller.nativePlayer).currentPosition >= 2_500
                        }) delay(50)
                }
                withContext(Dispatchers.Main) {
                    val native = assertNotNull(controller.nativePlayer)
                    val media = native.currentMediaItem
                    val position = native.currentPosition
                    controller.pause()
                    session.setPlaybackVisible(true)
                    session.setAudioOnly(false)
                    assertEquals(media, native.currentMediaItem)
                    assertTrue(native.currentPosition >= position)
                    assertFalse(native.playWhenReady)
                    assertFalse(native.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
                    assertFalse(session.state.value.audioOnly)
                }
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) {
                            assertNotNull(controller.nativePlayer).currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                        }) delay(50)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    // MediaController close only detaches; stop the service's test media first.
                    if (controllerCreated) controller.stop()
                    if (sessionCreated) session.close()
                    else if (controllerCreated) controller.close()
                }
                scope.cancel()
            }
        }
    }
}
