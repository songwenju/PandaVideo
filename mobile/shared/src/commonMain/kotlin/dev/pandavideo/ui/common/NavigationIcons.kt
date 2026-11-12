package dev.pandavideo.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Shared 24 dp navigation artwork, tinted by the navigation item's selected state. */
internal object NavigationIcons {
    val Refresh = outline("Refresh") {
        moveTo(20f, 10f)
        curveTo(19f, 5f, 14f, 2.5f, 9.5f, 4.2f)
        curveTo(5f, 5.8f, 2.8f, 10.8f, 4.5f, 15.2f)
        curveTo(6.2f, 19.7f, 11.4f, 21.5f, 15.8f, 19.4f)
        curveTo(18f, 18.4f, 19.5f, 16.4f, 20f, 14f)
        moveTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f)
    }
    val Cast = outline("Cast") {
        moveTo(3f, 7f); lineTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 18f); lineTo(13f, 18f)
        moveTo(3f, 12f); curveTo(6.9f, 12f, 10f, 15.1f, 10f, 19f)
        moveTo(3f, 16f); curveTo(4.7f, 16f, 6f, 17.3f, 6f, 19f)
        moveTo(3f, 19f); lineTo(3.1f, 19f)
    }
    val Close = outline("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }
    val Delete = outline("Delete") {
        moveTo(4f, 6f); lineTo(20f, 6f)
        moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
        moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f)
        moveTo(10f, 10f); lineTo(10f, 17f)
        moveTo(14f, 10f); lineTo(14f, 17f)
    }
    val Download = outline("Download") {
        moveTo(12f, 3f); lineTo(12f, 15f)
        moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
        moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f)
    }
    val Check = outline("Check") {
        moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f)
    }
    val Poster = outline("Poster") {
        moveTo(4f, 3f); lineTo(20f, 3f); lineTo(20f, 21f); lineTo(4f, 21f); close()
        circle(9f, 8f, 1.5f)
        moveTo(4f, 17f); lineTo(9f, 12f); lineTo(12f, 15f); lineTo(15f, 11f); lineTo(20f, 17f)
    }
    val Back = outline("Back") {
        moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
    }
    val Play = ImageVector.Builder("Play", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 4f); lineTo(20f, 12f); lineTo(8f, 20f); close()
        }
    }.build()
    val Pause = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 4f); lineTo(10f, 4f); lineTo(10f, 20f); lineTo(6f, 20f); close()
            moveTo(14f, 4f); lineTo(18f, 4f); lineTo(18f, 20f); lineTo(14f, 20f); close()
        }
    }.build()
    val Lock = outline("Lock") {
        moveTo(7f, 10f); lineTo(7f, 7f)
        curveTo(7f, 0.5f, 17f, 0.5f, 17f, 7f); lineTo(17f, 10f)
        moveTo(5f, 10f); lineTo(19f, 10f); lineTo(19f, 21f); lineTo(5f, 21f); close()
        moveTo(12f, 14f); lineTo(12f, 17f)
    }
    val Unlock = outline("Unlock") {
        moveTo(7f, 10f); lineTo(7f, 7f)
        curveTo(7f, 0.5f, 17f, 0.5f, 17f, 7f)
        moveTo(5f, 10f); lineTo(19f, 10f); lineTo(19f, 21f); lineTo(5f, 21f); close()
        moveTo(12f, 14f); lineTo(12f, 17f)
    }
    val Next = outline("Next") {
        moveTo(7f, 6f); lineTo(13f, 12f); lineTo(7f, 18f); close()
        moveTo(16.5f, 6f); lineTo(16.5f, 18f)
    }
    val Fullscreen = outline("Fullscreen") {
        moveTo(9f, 4f); lineTo(4f, 4f); lineTo(4f, 9f)
        moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(4f, 15f); lineTo(4f, 20f); lineTo(9f, 20f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
    }
    val Pip = outline("Pip") {
        moveTo(4f, 7f); lineTo(20f, 7f); lineTo(20f, 17f); lineTo(4f, 17f); close()
        moveTo(12f, 12.5f); lineTo(18f, 12.5f); lineTo(18f, 16f); lineTo(12f, 16f); close()
    }
    val Discover = outline("Discover") {
        circle(12f, 12f, 9f)
        moveTo(15.8f, 8.2f)
        lineTo(13.3f, 13.3f)
        lineTo(8.2f, 15.8f)
        lineTo(10.7f, 10.7f)
        close()
    }
    val Browse = outline("Browse") {
        tile(3f, 3f)
        tile(14f, 3f)
        tile(3f, 14f)
        tile(14f, 14f)
    }
    val Search = outline("Search") {
        circle(10.8f, 10.8f, 6.8f)
        moveTo(15.7f, 15.7f)
        lineTo(20.5f, 20.5f)
    }
    val Hot = outline("Hot") {
        moveTo(13f, 2.5f)
        curveTo(14f, 7f, 9f, 8f, 9f, 11f)
        curveTo(9f, 12f, 10f, 13f, 11f, 13.5f)
        curveTo(11f, 11.5f, 14f, 10f, 15f, 8f)
        curveTo(18f, 11f, 20f, 13f, 20f, 15.5f)
        curveTo(20f, 20f, 16f, 21.5f, 12f, 21.5f)
        curveTo(8f, 21.5f, 4f, 19f, 4f, 15f)
        curveTo(4f, 10f, 8f, 6f, 13f, 2.5f)
        close()
    }
    val Profile = outline("Profile") {
        circle(12f, 8.2f, 3.7f)
        moveTo(5f, 19.6f)
        curveTo(5.7f, 15.8f, 8.5f, 14.2f, 12f, 14.2f)
        curveTo(15.5f, 14.2f, 18.3f, 15.8f, 19f, 19.6f)
    }
    val Heart = outline("Heart") {
        heartPath()
    }
    val HeartFilled = ImageVector.Builder("HeartFilled", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathBuilder = { heartPath() })
    }.build()
    val History = outline("History") {
        moveTo(3.4f, 12f)
        curveTo(3.4f, 7.25f, 7.25f, 3.4f, 12f, 3.4f)
        curveTo(16.75f, 3.4f, 20.6f, 7.25f, 20.6f, 12f)
        curveTo(20.6f, 16.75f, 16.75f, 20.6f, 12f, 20.6f)
        curveTo(9.1f, 20.6f, 6.55f, 19.15f, 5.1f, 16.9f)
        moveTo(3.4f, 8.4f)
        lineTo(3.4f, 12f)
        lineTo(7f, 12f)
        moveTo(12f, 8f)
        lineTo(12f, 12f)
        lineTo(14.9f, 13.8f)
    }
    val Chevron = outline("Chevron") {
        moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
    }
    val Edit = outline("Edit") {
        moveTo(14.6f, 5.4f); lineTo(18.6f, 9.4f)
        moveTo(13.1f, 6.9f); lineTo(6.1f, 13.9f); lineTo(5.1f, 18.9f); lineTo(10.1f, 17.9f); lineTo(17.1f, 10.9f)
    }
    val Volume = outline("Volume") {
        moveTo(4f, 9f); lineTo(8f, 9f); lineTo(12.5f, 5f); lineTo(12.5f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close()
        moveTo(15.5f, 9.2f)
        curveTo(17f, 10.6f, 17f, 13.4f, 15.5f, 14.8f)
        moveTo(17.8f, 7f)
        curveTo(20.2f, 9.6f, 20.2f, 14.4f, 17.8f, 17f)
    }
    /** Headphones identify audio-only playback independently of volume controls. */
    val Headphones = outline("Headphones") {
        // A rounded headband and inward-tilted capsule earcups stay legible at 22 dp.
        moveTo(3.9f, 14.3f)
        curveTo(3.6f, 13.5f, 3.5f, 12.7f, 3.5f, 11.9f)
        curveTo(3.5f, 7.1f, 7.2f, 3.5f, 12f, 3.5f)
        curveTo(16.8f, 3.5f, 20.5f, 7.1f, 20.5f, 11.9f)
        curveTo(20.5f, 12.7f, 20.4f, 13.5f, 20.1f, 14.3f)
        moveTo(5.1f, 13.1f)
        curveTo(6.2f, 12.8f, 7f, 13.4f, 7.2f, 14.4f); lineTo(8.3f, 18.4f)
        curveTo(8.6f, 19.5f, 8.1f, 20.3f, 7.1f, 20.6f)
        curveTo(6.1f, 20.9f, 5.2f, 20.3f, 4.9f, 19.3f); lineTo(3.8f, 15.3f)
        curveTo(3.5f, 14.3f, 4.1f, 13.4f, 5.1f, 13.1f); close()
        moveTo(18.9f, 13.1f)
        curveTo(17.8f, 12.8f, 17f, 13.4f, 16.8f, 14.4f); lineTo(15.7f, 18.4f)
        curveTo(15.4f, 19.5f, 15.9f, 20.3f, 16.9f, 20.6f)
        curveTo(17.9f, 20.9f, 18.8f, 20.3f, 19.1f, 19.3f); lineTo(20.2f, 15.3f)
        curveTo(20.5f, 14.3f, 19.9f, 13.4f, 18.9f, 13.1f); close()
    }
    val Brightness = outline("Brightness") {
        moveTo(12f, 2.5f); lineTo(12f, 5f)
        moveTo(12f, 19f); lineTo(12f, 21.5f)
        moveTo(2.5f, 12f); lineTo(5f, 12f)
        moveTo(19f, 12f); lineTo(21.5f, 12f)
        moveTo(5.3f, 5.3f); lineTo(7f, 7f)
        moveTo(17f, 17f); lineTo(18.7f, 18.7f)
        moveTo(18.7f, 5.3f); lineTo(17f, 7f)
        moveTo(7f, 17f); lineTo(5.3f, 18.7f)
        circle(12f, 12f, 3.6f)
    }
    val Moon = outline("Moon") {
        moveTo(19.8f, 14.2f)
        curveTo(18.6f, 18.8f, 13.8f, 21.3f, 9.2f, 19.8f)
        curveTo(4.6f, 18.3f, 2.3f, 13.3f, 4.1f, 8.9f)
        curveTo(5f, 6.7f, 6.9f, 4.9f, 9.2f, 4.2f)
        curveTo(7.8f, 7.1f, 8.5f, 10.6f, 10.9f, 12.8f)
        curveTo(13.3f, 15f, 16.9f, 15.6f, 19.8f, 14.2f)
        close()
    }
    val Menu = outline("Menu") {
        moveTo(4f, 5f); lineTo(20f, 5f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 19f); lineTo(15f, 19f)
    }

    fun forRoute(route: String): ImageVector = when (route) {
        "discover" -> Discover
        "browse" -> Browse
        "hot" -> Hot
        "profile" -> Profile
        else -> error("Unknown navigation destination")
    }

    private fun PathBuilder.heartPath() {
        moveTo(12f, 20.2f)
        curveTo(12f, 20.2f, 3.9f, 14.9f, 3.9f, 9.6f)
        curveTo(3.9f, 7.1f, 5.9f, 5.1f, 8.3f, 5.1f)
        curveTo(9.9f, 5.1f, 11.2f, 6f, 12f, 7.3f)
        curveTo(12.8f, 6f, 14.1f, 5.1f, 15.7f, 5.1f)
        curveTo(18.1f, 5.1f, 20.1f, 7.1f, 20.1f, 9.6f)
        curveTo(20.1f, 14.9f, 12f, 20.2f, 12f, 20.2f)
        close()
    }

    private fun outline(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
        }.build()

    private fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
        val c = radius * 0.55228475f
        moveTo(x + radius, y)
        curveTo(x + radius, y + c, x + c, y + radius, x, y + radius)
        curveTo(x - c, y + radius, x - radius, y + c, x - radius, y)
        curveTo(x - radius, y - c, x - c, y - radius, x, y - radius)
        curveTo(x + c, y - radius, x + radius, y - c, x + radius, y)
        close()
    }

    private fun PathBuilder.tile(x: Float, y: Float) {
        val size = 7f
        val radius = 1.5f
        val c = radius * 0.55228475f
        moveTo(x + radius, y)
        lineTo(x + size - radius, y)
        curveTo(x + size - radius + c, y, x + size, y + radius - c, x + size, y + radius)
        lineTo(x + size, y + size - radius)
        curveTo(x + size, y + size - radius + c, x + size - radius + c, y + size, x + size - radius, y + size)
        lineTo(x + radius, y + size)
        curveTo(x + radius - c, y + size, x, y + size - radius + c, x, y + size - radius)
        lineTo(x, y + radius)
        curveTo(x, y + radius - c, x + radius - c, y, x + radius, y)
        close()
    }
}
