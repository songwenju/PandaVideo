package dev.pandavideo.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.core.platform.rememberAvatarPicker
import dev.pandavideo.data.user.UserProfile
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.LocalDarkTheme
import dev.pandavideo.ui.common.NavigationIcons

/** Edits a local profile while retaining ownership of unsaved avatar files. */
@Composable
internal fun EditProfileDialog(
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
    Dialog(
        onDismissRequest = { if (!picking) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ProfileEditorCard(
            profile = profile.copy(nickname = nickname, bio = bio, avatar = avatar),
            platform = platform, picking = picking, error = error,
            onNickname = { nickname = it.take(12) }, onBio = { bio = it.take(40) },
            onPick = { picking = true; error = false; pick() },
            onDismiss = onDismiss,
            onSave = {
                onSave(nickname, bio, avatar)
                // The saved profile now owns this file; dismissal must not remove it.
                draft.value = null
            },
        )
    }
}

/** Shared form content, with fixed actions and a scrollable editing area. */
@Composable
internal fun ProfileEditorCard(
    profile: UserProfile, platform: PlatformServices, picking: Boolean, error: Boolean,
    onNickname: (String) -> Unit, onBio: (String) -> Unit,
    onPick: () -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
) {
    val nickname = profile.nickname
    val bio = profile.bio
    val avatar = profile.avatar
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val focus = LocalFocusManager.current
    val fieldColor = if (dark) colors.surfaceVariant.copy(alpha = 0.65f) else Color(0xFFF5F6F8)
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = fieldColor, unfocusedContainerColor = fieldColor,
        disabledContainerColor = fieldColor,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent, cursorColor = Accent,
    )

        BoxWithConstraints(
            Modifier.fillMaxWidth().safeDrawingPadding().imePadding().padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(28.dp), color = colors.surface,
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("编辑资料", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = colors.onSurface)
                    Spacer(Modifier.height(20.dp))
                    // Keep the actions visible when a keyboard or a short window constrains
                    // the dialog. Only the editable content needs to scroll.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(Brush.horizontalGradient(listOf(
                                    if (dark) Color(0xFF3D2B24) else Color(0xFFFFE9DE),
                                    if (dark) Color(0xFF203B38) else Color(0xFFE4F6F0),
                                )))
                                .clickable(enabled = !picking, role = Role.Button) {
                                    focus.clearFocus()
                                    onPick()
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(Modifier.size(66.dp)) {
                                Box(Modifier.fillMaxSize().border(2.dp, colors.surface, CircleShape).padding(3.dp)) {
                                    ProfileAvatar(platform, avatar, Modifier.fillMaxSize())
                                }
                                Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape)
                                    .background(Accent).border(2.dp, colors.surface, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    if (picking) CircularProgressIndicator(
                                        Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp,
                                    ) else Icon(NavigationIcons.Edit, contentDescription = null,
                                        modifier = Modifier.size(12.dp), tint = Color.White)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(if (picking) "正在处理…" else "更换头像", fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                                Text("从相册选择一张照片", fontSize = 11.sp,
                                    color = colors.onSurfaceVariant)
                            }
                            Icon(NavigationIcons.Chevron, contentDescription = null,
                                modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        }
                        if (error) Text("图片读取失败，请重新选择", fontSize = 12.sp,
                            color = colors.error, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(20.dp))
                        ProfileFieldHeading("昵称", nickname.length, 12)
                        TextField(
                            value = nickname, onValueChange = onNickname,
                            placeholder = { Text("输入你的昵称", fontSize = 14.sp) },
                            singleLine = true, enabled = !picking,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                .semantics { contentDescription = "昵称" },
                            shape = RoundedCornerShape(13.dp), colors = fieldColors,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        )
                        Spacer(Modifier.height(18.dp))
                        ProfileFieldHeading("个人简介", bio.length, 40)
                        TextField(
                            value = bio, onValueChange = onBio,
                            placeholder = { Text("聊聊你喜欢的电影或剧集", fontSize = 14.sp) },
                            minLines = 2, maxLines = 3, enabled = !picking,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                .semantics { contentDescription = "个人简介" },
                            shape = RoundedCornerShape(13.dp), colors = fieldColors,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            onClick = onDismiss, enabled = !picking,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = colors.onSurfaceVariant, containerColor = fieldColor,
                            ),
                        ) { Text("取消", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                        Button(
                            onClick = onSave,
                            enabled = !picking, modifier = Modifier.weight(1.4f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        ) { Text("保存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
}

@Composable
private fun ProfileFieldHeading(label: String, length: Int, limit: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text("$length / $limit", fontSize = 11.sp,
            color = if (length == limit) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
