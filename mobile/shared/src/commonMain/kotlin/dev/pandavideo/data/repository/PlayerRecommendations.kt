package dev.pandavideo.data.repository

import dev.pandavideo.core.model.BrowseCountries
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.HomeMinimumRating
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.isTelevisionDrama

/** Maximum number of distinct recommendations rendered in the player rail. */
internal const val PlayerRecommendationLimit = 18

/** Keeps recommendations inside the current release's production region and content type. */
internal data class PlayerRecommendationScope(
    val kind: String,
    val region: String,
    val excludedUrls: Set<String>,
    val title: String,
    val year: String,
    val isSeries: Boolean?,
    val genres: List<String> = emptyList(),
    val casts: List<String> = emptyList(),
) {
    val country: String? get() = BrowseCountries.entries
        .firstOrNull { it.key != "all" && it.value == region }?.key

    /** Retains an originating filter snapshot even when another catalog supplies metadata. */
    fun select(items: List<CatalogItem>, originating: List<CatalogItem>? = null): List<CatalogItem> {
        if (region.isBlank()) return emptyList()
        val permitted = originating?.map { it.url.trimEnd('/') }?.toSet()
        return mergeRecommendationMetadata(items).filter { item ->
            val url = item.url.trimEnd('/')
            url !in excludedUrls && (permitted == null || url in permitted) &&
                !(item.title == title && (year.isBlank() || item.year.isBlank() || item.year == year)) &&
                item.kind == kind && (kind != "tv" || item.isTelevisionDrama()) &&
                (isSeries == null || item.isSeries == null || isSeries == item.isSeries) &&
                item.rating?.let { it in HomeMinimumRating..10.0 } == true &&
                item.countries.any { recommendationRegion(it) == region }
        }.sortedWith(compareByDescending<CatalogItem> { relevanceScore(it) }
            .thenByDescending { it.rating }.thenBy { it.url.trimEnd('/') })
            .take(PlayerRecommendationLimit)
    }

    /**
     * Scores independently normalized genre similarity, cast overlap and the ten-point rating.
     * Source cast order is a prominence proxy: the first three names receive full weight and
     * later names receive 0.35. It is not a claim that the provider identifies starring roles.
     * Missing metadata contributes zero without reallocating its weight to another signal.
     */
    internal fun relevanceScore(item: CatalogItem): Double {
        val referenceGenres = recommendationGenres(genres)
        val candidateGenres = recommendationGenres(item.genres)
        val union = referenceGenres + candidateGenres
        val genreScore = if (union.isEmpty()) 0.0 else
            referenceGenres.intersect(candidateGenres).size.toDouble() / union.size
        val referenceCast = recommendationCasts(casts)
        val candidateCast = recommendationCasts(item.casts)
        val castScore = if (referenceCast.isEmpty()) 0.0 else
            referenceCast.entries.sumOf { (name, weight) ->
                weight * (candidateCast[name] ?: 0.0)
            } / referenceCast.values.sum()
        val ratingScore = (item.rating ?: 0.0).coerceIn(0.0, 10.0) / 10.0
        return 0.50 * genreScore + 0.30 * castScore + 0.20 * ratingScore
    }
}

/** Joins sparse candidate metadata only by the same stable catalog URL and release identity. */
private fun mergeRecommendationMetadata(items: List<CatalogItem>): List<CatalogItem> =
    items.groupBy { it.url.trimEnd('/') }.values.map { rows ->
        val first = rows.first()
        val compatible = rows.filter { it.kind == first.kind && it.title == first.title &&
            (first.year.isBlank() || it.year.isBlank() || it.year == first.year) }
        first.copy(
            year = first.year.ifBlank { compatible.firstOrNull { it.year.isNotBlank() }?.year.orEmpty() },
            rating = first.rating ?: compatible.firstOrNull { it.rating != null }?.rating,
            cover = first.cover.ifBlank { compatible.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty() },
            genres = first.genres.ifEmpty { compatible.firstOrNull { it.genres.isNotEmpty() }?.genres.orEmpty() },
            casts = first.casts.ifEmpty { compatible.firstOrNull { it.casts.isNotEmpty() }?.casts.orEmpty() },
            countries = first.countries.ifEmpty {
                compatible.firstOrNull { it.countries.isNotEmpty() }?.countries.orEmpty()
            },
            isSeries = first.isSeries ?: compatible.firstOrNull { it.isSeries != null }?.isSeries,
        )
    }

private fun recommendationGenres(values: List<String>): Set<String> = values
    .flatMap { it.split(Regex("[/／,，、;；|\\s]+")) }.map(String::trim)
    .filter { it.isNotBlank() && it !in setOf("电影", "电视剧", "国产剧", "国产", "国产电视剧") }
    .toSet()

private fun recommendationCasts(values: List<String>): Map<String, Double> = values
    .flatMap { it.split(Regex("[/／,，、;；|]+")) }.map(String::trim)
    .filter(String::isNotBlank).distinct().mapIndexed { index, name ->
        name to if (index < 3) 1.0 else 0.35
    }.toMap()

/** Normalizes provider aliases without merging mainland, Hong Kong and Taiwan catalogs. */
private fun recommendationRegion(value: String): String = when (val name = value.trim()) {
    "中国", "大陆", "内地", "中国内地", "中国大陆" -> "中国大陆"
    "香港", "中国香港" -> "中国香港"
    "台湾", "中国台湾" -> "中国台湾"
    else -> name
}

/** Uses verified catalog metadata first, then the selected provider's search/detail metadata. */
internal fun playerRecommendationScope(
    catalog: CatalogItem?, search: SearchItem?, info: PlaybackInfo?,
): PlayerRecommendationScope {
    val regions = catalog?.countries.orEmpty().ifEmpty {
        search?.region.orEmpty().ifBlank { info?.region.orEmpty() }
            .split(Regex("[/／,，、;；|\\s]+"))
    }.map(::recommendationRegion).filter(String::isNotBlank)
    val metadataKind = when {
        "动画" in catalog?.genres.orEmpty() -> "animation"
        "纪录片" in catalog?.genres.orEmpty() -> "documentary"
        else -> catalog?.kind?.takeIf(String::isNotBlank)
    }
    val kind = metadataKind
        ?: search?.let(SearchPresentation::kind)?.takeIf(String::isNotBlank)
        ?: if (ContentParsers.serialEpisodes(info?.episodes.orEmpty())) "tv" else "movie"
    val genres = recommendationGenres(catalog?.genres.orEmpty())
        .ifEmpty { recommendationGenres(listOf(search?.genre.orEmpty())) }
    val casts = recommendationCasts(catalog?.casts.orEmpty()).keys
        .ifEmpty { recommendationCasts(listOf(search?.cast.orEmpty())).keys }
    return PlayerRecommendationScope(
        kind, regions.firstOrNull().orEmpty(),
        listOfNotNull(catalog?.url, catalog?.playbackPageUrl, search?.detailUrl, info?.pageUrl)
            .map { it.trimEnd('/') }.toSet(),
        catalog?.title ?: search?.title ?: info?.title.orEmpty(),
        catalog?.year ?: search?.year.orEmpty(), catalog?.isSeries,
        genres = genres.toList(), casts = casts.toList(),
    )
}
