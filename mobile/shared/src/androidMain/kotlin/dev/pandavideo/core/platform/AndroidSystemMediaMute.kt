package dev.pandavideo.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.Player

/** Returns decoder gain for the system's media mute state, without rescaling volume. */
internal fun systemMediaGain(index: Int, muted: Boolean): Float =
    if (index <= 0 || muted) 0f else 1f

/**
 * Enforces system media mute on TV firmware that retains audible output at index zero.
 *
 * Positive volume remains entirely controlled by Android's media stream. This gate only
 * supplies a hard decoder mute at zero or explicit system mute, including digital outputs
 * whose vendor volume curve can differ from the built-in speaker. Construct and close on
 * the player's application thread; the context's receiver also runs on that main thread.
 *
 * @param context Context used to read system media volume and receive changes.
 * @param player Foreground or service-owned player whose decoder gain is gated.
 */
class AndroidSystemMediaMute(context: Context, private val player: Player) : AutoCloseable {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = sync()
    }

    init {
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }
        // Read AudioManager rather than trusting broadcast extras. System broadcasts on
        // vendor TVs can originate in another privileged process, requiring EXPORTED.
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        sync()
    }

    private fun sync() {
        val gain = systemMediaGain(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.isStreamMute(AudioManager.STREAM_MUSIC))
        if (player.volume != gain) player.volume = gain
    }

    /** Removes the receiver before its player is released. */
    override fun close() {
        app.unregisterReceiver(receiver)
    }
}
