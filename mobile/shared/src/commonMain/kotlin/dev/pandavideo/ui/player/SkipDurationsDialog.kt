package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.core.player.SkipDurations
import dev.pandavideo.data.user.SkipDurationsStore
import dev.pandavideo.ui.common.Accent

/**
 * Player settings sheet. Playback sources expose no intro/outro markers, so the single
 * switch applies regional reference values (30 seconds for US series, 90 seconds for
 * everything else) whenever an edge is not configured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkipDurationsDialog(
    page: String, region: String, store: SkipDurationsStore, onDismiss: () -> Unit,
) {
    val fallback = remember(page, region) { SkipDurations.defaultSecondsFor(region) }
    val saved = remember(page, fallback) { store.get(page, fallback) }
    var current by remember(page) { mutableStateOf(saved) }
    var enabled by remember(page) { mutableStateOf(saved.enabled) }
    var error by remember(page) { mutableStateOf<String?>(null) }
    fun apply(on: Boolean) {
        try {
            if (store.setEnabled(page, on, fallback)) {
                current = store.get(page, fallback)
                enabled = on
                error = null
            } else error = "当前剧集暂无法保存设置"
        } catch (_: Exception) { error = "保存失败，请重试" }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("播放设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "仅应用于当前剧集，保存后从下一集生效。",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Accent.copy(alpha = 0.08f))
                    .clickable(role = Role.Switch) { apply(!enabled) }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动跳过片头片尾", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "片头 ${current.introSeconds} 秒 · 片尾 ${current.outroSeconds} 秒"
                        else "打开后按片头 $fallback 秒、片尾 $fallback 秒跳过",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                Switch(checked = enabled, onCheckedChange = ::apply)
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}
