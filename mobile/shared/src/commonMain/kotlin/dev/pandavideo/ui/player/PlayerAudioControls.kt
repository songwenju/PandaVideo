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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons

/** Switches audio output in the current presentation; the callback provides local click feedback. */
@Composable
internal fun PlayerAudioButton(session: PlaybackSession, tint: Color, onOpen: () -> Unit = {}) {
    IconButton(onClick = {
        session.setAudioOnly(true)
        onOpen()
    }, modifier = Modifier.size(44.dp)) {
        Icon(NavigationIcons.Headphones, "听音频", Modifier.size(22.dp), tint = tint)
    }
}

/** Opens the video presentation before switching the native output back on, retaining paused intent. */
internal fun returnToVideo(session: PlaybackSession, onOpen: () -> Unit = {}) {
    onOpen()
    session.setPlaybackVisible(true)
    session.setAudioOnly(false)
}

@Composable
internal fun AudioReturnButton(dark: Boolean = true, onClick: () -> Unit) {
    AudioActionButton("返回视频", NavigationIcons.Play, dark = dark, onClick = onClick)
}

@Composable
internal fun PlayerSleepTimerButton(model: AppState, onClick: () -> Unit, dark: Boolean = true) {
    val remaining by model.sleepRemaining.collectAsState()
    val afterCurrent by model.sleepAfterCurrent.collectAsState()
    val label = when {
        afterCurrent -> "播完关闭"
        remaining > 0 -> "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} 后关闭"
        else -> "定时关闭"
    }
    AudioActionButton(label, NavigationIcons.History, dark, active = afterCurrent || remaining > 0,
        description = "定时关闭，$label", onClick = onClick)
}

@Composable
private fun AudioActionButton(
    label: String, icon: ImageVector, dark: Boolean, active: Boolean = false,
    description: String = label, onClick: () -> Unit,
) {
    val foreground = if (active) Accent else if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (dark) Color.Black.copy(alpha = .28f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = foreground)
        Text(label, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
