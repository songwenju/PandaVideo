package dev.pandavideo.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.ui.common.Accent

/** Shared bottom drawer for inline, fullscreen and minimized playback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSleepTimerSheet(model: AppState, fullscreen: Boolean = false, onDismiss: () -> Unit) {
    val minutes by model.sleepTimerMinutes.collectAsState()
    val afterCurrent by model.sleepAfterCurrent.collectAsState()
    val background = if (fullscreen) Color(0xFF252529) else MaterialTheme.colorScheme.surface
    val foreground = if (fullscreen) Color.White else MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = if (fullscreen) 380.dp else 640.dp,
        containerColor = background,
        contentColor = foreground,
        dragHandle = null,
    ) {
        SleepTimerOptionsContent(
            minutes, afterCurrent, fullscreen, foreground,
            onMinutes = { model.platform.haptics.tick(); model.setSleepTimer(it); onDismiss() },
            onAfterCurrent = { model.platform.haptics.tick(); model.setSleepAfterCurrent(); onDismiss() },
            onDismiss = onDismiss,
        )
    }
}

/** Scrollable options retain their hit targets in short landscape windows and with larger fonts. */
@Composable
internal fun SleepTimerOptionsContent(
    minutes: Int, afterCurrent: Boolean, fullscreen: Boolean = false,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    onMinutes: (Int) -> Unit, onAfterCurrent: () -> Unit, onDismiss: () -> Unit,
) {
    var editingCustom by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }
    val customMinutes = customInput.toIntOrNull()?.takeIf { it in 1..180 }
    val invalid = customInput.isNotEmpty() && customMinutes == null
    Column(Modifier.fillMaxWidth().heightIn(max = if (fullscreen) 300.dp else 520.dp).imePadding()) {
        Text(
            if (editingCustom) "自定义定时关闭" else "定时结束后将暂停播放",
            color = foreground.copy(alpha = .6f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 16.dp),
        )
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup(),
        ) {
            if (editingCustom) {
                OutlinedTextField(
                    customInput, onValueChange = { customInput = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    label = { Text("时长（分钟）") },
                    supportingText = { Text(if (invalid) "请输入 1–180 的整数" else "支持 1–180 分钟",
                        color = if (invalid) MaterialTheme.colorScheme.error else foreground.copy(alpha = .6f)) },
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { customMinutes?.let(onMinutes) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = foreground, unfocusedTextColor = foreground,
                        focusedBorderColor = Accent, focusedLabelColor = Accent,
                        unfocusedLabelColor = foreground.copy(alpha = .6f),
                        unfocusedBorderColor = foreground.copy(alpha = .35f),
                    ),
                )
                TextButton(
                    onClick = { customMinutes?.let(onMinutes) }, enabled = customMinutes != null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) { Text("开启定时", color = if (customMinutes != null) Accent else foreground.copy(alpha = .4f)) }
            } else {
                SleepTimerOption("不开启", minutes == 0 && !afterCurrent, foreground) { onMinutes(0) }
                SleepTimerOption("播完当前", afterCurrent, foreground, onAfterCurrent)
                listOf(30, 60, 90).forEach { duration ->
                    SleepTimerOption("${duration}分钟后", minutes == duration && !afterCurrent, foreground) {
                        onMinutes(duration)
                    }
                }
                SleepTimerOption("自定义", minutes > 0 && minutes !in listOf(30, 60, 90), foreground) {
                    customInput = if (minutes > 0) minutes.toString() else ""
                    editingCustom = true
                }
            }
        }
        HorizontalDivider(color = foreground.copy(alpha = .08f), thickness = 6.dp)
        TextButton(
            onClick = { if (editingCustom) editingCustom = false else onDismiss() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (editingCustom) "返回" else "取消", color = foreground, fontSize = 16.sp) }
    }
}

@Composable
private fun SleepTimerOption(label: String, selected: Boolean, foreground: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) Accent else foreground, fontSize = 17.sp) }
    HorizontalDivider(color = foreground.copy(alpha = .05f))
}
