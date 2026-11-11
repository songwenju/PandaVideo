package dev.pandavideo.core.platform

import dev.pandavideo.core.player.PlayerController
import dev.pandavideo.data.repository.isWebUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.net.URI
import java.util.prefs.Preferences

/**
 * Window-level bridge implemented by the desktop entry so the shared player can toggle
 * fullscreen without depending on the Compose Desktop window APIs.
 */
interface DesktopWindowController {
    fun setFullscreen(enabled: Boolean)
}

/**
 * JVM/desktop [PlatformServices]. Network and storage reuse the same OkHttp/Ktor and
 * key-value contracts as the mobile targets; screen brightness has no desktop equivalent
 * and is therefore a no-op.
 */
class DesktopPlatform(
    private val window: DesktopWindowController? = null,
) : PlatformServices {
    override val persistentPlayerControls: Boolean = true
    private var activePlayer: DesktopPlayer? = null
    private var volumeLevel = 1f

    override fun httpClient(): HttpClient = HttpClient(OkHttp) { followRedirects = false }

    override fun player(): PlayerController =
        DesktopPlayer(volumeLevel).also { activePlayer = it }

    override fun storage(): KeyValueStorage = PreferencesStorage
    override fun catalogDatabasePath(): String = java.io.File(
        System.getProperty("user.home"), "Library/Application Support/PandaVideo/catalog-v1.db",
    ).also { it.parentFile.mkdirs() }.absolutePath
    override fun posterCacheDirectory(): String = java.io.File(
        System.getProperty("user.home"), "Library/Caches/PandaVideo/posters",
    ).absolutePath

    // Desktop JVM hosts expose no portable tactile actuator; feedback stays silent.
    override val haptics: Haptics = SilentHaptics

    override fun openLink(url: String) {
        if (!isWebUrl(url)) return
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (_: Exception) {
            // Browsers may be unavailable in sandboxed sessions; ignore rather than crash.
        }
    }

    override fun fullscreen(enabled: Boolean) {
        try {
            window?.setFullscreen(enabled)
        } catch (_: Exception) {
            // Window may already be closed during teardown.
        }
    }

    override fun currentVolume(): Float = volumeLevel

    override fun setVolume(value: Float) {
        volumeLevel = value.coerceIn(0f, 1f)
        activePlayer?.setVolume(volumeLevel)
    }

    override fun currentBrightness(): Float = 0.5f

    override fun setBrightness(value: Float) {
        // Desktops manage display brightness at the OS level; no in-app override exists.
    }

    override fun resetBrightness() {
        // Symmetric no-op with setBrightness.
    }

    // Desktop v1 has no picture-in-picture window mode; the shared UI hides the action when
    // pipSupported is false, so the flow stays idle and enter attempts always report failure.
    override val pipSupported: Boolean = false
    override val pictureInPictureActive: StateFlow<Boolean> =
        MutableStateFlow(false).asStateFlow()

    override fun enterPictureInPicture(): Boolean = false
}

/** Preferences-backed storage; mirrors the Android SharedPreferences named "suixinkan_user". */
internal object PreferencesStorage : KeyValueStorage {
    private val prefs = Preferences.userRoot().node("dev/pandavideo/suixinkan_user")

    override fun getString(key: String): String? = prefs.get(key, null)

    override fun putString(key: String, value: String) {
        prefs.put(key, value)
        prefs.flush()
    }

    override fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }
}

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()
