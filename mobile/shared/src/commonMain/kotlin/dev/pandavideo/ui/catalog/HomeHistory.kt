package dev.pandavideo.ui.catalog

import dev.pandavideo.app.CatalogState
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.data.user.HistoryTitle

/**
 * Keeps the resume card and catalog behind the same initial display boundary.
 *
 * The idle state before the load coroutine starts is also pending. A published page,
 * including an empty or stale cached page, is ready immediately; refreshes retain it.
 * A failed initial request releases the boundary so saved playback remains accessible.
 */
internal fun awaitingInitialCatalog(state: CatalogState): Boolean =
    state.page == null && state.items.isEmpty() && state.error == null

/** Selects the latest played title only for the first category of the user's first destination. */
internal fun homeHistoryFor(
    tabOrder: List<String>, destination: String, history: List<HistoryTitle>, categoryIndex: Int = 0,
): HistoryTitle? =
    if (categoryIndex == 0 && destination in listOf("hot", "discover", "browse") && tabOrder.firstOrNull() == destination)
        history.firstOrNull()
    else null

/**
 * Finds catalog metadata for a played title without merging ambiguous same-name editions.
 *
 * Playback and catalog URLs belong to different sources. An exact URL wins; otherwise the
 * title must identify one compatible year. Missing years never select between multiple films.
 */
internal fun historyCatalogMatch(record: HistoryTitle, items: List<CatalogItem>): CatalogItem? =
    items.firstOrNull { it.url == record.pageUrl } ?: items.filter {
        it.title.trim() == record.title.trim() &&
            (record.year.isBlank() || it.year.isBlank() || it.year == record.year)
    }.singleOrNull()

/** Removes only the matched row from the display, preserving source pagination and list identity. */
internal fun catalogWithoutHistory(items: List<CatalogItem>, record: HistoryTitle?): List<CatalogItem> {
    val match = record?.let { historyCatalogMatch(it, items) } ?: return items
    return items.filterNot { it.url == match.url }
}

/** Builds display metadata only; opening this card always uses the saved playback URL. */
internal fun historyCatalogPoster(record: HistoryTitle, items: List<CatalogItem>): CatalogItem {
    val match = historyCatalogMatch(record, items)
    // An empty or failed catalog may supply no metadata. Preserve the sketch identity
    // from the saved source URL so the resume card keeps the source's artwork ratio.
    val kind = if (dev.pandavideo.data.repository.SketchSource.owns(record.pageUrl)) "xiaopin" else "movie"
    return (match ?: CatalogItem(record.title, record.pageUrl, kind = kind)).copy(
        title = record.title,
        year = record.year.ifBlank { match?.year.orEmpty() },
        cover = record.cover.ifBlank { match?.cover.orEmpty() },
        rating = record.rating ?: match?.rating,
    )
}
