package dev.pandavideo.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.core.platform.avatarUri
import coil3.compose.AsyncImage
import dev.pandavideo.data.user.ThemeMode
import dev.pandavideo.data.repository.DownloadStatus
import dev.pandavideo.data.repository.OfflineDownload
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.ActionButtonPadding
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.Poster
import dev.pandavideo.ui.common.LocalDarkTheme
import dev.pandavideo.ui.common.topTabSwipe

@Composable
internal fun ProfileScreen(
    model: AppState, openSettings: () -> Unit, topInset: Dp = 0.dp, openPlayer: () -> Unit,
) {
    val profile by model.user.profile.collectAsState()
    val favorites by model.user.favorites.collectAsState()
    val history by model.user.history.collectAsState()
    val downloads by model.downloads.tasks.collectAsState()
    var deletingDownload by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<ClearTarget?>(null) }
    var choosingTheme by remember { mutableStateOf(false) }
    val settings by model.settings.settings.collectAsState()
    var selected by rememberSaveable { mutableStateOf(ProfileLibrary.History) }
    var managing by rememberSaveable { mutableStateOf(false) }
    val titles = if (selected == ProfileLibrary.History) history.map { record ->
        ProfileTitle(
            record.pageUrl, record.title,
            model.coverFor(record.title, record.cover),
            null,
            if (record.durationMs > 0)
                dev.pandavideo.data.user.progressFraction(record.positionMs, record.durationMs)
            else null,
        )
    } else if (selected == ProfileLibrary.Favorites) favorites.map {
        ProfileTitle(it.pageUrl, it.title, model.coverFor(it.title, it.cover), it.year, null)
    } else emptyList()
    val entryCount = if (selected == ProfileLibrary.Downloads) downloads.size else titles.size
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current

    Box(Modifier.fillMaxSize().background(colors.background)) {
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .topTabSwipe(selected.ordinal, ProfileLibrary.entries.size) { index ->
                selected = ProfileLibrary.entries[index]
                managing = false
            },
        // Reserve the initial safe area inside the list so scrolling has no hard viewport edge.
        contentPadding = PaddingValues(top = topInset, bottom = 100.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.horizontalGradient(
                        listOf(
                            if (dark) Color(0xFF472B22) else Color(0xFFFFD9BB),
                            colors.surface,
                            if (dark) Color(0xFF103B38) else Color(0xFFA9EDE3),
                        ),
                    ))
                    // Blend the decorative header into the page above and below.
                    .background(Brush.verticalGradient(
                        0f to colors.background,
                        0.3f to Color.Transparent,
                        0.65f to Color.Transparent,
                        1f to colors.background,
                    ))
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    ProfileToolbarButton(NavigationIcons.Moon, "深色模式") { choosingTheme = true }
                    ProfileToolbarButton(NavigationIcons.Menu, "播放设置", openSettings)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(60.dp).clip(CircleShape)
                            .background(colors.surface.copy(alpha = 0.8f))
                            .clickable(role = Role.Button) { editing = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        ProfileAvatar(model.platform, profile.avatar, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { editing = true }) {
                        Text(
                            profile.nickname, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            profile.bio, fontSize = 12.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${favorites.size} 收藏    ${history.size} 观看",
                            fontSize = 12.sp, color = colors.onSurface,
                        )
                    }
                    ProfileToolbarButton(NavigationIcons.Edit, "编辑资料") { editing = true }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileLibrary.entries.forEach { tab ->
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .selectable(selected = selected == tab, role = Role.Tab) {
                            selected = tab; managing = false
                        }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            tab.label, fontSize = 18.sp, lineHeight = 24.sp,
                            fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == tab) colors.onSurface else colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.width(18.dp).height(3.dp).clip(CircleShape)
                            .background(if (selected == tab) Accent else Color.Transparent))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton({ managing = !managing }, enabled = entryCount > 0 || managing,
                    contentPadding = ActionButtonPadding) {
                    Text(if (managing) "完成" else "管理", color = colors.onSurfaceVariant,
                        fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected == ProfileLibrary.Downloads) "$entryCount 集缓存" else "$entryCount 部影片",
                    color = colors.onSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                if (managing) TextButton({
                    confirmClear = when (selected) {
                        ProfileLibrary.History -> ClearTarget.History
                        ProfileLibrary.Favorites -> ClearTarget.Favorites
                        ProfileLibrary.Downloads -> ClearTarget.Downloads
                    }
                }, enabled = entryCount > 0) {
                    Text("清空全部", color = Accent, fontSize = 12.sp)
                }
            }
        }
        if (selected == ProfileLibrary.Downloads) {
            if (downloads.isEmpty()) item {
                EmptyLibraryHint("还没有离线缓存", NavigationIcons.Download,
                    if (model.downloads.supports("hls")) "在播放详情页缓存影片，随时离线观看"
                    else "当前设备暂不支持离线缓存")
            } else items(downloads.chunked(3), key = { "downloads:${it.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { entry ->
                        Box(Modifier.weight(1f)) {
                            DownloadPosterCard(
                                entry, model.coverFor(entry.info.title, entry.cover), managing,
                                onAction = {
                                    when (entry.status) {
                                        DownloadStatus.Completed -> { model.playDownload(entry); openPlayer() }
                                        DownloadStatus.Queued, DownloadStatus.Downloading -> model.downloads.pause(entry.id)
                                        DownloadStatus.Paused, DownloadStatus.Failed -> model.downloads.resume(entry.id)
                                    }
                                },
                                onRemove = { deletingDownload = entry.id },
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else if (titles.isEmpty()) {
            item {
                EmptyLibraryHint(
                    if (selected == ProfileLibrary.History) "还没有播放记录" else "还没有收藏的影片",
                    if (selected == ProfileLibrary.History) NavigationIcons.History else NavigationIcons.Heart,
                    if (selected == ProfileLibrary.History) "看过的影片会出现在这里" else "在影片详情页点击爱心，即可加入收藏",
                )
            }
        } else {
            items(titles.chunked(3), key = { "${selected.name}:${it.first().pageUrl}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { title ->
                        Box(Modifier.weight(1f)) {
                            SavedPosterCard(
                                title.title, title.cover, title.subtitle,
                                badge = if (selected == ProfileLibrary.History) "继续观看" else null,
                                progress = title.progress,
                                onRemove = if (managing) { {
                                    if (selected == ProfileLibrary.History) model.user.removeHistory(title.pageUrl)
                                    else model.user.removeFavorite(title.pageUrl)
                                } } else null,
                            ) { model.reopenSaved(title.pageUrl, title.title, title.cover); openPlayer() }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (topInset > 0.dp) {
        val protectionHeight = topInset + 24.dp
        // Protect status icons and fade the scrolling content out below the safe area.
        Box(Modifier.fillMaxWidth().height(protectionHeight).background(
            Brush.verticalGradient(
                0f to colors.background,
                (topInset.value / protectionHeight.value) to colors.background,
                1f to colors.background.copy(alpha = 0f),
            ),
        ))
    }
    }
    if (editing) {
        EditProfileDialog(
            profile,
            platform = model.platform,
            onDismiss = { editing = false },
            onSave = { nickname, bio, avatar ->
                model.user.updateProfile(nickname, bio, avatar)
                if (profile.avatar != avatar) model.platform.deleteAvatar(profile.avatar)
                editing = false
            },
        )
    }
    if (choosingTheme) {
        ThemePickerDialog(settings.themeMode) { mode ->
            mode?.let { model.settings.setThemeMode(it) }
            choosingTheme = false
        }
    }
    confirmClear?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text(when (target) {
                ClearTarget.Favorites -> "清空我的收藏"
                ClearTarget.History -> "清空播放记录"
                ClearTarget.Downloads -> "清空离线缓存"
            }) },
            text = {
                Text(
                    if (target == ClearTarget.Favorites) "确定要清空全部收藏吗？此操作不可恢复。"
                    else if (target == ClearTarget.Downloads) "确定要删除全部缓存任务与本地视频文件吗？此操作不可恢复。"
                    else "确定要清空全部播放记录吗？此操作不可恢复。",
                )
            },
            confirmButton = {
                TextButton({
                    when (target) {
                        ClearTarget.Favorites -> model.user.clearFavorites()
                        ClearTarget.History -> model.user.clearHistory()
                        ClearTarget.Downloads -> downloads.forEach { model.removeDownload(it.id) }
                    }
                    confirmClear = null
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmClear = null }) { Text("取消") } },
        )
    }
    deletingDownload?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingDownload = null },
            title = { Text("删除缓存？") },
            text = { Text("将移除此集的任务与本地视频文件。") },
            confirmButton = { TextButton({
                model.removeDownload(id); deletingDownload = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deletingDownload = null }) { Text("取消") } },
        )
    }
}

private enum class ClearTarget { Favorites, History, Downloads }
private enum class ProfileLibrary(val label: String) {
    History("历史"), Favorites("收藏"), Downloads("离线缓存"),
}
private data class ProfileTitle(
    val pageUrl: String, val title: String, val cover: String?, val subtitle: String?,
    val progress: Float?,
)

@Composable
private fun ProfileToolbarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DownloadPosterCard(
    entry: OfflineDownload, cover: String?, managing: Boolean,
    onAction: () -> Unit, onRemove: () -> Unit,
) {
    val completed = entry.status == DownloadStatus.Completed
    val actionLabel = when (entry.status) {
        DownloadStatus.Completed -> "播放"
        DownloadStatus.Queued, DownloadStatus.Downloading -> "暂停"
        DownloadStatus.Paused -> "继续"
        DownloadStatus.Failed -> "重试"
    }
    val actionIcon = when (entry.status) {
        DownloadStatus.Queued, DownloadStatus.Downloading -> NavigationIcons.Pause
        DownloadStatus.Failed -> NavigationIcons.Refresh
        else -> NavigationIcons.Play
    }
    val status = when (entry.status) {
        DownloadStatus.Completed -> "已缓存"
        DownloadStatus.Queued -> "等待缓存"
        DownloadStatus.Downloading -> entry.progress?.let { "缓存中 ${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: "缓存中"
        DownloadStatus.Paused -> "已暂停"
        DownloadStatus.Failed -> "缓存失败"
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = completed && !managing, role = Role.Button, onClick = onAction)) {
            Poster(cover, entry.info.title, Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(if (completed) NavigationIcons.Check else NavigationIcons.Download,
                    contentDescription = null, tint = if (completed) Accent else Color.White,
                    modifier = Modifier.size(12.dp))
                Text(status, color = Color.White, fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (!completed) {
                val fraction = entry.progress
                if (fraction != null) LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Accent, trackColor = Color.White.copy(alpha = 0.25f), drawStopIndicator = {},
                ) else if (entry.status == DownloadStatus.Downloading) LinearProgressIndicator(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Accent, trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
            if (managing) IconButton(
                onRemove, Modifier.align(Alignment.TopEnd).padding(3.dp).size(32.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(8.dp)),
            ) {
                Icon(NavigationIcons.Delete, "删除${entry.info.title} ${entry.episodeLabel}",
                    Modifier.size(18.dp), tint = Accent)
            }
        }
        Text(entry.info.title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp))
        if (entry.episodeLabel.isNotBlank()) Text(entry.episodeLabel, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        if (entry.status == DownloadStatus.Failed && entry.error.isNotBlank()) Text(
            entry.error, fontSize = 10.sp, color = MaterialTheme.colorScheme.error,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
        )
        if (!managing) TextButton(onAction, contentPadding = ActionButtonPadding) {
            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(14.dp), tint = Accent)
            Spacer(Modifier.width(4.dp))
            Text(actionLabel, color = Accent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SavedPosterCard(
    title: String, cover: String?, subtitle: String?, badge: String?,
    progress: Float? = null, onRemove: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .clickable(enabled = onRemove == null, role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(10.dp))) {
            Poster(cover, title, Modifier.fillMaxSize())
            if (badge != null) Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(badge, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress != null) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                    .background(Color.Black.copy(alpha = 0.35f)),
            ) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent))
            }
            if (onRemove != null) IconButton(
                onRemove,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(32.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(8.dp)),
            ) {
                Icon(NavigationIcons.Close, contentDescription = "移除$title",
                    modifier = Modifier.size(18.dp), tint = Accent)
            }
        }
        Text(
            title, color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp),
        )
        if (!subtitle.isNullOrBlank()) Text(
            subtitle, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun EmptyLibraryHint(text: String, icon: ImageVector, hint: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ProfileAvatar(platform: PlatformServices, filename: String, modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Icon(NavigationIcons.Profile, "头像", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        platform.avatarUri(filename)?.let { uri ->
            AsyncImage(model = uri, contentDescription = "头像", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ThemePickerDialog(current: ThemeMode, onChoose: (ThemeMode?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text("深色模式") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.RadioButton) { onChoose(mode) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == mode, onClick = { onChoose(mode) })
                        Text(mode.label, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton({ onChoose(null) }) { Text("关闭") } },
    )
}
