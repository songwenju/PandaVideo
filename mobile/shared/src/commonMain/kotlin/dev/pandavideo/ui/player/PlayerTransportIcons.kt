package dev.pandavideo.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
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

    /** Figma transport style: a round ring with a diagonal arrowhead above the numeral. */
    val Replay = ImageVector.Builder("PlayerReplay", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
            moveTo(3f, 12.5f)
            curveTo(3f, 17.47f, 7.03f, 21.5f, 12f, 21.5f)
            curveTo(16.97f, 21.5f, 21f, 17.47f, 21f, 12.5f)
            curveTo(21f, 7.53f, 16.97f, 3.5f, 12f, 3.5f)
            curveTo(9.51f, 3.5f, 7.26f, 4.51f, 5.64f, 6.14f)
            lineTo(4.2f, 7.58f)
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(2.8f, 8.98f)
            lineTo(3.2f, 3.38f)
            lineTo(8.4f, 8.58f)
            close()
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
