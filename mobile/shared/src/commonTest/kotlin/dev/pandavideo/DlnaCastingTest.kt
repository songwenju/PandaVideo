package dev.pandavideo

import dev.pandavideo.core.platform.*
import dev.pandavideo.core.player.*
import dev.pandavideo.ui.player.DlnaCasting
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DlnaCastingTest {
    private class TestPlayer : PlayerController {
        override val state = MutableStateFlow(PlayerState(1, PlayerPhase.Playing, 12_000, 100_000, true))
        var seeks = mutableListOf<Long>()
        override fun load(source: MediaSource) {}
        override fun play() { state.value = state.value.copy(wantsPlay = true) }
        override fun pause() { state.value = state.value.copy(wantsPlay = false) }
        override fun seekTo(position: Long) { seeks += position }
        override fun setPlaybackRate(rate: Float) {}
        override fun stop() {}
        override fun close() {}
    }
    private class TestPlatform(private val testScope: TestScope) : PlatformServices {
        val actions = mutableListOf<String>()
        var failPlay = false
        var failSeek = false
        var playDelay = 0L
        var discoveryError: Exception? = null
        var locations = emptyList<String>()
        var descriptors = emptyMap<String, Pair<Long, String>>()
        override suspend fun discoverDlnaLocations(host: String?): List<String> {
            discoveryError?.let { throw it }
            return locations
        }
        override fun httpClient() = HttpClient(testScope.mockEngine { request ->
            descriptors[request.url.toString()]?.let { (wait, xml) ->
                delay(wait)
                return@mockEngine respond(xml)
            }
            val action = request.headers["SOAPAction"].orEmpty().substringAfter('#').trimEnd('"')
            actions += action
            if (action == "Play") delay(playDelay)
            if ((action == "Play" && failPlay) || (action == "Seek" && failSeek))
                respond("<root/>", HttpStatusCode.InternalServerError)
            else respond("<root/>")
        })
        override fun player(): PlayerController = error("unused")
        override fun storage(): KeyValueStorage = FakeStorage()
        override val haptics = SilentHaptics
        override fun openLink(url: String) {}
        override fun fullscreen(enabled: Boolean) {}
        override fun currentVolume() = 0f
        override fun setVolume(value: Float) {}
        override fun currentBrightness() = 0f
        override fun setBrightness(value: Float) {}
        override fun resetBrightness() {}
        override val pipSupported = false
        override val pictureInPictureActive = MutableStateFlow(false)
        override fun enterPictureInPicture() = false
    }
    private val target = DlnaDevice("tv", "电视", "http://192.168.1.10/control",
        "urn:schemas-upnp-org:service:AVTransport:1")
    private val source = MediaSource(1, "https://media.example/video.mp4", "video", "电影")

    @Test fun responsiveTelevisionAppearsBeforeSlowDescriptorsFinish() = runTest {
        val platform = TestPlatform(this).apply {
            locations = listOf("http://192.168.1.10/root.xml", "http://192.168.1.11/root.xml")
            val xml = """<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>电视</friendlyName><UDN>tv</UDN><serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/control</controlURL></service></serviceList></device></root>"""
            descriptors = mapOf(locations[0] to (0L to xml), locations[1] to (20_000L to xml))
        }
        val casting = DlnaCasting(platform, TestPlayer(), StandardTestDispatcher(testScheduler))
        try {
            casting.search()
            runCurrent()
            assertEquals(listOf(target), casting.devices)
            assertTrue(casting.searching)
            advanceTimeBy(8_001); runCurrent()
            assertFalse(casting.searching)
            assertEquals(listOf(target), casting.devices)
        } finally { casting.close(); runCurrent() }
    }

    @Test fun discoveryPermissionErrorKeepsItsActionableExplanation() = runTest {
        val platform = TestPlatform(this).apply {
            discoveryError = DlnaException("请连接 Wi-Fi 或有线局域网后搜索投屏设备")
        }
        val casting = DlnaCasting(platform, TestPlayer(), StandardTestDispatcher(testScheduler))
        try {
            casting.search(); runCurrent()
            assertEquals(platform.discoveryError?.message, casting.error)
            assertFalse(casting.searching)
        } finally { casting.close(); runCurrent() }
    }

    @Test fun acceptedCastPausesPhoneAndDisconnectRestoresPositionWithoutAutoplay() = runTest {
        val platform = TestPlatform(this)
        val player = TestPlayer()
        val casting = DlnaCasting(platform, player, StandardTestDispatcher(testScheduler))
        casting.connect(target, source)
        runCurrent()
        assertEquals(listOf("SetAVTransportURI", "Play", "Seek"), platform.actions)
        assertEquals(target, casting.device)
        assertFalse(player.state.value.wantsPlay)
        casting.togglePlay()
        runCurrent()
        assertEquals("Pause", platform.actions.last())
        assertFalse(casting.playing)
        casting.seek(30_000)
        runCurrent()
        casting.disconnect()
        runCurrent()
        assertEquals("Stop", platform.actions.last())
        assertNull(casting.device)
        assertEquals(listOf(30_000L), player.seeks)
        assertFalse(player.state.value.wantsPlay)
        casting.close(); runCurrent()
    }
    @Test fun rejectedPlayStopsRemoteAndLeavesPhonePlaying() = runTest {
        val platform = TestPlatform(this).apply { failPlay = true }
        val player = TestPlayer()
        val casting = DlnaCasting(platform, player, StandardTestDispatcher(testScheduler))
        casting.connect(target, source); runCurrent()
        assertEquals(listOf("SetAVTransportURI", "Play", "Stop"), platform.actions)
        assertNull(casting.device)
        assertTrue(player.state.value.wantsPlay)
        assertNotNull(casting.error)
        assertFalse(casting.busy)
        casting.close(); runCurrent()
    }
    @Test fun unsupportedSeekKeepsWorkingCastAndReportsStartFromBeginning() = runTest {
        val platform = TestPlatform(this).apply { failSeek = true }
        val player = TestPlayer()
        val casting = DlnaCasting(platform, player, StandardTestDispatcher(testScheduler))
        casting.connect(target, source); runCurrent()
        assertEquals(target, casting.device)
        assertEquals(0, casting.position)
        assertFalse(player.state.value.wantsPlay)
        assertContains(casting.error.orEmpty(), "从头播放")
        casting.close(); runCurrent()
        assertEquals("Stop", platform.actions.last())
    }
    @Test fun changingEpisodeDuringHandoffNeverPausesNewMediaAndStopsStaleCast() = runTest {
        val platform = TestPlatform(this).apply { playDelay = 1000 }
        val player = TestPlayer()
        val casting = DlnaCasting(platform, player, StandardTestDispatcher(testScheduler))
        casting.connect(target, source); runCurrent()
        player.state.value = player.state.value.copy(mediaId = 2)
        casting.mediaChanged(2); runCurrent()
        assertEquals("Stop", platform.actions.last())
        assertTrue(player.state.value.wantsPlay)
        assertNull(casting.device)
        assertFalse(casting.busy)
        casting.close(); runCurrent()
    }
    @Test fun pageDisposalStopsConnectedTelevisionAndCancelsPolling() = runTest {
        val platform = TestPlatform(this)
        val casting = DlnaCasting(platform, TestPlayer(), StandardTestDispatcher(testScheduler))
        casting.connect(target, source); runCurrent()
        casting.close(); runCurrent()
        assertEquals("Stop", platform.actions.last())
        val count = platform.actions.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(count, platform.actions.size)
    }
}
