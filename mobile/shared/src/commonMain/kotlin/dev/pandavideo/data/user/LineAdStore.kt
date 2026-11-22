package dev.pandavideo.data.user

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.platform.KeyValueStorage
import dev.pandavideo.data.repository.isWebUrl
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable manual reports, independent of expiring speed measurements and media URLs. */
class LineAdStore(private val storage: KeyValueStorage) {
    private val json = Json
    private val mutable = MutableStateFlow(try {
        storage.getString(KEY)?.takeIf { it.length <= 300_000 }
            ?.let { json.decodeFromString<Set<String>>(it) }
            .orEmpty().filter { it.length in 1..4_096 }.take(1_000).toSet()
    } catch (_: Exception) { emptySet() })
    /** Observable source/line identities reported by the viewer; absence means unknown. */
    val marks = mutable.asStateFlow()

    /**
     * Tests a source-owned line report across episodes and signed media address changes.
     * Named lines are shared across a provider's titles; unnamed numeric lines are title-local.
     */
    fun hasAds(page: String, episode: Episode): Boolean = key(page, episode) in mutable.value

    /** Saves or removes a report; a failed write leaves the published state unchanged. */
    fun setHasAds(page: String, episode: Episode, marked: Boolean): Boolean {
        val key = key(page, episode) ?: return false
        val next = if (marked) mutable.value + key else mutable.value - key
        if (next.size > 1_000) return false
        val payload = json.encodeToString(next)
        if (payload.length > 300_000) return false
        try { storage.putString(KEY, payload) } catch (_: Exception) { return false }
        mutable.value = next
        return true
    }

    private fun key(page: String, episode: Episode): String? {
        if (!isWebUrl(page) || page.length > 2_048 || episode.line <= 0) return null
        // Aggregated mirrors reserve high line IDs for presentation. Reports still belong
        // to the original provider and original line, not to the title's initial provider.
        if (episode.line >= 100_000) {
            val owner = episode.path.substringBefore('#')
            val originalLine = episode.path.substringAfter('#', "").substringBefore('-').toIntOrNull()
                ?: return null
            if (originalLine <= 0 || !isWebUrl(owner)) return null
            return key(owner, episode.copy(line = originalLine,
                lineName = episode.lineName.removePrefix("荐片 · ").removePrefix("Pomo · ")))
        }
        val url = try { Url(page) } catch (_: Exception) { return null }
        // Pomo aggregates independent sites behind one host. Keep each upstream site
        // separate, and never treat an ordinal such as "线路1" as a global provider name.
        val provider = "${url.host.lowercase()}:${url.port}"
        val site = if (url.host == "pomo.mom") url.parameters["site"].orEmpty() else ""
        val name = episode.lineName.trim()
        if (name.length > 160) return null
        val named = name.isNotBlank() && !Regex("线路\\s*\\d+|\\d+").matches(name)
        return json.encodeToString(if (named) listOf(provider, site, name)
            else listOf(provider, site, page.substringBefore('#'), "line:${episode.line}"))
    }

    private companion object { const val KEY = "manual_line_ads_v1" }
}
