import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.pandavideo.app.PandaVideoApp
import dev.pandavideo.core.platform.DesktopPlatform
import dev.pandavideo.core.platform.DesktopWindowController

fun main() {
    // Playback controls have their own layout outside JFXPanel. Menus can extend over the
    // video, so host popups in separate windows that receive mouse input independently.
    // This must be configured before Compose initializes, including packaged DMG launches.
    System.setProperty("compose.layers.type", "WINDOW")
    desktopApplication()
}

private fun desktopApplication() = application {
    val windowState = rememberWindowState(width = 1280.dp, height = 860.dp)
    val platform = remember(windowState) {
        DesktopPlatform(object : DesktopWindowController {
            override fun setFullscreen(enabled: Boolean) {
                windowState.placement =
                    if (enabled) WindowPlacement.Fullscreen else WindowPlacement.Floating
            }
        })
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "熊猫视频",
        icon = painterResource("pandavideo.png"),
        state = windowState,
    ) {
        PandaVideoApp(platform)
    }
}
