package dev.pandavideo.core.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * [Haptics] backed by the system [Vibrator]. Predefined effects are preferred on API 29+
 * with amplitude-based one-shot/waveform fallbacks for API 26-28; devices without a
 * vibrator silently ignore every call.
 */
class AndroidHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = run {
        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        service?.takeIf { it.hasVibrator() }
    }

    override fun tick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            oneShot(durationMs = 15, amplitude = 45)
        }
    }

    override fun click() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            oneShot(durationMs = 22, amplitude = 110)
        }
    }

    override fun success() {
        // Two rising segments mirror the platform notification confirmation pattern.
        waveform(
            timings = longArrayOf(0, 35, 80, 55),
            amplitudes = intArrayOf(0, 90, 0, 180),
        )
    }

    override fun warning() {
        waveform(
            timings = longArrayOf(0, 60, 70, 90),
            amplitudes = intArrayOf(0, 180, 0, 120),
        )
    }

    private fun oneShot(durationMs: Long, amplitude: Int) {
        vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    private fun vibrate(effect: VibrationEffect) {
        try {
            vibrator?.vibrate(effect)
        } catch (_: Exception) {
            // Vendor vibrator services may reject effects in background; haptics are best-effort.
        }
    }
}
