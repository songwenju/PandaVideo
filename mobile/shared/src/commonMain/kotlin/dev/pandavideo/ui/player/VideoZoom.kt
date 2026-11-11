package dev.pandavideo.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Rejects degenerate pointer distances and keeps the picture between its original size and 4x. */
internal fun videoZoom(current: Float, multiplier: Float): Float =
    if (multiplier.isFinite() && multiplier > 0f) (current * multiplier).coerceIn(1f, 4f)
    else current

/**
 * Claims multi-touch before child tap/drag detectors. Once claimed, consume the entire
 * sequence, including the last remaining finger, so a pinch never becomes a seek or tap.
 * Single-finger sequences pass through untouched to the existing player controls.
 */
internal fun Modifier.videoPinch(
    enabled: Boolean,
    onZoom: (Float) -> Unit,
    onActive: (Boolean) -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var claimed = false
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    if (!claimed) {
                        claimed = true
                        onActive(true)
                    }
                    onZoom(event.calculateZoom())
                }
                if (claimed) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        } finally {
            if (claimed) onActive(false)
        }
    }
}
