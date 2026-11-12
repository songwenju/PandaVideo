package dev.pandavideo.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pandavideo.app.AppState
import dev.pandavideo.data.repository.DownloadStatus
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.Poster
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.ActionButtonPadding

/** Single task-management entry point for all explicitly cached episodes. */
@Composable
internal fun DownloadsScreen(model: AppState, back: () -> Unit, openPlayer: () -> Unit) {
    val tasks by model.downloads.tasks.collectAsState()
    var deleting by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                Icon(NavigationIcons.Back, contentDescription = "返回")
            }
            Text("离线缓存", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无缓存，在播放详情中缓存本集", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(tasks, key = { it.id }) { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Poster(entry.cover, entry.info.title, Modifier.width(64.dp).height(88.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.info.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (entry.episodeLabel.isNotBlank())
                            Text(entry.episodeLabel, style = MaterialTheme.typography.bodySmall)
                        val status = when (entry.status) {
                            DownloadStatus.Queued -> "等待缓存"
                            DownloadStatus.Downloading -> entry.progress?.let { "缓存中 ${(it * 100).toInt()}%" } ?: "缓存中"
                            DownloadStatus.Paused -> "已暂停"
                            DownloadStatus.Completed -> "已缓存，可离线观看"
                            DownloadStatus.Failed -> entry.error.ifBlank { "缓存失败，请重试" }
                        }
                        val statusColor = if (entry.status == DownloadStatus.Completed) Accent
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                when (entry.status) {
                                    DownloadStatus.Completed -> NavigationIcons.Check
                                    DownloadStatus.Paused -> NavigationIcons.Pause
                                    DownloadStatus.Failed -> NavigationIcons.Refresh
                                    else -> NavigationIcons.Download
                                },
                                contentDescription = null, modifier = Modifier.size(16.dp), tint = statusColor,
                            )
                            Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
                        }
                        if (entry.status == DownloadStatus.Downloading) {
                            val fraction = entry.progress
                            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(
                                progress = { fraction }, modifier = Modifier.fillMaxWidth(),
                                // Cache completion is already represented by the fill and status.
                                // Material's default endpoint dot looks like an extra progress marker.
                                drawStopIndicator = {},
                            )
                        }
                        Row {
                            when (entry.status) {
                                DownloadStatus.Completed -> DownloadAction(NavigationIcons.Play, "播放") {
                                    model.playDownload(entry); openPlayer()
                                }
                                DownloadStatus.Queued, DownloadStatus.Downloading -> DownloadAction(NavigationIcons.Pause, "暂停") {
                                    model.downloads.pause(entry.id)
                                }
                                DownloadStatus.Paused, DownloadStatus.Failed -> DownloadAction(
                                    if (entry.status == DownloadStatus.Failed) NavigationIcons.Refresh else NavigationIcons.Play,
                                    if (entry.status == DownloadStatus.Failed) "重试" else "继续",
                                ) {
                                    model.downloads.resume(entry.id)
                                }
                            }
                            DownloadAction(NavigationIcons.Delete, "删除") { deleting = entry.id }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除缓存？") }, text = { Text("将移除此集的任务与本地视频文件。") },
            confirmButton = { TextButton({ model.removeDownload(id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DownloadAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = ActionButtonPadding) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
