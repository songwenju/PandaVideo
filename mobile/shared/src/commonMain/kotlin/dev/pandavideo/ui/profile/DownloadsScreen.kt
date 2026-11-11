package dev.pandavideo.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pandavideo.app.AppState
import dev.pandavideo.data.repository.DownloadStatus
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.Poster

/** Single task-management entry point for all explicitly cached episodes. */
@Composable
internal fun DownloadsScreen(model: AppState, back: () -> Unit, openPlayer: () -> Unit) {
    val tasks by model.downloads.tasks.collectAsState()
    var deleting by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(back) { Text("返回") }
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
                        Text(status, style = MaterialTheme.typography.bodySmall,
                            color = if (entry.status == DownloadStatus.Completed) Accent
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.status == DownloadStatus.Downloading) {
                            val fraction = entry.progress
                            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        Row {
                            when (entry.status) {
                                DownloadStatus.Completed -> TextButton({
                                    model.playDownload(entry); openPlayer()
                                }) { Text("播放") }
                                DownloadStatus.Queued, DownloadStatus.Downloading -> TextButton({
                                    model.downloads.pause(entry.id)
                                }) { Text("暂停") }
                                DownloadStatus.Paused, DownloadStatus.Failed -> TextButton({
                                    model.downloads.resume(entry.id)
                                }) { Text(if (entry.status == DownloadStatus.Failed) "重试" else "继续") }
                            }
                            TextButton({ deleting = entry.id }) { Text("删除") }
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
