package dev.pandavideo.data.user

import dev.pandavideo.core.platform.KeyValueStorage
import dev.pandavideo.core.player.SkipDurations
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stores bounded, source-owned series preferences independently of global application settings. */
class SkipDurationsStore(private val storage: KeyValueStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entries: MutableMap<String, SkipDurations> = try {
        storage.getString(KEY)?.takeIf { it.length <= 300_000 }
            ?.let { json.decodeFromString<Map<String, SkipDurations>>(it) }
            .orEmpty().filter { (key, value) -> key.length in 1..2_048 && value.valid }
            .entries.take(500).associate { titlePage(it.key) to it.value }.toMutableMap()
    } catch (_: Exception) { mutableMapOf() }

    /**
     * Reads a saved preference, enabling both edges by default for an unconfigured series.
     *
     * @param page Source-owned detail page.
     * @param seconds Regional fallback used only when no explicit preference exists.
     * @return Saved durations, including an explicit disabled value, or bounded defaults.
     */
    fun get(page: String, seconds: Int = SkipDurations.DefaultSeconds): SkipDurations =
        entries[titlePage(page)] ?: SkipDurations().withDefaultFallback(seconds.coerceIn(0, 300))

    /** Saves validated durations; invalid input never overwrites a previous preference. */
    fun save(page: String, value: SkipDurations): Boolean {
        val key = titlePage(page)
        if (key.length !in 1..2_048 || !value.valid) return false
        val next = entries.toMutableMap()
        next.remove(key)
        // Zero is an explicit opt-out now that missing preferences enable skipping.
        // Removing it would silently re-enable both edges on the next episode or restart.
        next[key] = value
        while (next.size > 500) next.remove(next.keys.first())
        var encoded = json.encodeToString(next)
        while (encoded.length > 300_000 && next.size > 1) {
            next.remove(next.keys.first())
            encoded = json.encodeToString(next)
        }
        storage.putString(KEY, encoded)
        entries.clear()
        entries.putAll(next)
        return true
    }

    /** Switches automatic skipping for one series, filling missing edges with [seconds]. */
    fun setEnabled(page: String, enabled: Boolean, seconds: Int = SkipDurations.DefaultSeconds): Boolean =
        save(page, if (enabled) get(page, seconds).withDefaultFallback(seconds) else SkipDurations())

    private companion object { const val KEY = "series_skip_durations" }
}
