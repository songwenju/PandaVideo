package dev.pandavideo.ui.player

import androidx.compose.runtime.Composable

/** PiP changes overlays without releasing the native surface used by the system window. */
@Composable
internal fun PersistentVideoContent(
    pipMode: Boolean,
    surface: @Composable () -> Unit,
    overlays: @Composable () -> Unit,
    audioOnly: Boolean = false,
    audioBackdrop: @Composable () -> Unit = {},
) {
    surface()
    // Mode changes must update the native binding, never destroy/recreate the host view.
    if (audioOnly) audioBackdrop()
    if (!pipMode) overlays()
}
