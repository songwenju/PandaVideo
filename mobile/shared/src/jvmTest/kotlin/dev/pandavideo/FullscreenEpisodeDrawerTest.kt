package dev.pandavideo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.LineProbe
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.player.PlaybackState
import dev.pandavideo.ui.player.FullscreenEpisodeDrawer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Renders the production drawer and exercises its real semantics, without online sources. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class FullscreenEpisodeDrawerTest {
    @Test fun selectedEpisodeIsVisibleAndLineSwitchKeepsDrawerOpenUntilEpisodeSelection() = runTest {
        val owners = mutableListOf<SemanticsOwner>()
val context = object : PlatformContext by PlatformContext.Empty() {
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
            }
        }
        val scene = CanvasLayersComposeScene(size = IntSize(844, 390),
            coroutineContext = coroutineContext, platformContext = context)
        val bitmap = ImageBitmap(844, 390)
        val canvas = Canvas(bitmap)
        val episodes = (1..4).flatMap { line ->
            (1..100).map { Episode("$line-$it", "$it", line, "线路 $line") }
        }
        val visible = mutableStateOf(true)
        val state = mutableStateOf(PlaybackState(info = PlaybackInfo(
            "固定剧集", "", "hls", episodes, "1-75", "https://fixture.test",
            lineProbes = mapOf(1 to LineProbe(300, true), 2 to LineProbe(10, true),
                3 to LineProbe(available = false), 4 to LineProbe()))))
        var requestedLine: Int? = null
        var requestedEpisode: Episode? = null
        var frame = 0L
        fun render() {
            repeat(20) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                scene.render(canvas, frame++ * 16_000_000L)
                runCurrent()
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descendants(node: SemanticsNode): List<SemanticsNode> =
                listOf(node) + node.children.flatMap(::descendants)
            return owners.flatMap { descendants(it.rootSemanticsNode) }
        }
        fun text(value: String) = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        fun click(node: SemanticsNode) {
            val clickable = nodes().filter {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.boundsInRoot.contains(node.boundsInRoot.center)
            }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
            assertTrue(assertNotNull(clickable?.config?.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            render()
        }
        try {
            scene.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                        FullscreenEpisodeDrawer(visible.value, state.value,
                            onDismiss = { visible.value = false },
                            onLineSelected = { requestedLine = it },
                            onEpisodeSelected = { requestedEpisode = it; visible.value = false })
                    }
                }
            }
            render()
            assertTrue(assertNotNull(text("线路 2")).boundsInRoot.left <
                assertNotNull(text("线路 1")).boundsInRoot.left, "Fastest route must render first")
            assertFalse(text("线路 3") != null, "Confirmed failures must not render")
            assertNotNull(text("线路 4"), "Uncertain routes remain selectable")
            assertNotNull(text("10ms"), "Fullscreen must show the same measured latency as details")
            assertNotNull(text("300ms"))
            assertNotNull(text("待测速"))
            val selected = assertNotNull(text("75"))
            assertTrue(selected.config.getOrNull(SemanticsProperties.Selected) == true)
            assertTrue(selected.boundsInRoot.top >= 0 && selected.boundsInRoot.bottom <= 390)
            System.getenv("PANDAVIDEO_DRAWER_SCREENSHOT")?.let {
                File(it).writeBytes(Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData()!!.bytes)
            }
            click(assertNotNull(text("线路 2")))
            assertEquals(2, requestedLine)
            assertTrue(visible.value, "A line change must keep the picker open")
            click(assertNotNull(text("1")))
            assertEquals("2-1", requestedEpisode?.path)
            assertFalse(visible.value, "Selecting an episode closes the picker")

            visible.value = true
            render()
            click(assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭选集") == true
            }))
            assertFalse(visible.value, "The close button dismisses the picker")

            visible.value = true
            render()
            click(assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("收起选集") == true
            }))
            assertFalse(visible.value, "The scrim dismisses the picker")
        } finally { scene.close() }
    }

    @Test fun longEpisodeNamesGetWiderCells() = runTest {
        val owners = mutableListOf<SemanticsOwner>()
        val context = object : PlatformContext by PlatformContext.Empty() {
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
            }
        }
        val scene = CanvasLayersComposeScene(size = IntSize(844, 390),
            coroutineContext = coroutineContext, platformContext = context)
        val bitmap = ImageBitmap(844, 390)
        val canvas = Canvas(bitmap)
        val state = PlaybackState(info = PlaybackInfo(
            "固定剧集", "", "hls",
            listOf(Episode("a", "HD 1080P", 1), Episode("b", "HD 720P", 1), Episode("c", "1", 1)),
            "c", "https://fixture.test"))
        var frame = 0L
        fun render() {
            repeat(20) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                scene.render(canvas, frame++ * 16_000_000L)
                runCurrent()
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descendants(node: SemanticsNode): List<SemanticsNode> =
                listOf(node) + node.children.flatMap(::descendants)
            return owners.flatMap { descendants(it.rootSemanticsNode) }
        }
        fun text(value: String) = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        fun cellOf(label: String): SemanticsNode {
            val labelBounds = assertNotNull(text(label)).boundsInRoot
            // The dismiss scrim also covers the label; the cell is the smallest clickable node.
            return assertNotNull(nodes().filter { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.boundsInRoot.contains(labelBounds.center)
            }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height })
        }
        try {
            scene.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                        FullscreenEpisodeDrawer(true, state,
                            onDismiss = {}, onLineSelected = {}, onEpisodeSelected = {})
                    }
                }
            }
            render()
            val shortCell = cellOf("1")
            val longCell = cellOf("HD 1080P")
            assertTrue(longCell.boundsInRoot.width > shortCell.boundsInRoot.width * 1.5f,
                "A quality-style episode name must span two grid cells instead of being truncated " +
                    "(long=${longCell.boundsInRoot.width}, short=${shortCell.boundsInRoot.width})")
        } finally { scene.close() }
    }

    @Test fun narrowLandscapeAndLargeFontsShowCompleteEpisodeLabelsAndSourceNames() = runTest {
        for (label in listOf("HD中字", "第十二集：漫长的旅途与重新启程（高清完整版）")) {
            val owners = mutableListOf<SemanticsOwner>()
            val context = object : PlatformContext by PlatformContext.Empty() {
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
                }
            }
            val size = IntSize(640, 320)
            val scene = CanvasLayersComposeScene(size = size, density = Density(1f, 1.5f),
                coroutineContext = coroutineContext, platformContext = context)
            val canvas = Canvas(ImageBitmap(size.width, size.height))
            var frame = 0L
            fun nodes(): List<SemanticsNode> {
                fun descendants(node: SemanticsNode): List<SemanticsNode> =
                    listOf(node) + node.children.flatMap(::descendants)
                return owners.flatMap { descendants(it.rootSemanticsNode) }
            }
            fun text(value: String) = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
            }
            try {
                scene.setContent {
                    MaterialTheme {
                        FullscreenEpisodeDrawer(true, PlaybackState(info = PlaybackInfo(
                            "测试", "", "hls", listOf(Episode("a", label, 1)), "a", "https://fixture.test",
                            source = "Pomo · 示例来源", lineProbes = mapOf(1 to LineProbe(42, true)))),
                            onDismiss = {}, onLineSelected = {}, onEpisodeSelected = {})
                    }
                }
                repeat(20) {
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    scene.render(canvas, frame++ * 16_000_000L)
                    runCurrent()
                }
                assertNotNull(text("示例来源 · 线路 1"), "Fallback source identity must match details")
                assertNotNull(text("42ms"))
                val episode = assertNotNull(text(label))
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(assertNotNull(episode.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
                assertFalse(layouts.single().hasVisualOverflow,
                    "Episode label '$label' overflows: size=${layouts.single().size}, " +
                        "lines=${layouts.single().lineCount}, width=${layouts.single().didOverflowWidth}, " +
                        "height=${layouts.single().didOverflowHeight}, " +
                        "fontScale=${layouts.single().layoutInput.density.fontScale}, " +
                        "constraints=${layouts.single().layoutInput.constraints}")
                assertTrue(episode.boundsInRoot.top >= 0 && episode.boundsInRoot.bottom <= size.height)
                if (label.length > 5) assertTrue(layouts.single().lineCount > 1, "Long labels must wrap")
            } finally { scene.close() }
        }
    }
}
