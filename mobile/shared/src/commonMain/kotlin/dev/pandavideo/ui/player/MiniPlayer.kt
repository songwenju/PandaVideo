package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * outside the player screen. Audio mode continues in this bar; video stays paused.
 * The close action releases the session.
 */
@Composable
internal fun MiniPlayerBar(model: AppState, onOpen: () -> Unit) {
    val session = model.session ?: return
    val player by session.controller.state.collectAsState()
    val playback by session.state.collectAsState()
    var sleepTimerRequested by remember(session) { mutableStateOf(false) }
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
                // Two text lines must also fit when the system font scale is enlarged.
                .heightIn(min = 54.dp)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(cover, title, Modifier.width(38.dp).height(50.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontSize = 13.sp, lineHeight = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!episode.isNullOrBlank() || playback.audioOnly) Text(
                    if (playback.audioOnly) listOfNotNull("音频模式", episode?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    else episode.orEmpty(),
                    fontSize = 11.sp, lineHeight = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(
                onClick = {
                    model.platform.haptics.tick()
                    if (player.wantsPlay) session.controller.pause() else {
                        if (!playback.audioOnly) {
                            onOpen()
                            session.setPlaybackVisible(true)
                        }
                        if (playback.finished) session.retry() else session.controller.play()
                    }
                },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    if (player.wantsPlay) NavigationIcons.Pause else NavigationIcons.Play,
                    contentDescription = if (player.wantsPlay) "暂停" else "播放",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!playback.audioOnly && session.controller.capabilities.audioOnly && info != null) PlayerAudioButton(
                session = session,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onOpen = { model.platform.haptics.tick() },
            )
            IconButton(
                onClick = {
                    model.platform.haptics.tick()
                    model.closePlayer()
                },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    NavigationIcons.Close, contentDescription = "关闭播放器",
                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (playback.audioOnly) FlowRow(
            Modifier.fillMaxWidth().padding(start = 60.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            AudioReturnButton(dark = false) {
                model.platform.haptics.tick()
                returnToVideo(session, onOpen)
            }
            PlayerSleepTimerButton(model, onClick = {
                model.platform.haptics.tick()
                sleepTimerRequested = true
            }, dark = false)
        }
    }
    if (sleepTimerRequested) PlayerSleepTimerSheet(model) { sleepTimerRequested = false }
}
