package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.Poster
import dev.pandavideo.ui.common.playbackTime

internal val CastBackground = Color(0xFF232630)
internal val CastSurface = Color(0xFF353946)
internal val CastBlue = Color(0xFF5B8DFF)
private val CastMuted = Color(0xFFAAB0BD)
private val CastGreen = Color(0xFF5EE392)
private val CastRed = Color(0xFFF04B5E)

/** Compact transport remote styled consistently with the discovery and handoff screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DlnaRemoteScreen(
    casting: DlnaCasting, title: String, episode: String, cover: String?,
    canSelectEpisode: Boolean, onEpisodes: () -> Unit, back: () -> Unit,
) {
    var scrub by remember(casting.device) { mutableStateOf<Float?>(null) }
    CastPage(back, remote = true) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Surface(color = CastSurface.copy(alpha = .7f), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(CastGreen, CircleShape))
                    Text("已连接 · ${casting.device?.name.orEmpty()}", color = CastGreen, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Reuse the catalog's decoded artwork, bundled covers and anti-hotlink headers.
                // A bare Coil URL can leave this thumbnail empty even when the catalog showed it.
                if (!cover.isNullOrBlank()) Poster(cover, title,
                    Modifier.size(width = 96.dp, height = 68.dp), contentScale = ContentScale.Crop)
                else Box(Modifier.size(68.dp).background(CastSurface, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(NavigationIcons.Cast, null, Modifier.size(30.dp), tint = CastBlue)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (episode.isNotBlank()) Text(episode, color = CastMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 5.dp))
                    Text("${playbackTime(scrub?.toLong() ?: casting.position)} / ${playbackTime(casting.duration)}",
                        color = CastMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
            Column {
                val sliderColors = SliderDefaults.colors(thumbColor = CastBlue, activeTrackColor = CastBlue,
                    inactiveTrackColor = CastSurface, disabledThumbColor = CastBlue,
                    disabledActiveTrackColor = CastBlue, disabledInactiveTrackColor = CastSurface)
                Slider(
                    value = scrub ?: casting.position.coerceIn(0, casting.duration.coerceAtLeast(1)).toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { casting.seek(it.toLong()) }; scrub = null },
                    valueRange = 0f..casting.duration.coerceAtLeast(1).toFloat(),
                    enabled = casting.duration > 0 && !casting.busy,
                    colors = sliderColors,
                    thumb = {
                        Box(Modifier.size(16.dp).background(
                            if (casting.duration > 0) CastBlue else CastMuted, CircleShape))
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(sliderState, Modifier.height(4.dp),
                            enabled = casting.duration > 0 && !casting.busy, colors = sliderColors,
                            thumbTrackGapSize = 0.dp, drawStopIndicator = null)
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "电视播放进度" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playbackTime(scrub?.toLong() ?: casting.position), color = CastMuted, fontSize = 12.sp)
                    Text(playbackTime(casting.duration), color = CastMuted, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                RemoteSeekButton(false, casting.duration > 0 && !casting.busy) {
                    casting.seek((casting.position - 10_000).coerceIn(0, casting.duration))
                }
                FilledIconButton(casting::togglePlay, enabled = !casting.busy,
                    modifier = Modifier.size(76.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CastSurface, contentColor = Color.White,
                        disabledContainerColor = CastSurface, disabledContentColor = Color.White)) {
                    // A short command locks input without restyling the whole remote.
                    Icon(if (casting.playing) PlayerTransportIcons.Pause else PlayerTransportIcons.Play,
                        if (casting.playing) "暂停电视" else "播放电视", Modifier.size(30.dp))
                }
                RemoteSeekButton(true, casting.duration > 0 && !casting.busy) {
                    casting.seek((casting.position + 10_000).coerceIn(0, casting.duration))
                }
            }
            Text(if (casting.playing) "电视播放中" else "电视已暂停",
                color = CastMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (canSelectEpisode) {
                RemoteOption("选集", NavigationIcons.Menu, !casting.busy, Modifier.fillMaxWidth(), onEpisodes)
            }
            casting.error?.let {
                Text(it, color = CastRed, fontSize = 13.sp)
            }
            Button(casting::disconnect, enabled = !casting.busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CastRed, contentColor = Color.White,
                    disabledContainerColor = CastRed, disabledContentColor = Color.White)) {
                Text("退出投屏", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Dedicated connection and completion feedback; reconnect returns to the device picker. */
@Composable
internal fun DlnaCastStatusScreen(
    deviceName: String, connecting: Boolean, onAction: () -> Unit, back: () -> Unit,
) {
    CastPage(back) {
        Box(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
            Column(Modifier.align(Alignment.Center).padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (connecting) CircularProgressIndicator(Modifier.size(56.dp), color = CastBlue, strokeWidth = 4.dp)
                else Box(Modifier.size(88.dp).background(CastBlue.copy(alpha = .15f), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(NavigationIcons.Cast, null, Modifier.size(52.dp), tint = CastBlue)
                }
                Text(if (connecting) "正在连接…" else "投屏已结束", color = Color.White,
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
                Text(if (connecting) deviceName else "已断开与 $deviceName 的连接",
                    color = CastMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            }
            Button(onAction, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                .widthIn(max = 420.dp).fillMaxWidth().height(50.dp), shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (connecting) CastSurface else CastBlue)) {
                Text(if (connecting) "取消投屏" else "重新投屏", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun CastPage(back: () -> Unit, remote: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(Color(0xFF282C38), CastBackground, Color(0xFF1F222B)))).safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            IconButton(back, Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                Icon(NavigationIcons.Back, "返回", tint = Color.White)
            }
            Text("投屏", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).semantics {
                    contentDescription = if (remote) "投屏遥控器" else "投屏"
                })
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter, content = content)
    }
}

@Composable
private fun RemoteOption(
    label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    FilledTonalButton(onClick, enabled = enabled, modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CastSurface, contentColor = Color.White,
            disabledContainerColor = CastSurface, disabledContentColor = Color.White)) {
        Icon(icon, null, Modifier.size(20.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun RemoteSeekButton(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(onClick, enabled = enabled, modifier = Modifier.size(64.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = CastSurface, contentColor = Color.White,
            disabledContainerColor = CastSurface, disabledContentColor = Color.White)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(PlayerTransportIcons.Replay, if (forward) "快进 10 秒" else "快退 10 秒",
                Modifier.size(30.dp).graphicsLayer { if (forward) scaleX = -1f })
            // The numeral is already included in the icon's spoken action. Keep the
            // composed button label unambiguous for VoiceOver and switch access.
            Text("10", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {})
        }
    }
}
