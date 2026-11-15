package dev.pandavideo.core.player

/**
 * Keeps a host idle timer disabled while video surfaces are mounted.
 *
 * Acquire and release on the UI thread. Overlapping inline/fullscreen surfaces share ownership,
 * so disposing the old surface cannot restore auto-lock while its replacement is still visible.
 */
internal class ScreenWakeLock(
    private val readDisabled: () -> Boolean,
    private val writeDisabled: (Boolean) -> Unit,
) {
    private var owners = 0
    private var originalDisabled = false

    /** Returns an idempotent release action that restores the original policy for the last owner. */
    fun acquire(): () -> Unit {
        if (owners == 0) {
            originalDisabled = readDisabled()
            writeDisabled(true)
        }
        owners++
        var released = false
        return {
            if (!released) {
                released = true
                owners--
                if (owners == 0) writeDisabled(originalDisabled)
            }
        }
    }
}
