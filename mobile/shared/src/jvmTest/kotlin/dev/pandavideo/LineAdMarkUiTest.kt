package dev.pandavideo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.player.PlaybackState
import dev.pandavideo.data.user.LineAdStore
import dev.pandavideo.ui.player.FullscreenEpisodeDrawer
import dev.pandavideo.ui.player.LineAdMarkDialog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class LineAdMarkUiTest {
    @Test fun longPressMarksAndCancelsWithoutSwitchingLineOrClosingDrawer() = runTest {
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
        val canvas = Canvas(ImageBitmap(844, 390))
        val page = "https://fixture.test/title/1"
        val episodes = listOf(Episode("a", "1", 1, "PL"), Episode("b", "1", 2, "WJ"))
        val store = LineAdStore(FakeStorage())
        var target by mutableStateOf<Episode?>(null)
        var lineSelections = 0
        var dismissals = 0
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
        fun action(value: String, longPress: Boolean = false) {
            val bounds = nodes().filter {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
            }.map { it.boundsInRoot }
            assertTrue(bounds.isNotEmpty(), "Missing UI text: $value")
            val node = assertNotNull(nodes().filter {
                bounds.any { bounds -> it.boundsInRoot.contains(bounds.center) } &&
                    if (longPress) it.config.getOrNull(SemanticsActions.OnLongClick) != null
                    else it.config.getOrNull(SemanticsActions.OnClick) != null
            }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height })
            val callback = if (longPress) node.config.getOrNull(SemanticsActions.OnLongClick)?.action
                else node.config.getOrNull(SemanticsActions.OnClick)?.action
            assertTrue(assertNotNull(callback).invoke())
            render()
        }
        try {
            scene.setContent {
                MaterialTheme {
                    val marks by store.marks.collectAsState()
                    FullscreenEpisodeDrawer(true,
                        PlaybackState(info = PlaybackInfo("测试", "", "hls", episodes, "a", page)),
                        onDismiss = { dismissals++ }, onLineSelected = { lineSelections++ },
                        onEpisodeSelected = { error("Marking must not select an episode") },
                        adMarkedLines = marks.let { episodes.filter { store.hasAds(page, it) }.map { it.line }.toSet() },
                        onLineLongPress = { line -> target = episodes.first { it.line == line } })
                    target?.let {
                        LineAdMarkDialog(store, page, it, onDismiss = { target = null },
                            onFailure = { error("Persistence failed") })
                    }
                }
            }
            render()
            action("WJ", longPress = true)
            action("返回")
            assertFalse(store.hasAds(page, episodes[1]))
            action("WJ", longPress = true)
            action("标记有广告")
            assertTrue(store.hasAds(page, episodes[1]))
            assertNotNull(text("待测速 · 有广告"))
            action("WJ", longPress = true)
            action("取消广告标记")
            assertFalse(store.hasAds(page, episodes[1]))
            assertNotNull(text("WJ"))
            assertEquals(0, lineSelections)
            assertEquals(0, dismissals)
        } finally { scene.close() }
    }
}
