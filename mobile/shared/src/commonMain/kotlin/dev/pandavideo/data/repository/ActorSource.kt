package dev.pandavideo.data.repository

import dev.pandavideo.core.model.*
import io.ktor.http.encodeURLParameter
import io.ktor.http.Url
import kotlinx.serialization.json.*

/** Reads public iQIYI person identities and explicit credits, independently of playback sources. */
internal class ActorSource(private val fetch: suspend (String) -> String) {
    suspend fun search(name: String): ActorSuggestions = ActorSuggestions(parseSuggestions(fetch(
        "$ORIGIN/portal/lw/search/homePageV3?key=${name.encodeURLParameter()}" +
            "&current_page=1&mode=1&pageNum=1&pageSize=25",
    )))

    /** Refreshes person properties using the source identity, never a same-name substitute. */
    suspend fun profile(actor: ActorRef): ActorRef {
        require(actor.id.startsWith("iqiyi:") &&
            Regex("[0-9]+").matches(actor.id.removePrefix("iqiyi:")))
        return search(actor.name).actors.singleOrNull { it.id == actor.id }
            ?: throw ApiError("演员资料来源暂时不可用")
    }

    suspend fun filmography(actor: ActorRef): ActorFilmography {
        val person = actor.id.removePrefix("iqiyi:")
        require(actor.id.startsWith("iqiyi:") && Regex("[0-9]+").matches(person))
        val body = fetch("$ORIGIN/portal/lw/v7/channel/page/star?peopleId=$person&page=1&mode=page")
        val response = Json.parseToJsonElement(body).jsonObject
        if (response.text("code") != "0" || response["items"] !is JsonArray) {
            throw ApiError("演员作品来源暂时不可用")
        }
        // This page is a source snapshot, not a complete career history. Read only its explicit
        // film_works block; related people, recommendations and nested preview videos are not credits.
        val rows = response["items"]!!.jsonArray.flatMap { item ->
            item.jsonObject.array("video").map { it.jsonObject }
                .filter { it.text("block_id") == "film_works" }
                .flatMap { it.array("data") }
        }
        return ActorFilmography(rows.mapNotNull { parseWork(it.jsonObject, person) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<ActorWork> { it.year.toIntOrNull() ?: 0 }.thenBy { it.title }),
            limited = true)
    }

    /** Maps unique catalog identities with bounded title/year differences and matching media kind. */
    suspend fun catalogWork(work: ActorWork): ActorWork {
        val rows = Json.parseToJsonElement(fetch(
            "https://movie.douban.com/j/subject_suggest?q=${work.title.encodeURLParameter()}",
        )).jsonArray.map { it.jsonObject }
        val matches = rows.filter {
            it.text("type") == "movie" && catalogTitle(it.text("title"), work.kind) == catalogTitle(work.title, work.kind) &&
                Regex("[0-9]+").matches(it.text("id")) &&
                compatibleYear(work.year, it.text("year")) &&
                ((it.text("episode").isNotBlank()) == (work.kind == "tv"))
        }.distinctBy { it.text("id") }
        // Prefer an exact normalized title/year before falling back to a unique relaxed match.
        // Never select the first hit when remakes or otherwise distinct subjects remain ambiguous.
        val exact = matches.filter {
            ContentParsers.normalizedTitle(it.text("title")) == ContentParsers.normalizedTitle(work.title) &&
                (work.year.isBlank() || it.text("year") == work.year)
        }
        val subject = (exact.ifEmpty { matches }).singleOrNull() ?: return work
        // Playback searches must use the verified catalog's spelling and release year too.
        // Otherwise a resolved subject can still miss sources using "马大帅"/2004 rather than
        // the person page's "马大帅1"/2003. The original source URL/identity remain intact.
        return work.copy(subjectId = subject.text("id"), verifiedCatalog = true,
            title = subject.text("title"), year = subject.text("year").ifBlank { work.year },
            cover = externalPoster(subject.text("img")).ifBlank { externalPoster(work.cover) })
    }

