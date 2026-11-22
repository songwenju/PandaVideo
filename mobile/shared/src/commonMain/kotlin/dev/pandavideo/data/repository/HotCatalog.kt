package dev.pandavideo.data.repository

import dev.pandavideo.core.model.CatalogItem

/**
 * Reuses the hot catalog's source priority for the selected catalog's high-rated rows.
 * Sorting before merging keeps ratings descending within each source and lets newly loaded
 * Jianpian identities replace older catalog mirrors without losing priority to a higher score.
 */
internal fun mergeHighRatedCatalog(items: List<CatalogItem>): List<CatalogItem> =
    mergeHotCatalog(items.sortedByDescending { it.rating })

/**
 * Prefers source-owned Jianpian identities across all loaded pages, preserving source order.
 * Known remake years and content types remain separate. An unknown year is merged only when
 * there is at most one known release in the group; ambiguous releases retain their identities.
 */
internal fun mergeHotCatalog(items: List<CatalogItem>): List<CatalogItem> {
    fun name(item: CatalogItem) = ContentParsers.normalizedTitle(item.title)
        .replace(Regex("(国语|粤语|普通话|国配|粤配)(版)?$"), "")
    fun year(item: CatalogItem) = Regex("(?:18|19|20)\\d{2}").findAll(item.year)
        .map { it.value }.distinct().toList().singleOrNull().orEmpty()

    val ordered = items.sortedBy {
        if (JianpianSource.owns(it.playbackPageUrl.ifBlank { it.url })) 0 else 1
    }.distinctBy { it.url }
    val years = ordered.groupBy { it.kind to name(it) }
        .mapValues { (_, rows) -> rows.map(::year).filter(String::isNotBlank).distinct() }
    val seen = mutableSetOf<Triple<String, String, String>>()
    return ordered.filter { item ->
        val title = name(item)
        val known = years[item.kind to title].orEmpty()
        val release = year(item).ifBlank {
            if (known.size <= 1) known.singleOrNull().orEmpty() else item.url
        }
        seen.add(Triple(item.kind, title.ifBlank { item.url }, release))
    }
}
