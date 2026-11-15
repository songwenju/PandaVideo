package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.Episode
import dev.pandavideo.data.repository.DownloadStatus
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons

/** Selects offline episodes independently of playback and its current source line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeCacheSheet(
    info: PlaybackInfo, model: AppState, cover: String?, onDismiss: () -> Unit,
) {
    val tasks by model.downloads.tasks.collectAsState()
    val selectableEpisodes = remember(info.episodes, info.selected) {
        info.episodes.ifEmpty { listOf(Episode(info.selected, "正片", 1)) }.distinctBy { it.path }
    }
    val lines = remember(selectableEpisodes) { selectableEpisodes.groupBy { it.line } }
    var line by remember(info.pageUrl) {
        mutableStateOf(info.episodes.find { it.path == info.selected }?.line ?: lines.keys.firstOrNull())
    }
    val statuses = remember(tasks, info.pageUrl) {
        tasks.filter { it.info.pageUrl == info.pageUrl }.associate { it.info.selected to it.status }
    }
    val busy = setOf(DownloadStatus.Completed, DownloadStatus.Queued, DownloadStatus.Downloading)
    var selected by remember(info.pageUrl) {
        mutableStateOf(if (statuses[info.selected] !in busy) setOf(info.selected) else emptySet())
    }
    val episodes = lines[line].orEmpty()
    val available = episodes.filter { statuses[it.path] !in busy }.map { it.path }.toSet()
    val accepted = selected.filter { path ->
        selectableEpisodes.any { it.path == path } && statuses[path] !in busy
    }.toSet()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择缓存集数", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Text(info.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lines.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines.keys.toList()) { target ->
                    FilterChip(selected = target == line, onClick = {
                        line = target
                        selected = emptySet()
                    }, label = { Text("线路 $target") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${accepted.size} / ${episodes.size} 集", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (accepted == available) emptySet() else available },
                    enabled = available.isNotEmpty()) {
                    Text(if (accepted == available && available.isNotEmpty()) "取消全选" else "全选", color = Accent)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp),
                modifier = Modifier.weight(1f, fill = false).heightIn(min = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(episodes, key = { it.path }) { episode ->
                    val status = statuses[episode.path]
                    val checked = episode.path in accepted
                    val enabled = status !in busy
                    val shape = RoundedCornerShape(10.dp)
                    val color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    val statusLabel = when (status) {
                        DownloadStatus.Completed -> "已缓存"
                        DownloadStatus.Queued -> "等待缓存"
                        DownloadStatus.Downloading -> "缓存中"
                        DownloadStatus.Paused -> "继续缓存"
                        DownloadStatus.Failed -> "重试缓存"
                        else -> null
                    }
                    Box(
                        Modifier.clip(shape).border(1.dp,
                            MaterialTheme.colorScheme.outlineVariant, shape)
                            .toggleable(checked, enabled = enabled, role = Role.Checkbox) {
                                selected = if (it) selected + episode.path else selected - episode.path
                            }.heightIn(min = 68.dp).padding(8.dp),
                    ) {
                        // The card owns checkbox semantics; this visual marker is decorative.
                        Box(
                            Modifier.align(Alignment.TopStart).size(16.dp)
                                .then(if (checked) Modifier.background(Accent, CircleShape)
                                    else Modifier.border(1.dp,
                                        MaterialTheme.colorScheme.outlineVariant, CircleShape)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) Icon(NavigationIcons.Check, null,
                                Modifier.size(12.dp), tint = Color.White)
                        }
                        Column(
                            Modifier.align(Alignment.Center).fillMaxWidth()
                                .padding(top = 14.dp, bottom = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(episode.label, fontSize = 13.sp, color = color, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            statusLabel?.let { Text(it, fontSize = 10.sp, color = color) }
                        }
                    }
                }
            }
            Button(
                onClick = { if (model.cacheEpisodes(info, accepted, cover.orEmpty())) onDismiss() },
                enabled = accepted.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("缓存 ${accepted.size} 集") }
        }
    }
}
