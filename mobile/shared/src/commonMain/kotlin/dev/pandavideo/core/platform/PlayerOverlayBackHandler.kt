package dev.pandavideo.core.platform

import androidx.compose.runtime.Composable

/** Intercepts platform back navigation while an in-player overlay is visible. */
@Composable
internal expect fun PlayerOverlayBackHandler(enabled: Boolean, onBack: () -> Unit)
