package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.Poster

/**
 * Compact floating bar shown above the bottom tabs while a playback session stays alive
 * outside the player screen. Tapping the body reopens the paused player; the play action
 * reopens and explicitly resumes it, while the close action releases the session.
 */
@Composable
internal fun MiniPlayerBar(model: AppState, onOpen: () -> Unit) {
    val session = model.session ?: return
    val player by session.controller.state.collectAsState()
    val playback by session.state.collectAsState()
    val info = playback.info
    val cover = model.playingCatalog?.let(model::poster)
        ?: model.playingSearch?.let(model::poster)
        ?: model.coverFor(info?.title, model.playingCover)
    val title = info?.title
        ?: model.playingCatalog?.title
        ?: model.playingSearch?.title
        ?: "正在播放"
    val episode = info?.episodes?.find { it.path == info.selected }?.label
    val fraction = if (player.duration > 0) {
        (player.position.toFloat() / player.duration).coerceIn(0f, 1f)
    } else 0f
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Accent))
        }
        Row(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 12.dp, end = 4.dp)
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(cover, title, Modifier.width(38.dp).height(50.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!episode.isNullOrBlank()) Text(
                    if (playback.ended) "${playback.countdownSeconds} 秒后下一集" else episode,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (playback.ended) TextButton({ session.dismissEnded() }) { Text("取消") }
            IconButton(
                onClick = {
                    model.platform.haptics.tick()
                    if (player.wantsPlay) session.controller.pause() else {
                        onOpen()
                        session.setPlaybackVisible(true)
                        session.controller.play()
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (player.wantsPlay) NavigationIcons.Pause else NavigationIcons.Play,
                    contentDescription = if (player.wantsPlay) "暂停" else "播放",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = {
                    model.platform.haptics.tick()
                    model.closePlayer()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Text(
                    "×", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
