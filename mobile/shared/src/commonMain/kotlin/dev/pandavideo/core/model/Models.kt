package dev.pandavideo.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CatalogItem(
    val title: String, val url: String, val year: String = "",
    val rating: Double? = null, val cover: String = "", val casts: List<String> = emptyList(),
    // Content type tag ("movie" / "tv" / "documentary" / "animation" / "variety"), used to keep related
    // recommendations on the player page within the same category. Defaults to "movie" so
    // legacy cached Top 250 rows remain valid after a schema upgrade.
    val kind: String = "movie",
    // Plot summary from catalog metadata. Playback sources often omit it, especially for
    // Pomo routes, so the player details panel falls back to this value.
    val synopsis: String = "",
    /** Source-owned genre labels, independent from the playback content type. */
    val genres: List<String> = emptyList(),
    /** Source-owned production countries; co-productions can belong to multiple regions. */
    val countries: List<String> = emptyList(),
    /** Verified stable provider detail identity; expiring media URLs must never be stored here. */
    @SerialName("playback_page_url") val playbackPageUrl: String = "",
    /** Whether the catalog subject is episodic, independent of animation/documentary genres. */
    @SerialName("is_series") val isSeries: Boolean? = null,
)
@Serializable
data class CatalogPage(
    val items: List<CatalogItem>, val start: Int, @SerialName("page_size") val pageSize: Int,
    @SerialName("has_more") val hasMore: Boolean, val label: String = "", val stale: Boolean = false,
) {
    fun nextStart(): Int = start + pageSize
}
data class BrowseFilters(
    val type: String = "movie", val genre: String = "all", val year: String = "all",
    val sort: String = "hot", val country: String = "all",
)

/** Minimum Douban score for every homepage category, including cached results. */
internal const val HomeMinimumRating = 8.0

/**
 * Rejects positively identified non-drama programs from television catalogs.
 *
 * Douban also assigns sports broadcasts, concerts and courses the TV subtype; older bundled
 * rows have no genre metadata. Match structured event/course titles for those sparse rows,
 * while retaining unknown programs and sports-themed dramas rather than guessing by score.
 */
internal fun CatalogItem.isTelevisionDrama(): Boolean {
    if (kind in setOf("documentary", "variety") ||
        genres.any { it in setOf("纪录片", "真人秀", "脱口秀", "综艺") }) return false
    val name = title.trim()
    val datedEvent = Regex("""(?:19|20)\d{2}|第\s*\d+\s*届""").containsMatchIn(name) &&
        Regex("""(?:奥运会|冬奥会|亚运会|世界杯|锦标赛|运动会)(?:开幕式|闭幕式)?$""").containsMatchIn(name)
    val concert = Regex("""(?:演唱会|音乐会|音乐厅)$""").containsMatchIn(name)
    val course = Regex("""^(?:MIT\s|3Blue\s*1Brown\b|十分钟速成课[:：])""",
        RegexOption.IGNORE_CASE).containsMatchIn(name)
    return !datedEvent && !concert && !course
}

/** Homepage keys select full upstream genre/type catalogs independently of Top 250. */
internal val HomeCatalogFilters = linkedMapOf(
    "tv" to BrowseFilters(type = "tv", sort = "rating"),
    "comedy" to BrowseFilters(genre = "comedy", sort = "rating"),
    "anime" to BrowseFilters(type = "animation", sort = "rating"),
    "nature" to BrowseFilters(type = "documentary", genre = "nature", sort = "rating"),
    "movie" to BrowseFilters(sort = "rating"),
    "documentary" to BrowseFilters(type = "documentary", sort = "rating"),
    "drama" to BrowseFilters(genre = "drama", sort = "rating"),
    "action" to BrowseFilters(genre = "action", sort = "rating"),
    "romance" to BrowseFilters(genre = "romance", sort = "rating"),
    "scifi" to BrowseFilters(genre = "scifi", sort = "rating"),
    "mystery" to BrowseFilters(genre = "mystery", sort = "rating"),
    "finance" to BrowseFilters(genre = "finance", sort = "rating"),
    "biography" to BrowseFilters(genre = "biography", sort = "rating"),
    "war" to BrowseFilters(genre = "war", sort = "rating"),
    "hollywood" to BrowseFilters(genre = "hollywood", sort = "rating"),
)

/** Stable region keys and upstream tags shared by the filter UI and request validation. */
internal val BrowseCountries = linkedMapOf(
    "all" to "全部地区",
    "cn" to "中国大陆", "hk" to "中国香港", "tw" to "中国台湾",
    "us" to "美国", "jp" to "日本", "kr" to "韩国",
    "gb" to "英国", "fr" to "法国", "de" to "德国",
    "it" to "意大利", "es" to "西班牙", "in" to "印度", "th" to "泰国",
    "ca" to "加拿大", "au" to "澳大利亚", "ru" to "俄罗斯",
)
@Serializable
data class SearchItem(
    val title: String, val source: String,
    @SerialName("source_id") val sourceId: String = "", val playable: Boolean = false,
    @SerialName("detail_url") val detailUrl: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    val year: String = "", val region: String = "", val genre: String = "",
    val cast: String = "", val remark: String = "",
    /** Verified Douban score on the ten-point scale; absent or unverified scores stay null. */
    val rating: Double? = null,
)
@Serializable
data class SourceCount(val name: String, val count: Int, val status: String)
@Serializable
data class SearchResult(
    val results: List<SearchItem>, val sources: List<SourceCount> = emptyList(),
    val errors: List<String> = emptyList(), val stale: Boolean = false,
)
@Serializable
data class Episode(val path: String, val label: String, val line: Int)
@Serializable
data class PlaybackInfo(
    val title: String, val url: String, val kind: String, val episodes: List<Episode> = emptyList(),
    val selected: String = "", @SerialName("page_url") val pageUrl: String, val source: String = "",
    val synopsis: String = "",
    /** Detail-page area label (e.g. "美国"); empty when the source does not expose one. */
    val region: String = "",
)
@Serializable
data class MatchResult(val playable: Boolean, @SerialName("page_url") val pageUrl: String = "")
data class PlayerRequest(
    val page: String? = null, val title: String? = null, val episode: String? = null,
    /** In-memory stream failures for this load; never persisted in bookmarks or progress. */
    val rejectedMedia: Set<String> = emptySet(),
    /** Applies completion-aware episode choice only when reopening a saved title. */
    val resumeHistory: Boolean = false,
    /** Failed source-owned episode paths; rotating media tokens must not reuse a failed line. */
    val rejectedEpisodes: Set<String> = emptySet(),
)

/** Returns the next episode in the selected source line, preserving server order. */
fun nextEpisode(info: PlaybackInfo): Episode? {
    val selected = info.episodes.find { it.path == info.selected } ?: return null
    val line = info.episodes.filter { it.line == selected.line }
    return line.getOrNull(line.indexOf(selected) + 1)
}

fun appendCatalog(current: List<CatalogItem>, incoming: List<CatalogItem>): List<CatalogItem> =
    (current + incoming).distinctBy { it.url }

/** Formats milliseconds as m:ss, never negative; hours roll into minutes (e.g. 60:00). */
fun formatMinutesSeconds(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
