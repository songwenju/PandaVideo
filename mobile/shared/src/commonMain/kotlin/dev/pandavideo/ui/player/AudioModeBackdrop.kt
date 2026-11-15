package dev.pandavideo.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.Poster

/** Static artwork keeps audio playback inexpensive while giving the player a soft backdrop. */
@Composable
internal fun AudioModeBackdrop(cover: String?, title: String) {
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
    }
}

/** Foreground actions live above the gesture layer so real touch input reaches the buttons. */
@Composable
internal fun AudioModeContent(cover: String?, title: String, actions: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 250.dp
        val artworkHeight = (maxHeight - 132.dp).coerceIn(40.dp, 160.dp)
        val horizontalPadding = if (compact) 12.dp else if (maxWidth < 400.dp) 16.dp else 40.dp
        Box(
            Modifier.fillMaxSize().padding(
                top = if (compact) 44.dp else 0.dp,
                bottom = if (compact) 88.dp else 0.dp,
            ),
        ) {
            // Center the measured artwork/action group rather than a full-width row.
            // Only inline players reserve extra space for their timeline and transport controls.
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = horizontalPadding)
                    .widthIn(max = 480.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
            ) {
                Poster(
                    cover, title,
                    Modifier.height(artworkHeight).aspectRatio(2f / 3f)
                        .shadow(16.dp, RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
                )
                Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在锁屏或切换后台时也能播放音频",
                        color = Color.White.copy(alpha = 0.62f), fontSize = if (compact) 11.sp else 13.sp,
                        lineHeight = if (compact) 16.sp else 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    actions()
                }
            }
        }
    }
}
