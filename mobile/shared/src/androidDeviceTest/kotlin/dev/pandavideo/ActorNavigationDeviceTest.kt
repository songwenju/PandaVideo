package dev.pandavideo

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Opt-in live UI acceptance via accessibility actions, including XR spatial windows. */
@RunWith(AndroidJUnit4::class)
class ActorNavigationDeviceTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (node == null) return emptyList()
            return listOf(node) + (0 until node.childCount).flatMap { walk(node.getChild(it)) }
        }
        return walk(automation.rootInActiveWindow)
    }

    private suspend fun waitText(text: String): AccessibilityNodeInfo = withTimeout(180_000) {
        while (true) {
            nodes().firstOrNull { !it.isEditable &&
                (it.text?.toString() == text || it.contentDescription?.toString() == text) }
                ?.let { return@withTimeout it }
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error("No matching node")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    @Test fun searchActorFilterWorksAndReturnToSearch() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("actorFlowLive") == "1")
        click(waitText("搜索影视或演员"))
        withTimeout(10_000) {
            while (true) {
                val input = nodes().firstOrNull { it.isEditable }
                if (input != null) {
                    assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "朱一龙")
                    }))
                    break
                }
                delay(200)
            }
        }
        click(waitText("搜索"))
        click(waitText("朱一龙"))
        println("ACTOR_FLOW actor_card_selected=true")
        waitText("参演作品")
        waitText("全部")
        assertTrue(nodes().none { it.text?.toString()?.let { text ->
            text.contains("资料：爱奇艺") || text.contains("完整演艺履历") ||
                text.contains("已隐藏确认无播放资源")
        } == true }, "The actor page must not expose source/crawling explanations")
        withTimeout(180_000) {
            while (nodes().none { it.text?.toString()?.let { text ->
                    text.startsWith("电影 ·") || text.startsWith("电视剧 ·")
                } == true }) {
                assertTrue(nodes().none { it.text?.toString() == "演员信息暂时无法加载" },
                    "Live actor source failed on device")
                delay(200)
            }
        }
        click(waitText("电视剧"))
        waitText("叛逆者")
        click(waitText("电影"))
        withTimeout(10_000) {
            while (nodes().none { it.text?.toString()?.startsWith("电影 ·") == true }) delay(200)
        }
        waitText("参演作品")
        click(waitText("返回"))
        waitText("演员")
        waitText("朱一龙")
        println("ACTOR_FLOW search=true identity=true works=true tv_filter=true movie_filter=true return=true")
    }
}
