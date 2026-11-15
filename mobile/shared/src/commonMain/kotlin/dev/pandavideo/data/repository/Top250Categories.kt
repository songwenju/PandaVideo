package dev.pandavideo.data.repository

import dev.pandavideo.core.model.BrowseCountries
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.model.HomeCatalogFilters
import dev.pandavideo.core.model.HomeMinimumRating

/** Classifies only ranking members; curated themes never add titles from outside the ranking. */
internal object Top250Categories {
    private val genreLabels = mapOf(
        "comedy" to "喜剧", "anime" to "动画", "documentary" to "纪录片", "drama" to "剧情",
        "action" to "动作", "romance" to "爱情", "scifi" to "科幻", "mystery" to "悬疑",
        "biography" to "传记", "war" to "战争",
    )
    // Douban has no standard finance/nature genre. These explicit subject identities cover
    // stockbroker careers / cheque fraud and wildlife protection respectively. The list is
    // intersected with the actual ranking, rather than guessing genres from title keywords.
    private val finance = setOf("1849031", "1305487") // The Pursuit of Happyness, Catch Me If You Can.
    private val nature = setOf("3442220", "1308857") // The Cove, Kekexili.
    private val regionAliases = mapOf(
        "中国大陆" to setOf("中国大陆", "中国"),
        "中国香港" to setOf("中国香港", "香港"),
        "中国台湾" to setOf("中国台湾", "台湾"),
    )

    fun page(pages: List<CatalogPage>, kind: String, country: String, start: Int): CatalogPage {
        val label = if (kind == "top250") "Top 250" else
            "Top 250 · " + (genreLabels[kind] ?: when (kind) {
                "movie" -> "电影"; "nature" -> "自然"; "finance" -> "金融"; else -> "好莱坞"
            })
        val items = pages.flatMap { it.items }.distinctBy { it.url }.filter { item ->
            item.rating != null && item.rating in HomeMinimumRating..10.0 &&
                matches(item, kind, country) && inRegion(item, country)
        }.sortedByDescending { it.rating }
        return CatalogPage(
            items.drop(start).take(20), start, 20, start + 20 < items.size,
            label + if (country == "all") "" else " · ${BrowseCountries.getValue(country)}",
            stale = pages.any { it.stale },
        )
    }

    private fun matches(item: CatalogItem, kind: String, country: String): Boolean = when (kind) {
        "top250", "movie" -> true
        "finance" -> DoubanArtwork.subjectId(item.url) in finance
        "nature" -> DoubanArtwork.subjectId(item.url) in nature
        // Retain the existing regional override: a selected region replaces the implied US
        // constraint. Only live-action action/adventure/fantasy/sci-fi films are "blockbusters".
        "hollywood" -> item.kind == "movie" &&
            item.genres.any { it in setOf("动作", "冒险", "奇幻", "科幻") } &&
            (country != "all" || "美国" in item.countries)
        else -> HomeCatalogFilters.containsKey(kind) && genreLabels[kind] in item.genres
    }

    private fun inRegion(item: CatalogItem, country: String): Boolean {
        if (country == "all") return true
        val label = BrowseCountries.getValue(country)
        return item.countries.any { it in (regionAliases[label] ?: setOf(label)) }
    }
}
