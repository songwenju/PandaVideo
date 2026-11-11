package dev.pandavideo.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.pandavideo.ui.common.NavigationIcons

/** Filled transport glyphs with consistent optical weight in the video control bar. */
internal object PlayerTransportIcons {
    val Next = ImageVector.Builder("PlayerNext", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(5f, 5f); lineTo(17f, 12f); lineTo(5f, 19f); close()
            moveTo(17f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(17f, 19f); close()
        }
    }.build()

    val Play = ImageVector.Builder("PlayerPlay", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 4f)
            curveTo(7f, 3.2f, 7.8f, 2.8f, 8.5f, 3.3f)
            lineTo(20f, 11.1f)
            curveTo(20.7f, 11.5f, 20.7f, 12.5f, 20f, 12.9f)
            lineTo(8.5f, 20.7f)
            curveTo(7.8f, 21.2f, 7f, 20.8f, 7f, 20f)
            close()
        }
    }.build()

    val Pause = NavigationIcons.Pause
}
