package dev.pandavideo.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.pandavideo.core.player.PlayerController
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Small string key-value store backed by platform preferences. */
interface KeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

interface PlatformServices {
    /** Native offline-media adapter, retained across screen changes. */
    fun offlineEngine(): dev.pandavideo.data.repository.OfflineEngine =
        dev.pandavideo.data.repository.UnsupportedOfflineEngine
    /** Persistent directory containing private avatar JPEG files, or null when unsupported. */
    fun avatarDirectory(): String? = null
    /**
     * Deletes a superseded or discarded avatar from private storage.
     *
     * @param filename Relative avatar filename, never a path.
     */
    fun deleteAvatar(filename: String) {}
    /** Whether this host offers a DLNA renderer picker. */
    val dlnaSupported: Boolean get() = false
    /**
     * Finds UPnP renderer descriptor URLs within a bounded search window.
     *
     * @param host Optional television IPv4 address for unicast discovery.
     * @return Distinct device description URLs advertised by SSDP.
     */
    suspend fun discoverDlnaLocations(host: String? = null): List<String> = emptyList()
    /** Whether this host offers a native video route picker in addition to DLNA. */
    val nativeCastingSupported: Boolean get() = false
    /**
     * Renders the native video route picker.
     *
     * @param modifier Layout and sizing for the picker.
     * @param tint Icon color matching the surrounding controls.
     */
    @Composable
    fun CastingButton(modifier: Modifier, tint: Color = Color.White) {}
    /** Keeps playback controls visible for hosts primarily operated with a mouse. */
    val persistentPlayerControls: Boolean get() = false
    /** Absolute persistent SQLite path, or null for hosts without local catalog storage. */
    fun catalogDatabasePath(): String? = null
    /** Absolute expendable directory for downloaded poster images, or null to use Coil defaults. */
    fun posterCacheDirectory(): String? = null
    fun httpClient(): HttpClient
    fun player(): PlayerController
    fun storage(): KeyValueStorage
    val haptics: Haptics
    fun openLink(url: String)
    /** Opens the system settings page for this application when available. */
    fun openAppSettings() {}
    fun fullscreen(enabled: Boolean)
    /**
     * Matches system bar icon contrast to the visible app surfaces.
     *
     * @param lightStatusBarIcons Whether the status bar has a dark backdrop, including video.
     * @param darkTheme Whether the bottom system navigation area uses the dark app scheme.
     */
    fun setSystemBarAppearance(lightStatusBarIcons: Boolean, darkTheme: Boolean) {}
    /**
     * One-shot pulses emitted when the operating system denies a fullscreen request. On iOS
     * this happens while the portrait orientation lock is enabled, so the host can explain why
     * the device did not rotate. Platforms whose fullscreen request cannot be rejected never emit.
     */
    val fullscreenBlocked: Flow<Unit> get() = emptyFlow()
    /** Current media playback volume in 0f..1f. */
    fun currentVolume(): Float
    /** Sets media playback volume; the value is clamped to 0f..1f. */
    fun setVolume(value: Float)
    /** Current screen brightness in 0f..1f. */
    fun currentBrightness(): Float
    /** Overrides screen brightness while playing; the value is clamped to 0f..1f. */
    fun setBrightness(value: Float)
    /** Restores system-managed brightness after leaving the player. */
    fun resetBrightness()
    /** Whether the platform supports picture-in-picture on this device. */
    val pipSupported: Boolean
    /** Android shrinks the app window for PiP; iOS keeps the app's inline layout. */
    val pipResizesAppWindow: Boolean get() = false
    /** Emits true while the player is hosted inside a picture-in-picture window. */
    val pictureInPictureActive: StateFlow<Boolean>
    /** Starts picture-in-picture from a user action; returns false when unavailable. */
    fun enterPictureInPicture(): Boolean
}
/**
 * Hosts the native video surface without scaling playback controls.
 *
 * @param controller Player whose video is displayed.
 * @param modifier Layout of the fixed viewport.
 * @param zoom Centered picture scale on mobile, from 1 to 4; overflow is clipped to the viewport.
 */
@Composable
expect fun PlayerSurface(controller: PlayerController, modifier: Modifier, zoom: Float = 1f)

/** Wall-clock epoch milliseconds provided by the platform. */
internal expect fun currentEpochMillis(): Long

/**
 * Remembers a system photo picker that copies a selected image to private avatar storage.
 *
 * @param platform Host providing the private avatar directory.
 * @param onResult Selected relative filename, null on cancellation, or a failure.
 * @return Callback launching the system picker.
 */
@Composable
expect fun rememberAvatarPicker(platform: PlatformServices, onResult: (Result<String?>) -> Unit): () -> Unit

/** Resolves a private avatar filename without accepting paths or remote URLs. */
fun PlatformServices.avatarUri(filename: String): String? =
    if (filename.matches(Regex("[A-Za-z0-9-]+\\.jpg")))
        avatarDirectory()?.let { "file://$it/$filename" }
    else null
