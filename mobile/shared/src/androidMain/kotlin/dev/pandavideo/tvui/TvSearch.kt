package dev.pandavideo.tvui

import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.data.repository.SearchPresentation

/** Maps television genres onto the three search scopes supported by playback providers. */
internal fun tvSearchSourceType(kind: String): String =
    if (kind in setOf("movie", "tv")) kind else "all"

/** Keeps sparse provider metadata visible until its content type can be established. */
internal fun tvSearchResults(items: List<SearchItem>, kind: String, query: String = ""): List<SearchItem> {
    val visible = items.filter { item ->
        // Episode counts establish serial shape, not genre: animation and documentaries
        // also say "更新至第1180集". Only use title/genre evidence for this UI filter.
        val declared = SearchPresentation.kind(item.copy(remark = ""))
        item.playable && (kind == "all" || declared.isBlank() || declared == kind)
    }.distinctBy { it.detailUrl }
    if (query.isBlank()) return visible
    // Providers can match initials but order every release by date. Reuse the local
    // phonetic ranking so HZW finds the 1999 series before newer spin-offs or loose
    // matches. Keep provider-only aliases after known matches rather than discarding them.
    val ranked = TvTitleIndex(visible.map { CatalogItem(SearchPresentation.title(it), it.detailUrl) })
        .ranks(query)
    return visible.sortedBy { ranked.getValue(it.detailUrl) }
}

/** Orders the actual grid across catalog and provider results, retaining playback identities. */
internal fun tvSearchOrder(catalog: List<CatalogItem>, online: List<SearchItem>, query: String): List<String> {
    // Sorting each source independently still puts every local partial match before an
    // online exact match. One order must also drive the grid's saved focus and indices.
    val titles = (catalog + online.map { CatalogItem(SearchPresentation.title(it), it.detailUrl) })
        .distinctBy { it.url }
    val ranks = TvTitleIndex(titles).ranks(query)
    return titles.sortedBy { ranks.getValue(it.url) }.map { it.url }
}
