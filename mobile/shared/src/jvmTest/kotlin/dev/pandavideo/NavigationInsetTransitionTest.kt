package dev.pandavideo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.pandavideo.app.NavigationDestinationContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Measures real Compose layout while the outgoing actor and incoming player coexist. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class NavigationInsetTransitionTest {
    @Test fun actorKeepsItsSafeAreaUntilExitFinishes() = verifyTransition(legacyParentInset = false)

    // Negative control reproduces the old route-dependent parent resize with the same animation.
    @Test fun legacyParentInsetReproducesTheUpwardJump() = verifyTransition(legacyParentInset = true)

    private fun verifyTransition(legacyParentInset: Boolean) = runTest {
        val scene = CanvasLayersComposeScene(size = IntSize(390, 844), coroutineContext = coroutineContext)
        val canvas = Canvas(ImageBitmap(390, 844))
        val route = mutableStateOf("actor")
        val positions = mutableMapOf<String, Float>()
        var frame = 0L
        fun render() {
            Snapshot.sendApplyNotifications()
            runCurrent()
            scene.render(canvas, frame++ * 16_000_000L)
            runCurrent()
        }
        try {
            scene.setContent {
                Box(Modifier.fillMaxSize().padding(top =
                    if (legacyParentInset && route.value != "player") 59.dp else 0.dp)) {
                    AnimatedContent(
                        targetState = route.value,
                        transitionSpec = {
                            val enter = if (targetState == "player")
                                slideInHorizontally(tween(300)) { it } else EnterTransition.None
                            val exit = if (initialState == "player")
                                slideOutHorizontally(tween(300)) { it } else ExitTransition.None
                            enter togetherWith exit
                        },
                    ) { destination ->
                        val body: @androidx.compose.runtime.Composable () -> Unit = {
                            DisposableEffect(destination) {
                                onDispose { positions.remove(destination) }
                            }
                            Box(Modifier.fillMaxSize().onGloballyPositioned {
                                positions[destination] = it.positionInRoot().y
                            })
                        }
                        if (legacyParentInset || destination == "player") body()
                        else NavigationDestinationContent(59.dp, body)
                    }
                }
            }
            repeat(25) { render() }
            assertEquals(59f, positions["actor"])
            route.value = "player"
            var overlappingFrames = 0
            repeat(25) {
                render()
                if ("actor" in positions && "player" in positions) {
                    overlappingFrames++
                    assertEquals(if (legacyParentInset) 0f else 59f, positions["actor"],
                        "The actor header must retain its top inset during pop")
                    assertEquals(0f, positions["player"], "Player owns its own insets")
                }
            }
            assertTrue(overlappingFrames > 0, "Must inspect frames before the outgoing page is disposed")
            assertEquals("player", route.value)
        } finally {
            scene.close()
        }
    }
}
