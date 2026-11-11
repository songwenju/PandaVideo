package dev.pandavideo.core.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberAvatarPicker(platform: PlatformServices, onResult: (Result<String?>) -> Unit): () -> Unit =
    { onResult(Result.failure(UnsupportedOperationException("Photo picker unavailable"))) }
