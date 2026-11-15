package dev.pandavideo.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier

@Composable
internal actual fun DoubanLoginView(modifier: Modifier, onError: () -> Unit, onLoginFinished: () -> Unit) {
    Text("请在 Android 或 iOS 应用中连接豆瓣账号", modifier)
}
