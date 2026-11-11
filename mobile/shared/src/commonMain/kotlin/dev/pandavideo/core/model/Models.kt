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

/** Homepage keys retain their original meanings and always request rating order. */
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
)
@Serializable
data class MatchResult(val playable: Boolean, @SerialName("page_url") val pageUrl: String = "")
data class PlayerRequest(
    val page: String? = null, val title: String? = null, val episode: String? = null,
    /** In-memory stream failures for this load; never persisted in bookmarks or progress. */
    val rejectedMedia: Set<String> = emptySet(),
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
