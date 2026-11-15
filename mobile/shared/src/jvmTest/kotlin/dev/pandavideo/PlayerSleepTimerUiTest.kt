package dev.pandavideo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import dev.pandavideo.ui.player.AudioModeContent
import dev.pandavideo.ui.player.AudioReturnButton
import dev.pandavideo.ui.player.SleepTimerOptionsContent
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerSleepTimerUiTest {
    private class SceneHarness(val scope: TestScope, size: IntSize, fontScale: Float = 1f) : AutoCloseable {
        val owners = mutableListOf<SemanticsOwner>()
        private val context = object : PlatformContext by PlatformContext.Empty() {
            override val windowInfo = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize = size
            }
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
            }
        }
        val scene = CanvasLayersComposeScene(size = size, density = Density(1f, fontScale),
            coroutineContext = scope.coroutineContext, platformContext = context)
        private val canvas = Canvas(ImageBitmap(size.width, size.height))
        private var frame = 0L
        fun pump() {
            repeat(20) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                scene.render(canvas, frame++ * 16_000_000L)
                scope.runCurrent()
            }
        }
        fun nodes() = owners.flatMap { it.getAllSemanticsNodes(true) }
        fun text(label: String) = nodes().last { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
        fun tap(label: String) {
            val point = text(label).boundsInWindow.center
            assertTrue(point.y in 0f..scene.size!!.height.toFloat(), "$label must be reachable in the window")
            scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Touch)
            pump()
        }
        fun scrollOptions() {
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(120f, 160f),
                scrollDelta = Offset(0f, 200f), type = PointerType.Mouse)
            pump()
        }
        override fun close() = scene.close()
    }

    @Test fun timerOptionsAndCustomInputWorkInPortraitAndShortLandscape() = runTest {
        for (size in listOf(IntSize(320, 640), IntSize(780, 390))) {
            var minutes by mutableStateOf(0)
            var afterCurrent by mutableStateOf(false)
            var cancelled = false
            SceneHarness(this, size, fontScale = 1.3f).use { harness ->
                harness.scene.setContent {
                    MaterialTheme {
                        SleepTimerOptionsContent(minutes, afterCurrent, fullscreen = size.width > size.height,
                            onMinutes = { minutes = it; afterCurrent = false },
                            onAfterCurrent = { afterCurrent = true; minutes = 0 },
                            onDismiss = { cancelled = true })
                    }
                }
                harness.pump()
                harness.tap("播完当前")
                assertTrue(afterCurrent)
                harness.tap("30分钟后")
                assertEquals(30, minutes)
                assertFalse(afterCurrent)
                if (size.width > size.height) harness.scrollOptions()
                harness.tap("90分钟后")
                assertEquals(90, minutes)
                harness.tap("自定义")
                val field = harness.nodes().last { it.config.contains(SemanticsActions.SetText) }
                field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("0"))
                harness.pump()
                harness.tap("开启定时")
                assertEquals(90, minutes, "Invalid durations must not replace the active timer")
                field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("45"))
                harness.pump()
                harness.tap("开启定时")
                assertEquals(45, minutes)
                harness.tap("返回")
                harness.tap("取消")
                assertTrue(cancelled)
                assertEquals(45, minutes, "Dismissing the drawer must preserve the timer")
            }
        }
    }

    @Test fun audioReturnActionReceivesTouchAboveThePlayerGestureLayer() = runTest {
        for (size in listOf(IntSize(320, 240), IntSize(780, 390))) {
            var returned = false
            var videoTaps = 0
            SceneHarness(this, size, fontScale = 1.3f).use { harness ->
                harness.scene.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                                detectTapGestures { videoTaps++ }
                            })
                            AudioModeContent(null, "测试影片") {
                                AudioReturnButton { returned = true }
                            }
                        }
                    }
                }
                harness.pump()
                if (size.width < size.height * 2) assertTrue(
                    harness.text("返回视频").boundsInWindow.bottom <= size.height - 88,
                    "Audio actions must stay above the inline timeline and transport controls",
                )
                harness.tap("返回视频")
                assertTrue(returned)
                assertEquals(0, videoTaps)
            }
        }
    }
}
