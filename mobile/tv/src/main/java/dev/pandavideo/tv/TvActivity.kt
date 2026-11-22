package dev.pandavideo.tv

import android.os.Bundle
import android.content.Intent
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.pandavideo.core.platform.AndroidPlatform
import dev.pandavideo.tvui.AndroidTvApp

/** Hosts the direct-source television application and its remote-focused presentation. */
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent { AndroidTvApp(AndroidPlatform(this)) {
            startActivity(Intent(this, dev.pandavideo.tv.cast.ReceiverActivity::class.java))
        } }
    }
}
