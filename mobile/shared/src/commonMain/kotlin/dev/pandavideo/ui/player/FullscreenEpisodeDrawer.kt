package dev.pandavideo.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.player.PlaybackState
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons

/** In-place fullscreen picker; changing lines must not dismiss or recreate the player. */
@Composable
internal fun FullscreenEpisodeDrawer(
    visible: Boolean,
    state: PlaybackState,
    onDismiss: () -> Unit,
    onLineSelected: (Int) -> Unit,
    onEpisodeSelected: (Episode) -> Unit,
) {
    val info = state.info
    // Playback progress changes frequently. Only a new episode list invalidates grouping.
    val lines = remember(info?.episodes) { info?.episodes?.groupBy { it.line }.orEmpty() }
    var requestedLine by remember(info?.pageUrl) { mutableStateOf<Int?>(null) }
    val currentLine = remember(info?.episodes, info?.selected) {
        info?.episodes?.find { it.path == info.selected }?.line
    }
    LaunchedEffect(visible, currentLine) { requestedLine = currentLine }
    val line = requestedLine?.takeIf { it in lines } ?: currentLine ?: lines.keys.firstOrNull()
    val episodes = lines[line].orEmpty()
    val gridState = rememberLazyGridState()
    LaunchedEffect(visible, line, info?.selected) {
        if (visible) {
            val selectedIndex = episodes.indexOfFirst { it.path == info?.selected }
            gridState.scrollToItem(selectedIndex.coerceAtLeast(0))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = minOf(maxWidth, (maxWidth * .48f).coerceIn(300.dp, 440.dp))
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f))
                .semantics { contentDescription = "收起选集" }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ))
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(160)),
        ) {
            Column(
                Modifier.width(panelWidth).fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(Color(0xFF1B1B1E))
                    // Consume panel whitespace so it cannot reach the scrim or video gestures.
                    .pointerInput(Unit) { detectTapGestures {} }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End))
                    .padding(start = 16.dp, end = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("共 ${episodes.size} 集", color = Color(0xFF9B9BA2), fontSize = 12.sp,
                        modifier = Modifier.weight(1f).padding(start = 10.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                        Icon(NavigationIcons.Close, "关闭选集", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                if (lines.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(lines.keys.toList()) { target ->
                            val isSelected = target == line
                            Text("线路 $target", color = if (isSelected) Accent else Color(0xFFBCBCC3),
                                fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Accent.copy(alpha = .15f) else Color(0xFF29292E))
                                    .semantics { selected = isSelected }
                                    .clickable(role = Role.Tab) {
                                        requestedLine = target
                                        onLineSelected(target)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
                }
                if (episodes.isEmpty()) {
                    Text("暂无选集", color = Color(0xFF9B9BA2), fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 20.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (panelWidth >= 360.dp) 5 else 4),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                    ) {
                        // Source labels may be quality names such as “HD 1080P”. They need two
                        // cells so the name is readable instead of collapsing to an ellipsis.
                        items(episodes, key = { it.path }, span = { episode ->
                            GridItemSpan(if (episode.label.length > 5) 2 else 1)
                        }) { episode ->
                            val isSelected = episode.path == info?.selected
                            val shape = RoundedCornerShape(10.dp)
                            Box(
                                Modifier.heightIn(min = 52.dp).clip(shape)
                                    .background(if (isSelected) Accent.copy(alpha = .16f) else Color(0xFF29292E))
                                    .then(if (isSelected) Modifier.border(1.dp, Accent.copy(alpha = .7f), shape) else Modifier)
                                    .semantics { selected = isSelected }
                                    .clickable(enabled = !state.loading, role = Role.Button) { onEpisodeSelected(episode) }
                                    .padding(horizontal = 4.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(episode.label, color = if (isSelected) Accent else Color.White.copy(
                                    alpha = if (state.loading) .45f else .9f),
                                    fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
