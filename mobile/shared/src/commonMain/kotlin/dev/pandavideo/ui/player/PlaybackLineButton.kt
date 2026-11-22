package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.LineProbe
import dev.pandavideo.ui.common.Accent

/** Uses the same source identity in inline, fullscreen and remote episode pickers. */
internal fun playbackLineName(episode: Episode, source: String): String =
    episode.lineName.takeIf(String::isNotBlank)
        ?: "${source.takeIf(String::isNotBlank)?.removePrefix("Pomo · ") ?: "播放"} · 线路 ${episode.line}"

/** Shared route presentation; measurements and manual reports never alter its click action. */
@Composable
internal fun PlaybackLineButton(
    name: String,
    probe: LineProbe?,
    selected: Boolean,
    marked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    supporting: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(Modifier.widthIn(min = 96.dp, max = 132.dp).heightIn(min = 48.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(if (selected) Accent else background)
        .semantics { this.selected = selected }
        .combinedClickable(enabled = enabled, role = Role.Tab,
            onLongClickLabel = if (marked) "取消广告标记" else "标记插播广告",
            onLongClick = onLongClick, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else foreground,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val speed = when {
            probe?.available == false -> "不可用"
            probe?.elapsedMs != null -> "${probe.elapsedMs}ms"
            else -> "待测速"
        }
        Text(speed + if (marked) " · 有广告" else "", fontSize = 12.sp, lineHeight = 14.sp,
            color = if (selected) Color.White.copy(alpha = .85f) else if (marked) Accent else supporting)
    }
}
