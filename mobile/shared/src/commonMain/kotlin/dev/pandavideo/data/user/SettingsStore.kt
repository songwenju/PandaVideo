package dev.pandavideo.data.user

import dev.pandavideo.core.platform.KeyValueStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** User-selectable color scheme mode. */
enum class ThemeMode(val key: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色模式"),
    Dark("dark", "深色模式"),
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}

/**
 * Persisted application preferences. New knobs (default speed, sleep timer, …) extend this
 * class with safe defaults; unknown JSON keys are ignored on load.
 */
@Serializable
data class AppSettings(
    @SerialName("theme_mode") val themeModeKey: String = ThemeMode.System.key,
    val defaultRate: Float = 1f,
    val rememberVolume: Boolean = false,
    val rememberBrightness: Boolean = false,
    val volume: Float = 0.5f,
    val brightness: Float = 0.5f,
) {
    val themeMode: ThemeMode get() = ThemeMode.fromKey(themeModeKey)
    internal fun normalized() = copy(
        defaultRate = defaultRate.takeIf { it in SupportedRates } ?: 1f,
        volume = volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
        brightness = brightness.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
    )
}

internal val SupportedRates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Owns persisted [AppSettings] backed by the platform key-value storage. */
class SettingsStore(private val storage: KeyValueStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(load())
    val settings = mutable.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        update { it.copy(themeModeKey = mode.key) }
    }
    fun setDefaultRate(rate: Float) = update { it.copy(defaultRate = rate) }
    fun setRememberVolume(enabled: Boolean, current: Float) =
        update { it.copy(rememberVolume = enabled, volume = if (enabled) current else it.volume) }
    fun setRememberBrightness(enabled: Boolean, current: Float) =
        update { it.copy(rememberBrightness = enabled, brightness = if (enabled) current else it.brightness) }
    fun saveLevels(volume: Float, brightness: Float) = update {
        it.copy(volume = if (it.rememberVolume) volume else it.volume,
            brightness = if (it.rememberBrightness) brightness else it.brightness)
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(mutable.value).normalized()
        if (next == mutable.value) return
        mutable.value = next
        storage.putString(KEY_SETTINGS, json.encodeToString(next))
    }

    private fun load(): AppSettings = try {
        storage.getString(KEY_SETTINGS)?.let { json.decodeFromString<AppSettings>(it).normalized() } ?: AppSettings()
    } catch (_: Exception) {
        AppSettings()
    }

    private companion object {
        const val KEY_SETTINGS = "settings"
    }
}
