package dev.pandavideo.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.data.user.SupportedRates
import dev.pandavideo.data.user.ThemeMode
import dev.pandavideo.data.repository.UpdateStore
import dev.pandavideo.data.repository.formatCacheBytes
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.LocalDarkTheme
import dev.pandavideo.ui.common.NavigationIcons

@Composable
internal fun SettingsScreen(model: AppState, back: () -> Unit) {
    val settings by model.settings.settings.collectAsState()
    val remaining by model.sleepRemaining.collectAsState()
    val sleepMinutes by model.sleepTimerMinutes.collectAsState()
    var editingSleepTimer by rememberSaveable { mutableStateOf(false) }
    var customSleepInput by rememberSaveable { mutableStateOf("") }
    val checkingUpdate by model.checkingUpdate.collectAsState()
    val update by model.updates.notice.collectAsState()
    val cacheState by model.cacheMaintenance.state.collectAsState()
    var confirmingCacheClear by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.cacheMaintenance.scan() }
    var updateUrl by rememberSaveable { mutableStateOf(model.updates.sourceUrl) }
    var editingUpdateSource by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val pageColor = if (LocalDarkTheme.current) colors.background else Color(0xFFF6F7F9)
    Column(Modifier.fillMaxSize().background(pageColor).imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(colors.surface).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = back, modifier = Modifier.size(44.dp)) {
                Icon(NavigationIcons.Back, contentDescription = "返回", Modifier.size(22.dp), tint = colors.onSurface)
            }
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onSurface,
                modifier = Modifier.padding(start = 4.dp))
        }
        LazyColumn(
            Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            // The app overlays its bottom navigation; keep the last card and its actions
            // reachable above it, including when the update address opens the keyboard.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsCard {
                    SettingsHeading(NavigationIcons.Brightness, "外观", "选择你喜欢的显示方式")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            ThemeOption(mode, settings.themeMode == mode, Modifier.weight(1f)) {
                                model.settings.setThemeMode(mode)
                            }
                        }
                    }
                }
            }
            item {
                SettingsCard {
                    SettingsHeading(NavigationIcons.Play, "播放偏好", "让每次播放都更顺手")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("默认倍速", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface,
                            modifier = Modifier.weight(1f))
                        Text("${settings.defaultRate}×", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceVariant).padding(4.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        SupportedRates.forEach { rate ->
                            SettingsChoice("${rate}×", settings.defaultRate == rate, Modifier.weight(1f)) {
                                model.settings.setDefaultRate(rate)
                            }
                        }
                    }
                    SettingsCaption("新播放使用此倍速，播放中仍可单独调整。", Modifier.padding(top = 9.dp))
                    SettingsDivider()
                    SettingsToggle("记住播放音量", "下次播放沿用上次的音量", settings.rememberVolume) {
                        model.settings.setRememberVolume(it, model.platform.currentVolume())
                    }
                    SettingsDivider(verticalPadding = 6)
                    SettingsToggle("记住播放亮度", "播放时沿用记忆值，退出后恢复系统亮度", settings.rememberBrightness) {
                        model.settings.setRememberBrightness(it, model.platform.currentBrightness())
                    }
                }
            }
            item {
                SettingsCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SettingsHeading(NavigationIcons.Moon, "睡眠定时",
                                if (remaining > 0) "剩余 ${remaining / 60} 分 ${remaining % 60} 秒" else "到时自动暂停播放")
                        }
                        if (remaining > 0) TextButton(
                            { model.setSleepTimer(0) }, contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("取消", color = Accent, fontSize = 12.sp) }
                        else Text("未开启", fontSize = 11.sp, color = colors.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceVariant).padding(horizontal = 8.dp, vertical = 5.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(15, 30, 60).forEach { minutes ->
                            SettingsChoice("$minutes 分钟", sleepMinutes == minutes, Modifier.weight(1f)) {
                                model.setSleepTimer(minutes)
                            }
                        }
                        val customSelected = sleepMinutes > 0 && sleepMinutes !in listOf(15, 30, 60)
                        SettingsChoice(
                            if (customSelected) "$sleepMinutes 分钟" else "自定义",
                            customSelected, Modifier.weight(1f), radio = false,
                        ) {
                            customSleepInput = if (sleepMinutes > 0) sleepMinutes.toString() else ""
                            editingSleepTimer = true
                        }
                    }
                    SettingsCaption("可提前设置，到时暂停当前播放", Modifier.padding(top = 10.dp))
                }
            }
            item {
                SettingsCard {
                    SettingsHeading(NavigationIcons.Delete, "缓存管理", "扫描并释放可重新加载的数据")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!cacheState.busy && cacheState.usage != null) {
                            SettingsCaption("可清理约", Modifier.padding(bottom = 4.dp))
                        }
                        Text(
                            when {
                                cacheState.scanning -> "扫描中…"
                                cacheState.cleaning -> "清理中…"
                                else -> cacheState.usage?.let { formatCacheBytes(it.totalBytes) } ?: "待扫描"
                            },
                            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Accent,
                        )
                    }
                    CacheUsageRow(
                        NavigationIcons.Poster, "海报缓存", "已加载的海报图片",
                        cacheState.usage?.posterBytes,
                    )
                    SettingsDivider(verticalPadding = 10)
                    CacheUsageRow(
                        NavigationIcons.Discover, "内容缓存",
                        cacheState.usage?.let { "搜索、详情及筛选结果 · ${it.contentEntries} 项" }
                            ?: "搜索、详情及筛选结果",
                        cacheState.usage?.contentBytes,
                    )
                    SettingsCaption("历史、收藏和离线视频会保留", Modifier.padding(top = 14.dp))
                    cacheState.error?.let {
                        Text(it, Modifier.padding(top = 8.dp), fontSize = 12.sp, color = colors.error)
                    }
                    cacheState.cleanedBytes?.let {
                        SettingsCaption(
                            if (it > 0) "已清理约 ${formatCacheBytes(it)}" else "缓存已清理",
                            Modifier.padding(top = 8.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            { model.cacheMaintenance.scan() }, Modifier.weight(1f).heightIn(min = 44.dp),
                            enabled = !cacheState.busy, shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(NavigationIcons.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (cacheState.usage == null) "扫描缓存" else "重新扫描", fontSize = 13.sp)
                        }
                        Button(
                            { confirmingCacheClear = true }, Modifier.weight(1f).heightIn(min = 44.dp),
                            enabled = !cacheState.busy && (cacheState.usage?.totalBytes ?: 0) > 0,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        ) {
                            Icon(NavigationIcons.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("清理缓存", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                SettingsCard {
                    SettingsHeading(NavigationIcons.Refresh, "应用更新", "当前版本 ${UpdateStore.CURRENT_VERSION}")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) { editingUpdateSource = !editingUpdateSource }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("更新来源", fontSize = 14.sp, color = colors.onSurface, modifier = Modifier.weight(1f))
                        Text(if (updateUrl.isBlank()) "未配置" else "已配置", fontSize = 12.sp, color = colors.onSurfaceVariant)
                        Icon(if (editingUpdateSource) NavigationIcons.Close else NavigationIcons.Chevron,
                            if (editingUpdateSource) "收起更新来源" else "展开更新来源",
                            Modifier.padding(start = 6.dp).size(16.dp), tint = colors.onSurfaceVariant)
                    }
                    if (editingUpdateSource) {
                        OutlinedTextField(
                            updateUrl, { updateUrl = it.take(2_048) }, label = { Text("发布地址", fontSize = 12.sp) },
                            placeholder = { Text("https://…", fontSize = 13.sp) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), singleLine = true,
                            enabled = !checkingUpdate, shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent, focusedLabelColor = Accent,
                                unfocusedBorderColor = colors.outline.copy(alpha = 0.45f),
                            ),
                        )
                        SettingsCaption("填写版本发布信息的 JSON 地址，下次启动自动检查。", Modifier.padding(bottom = 12.dp))
                    }
                    Button(
                        { model.checkUpdate(updateUrl) }, Modifier.fillMaxWidth().height(42.dp),
                        enabled = !checkingUpdate && updateUrl.isNotBlank(), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent, contentColor = Color.White,
                            disabledContainerColor = colors.surfaceVariant,
                            disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        ),
                    ) { Text(if (checkingUpdate) "检查中…" else "检查更新", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    update?.let { release ->
                        SettingsDivider()
                        Text("发现新版本 ${release.version}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                        SettingsCaption(release.notes, Modifier.padding(top = 8.dp))
                        TextButton({ model.platform.openLink(release.url) }) {
                            Text("查看更新详情", color = Accent, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
    if (editingSleepTimer) {
        val customMinutes = customSleepInput.trim().toIntOrNull()?.takeIf { it in 1..180 }
        val invalid = customSleepInput.isNotEmpty() && customMinutes == null
        val focusRequester = remember { FocusRequester() }
        fun applyCustomTimer() {
            customMinutes?.let {
                model.setSleepTimer(it)
                editingSleepTimer = false
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        AlertDialog(
            onDismissRequest = { editingSleepTimer = false },
            icon = { Icon(NavigationIcons.Moon, null, tint = Accent) },
            title = { Text("自定义睡眠定时", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = customSleepInput,
                    onValueChange = { customSleepInput = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text("时长（分钟）") },
                    placeholder = { Text("例如：45") },
                    supportingText = { Text(if (invalid) "请输入 1–180 的整数" else "支持 1–180 分钟，到时自动暂停播放") },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { applyCustomTimer() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, focusedLabelColor = Accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { applyCustomTimer() }, enabled = customMinutes != null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("开启定时", color = if (customMinutes != null) Accent else colors.onSurfaceVariant) }
            },
            dismissButton = {
                TextButton(
                    { editingSleepTimer = false }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("取消") }
            },
        )
    }
    if (confirmingCacheClear) {
        AlertDialog(
            onDismissRequest = { confirmingCacheClear = false },
            icon = { Icon(NavigationIcons.Delete, null, tint = Accent) },
            title = { Text("清理缓存？", fontWeight = FontWeight.SemiBold) },
            text = { Text("将清理海报及搜索、详情等临时数据，之后会按需重新加载。历史、收藏和离线视频会保留。") },
            confirmButton = {
                TextButton(
                    {
                        confirmingCacheClear = false
                        model.cacheMaintenance.clear()
                    }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("确认清理", color = Accent) }
            },
            dismissButton = {
                TextButton(
                    { confirmingCacheClear = false },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CacheUsageRow(icon: ImageVector, title: String, subtitle: String, bytes: Long?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
            SettingsCaption(subtitle)
        }
        Text(bytes?.let(::formatCacheBytes) ?: "—", fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, color = colors.onSurface)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SettingsHeading(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Accent.copy(alpha = 0.09f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = Accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            SettingsCaption(subtitle)
        }
    }
}

@Composable
private fun ThemeOption(mode: ThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.07f) else colors.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, if (selected) Accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(top = 12.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Small interface previews are drawn locally so light/dark/system choices need
        // no network artwork and stay legible when the active app theme changes.
        Box(
            Modifier.width(42.dp).height(48.dp).clip(RoundedCornerShape(7.dp))
                .background(if (mode == ThemeMode.Dark) Color(0xFF292D36) else Color.White)
                .border(1.dp, Color(0xFF9CA3AF).copy(alpha = 0.2f), RoundedCornerShape(7.dp)),
        ) {
            if (mode == ThemeMode.System) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f)
                .background(Color(0xFF292D36)))
            Column(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(12.dp).height(3.dp).clip(CircleShape).background(Accent))
                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (mode == ThemeMode.Dark) Color(0xFF46505E) else Color(0xFFEDEFF3)))
                Box(Modifier.fillMaxWidth(0.7f).height(3.dp).clip(CircleShape).background(Color(0xFF9CA3AF).copy(alpha = 0.5f)))
            }
        }
        Text(mode.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Accent else colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun SettingsChoice(
    label: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true,
    radio: Boolean = true, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.surface else colors.surfaceVariant)
            .then(if (radio) Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = when {
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.45f)
                selected -> Accent
                else -> colors.onSurfaceVariant
            }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
            SettingsCaption(subtitle)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Accent, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = colors.outline.copy(alpha = 0.45f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SettingsCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsDivider(verticalPadding: Int = 14) {
    HorizontalDivider(Modifier.padding(vertical = verticalPadding.dp), thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}
