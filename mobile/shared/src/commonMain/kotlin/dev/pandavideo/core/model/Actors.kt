package dev.pandavideo.core.model

import kotlinx.serialization.Serializable

/** A public person property, preserving the source identifier, label and unformatted value. */
@Serializable
internal data class ActorProfileField(val id: String, val label: String, val value: String) {
    /** Formats valid source birthdays without altering cached values or guessing invalid dates. */
    fun displayValue(): String {
        if (id != "birthday" || !Regex("[0-9]{8}").matches(value)) return value
        val year = value.take(4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.takeLast(2).toInt()
        if (year == 0 || month !in 1..12) return value
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (day !in 1..days[month - 1]) return value
        return "${year}年${month}月${day}日"
    }
}

/** A source-owned person identity; names are display metadata, never identity keys. */
@Serializable
internal data class ActorRef(
    val id: String, val name: String, val avatar: String = "", val alternateName: String = "",
    val profileFields: List<ActorProfileField> = emptyList(),
) {
    /** Reads legacy cached birthdays while keeping real aliases distinct from person properties. */
    fun visibleProfileFields(): List<ActorProfileField> {
        if (profileFields.isNotEmpty()) return profileFields.filter { it.value.isNotBlank() }
        if (alternateName.isBlank()) return emptyList()
        return if (Regex("[0-9]{8}").matches(alternateName)) {
            listOf(ActorProfileField("birthday", "生日", alternateName))
        } else listOf(ActorProfileField("alias", "别名", alternateName))
    }
}

/** A source credit with an optional verified local catalog mapping. */
@Serializable
internal data class ActorWork(
    val id: String, val title: String, val kind: String, val year: String = "",
    val subjectId: String = "", val cover: String = "", val voice: Boolean = false,
    val verifiedCatalog: Boolean = false,
    val sourceUrl: String = "",
    /** Verified playback detail page, independent of optional Douban metadata. Never a media URL. */
    val playbackPageUrl: String = "",
) {
    fun catalog(): CatalogItem = CatalogItem(title,
        if (playbackPageUrl.isNotBlank()) playbackPageUrl
        else if (subjectId.isNotEmpty()) "https://movie.douban.com/subject/$subjectId/"
        else sourceUrl, year, cover = cover, kind = kind)
}

/**
 * Returns catalog-mapped or independently playback-verified credits unless confirmed missing.
 * A missing Douban suggestion must not conceal a verified playback source. Unresolved credits
 * stay in the source snapshot without exposing an official person-source page as a player.
 */
internal fun actorPlaybackWorks(works: List<ActorWork>, missingUrls: Set<String>): List<ActorWork> =
    works.filter { (it.verifiedCatalog && it.subjectId.isNotBlank() || it.playbackPageUrl.isNotBlank()) &&
        it.catalog().url !in missingUrls }

/**
 * Supplements a partial provider filmography with a credit confirmed by the current player.
 * The caller supplies a validated playback detail identity and verifies the unique person ID.
 * Exact cast tokens prevent substring
 * name matches, and title/year/kind equality prevents merging same-name remakes or films.
 */
internal fun ActorFilmography.withPlayingCredit(
    actor: ActorRef, info: PlaybackInfo?, catalog: CatalogItem?, search: SearchItem?, page: String?,
): ActorFilmography {
    if (info == null || page == null) return this
    val cast = catalog?.casts?.takeIf { it.isNotEmpty() } ?: search?.cast.orEmpty()
        .split(Regex("[,，、/|;；\\s]+")).filter(String::isNotBlank)
    if (actor.name.trim() !in cast.map(String::trim)) return this
    val title = catalog?.title ?: search?.title ?: info.title
    val year = catalog?.year?.takeIf(String::isNotBlank) ?: search?.year.orEmpty()
    val serial = info.episodes.groupBy { it.line }.values.any { it.size > 1 } ||
        info.episodes.any { Regex("(?:第\\s*)?\\d{1,4}\\s*集").containsMatchIn(it.label) }
    val kind = if (serial) "tv" else catalog?.kind ?: "movie"
    if (kind !in setOf("tv", "movie")) return this
    val existing = works.indexOfFirst {
        it.title == title && it.year == year && it.kind == kind
    }
    val credit = if (existing >= 0) works[existing].copy(playbackPageUrl = page)
        else ActorWork("playback:$page", title, kind, year,
            cover = catalog?.cover?.takeIf(String::isNotBlank) ?: search?.thumbnailUrl.orEmpty(),
            playbackPageUrl = page)
    val merged = if (existing >= 0) works.mapIndexed { index, work ->
        if (index == existing) credit else work
    } else works + credit
    return copy(works = merged.sortedByDescending { it.year })
}

/** A bounded, globally year-sorted snapshot, independent of the displayed filter/page. */
@Serializable
internal data class ActorFilmography(
    val works: List<ActorWork>, val limited: Boolean = false, val stale: Boolean = false,
)

/** Search snapshots use their own cache and never replace film-search results. */
@Serializable
internal data class ActorSuggestions(val actors: List<ActorRef>, val stale: Boolean = false)
