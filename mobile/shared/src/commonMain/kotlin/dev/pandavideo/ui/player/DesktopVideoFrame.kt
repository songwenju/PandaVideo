package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.core.model.nextEpisode
import dev.pandavideo.core.platform.PlayerSurface
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.core.player.PlaybackState
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.playbackTime

/**
 * Keeps desktop controls in sibling layout regions outside the native video panel.
 *
 * Interop blending can make Compose overlays visible over JFXPanel while native mouse
 * dispatch still targets the panel. Accessibility actions bypass that dispatch, so they
 * cannot establish mouse usability. No interactive control overlaps PlayerSurface here.
 */
@Composable
internal fun DesktopVideoFrame(
    session: PlaybackSession, player: PlayerState, state: PlaybackState,
    fullscreen: Boolean, title: String, modifier: Modifier,
    back: () -> Unit, home: () -> Unit, search: () -> Unit,
    toggleFullscreen: () -> Unit, openDetails: () -> Unit,
    openSkipSettings: () -> Unit = {},
) {
    var scrubbing by remember(player.mediaId) { mutableStateOf<Float?>(null) }
    var rateMenu by remember { mutableStateOf(false) }
    val error = player.error ?: state.error?.takeIf { state.info == null || player.phase == PlayerPhase.Idle }
    val busy = state.loading || player.phase in setOf(PlayerPhase.Preparing, PlayerPhase.Buffering, PlayerPhase.Ready)
    val next = state.info?.let(::nextEpisode)
    fun togglePlay() {
        when {
            player.phase == PlayerPhase.Ended || state.finished -> session.retry()
            player.wantsPlay -> session.controller.pause()
            else -> session.controller.play()
        }
    }
    Column(modifier.background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(back) {
                Icon(NavigationIcons.Back, if (fullscreen) "退出全屏" else "返回", tint = Color.White)
            }
            TextButton(home) { Text("返回首页", color = Color.White) }
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (state.info?.episodes?.groupingBy { it.line }?.eachCount()?.values?.any { it > 1 } == true) {
                IconButton(openSkipSettings) {
                    Icon(NavigationIcons.More, "播放设置", tint = Color.White)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // AppState presents the same single recovery dialog on desktop and mobile.
            if (error == null) {
                PlayerSurface(session.controller, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.fillMaxWidth().height(92.dp).padding(horizontal = 12.dp)) {
            Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(playbackTime(scrubbing?.toLong() ?: player.position), color = Color.White, fontSize = 11.sp)
                Slider(
                    value = scrubbing ?: player.position.coerceIn(0, player.duration.coerceAtLeast(1)).toFloat(),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        scrubbing?.let { session.seekTo(it.toLong()) }
                        scrubbing = null
                    },
                    valueRange = 0f..player.duration.coerceAtLeast(1).toFloat(),
                    enabled = error == null && player.duration > 0 && session.controller.capabilities.seeking,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                )
                Text(playbackTime(player.duration), color = Color.White, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(::togglePlay, enabled = state.info != null && error == null) {
                    Icon(if (player.wantsPlay) NavigationIcons.Pause else NavigationIcons.Play,
                        if (player.wantsPlay) "暂停" else "播放", tint = Color.White)
                }
                IconButton({ next?.let(session::select) }, enabled = next != null && !state.loading) {
                    Icon(NavigationIcons.Next, "下一集",
                        tint = if (next != null) Color.White else Color.Gray)
                }
                if (busy) Text(if (state.loading) "正在解析…" else "缓冲中…",
                    color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (session.controller.capabilities.playbackRate) PlayerRateControl(
                    rate = player.rate,
                    expanded = rateMenu,
                    onOpen = { rateMenu = true },
                    onDismiss = { rateMenu = false },
                    onSelect = session::setPlaybackRate,
                    enabled = error == null && state.info != null,
                )
                if (fullscreen) TextButton(openDetails) { Text("选集", color = Color.White) }
                if (session.controller.capabilities.fullscreen) IconButton(toggleFullscreen) {
                    Icon(if (fullscreen) NavigationIcons.FullscreenExit else NavigationIcons.Fullscreen,
                        if (fullscreen) "退出全屏" else "全屏", tint = Color.White)
                }
            }
        }
    }
}
