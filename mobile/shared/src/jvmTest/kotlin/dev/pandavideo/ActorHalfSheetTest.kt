package dev.pandavideo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.player.*
import dev.pandavideo.ui.search.ActorHalfSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Exercises the production overlay and an active session through actual pointer dispatch. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActorHalfSheetTest {
    private class FixturePlayer : PlayerController {
        override val state = MutableStateFlow(PlayerState())
        var loads = 0
        var seeks = 0
        override fun load(source: MediaSource) {
            loads++
            state.value = PlayerState(source.id, PlayerPhase.Playing, position = 42_000,
                duration = 120_000, rate = 1.5f, wantsPlay = true)
        }
        override fun play() { state.value = state.value.copy(wantsPlay = true) }
        override fun pause() { state.value = state.value.copy(wantsPlay = false) }
        override fun seekTo(position: Long) { seeks++ }
        override fun setPlaybackRate(rate: Float) {}
        override fun stop() {}
        override fun close() {}
    }

    @Test fun halfSheetKeepsSessionAndUpperPlayerControlsInteractive() = runTest {
        for (size in listOf(IntSize(390, 844), IntSize(844, 390))) {
            val player = FixturePlayer()
            val session = PlaybackSession({
                PlaybackInfo("测试影片", "https://fixture.test/video", "video", emptyList(), "",
                    "https://fixture.test/page")
            }, player, backgroundScope)
            session.load(PlayerRequest(page = "https://fixture.test/page"))
            runCurrent()
            val owners = mutableListOf<SemanticsOwner>()
            val context = object : PlatformContext by PlatformContext.Empty() {
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
                }
            }
            val scene = CanvasLayersComposeScene(size = size, coroutineContext = coroutineContext,
                platformContext = context)
            val canvas = Canvas(ImageBitmap(size.width, size.height))
            var frame = 0L
            var shown by mutableStateOf(false)
            var mounts = 0
            var releases = 0
            fun pump(frames: Int = 25) {
                repeat(frames) {
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    scene.render(canvas, frame++ * 16_000_000L)
                    runCurrent()
                }
            }
            fun nodes() = owners.flatMap { it.getAllSemanticsNodes(true) }
            fun described(label: String) = nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
            }
            fun tap(node: SemanticsNode) {
                val point = node.boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Touch)
                pump()
            }
            try {
                scene.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            Column {
                                DisposableEffect(session) {
                                    mounts++
                                    onDispose { releases++ }
                                }
                                TextButton({ player.pause() }) { Text("暂停视频") }
                                TextButton({ shown = true }) { Text("演员") }
                            }
                            val viewport = if (size.width > size.height) {
                                Modifier.align(Alignment.CenterEnd).width((size.width * 0.45f).dp).fillMaxHeight()
                            } else {
                                Modifier.fillMaxSize().padding(top = (size.width * 9f / 16f).dp)
                            }
                            ActorHalfSheet(shown, { shown = false }, viewport) {
                                Text("演员资料与作品")
                            }
                        }
                    }
                }
                pump()
                shown = true
                pump(5)
                val entering = described("演员资料面板").boundsInWindow
                pump()
                val panel = described("演员资料面板").boundsInWindow
                val expectedTop = if (size.width > size.height) 0f else size.width * 9f / 16f
                assertEquals(expectedTop, panel.top, 1f)
                assertEquals(size.height - expectedTop, panel.height, 1f)
                assertEquals(size.width.toFloat(), panel.right, 1f)
                assertTrue(entering.left > panel.left, "The panel must enter from the right")
                assertEquals(panel.top, entering.top, 1f, "Entry must not move vertically")
                assertEquals(panel.height, entering.height, 1f)
                assertTrue(player.state.value.wantsPlay, "Opening actor data must not pause playback")
                val pause = nodes().first {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "暂停视频" } == true
                }
                tap(pause)
                assertFalse(player.state.value.wantsPlay, "Upper player controls must remain reachable")
                tap(described("关闭演员资料"))
                assertFalse(shown)
                shown = true
                pump()
                tap(described("关闭演员资料"))
                assertEquals(1, mounts)
                assertEquals(0, releases, "Opening and closing the sheet must retain the playback host")
                assertEquals(1, player.loads)
                assertEquals(0, player.seeks)
                assertEquals(42_000L, player.state.value.position)
                assertEquals(1.5f, player.state.value.rate)
                assertFalse(player.state.value.wantsPlay, "Dismissal must retain the user's pause")
            } finally {
                scene.close()
                session.close()
            }
        }
    }
}
