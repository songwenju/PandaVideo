package dev.pandavideo.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pandavideo.app.AppState
import dev.pandavideo.data.user.SupportedRates
import dev.pandavideo.data.user.ThemeMode
import dev.pandavideo.data.repository.UpdateStore
import dev.pandavideo.ui.common.NavigationIcons

@Composable
internal fun SettingsScreen(model: AppState, back: () -> Unit) {
    val settings by model.settings.settings.collectAsState()
    val remaining by model.sleepRemaining.collectAsState()
    val checkingUpdate by model.checkingUpdate.collectAsState()
    val update by model.updates.notice.collectAsState()
    var updateUrl by remember { mutableStateOf(model.updates.sourceUrl) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                Icon(NavigationIcons.Back, contentDescription = "返回")
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(settings.themeMode == mode, { model.settings.setThemeMode(mode) },
                    label = { Text(mode.label) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        Text("默认倍速", style = MaterialTheme.typography.titleMedium)
        Text("新打开的影片使用此倍速，当前播放可在播控栏单独调整。",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            SupportedRates.forEach { rate ->
                FilterChip(settings.defaultRate == rate, { model.settings.setDefaultRate(rate) },
                    label = { Text("${rate}×") }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记住播放音量", Modifier.weight(1f))
            Switch(settings.rememberVolume, {
                model.settings.setRememberVolume(it, model.platform.currentVolume())
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记住播放亮度", Modifier.weight(1f))
            Switch(settings.rememberBrightness, {
                model.settings.setRememberBrightness(it, model.platform.currentBrightness())
            })
        }
        Text("播放时恢复记忆值，离开播放页恢复系统亮度。",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("睡眠定时", style = MaterialTheme.typography.titleMedium)
        Text(if (remaining > 0) "剩余 ${remaining / 60} 分 ${remaining % 60} 秒"
            else "未开启；计时结束后暂停播放。")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(15, 30, 60).forEach { minutes ->
                TextButton({ model.setSleepTimer(minutes) }, enabled = model.session != null) {
                    Text("$minutes 分钟")
                }
            }
            TextButton({ model.setSleepTimer(0) }, enabled = remaining > 0) { Text("取消") }
        }
        if (model.session == null) Text("开始播放后可开启睡眠定时。",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("应用更新 · ${UpdateStore.CURRENT_VERSION}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(updateUrl, { updateUrl = it.take(2_048) }, label = { Text("发布信息 JSON 地址") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !checkingUpdate)
        TextButton({ model.checkUpdate(updateUrl) }, enabled = !checkingUpdate && updateUrl.isNotBlank()) {
            Text(if (checkingUpdate) "检查中…" else "检查更新")
        }
        update?.let { release ->
            Text("新版本 ${release.version}", style = MaterialTheme.typography.titleSmall)
            Text(release.notes)
            OutlinedButton({ model.platform.openLink(release.url) }) { Text("打开发布页面") }
        }
        Text("配置发布地址后，下次启动自动检查；下载安装由系统处理。",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(160.dp))
    }
}
