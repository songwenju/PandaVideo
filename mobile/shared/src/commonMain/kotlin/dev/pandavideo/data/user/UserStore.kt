package dev.pandavideo.data.user

import dev.pandavideo.core.platform.KeyValueStorage
import dev.pandavideo.core.platform.currentEpochMillis
import dev.pandavideo.data.repository.ContentParsers
import dev.pandavideo.data.repository.isWebUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Locally editable profile; the app has no account system. */
@Serializable
data class UserProfile(
    val nickname: String = "影迷朋友",
    val bio: String = "这个人很懒，还没有填写简介",
    val avatar: String = "",
)

/** A favorited title. [pageUrl] is the stable source detail reference and the unique key. */
@Serializable
data class SavedTitle(
    val title: String,
    @SerialName("page_url") val pageUrl: String,
    val cover: String = "",
    val year: String = "",
    @SerialName("saved_at") val savedAt: Long = 0,
)

/** A played title, kept newest-first and capped to [UserStore.HISTORY_LIMIT]. */
@Serializable
data class HistoryTitle(
    val title: String,
    @SerialName("page_url") val pageUrl: String,
    val cover: String = "",
    val year: String = "",
    val episode: String = "",
    @SerialName("episode_path") val episodePath: String = "",
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

/**
 * Per-episode resume record. Positions are milliseconds. A [finished] episode stores zero as
 * its position so reopening it starts from the beginning.
 */
@Serializable
data class EpisodeProgress(
    @SerialName("episode_path") val episodePath: String,
    @SerialName("episode_label") val episodeLabel: String = "",
    val position: Long = 0,
    val duration: Long = 0,
    val finished: Boolean = false,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

/** Watched fraction in 0f..1f used by resume badges and episode progress bars. */
fun progressFraction(position: Long, duration: Long): Float =
    if (duration <= 0L) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

/** Owns the local profile, favorites and playback history with JSON persistence. */
class UserStore(private val storage: KeyValueStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profileMutable = MutableStateFlow(loadProfile())
    val profile = profileMutable.asStateFlow()
    private val favoritesMutable = MutableStateFlow(loadFavorites())
    val favorites = favoritesMutable.asStateFlow()
    private val historyMutable = MutableStateFlow(loadHistory())
    val history = historyMutable.asStateFlow()
    private val progressMutable = MutableStateFlow(loadProgress())
    /** Per-title episode progress, each list newest-first; keyed by playable page url. */
    val progress = progressMutable.asStateFlow()

    /** Exports local profile, favorites and resume data without parental credentials or media URLs. */
    fun exportBackup(): String = json.encodeToString(UserBackup(
        profile = profileMutable.value, favorites = favoritesMutable.value.take(500),
        history = historyMutable.value, progress = progressMutable.value,
    ))

    /** Validates the complete payload before merging bounded data with existing records. */
    fun importBackup(text: String) {
        require(text.length <= 1_048_576) { "备份文件过大" }
        val backup = json.decodeFromString<UserBackup>(text)
        require(backup.schema == 1 && backup.favorites.size <= 500 &&
            backup.history.size <= HISTORY_LIMIT && backup.progress.size <= PROGRESS_TITLE_LIMIT) { "备份版本或记录数量无效" }
        require(backup.profile.nickname.length <= 12 && backup.profile.bio.length <= 40) { "个人资料过长" }
        require(backup.profile.avatar.isEmpty() ||
            backup.profile.avatar.matches(Regex("[A-Za-z0-9-]+\\.jpg"))) { "头像文件名无效" }
        fun page(value: String) { ContentParsers.pageUrl(value) }
        fun title(title: String, url: String, cover: String) {
            page(url)
            require(title.length in 1..160 && (cover.isBlank() || cover.length <= 2_048 && isWebUrl(cover))) { "备份条目无效" }
        }
        backup.favorites.forEach { title(it.title, it.pageUrl, it.cover) }
        backup.history.forEach {
            title(it.title, it.pageUrl, it.cover)
            require(it.positionMs >= 0 && it.durationMs >= it.positionMs && it.episode.length <= 80) { "历史进度无效" }
        }
        backup.progress.forEach { (url, records) ->
            page(url)
            require(records.size <= PROGRESS_PER_TITLE_LIMIT) { "剧集记录过多" }
            records.forEach {
                val episodeUrl = ContentParsers.pageUrl(if (it.episodePath.startsWith("/"))
                    "https://huolot.com${it.episodePath}" else it.episodePath)
                require((episodeUrl.contains("/vodplay/") || '#' in episodeUrl &&
                    episodeUrl.substringBefore('#') == titlePage(url)) && it.position >= 0 &&
                    it.duration >= it.position && it.episodeLabel.length <= 80) { "剧集进度无效" }
            }
        }
        val favorites = (favoritesMutable.value + backup.favorites)
            .map { it.copy(pageUrl = titlePage(it.pageUrl)) }.sortedByDescending { it.savedAt }
            .distinctBy { it.pageUrl }.take(500)
        val history = (historyMutable.value + backup.history)
            .map { it.copy(pageUrl = titlePage(it.pageUrl)) }.sortedByDescending { it.updatedAt }
            .distinctBy { it.pageUrl }.take(HISTORY_LIMIT)
        val keys = (favorites.map { it.pageUrl } + history.map { it.pageUrl }).toSet()
        val progress = (progressMutable.value.keys + backup.progress.keys).associateWith { key ->
            (progressMutable.value[key].orEmpty() + backup.progress[key].orEmpty())
                .sortedByDescending { it.updatedAt }.distinctBy { it.episodePath }.take(PROGRESS_PER_TITLE_LIMIT)
        }.canonicalProgress().filterKeys { it in keys }.takeKeysByRecency(PROGRESS_TITLE_LIMIT)
        profileMutable.value = backup.profile
        favoritesMutable.value = favorites
        historyMutable.value = history
        progressMutable.value = progress
        storage.putString(KEY_PROFILE, json.encodeToString(backup.profile))
        persistFavorites(); persistHistory(); persistProgress()
    }

    /**
     * Persists profile edits while preserving the current avatar for text-only updates.
     *
     * @param nickname Display name, trimmed and limited to twelve characters.
     * @param bio Profile description, trimmed and limited to forty characters.
     * @param avatar Relative private JPEG filename; blank restores the default avatar.
     */
    fun updateProfile(nickname: String, bio: String, avatar: String = profileMutable.value.avatar) {
        require(avatar.isEmpty() || avatar.matches(Regex("[A-Za-z0-9-]+\\.jpg")))
        val next = UserProfile(
            nickname = nickname.trim().ifBlank { "影迷朋友" }.take(NICKNAME_LIMIT),
            bio = bio.trim().take(BIO_LIMIT).ifBlank { UserProfile().bio },
            avatar = avatar,
        )
        storage.putString(KEY_PROFILE, json.encodeToString(next))
        profileMutable.value = next
    }

    fun isFavorite(pageUrl: String): Boolean =
        pageUrl.isNotBlank() && favoritesMutable.value.any { it.pageUrl == pageUrl }

    /** Toggles the favorite state and returns true when the title is now saved. */
    fun toggleFavorite(title: String, pageUrl: String, cover: String, year: String): Boolean {
        if (pageUrl.isBlank()) return false
        val existing = favoritesMutable.value.find { it.pageUrl == pageUrl }
        return if (existing != null) {
            favoritesMutable.value = favoritesMutable.value - existing
            persistFavorites()
            false
        } else {
            // The favorite action can fire before artwork is resolved; the matching history row
            // (kept across plays) is the local fallback so the saved card has a poster.
            val inheritedCover = cover.ifBlank {
                historyMutable.value.find { it.pageUrl == pageUrl }?.cover.orEmpty()
            }
            favoritesMutable.value = listOf(
                SavedTitle(title.ifBlank { existing?.title.orEmpty() }, pageUrl, inheritedCover, year, currentEpochMillis()),
            ) + favoritesMutable.value
            persistFavorites()
            true
        }
    }

    fun removeFavorite(pageUrl: String) {
        favoritesMutable.value = favoritesMutable.value.filterNot { it.pageUrl == pageUrl }
        persistFavorites()
    }

    fun clearFavorites() {
        favoritesMutable.value = emptyList()
        storage.remove(KEY_FAVORITES)
    }

    /** Inserts or refreshes a title and moves it to the front, preserving known artwork. */
    fun recordHistory(
        title: String, pageUrl: String, cover: String, year: String, episode: String = "",
        episodePath: String = "", positionMs: Long? = null, durationMs: Long? = null,
    ) {
        if (pageUrl.isBlank()) return
        val previous = historyMutable.value.find { it.pageUrl == pageUrl }
        val merged = HistoryTitle(
            title = title.ifBlank { previous?.title.orEmpty() },
            pageUrl = pageUrl,
            cover = cover.ifBlank { previous?.cover.orEmpty() },
            year = year.ifBlank { previous?.year.orEmpty() },
            episode = episode.ifBlank { previous?.episode.orEmpty() },
            episodePath = episodePath.ifBlank { previous?.episodePath.orEmpty() },
            positionMs = positionMs ?: previous?.positionMs ?: 0,
            durationMs = durationMs ?: previous?.durationMs ?: 0,
            updatedAt = currentEpochMillis(),
        )
        historyMutable.value = (listOf(merged) + historyMutable.value.filter { it.pageUrl != pageUrl })
            .take(HISTORY_LIMIT)
        // Artwork may only become known during playback. Repair cover-less favorite snapshots
        // (including records from older versions) once, without ever replacing an existing cover.
        if (cover.isNotBlank() && isWebUrl(cover)) {
            val favorite = favoritesMutable.value.find { it.pageUrl == pageUrl }
            if (favorite != null && favorite.cover.isBlank()) {
                favoritesMutable.value = favoritesMutable.value.map {
                    if (it.pageUrl == pageUrl) it.copy(cover = cover) else it
                }
                persistFavorites()
            }
        }
        persistHistory()
    }

    /**
     * Persists a throttled playback position. Episodes watched past [FINISH_THRESHOLD] are
     * marked finished and reset to zero. The matching history entry is refreshed and moved to
     * the front so "continue watching" stays ordered by recency. Returns the stored record.
     */
    fun saveProgress(
        pageUrl: String, episodePath: String, episodeLabel: String, position: Long, duration: Long,
    ): EpisodeProgress? {
        if (pageUrl.isBlank() || episodePath.isBlank()) return null
        val safeDuration = duration.coerceAtLeast(0)
        val safePosition = position.coerceIn(0, safeDuration)
        val finished = safeDuration >= MIN_PROGRESS_DURATION_MS &&
            safePosition.toDouble() / safeDuration >= FINISH_THRESHOLD
        val record = EpisodeProgress(
            episodePath = episodePath,
            episodeLabel = episodeLabel,
            position = if (finished) 0 else safePosition,
            duration = safeDuration,
            finished = finished,
            updatedAt = currentEpochMillis(),
        )
        val titles = progressMutable.value.toMutableMap()
        titles[pageUrl] = (listOf(record) + titles[pageUrl].orEmpty()
            .filter { it.episodePath != episodePath }).take(PROGRESS_PER_TITLE_LIMIT)
        // Progress only exists for titles that history already knows about (title/cover source).
        val known = titles.keys.filter { key ->
            historyMutable.value.any { it.pageUrl == key } || favoritesMutable.value.any { it.pageUrl == key }
        }
        progressMutable.value = titles.filterKeys { it in known }.takeKeysByRecency(PROGRESS_TITLE_LIMIT)
        persistProgress()
        touchHistory(pageUrl, record)
        return record
    }

    /** Returns the stored progress for one episode, or null when it was never played. */
    fun progressFor(pageUrl: String, episodePath: String): EpisodeProgress? =
        progressMutable.value[pageUrl]?.firstOrNull { it.episodePath == episodePath }

    /** Returns the most recently played episode record for a title. */
    fun latestProgress(pageUrl: String): EpisodeProgress? = progressMutable.value[pageUrl]?.firstOrNull()

    /** Refreshes the history snapshot (episode label, position, recency) after a progress save. */
    private fun touchHistory(pageUrl: String, progress: EpisodeProgress) {
        val current = historyMutable.value
        val existing = current.find { it.pageUrl == pageUrl } ?: return
        val merged = existing.copy(
            episode = progress.episodeLabel.ifBlank { existing.episode },
            episodePath = progress.episodePath,
            positionMs = progress.position,
            durationMs = progress.duration,
            updatedAt = currentEpochMillis(),
        )
        historyMutable.value = (listOf(merged) + current.filter { it.pageUrl != pageUrl }).take(HISTORY_LIMIT)
        persistHistory()
    }

    fun removeHistory(pageUrl: String) {
        historyMutable.value = historyMutable.value.filterNot { it.pageUrl == pageUrl }
        persistHistory()
        if (progressMutable.value.containsKey(pageUrl)) {
            progressMutable.value = progressMutable.value - pageUrl
            persistProgress()
        }
    }

    fun clearHistory() {
        historyMutable.value = emptyList()
        storage.remove(KEY_HISTORY)
        progressMutable.value = emptyMap()
        storage.remove(KEY_PROGRESS)
    }

    private fun persistFavorites() {
        storage.putString(KEY_FAVORITES, json.encodeToString(favoritesMutable.value))
    }

    private fun persistHistory() {
        storage.putString(KEY_HISTORY, json.encodeToString(historyMutable.value))
    }

    private fun persistProgress() {
        storage.putString(KEY_PROGRESS, json.encodeToString(progressMutable.value))
    }

    private fun loadProfile(): UserProfile = try {
        storage.getString(KEY_PROFILE)?.let { json.decodeFromString<UserProfile>(it) } ?: UserProfile()
    } catch (_: Exception) {
        UserProfile()
    }

    private fun loadFavorites(): List<SavedTitle> = try {
        storage.getString(KEY_FAVORITES)?.let { json.decodeFromString<List<SavedTitle>>(it) }.orEmpty()
            .map { it.copy(pageUrl = titlePage(it.pageUrl)) }.sortedByDescending { it.savedAt }
            .distinctBy { it.pageUrl }.take(500)
    } catch (_: Exception) {
        emptyList()
    }

    private fun loadHistory(): List<HistoryTitle> = try {
        storage.getString(KEY_HISTORY)?.let { json.decodeFromString<List<HistoryTitle>>(it) }.orEmpty()
            .map { it.copy(pageUrl = titlePage(it.pageUrl)) }.sortedByDescending { it.updatedAt }
            .distinctBy { it.pageUrl }.take(HISTORY_LIMIT)
    } catch (_: Exception) {
        emptyList()
    }

    private fun loadProgress(): Map<String, List<EpisodeProgress>> = try {
        storage.getString(KEY_PROGRESS)?.let {
            json.decodeFromString<Map<String, List<EpisodeProgress>>>(it)
        }.orEmpty().canonicalProgress().takeKeysByRecency(PROGRESS_TITLE_LIMIT)
    } catch (_: Exception) {
        emptyMap()
    }

    companion object {
        const val HISTORY_LIMIT = 50
        const val PROGRESS_TITLE_LIMIT = 200
        const val PROGRESS_PER_TITLE_LIMIT = 100
        /** Episodes shorter than 20s (ads/clips) never produce resume records. */
        const val MIN_PROGRESS_DURATION_MS = 20_000L
        /** Fraction at/above which an episode is treated as fully watched. */
        const val FINISH_THRESHOLD = 0.97f
        private const val NICKNAME_LIMIT = 12
        private const val BIO_LIMIT = 40
        private const val KEY_PROFILE = "profile"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HISTORY = "history"
        private const val KEY_PROGRESS = "progress"
    }
}

/** Migrates old episode-page title keys to one stable detail-page key. */
private fun titlePage(value: String): String = try {
    val page = ContentParsers.pageUrl(value)
    if (page.startsWith("https://pomo.mom/") || page.startsWith("https://japi.zxfmj.com/"))
        page.substringBefore('#') else {
        val id = Regex("/vod(?:detail|play)/(\\d+)").find(page)!!.groupValues[1]
        "https://huolot.com/voddetail/$id.html"
    }
} catch (_: Exception) { value }

private fun Map<String, List<EpisodeProgress>>.canonicalProgress(): Map<String, List<EpisodeProgress>> =
    entries.groupBy { titlePage(it.key) }.mapValues { (_, entries) ->
        entries.flatMap { it.value }.sortedByDescending { it.updatedAt }
            .distinctBy { it.episodePath }.take(UserStore.PROGRESS_PER_TITLE_LIMIT)
    }

@Serializable
private data class UserBackup(
    val schema: Int = 1, val profile: UserProfile = UserProfile(),
    val favorites: List<SavedTitle> = emptyList(), val history: List<HistoryTitle> = emptyList(),
    val progress: Map<String, List<EpisodeProgress>> = emptyMap(),
)

/** Keeps the [limit] titles whose most recent episode was updated last. */
private fun Map<String, List<EpisodeProgress>>.takeKeysByRecency(limit: Int): Map<String, List<EpisodeProgress>> {
    if (size <= limit) return this
    val keep = entries.sortedByDescending { it.value.firstOrNull()?.updatedAt ?: 0L }.take(limit).map { it.key }.toSet()
    return filterKeys { it in keep }
}
