package dev.pandavideo.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Keeps the video layout in place while the host restores the inline window orientation. */
internal class FullscreenLayoutState {
    var requested by mutableStateOf(false)
        private set
    private var inlineLandscape = false
    private var restoringLandscape by mutableStateOf<Boolean?>(null)
    val restoring: Boolean get() = restoringLandscape != null

    /** Requests the native mode, remembering the window shape before entering fullscreen. */
    fun request(enabled: Boolean, landscape: Boolean) {
        if (enabled == requested) return
        if (enabled) {
            if (restoringLandscape == null) inlineLandscape = landscape
            restoringLandscape = null
        } else {
            restoringLandscape = inlineLandscape.takeIf { it != landscape }
        }
        requested = enabled
    }

    /** Uses the old video layout until Compose receives the restored window constraints. */
    fun usesFullscreenLayout(landscape: Boolean): Boolean =
        requested || restoringLandscape?.let { it != landscape } == true

    /** Completes restoration so later user-driven window changes retain the inline layout. */
    fun windowChanged(landscape: Boolean) {
        if (restoringLandscape == landscape) restoringLandscape = null
    }

    /** Releases the layout when a host keeps its current orientation instead of resizing. */
    fun finishUnchangedWindow() {
        restoringLandscape = null
    }

    /** PiP owns its viewport and must never wait for the normal inline window to return. */
    fun enterPictureInPicture() {
        restoringLandscape = null
        requested = false
    }
}
