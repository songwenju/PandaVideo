package dev.pandavideo.data.repository

import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.SearchResult
import dev.pandavideo.core.model.CatalogItem

/** Presentation only: catalog matching must retain every provider's independent resource. */
internal object SearchPresentation {
    fun rating(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0 && it <= 10 }

    fun year(item: SearchItem): String = Regex("(?:18|19|20)\\d{2}").findAll(item.year)
        .map { it.value }.distinct().toList().singleOrNull() ?: ContentParsers.titleYear(item.title).orEmpty()

    /** Removes source release/language labels only when the declared year corroborates them. */
    fun title(item: SearchItem): String {
        var title = ContentParsers.normalizedTitle(item.title)
        val year = year(item)
        if (year.isBlank()) return title
        repeat(2) {
            title = title.replace(Regex("(国语|粤语|普通话|国配|粤配)$"), "")
            for (suffix in listOf("${year}版", year, "${year.takeLast(2)}版")) {
                if (title.endsWith(suffix) && title.length > suffix.length) {
                    title = title.dropLast(suffix.length)
                    break
                }
            }
        }
        return title
    }

    fun kind(item: SearchItem): String = when {
        Regex("动画|动漫").containsMatchIn(item.genre + item.title) -> "animation"
        "纪录" in item.genre -> "documentary"
        "综艺" in item.genre -> "variety"
        Regex("电视剧|连续剧|国产剧|欧美剧|港剧|台剧|韩剧|日剧|泰剧|短剧")
            .containsMatchIn(item.genre) -> "tv"
        Regex("电影|片$").containsMatchIn(item.genre) ||
            Regex("HD|BD|正片", RegexOption.IGNORE_CASE).containsMatchIn(item.remark) -> "movie"
        Regex("第\\d+集|更新.*集|全\\d+集").containsMatchIn(item.remark) -> "tv"
        else -> ""
    }

    private fun region(item: SearchItem): String = when (val value = item.region.trim()) {
        "大陆", "中国", "中国大陆", "内地" -> "中国大陆"
        "香港", "中国香港" -> "中国香港"
        "台湾", "中国台湾" -> "中国台湾"
        else -> value
    }

    private fun sameRelease(first: SearchItem, second: SearchItem): Boolean {
        val year = year(first)
        if (year.isBlank() || year != year(second) || title(first) != title(second)) return false
        val firstKind = kind(first)
        val secondKind = kind(second)
        // Unknown shape cannot establish that a same-year row is the animation or live action
        // version. Keep those separate until both sources state the same shape.
        if (firstKind != secondKind) return false
        val firstRegion = region(first)
        val secondRegion = region(second)
        if (firstRegion.isNotBlank() && secondRegion.isNotBlank() && firstRegion != secondRegion) return false
        // Disjoint stated casts are evidence of separate versions, even in the same year.
        val casts = first.cast.split(Regex("[、,，/\\s]+")).filter(String::isNotBlank)
        return first.cast.isBlank() || second.cast.isBlank() ||
            ContentParsers.castOverlapScore(casts, second.cast) > 0
    }

    /** A score must belong to this exact release, never to a newer same-name remake. */
    fun matchesCatalog(item: SearchItem, catalog: CatalogItem): Boolean {
        if (year(item).isBlank() || year(item) != catalog.year ||
            title(item) != ContentParsers.normalizedTitle(catalog.title)) return false
        val kind = kind(item)
        if (kind.isNotBlank() && kind != catalog.kind &&
            !(kind == "animation" && "动画" in catalog.genres)) return false
        return item.cast.isBlank() || catalog.casts.isEmpty() ||
            ContentParsers.castOverlapScore(catalog.casts, item.cast) > 0
    }

    fun apply(result: SearchResult, query: String): SearchResult {
        val groups = mutableListOf<MutableList<SearchItem>>()
        for (item in result.results.distinctBy { it.detailUrl }) {
            // Check every member, so an unknown row cannot bridge conflicting known versions.
            val group = groups.firstOrNull { rows -> rows.all { sameRelease(it, item) } }
            if (group == null) groups += mutableListOf(item) else group += item
        }
        val target = ContentParsers.normalizedTitle(query)
        val items = groups.map { rows ->
            val selected = rows.firstOrNull { it.playable } ?: rows.first()
            selected.copy(
                rating = rows.firstNotNullOfOrNull { rating(it.rating) },
                thumbnailUrl = selected.thumbnailUrl.ifBlank { rows.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl.orEmpty() },
                year = selected.year.ifBlank { year(selected) },
                region = selected.region.ifBlank { rows.firstOrNull { it.region.isNotBlank() }?.region.orEmpty() },
                genre = selected.genre.ifBlank { rows.firstOrNull { it.genre.isNotBlank() }?.genre.orEmpty() },
                cast = selected.cast.ifBlank { rows.firstOrNull { it.cast.isNotBlank() }?.cast.orEmpty() },
            )
        }.sortedWith(compareByDescending<SearchItem> { rating(it.rating) ?: -1.0 }
            .thenBy {
                val title = title(it)
                when {
                    title == target -> 0
                    title.startsWith(target) -> 1
                    target in title -> 2
                    else -> 3
                }
            }.thenByDescending { year(it).toIntOrNull() ?: 0 })
        return result.copy(results = items,
            sources = result.sources.map { it.copy(count = items.count { item -> item.source == it.name }) })
    }
}
