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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dev.pandavideo.core.platform.rememberAvatarPicker
import coil3.compose.AsyncImage
import dev.pandavideo.data.user.ThemeMode
import dev.pandavideo.data.user.UserProfile
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.Poster
import dev.pandavideo.ui.common.LocalDarkTheme

@Composable
internal fun ProfileScreen(
    model: AppState, openSettings: () -> Unit, topInset: Dp = 0.dp, openPlayer: () -> Unit,
    openDownloads: () -> Unit = {},
) {
    val profile by model.user.profile.collectAsState()
    val favorites by model.user.favorites.collectAsState()
    val history by model.user.history.collectAsState()
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
    } else favorites.map {
        ProfileTitle(it.pageUrl, it.title, model.coverFor(it.title, it.cover), it.year, null)
    }
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current

    Box(Modifier.fillMaxSize().background(colors.background)) {
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth()) {
                    ProfileShortcut(NavigationIcons.History, "播放记录", Modifier.weight(1f)) {
                        selected = ProfileLibrary.History; managing = false
                    }
                    ProfileShortcut(NavigationIcons.Heart, "个人收藏", Modifier.weight(1f)) {
                        selected = ProfileLibrary.Favorites; managing = false
                    }
                    if (model.downloads.supports("hls")) {
                        ProfileShortcut(NavigationIcons.Menu, "离线缓存", Modifier.weight(1f), openDownloads)
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                ProfileLibrary.entries.forEach { tab ->
                    Column(
                        Modifier.selectable(selected = selected == tab, role = Role.Tab) {
                            selected = tab; managing = false
                        }
                            .padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            tab.label, fontSize = 18.sp,
                            fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == tab) colors.onSurface else colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.width(18.dp).height(3.dp).clip(CircleShape)
                            .background(if (selected == tab) Accent else Color.Transparent))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton({ managing = !managing }, enabled = titles.isNotEmpty() || managing) {
                    Text(if (managing) "完成" else "管理", color = colors.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${titles.size} 部影片", color = colors.onSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                if (managing) TextButton({
                    confirmClear = if (selected == ProfileLibrary.History) ClearTarget.History else ClearTarget.Favorites
                }, enabled = titles.isNotEmpty()) {
                    Text("清空全部", color = Accent, fontSize = 12.sp)
                }
            }
        }
        if (titles.isEmpty()) {
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
            title = { Text(if (target == ClearTarget.Favorites) "清空我的收藏" else "清空播放记录") },
            text = {
                Text(
                    if (target == ClearTarget.Favorites) "确定要清空全部收藏吗？此操作不可恢复。"
                    else "确定要清空全部播放记录吗？此操作不可恢复。",
                )
            },
            confirmButton = {
                TextButton({
                    if (target == ClearTarget.Favorites) model.user.clearFavorites() else model.user.clearHistory()
                    confirmClear = null
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmClear = null }) { Text("取消") } },
        )
    }
}

private enum class ClearTarget { Favorites, History }
private enum class ProfileLibrary(val label: String) { History("历史"), Favorites("收藏") }
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
private fun ProfileShortcut(
    icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(12.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
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
private fun EditProfileDialog(
    profile: UserProfile, platform: PlatformServices,
    onDismiss: () -> Unit, onSave: (String, String, String) -> Unit,
) {
    var nickname by remember { mutableStateOf(profile.nickname) }
    var bio by remember { mutableStateOf(profile.bio) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val draft = remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { draft.value?.let(platform::deleteAvatar) }
    }
    val pick = rememberAvatarPicker(platform) { result ->
        picking = false
        error = result.isFailure
        result.getOrNull()?.let {
            draft.value?.let(platform::deleteAvatar)
            draft.value = it
            avatar = it
        }
    }
    AlertDialog(
        onDismissRequest = { if (!picking) onDismiss() },
        title = { Text("编辑资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProfileAvatar(platform, avatar, Modifier.size(72.dp))
                    TextButton({ picking = true; error = false; pick() }, enabled = !picking) {
                        Text(if (picking) "正在处理…" else "更换头像", color = Accent)
                    }
                    if (error) Text("图片读取失败，请重新选择", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it.take(12) },
                    label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bio, onValueChange = { bio = it.take(40) },
                    label = { Text("简介") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton({
            onSave(nickname, bio, avatar)
            draft.value = null
        }, enabled = !picking) { Text("保存") } },
        dismissButton = { TextButton(onDismiss, enabled = !picking) { Text("取消") } },
    )
}

@Composable
private fun ProfileAvatar(platform: PlatformServices, filename: String, modifier: Modifier) {
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
                            .padding(vertical = 6.dp),
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
