package dev.pandavideo.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.Poster

/** Static artwork keeps audio playback inexpensive while giving the player a soft backdrop. */
@Composable
internal fun AudioModeBackdrop(cover: String?, title: String, showArtwork: Boolean) {
    BoxWithConstraints(
        Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).background(Color(0xFF1B2025)),
    ) {
        // Reuse the poster loader and its offline fallback. The enlarged, subdued artwork
        // remains decorative; only the foreground cover is announced by accessibility.
        Poster(cover, title, Modifier.fillMaxSize().graphicsLayer {
            scaleX = 1.2f
            scaleY = 1.2f
            alpha = 0.32f
        }.blur(40.dp).clearAndSetSemantics {})
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF141A20).copy(alpha = 0.62f))
            val radius = size.maxDimension * 0.8f
            drawRect(Brush.radialGradient(
                listOf(Accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.4f), radius = radius,
            ))
            drawRect(Brush.radialGradient(
                listOf(Color(0xFF6CB6B5).copy(alpha = 0.13f), Color.Transparent),
                center = Offset(size.width * 0.9f, size.height * 0.2f), radius = radius,
            ))
            drawRect(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.1f), Color.Transparent, Color.Black.copy(alpha = 0.4f)),
            ))
        }
        if (showArtwork) {
            // Reserve the existing toolbar and timeline rather than placing art behind
            // their hit targets. Size from the actual player bounds, including split panes.
            val artworkHeight = (maxHeight - 140.dp).coerceIn(48.dp, 176.dp)
            val compact = artworkHeight < 92.dp
            val horizontalPadding = if (maxWidth < 400.dp) 28.dp else 48.dp
            Row(
                Modifier.fillMaxSize().padding(
                    start = horizontalPadding, end = horizontalPadding, top = 52.dp, bottom = 88.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
            ) {
                Poster(
                    cover, title,
                    Modifier.height(artworkHeight).aspectRatio(2f / 3f)
                        .shadow(16.dp, RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
                    if (!compact) Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(NavigationIcons.Headphones, null, Modifier.size(14.dp), tint = Accent)
                        Text("听视频", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        title, color = Color.White, fontSize = if (compact) 14.sp else 17.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "锁屏或切到后台\n也能继续听",
                        color = Color.White.copy(alpha = 0.62f), fontSize = if (compact) 11.sp else 12.sp,
                        lineHeight = if (compact) 16.sp else 19.sp,
                    )
                }
            }
        }
    }
}