    companion object {
        private const val ORIGIN = "https://mesh.if.iqiyi.com"

        /** Accepts alternative artwork without falling back to protected iQIYI images. */
        internal fun externalPoster(address: String): String {
            if (!isWebUrl(address)) return ""
            val host = runCatching { Url(address).host.lowercase() }.getOrNull() ?: return ""
            return address.takeUnless {
                listOf("iqiyipic.com", "iqiyi.com", "qiyipic.com").any { domain ->
                    host == domain || host.endsWith(".$domain")
                }
            }.orEmpty()
        }

        /** Normalizes typography and only the optional first-part suffix of television titles. */
        private fun catalogTitle(title: String, kind: String): String {
            val normalized = actorCreditTitle(title)
            if (kind != "tv") return normalized
            val withoutFirst = normalized.replace(Regex("第(?:1|一)(?:季|部)$"), "")
            // Strip "1" in "马大帅1", but preserve 11, 21, 1981 and every later season number.
            return if (withoutFirst.endsWith("1") && withoutFirst.length > 1 &&
                !withoutFirst[withoutFirst.lastIndex - 1].isDigit()) withoutFirst.dropLast(1)
            else withoutFirst
        }

        /** Permits a one-year production/premiere difference, never an unbounded year mismatch. */
        private fun compatibleYear(expected: String, actual: String): Boolean {
            if (expected.isBlank() || expected == actual) return true
            val first = expected.toIntOrNull()?.takeIf { it in 1000..9999 } ?: return false
            val second = actual.toIntOrNull()?.takeIf { it in 1000..9999 } ?: return false
            return kotlin.math.abs(first - second) <= 1
        }

        internal fun parseSuggestions(body: String): List<ActorRef> {
            val response = Json.parseToJsonElement(body).jsonObject
            val templates = response["data"]?.jsonObject?.get("templates") as? JsonArray
                ?: throw ApiError("演员搜索来源返回异常")
            return templates.mapNotNull { row ->
                val item = row.jsonObject
                if (item.text("template") != "105") return@mapNotNull null
                val person = item["albumInfo"] as? JsonObject ?: return@mapNotNull null
                val id = person.text("qipuId")
                val name = person.text("title")
                if (!Regex("[0-9]+").matches(id) || name.isBlank()) return@mapNotNull null
                // properties gives the source display order. Keep all labelled person properties,
                // including empty values, and append additional fields for forward compatibility.
                // Grouped videos and playback metadata are not person biography fields.
                val properties = person.filterValues {
                    it is JsonObject && it.text("key").isNotBlank() && it["value"] is JsonPrimitive
                }
                val ordered = (person.array("properties").mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull
                } + properties.keys).distinct()
                val fields = ordered.mapNotNull property@ { key ->
                    val property = properties[key] as? JsonObject ?: return@property null
                    ActorProfileField(key, property.text("key"), property.text("value"))
                }
                ActorRef("iqiyi:$id", name, person.text("img").takeIf(::isWebUrl).orEmpty(),
                    profileFields = fields)
            }.distinctBy { it.id }
        }

        internal fun parseWork(row: JsonObject, person: String): ActorWork? {
            if (row.text("content_type") != "FEATURE_FILM" || row.text("is_episode") == "true" ||
                row.text("isAd") == "true") return null
            val kind = when (row.text("channel_id")) {
                "1" -> "movie"
                "2" -> "tv"
                else -> return null
            }
            // contributor is the explicit acting list; creator and guest must not confer a credit.
            // desc is a presentation label, not an exclusive role: iQIYI labels 马大帅/老兵
            // "导演" even though Zhao Benshan also appears in contributor. Trust the cast evidence.
            if (row.array("contributor").none { it.jsonObject.text("id") == person }) return null
            val role = row.text("desc")
            val id = row.text("album_id").ifBlank { row.text("entity_id") }
            val title = row.text("title").trim()
                .replace(Regex("[（(](?:普通话|国语|粤语|国配|粤配)[）)]$"), "")
            val url = row.text("page_url")
            if (!Regex("[0-9]+").matches(id) || title.isBlank() ||
                !Regex("^https://www\\.iqiyi\\.com/v_[a-zA-Z0-9]+\\.html(?:\\?.*)?$").matches(url)) return null
            return ActorWork("iqiyi:$id", title, kind,
                row["date"]?.jsonObject?.text("year").orEmpty(),
                // Credits still come from the person source; artwork is supplied separately
                // by a uniquely matched catalog or verified playback detail.
                cover = "",
                voice = role.startsWith("配音"), sourceUrl = url)
        }

        private fun JsonObject.text(key: String): String =
            (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.array(key: String): JsonArray = (get(key) as? JsonArray) ?: JsonArray(emptyList())
    }
}
