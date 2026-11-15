package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons

/** Shared audio/video action drawer for the player and the minimized playback bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerMoreMenu(
    session: PlaybackSession,
    tint: Color,
    onInteraction: () -> Unit,
    onReturnToVideo: () -> Unit = {},
    onMenuVisibilityChanged: (Boolean) -> Unit = {},
) {
    val playback by session.state.collectAsState()
    var expanded by remember(session) { mutableStateOf(false) }
    IconButton(
        onClick = { onInteraction(); expanded = true; onMenuVisibilityChanged(true) },
        modifier = Modifier.size(44.dp),
    ) {
        Icon(NavigationIcons.More, "更多播放选项", Modifier.size(22.dp),
            tint = if (playback.audioOnly) Accent else tint)
    }
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false; onMenuVisibilityChanged(false) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("播放方式", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (playback.audioOnly) "当前正在听音频" else "当前正在看视频",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Accent.copy(alpha = 0.08f))
                    .clickable(role = Role.Button) {
                        onInteraction()
                        expanded = false
                        onMenuVisibilityChanged(false)
                        if (playback.audioOnly) {
                            // Open the video page before removing hidden-playback permission.
                            // Reversing this order would pause a running minimized audio session.
                            onReturnToVideo()
                            session.setPlaybackVisible(true)
                        }
                        session.setAudioOnly(!playback.audioOnly)
                    }.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    if (playback.audioOnly) NavigationIcons.Play else NavigationIcons.Headphones,
                    null, Modifier.size(26.dp), tint = Accent,
                )
                Column(Modifier.weight(1f)) {
                    Text(if (playback.audioOnly) "返回视频" else "听音频",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (playback.audioOnly) "恢复画面，接着当前进度看" else "锁屏或切到后台也能继续听",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                Icon(NavigationIcons.Chevron, null, Modifier.size(20.dp), tint = Accent)
            }
        }
    }
}
