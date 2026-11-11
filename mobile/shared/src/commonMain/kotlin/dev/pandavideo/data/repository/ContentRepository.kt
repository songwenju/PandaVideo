package dev.pandavideo.data.repository

import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.BrowseCountries
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.model.HomeCatalogFilters
import dev.pandavideo.core.model.HomeMinimumRating
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.MatchResult
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.SearchResult
import dev.pandavideo.core.model.SourceCount
import dev.pandavideo.data.html.HtmlNode
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import dev.pandavideo.core.platform.currentEpochMillis

class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause)

fun isWebUrl(value: String): Boolean = try {
    val url = Url(value)
    Regex("^https?://[^/\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(value) &&
        url.protocol.name in listOf("http", "https") && url.host.isNotBlank() &&
        url.user == null && url.password == null
} catch (_: Exception) { false }

/** Owns direct catalog access and compatible public playback sources with automatic failover. */
class ContentRepository(
    private val client: HttpClient,
    private val database: CatalogDatabase? = null,
    private val rules: () -> ParserRules = { ParserRules() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pages = linkedMapOf<String, CatalogPage>()
    private val jianpian = JianpianClient(fetch = { address, budget, headers -> fetch(address, budget, headers) })
    private val availability = CatalogAvailability(database, { "online:2:${json.encodeToString(rules())}" },
        check = { probeMatch(it).status })

    internal suspend fun visibleCatalog(page: CatalogPage): CatalogPage = availability.visible(page)

    internal suspend fun inspectCatalog(items: List<CatalogItem>, onMissing: (CatalogItem) -> Unit) =
        availability.inspect(items, onMissing)

    /** Fetches an explicitly configured HTTPS JSON document with the same redirect bounds as source pages. */
    internal suspend fun configuration(address: String): String {
        val url = try { Url(address) } catch (_: Exception) { throw ApiError("请输入有效的 HTTPS 地址") }
        if (!isWebUrl(address) || url.protocol != URLProtocol.HTTPS || url.port != 443 ||
            url.fragment.isNotEmpty()) throw ApiError("请输入有效的 HTTPS 地址")
        return fetch(address).also { if (it.length > 65_536) throw ApiError("配置文件过大") }
    }

    private suspend fun fetch(address: String, budget: Long = 15_000, headers: Map<String, String> = emptyMap()): String {
        try {
            return withTimeoutOrNull(budget) {
                val origin = Url(address)
                var target = address
                var redirects = 0
                while (true) {
                    val response = client.get(target) {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0")
                        header(HttpHeaders.Referrer, "${origin.protocol.name}://${origin.host}/")
                        headers.forEach { (name, value) -> header(name, value) }
                    }
                    if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                        response.bodyAsChannel().cancel(null)
                        if (++redirects > 4) throw ApiError("来源跳转次数过多")
                        val location = response.headers[HttpHeaders.Location] ?: throw ApiError("来源跳转地址缺失")
                        val next = URLBuilder(target).apply { takeFrom(location) }.build()
                        if (!isWebUrl(next.toString())) {
                            throw ApiError("来源跳转地址无效")
                        }
                        // A mirror has its own initialization secret. Never forward a signed
                        // request to an unrelated origin through an upstream redirect.
                        if (headers.containsKey("signature") &&
                            (next.protocol != origin.protocol || next.host != origin.host || next.port != origin.port))
                            throw ApiError("签名接口跳转到其他来源")
                        target = next.toString()
                        continue
                    }
                    if (!response.status.isSuccess()) {
                        response.bodyAsChannel().cancel(null)
                        throw ApiError("来源暂时不可用（HTTP ${response.status.value}），请稍后重试")
                    }
                    val limit = 2 * 1024 * 1024
                    val bytes = ByteArray(limit + 1)
                    val channel = response.bodyAsChannel()
                    var count = 0
                    while (count < bytes.size) {
                        val read = channel.readAvailable(bytes, count, bytes.size - count)
                        if (read == -1) break
                        count += read
                    }
                    channel.cancel(null)
                    if (count > limit) throw ApiError("来源页面过大")
                    return@withTimeoutOrNull bytes.decodeToString(endIndex = count)
                }
                @Suppress("UNREACHABLE_CODE")
                ""
            } ?: throw ApiError("来源请求超时，请稍后重试")
        } catch (error: CancellationException) { throw error }
        catch (error: ApiError) { throw error }
        catch (error: Exception) { throw ApiError("无法访问影视来源，请检查网络后重试", error) }
    }

    private suspend fun catalog(key: String, ttl: Long = 7L * 24 * 60 * 60 * 1000, load: suspend () -> CatalogPage): CatalogPage {
        val stored = try {
            database?.read(key)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        if (stored != null && currentEpochMillis() - stored.savedAt in 0 until ttl) {
            return stored.page
        }
        try {
            val page = load()
            if (stored != null && page.items.isEmpty()) return stored.page.copy(stale = true)
            try {
                database?.write(key, page, currentEpochMillis())
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Storage failure must not hide a successful upstream result. */ }
            pages[key] = page
            if (pages.size > 64) pages.remove(pages.keys.first())
            return page
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { return (stored?.page ?: pages[key])?.copy(stale = true) ?: throw error }
    }

    private suspend inline fun <reified T> cachedResponse(
        key: String, ttl: Long, maxStale: Long, onStale: (T) -> T = { it }, load: () -> T,
    ): T {
        val saved = try { database?.readResponse(key) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        val cached = saved?.let {
            try { json.decodeFromString<T>(it.payload) } catch (_: Exception) { null }
        }
        val age = saved?.let { currentEpochMillis() - it.savedAt }
        if (cached != null && age != null && age in 0 until ttl) return cached
        try {
            // Parse and validate before persisting. Captcha/error pages must never replace
            // useful data; a storage failure must not turn a successful request into an error.
            val result = load()
            try { database?.writeResponse(key, json.encodeToString(result), currentEpochMillis()) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { }
            return result
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (cached != null && age != null && age in 0..maxStale) return onStale(cached)
            throw error
        }
    }

    private suspend fun detail(address: String): String = cachedResponse(
        "detail:$address", ttl = 6L * 60 * 60 * 1000, maxStale = 7L * 24 * 60 * 60 * 1000,
    ) {
        (if (JianpianSource.owns(address)) jianpian.detail(address) else fetch(address)).also {
            if (episodes(it, address).isEmpty()) throw ApiError("原站暂无可播放剧集")
        }
    }

    private fun episodes(body: String, page: String): List<Pair<Episode, String>> =
        if (JianpianSource.owns(page)) JianpianSource.episodes(body, page) else PomoSource.episodes(body, page)

    private fun metadata(body: String, page: String): JsonObject? =
        (if (JianpianSource.owns(page)) JianpianSource.data(body) else PomoSource.data(body)) as? JsonObject

    /** Races useful results rather than TCP connections, cancelling slower in-flight requests. */
    private suspend fun fastestSearch(loads: List<suspend () -> SearchResult>): SearchResult = coroutineScope {
        val results = Channel<Pair<Int, Result<SearchResult>>>(loads.size)
        val jobs = loads.mapIndexed { index, load ->
            launch {
                val result = try { Result.success(load()) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { Result.failure(error) }
                results.send(index to result)
            }
        }
        try {
            var empty: SearchResult? = null
            val failures = mutableMapOf<Int, Throwable>()
            repeat(loads.size) {
                val (index, result) = results.receive()
                val value = result.getOrNull()
                if (value != null && value.results.isNotEmpty()) return@coroutineScope value
                if (value != null) empty = value else result.exceptionOrNull()?.let { failures[index] = it }
            }
            // One successful empty search plus an unavailable backup is not proof that a
            // title has no resource. Preserve the existing catalog's "unknown" semantics.
            val failure = failures.minByOrNull { it.key }?.value
            empty?.let { it.copy(stale = it.stale || failure != null) }
                ?: throw (failure as? ApiError ?: ApiError("所有播放来源暂时不可用，请稍后重试", failure))
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }

    suspend fun list(kind: String, start: Int = 0): CatalogPage {
        if (kind != "top250") {
            val filters = HomeCatalogFilters[kind] ?: throw ApiError("无效的榜单")
            if (kind == "tv" && start !in 0..280) throw ApiError("无效的榜单页码")
            val page = browse(filters, start, HomeMinimumRating).highRated()
            return if (kind == "tv") page.copy(hasMore = page.hasMore && page.nextStart() < 300) else page
        }
        if (start !in 0..225 || start % 25 != 0) throw ApiError("无效的榜单页码")
        return catalog("top250:$start") {
            val html = fetch("https://movie.douban.com/top250?start=$start&filter=")
            val items = ContentParsers.top250(html)
            if (items.isEmpty()) throw ApiError("榜单页面不可用或需要站点验证，请稍后重试")
            CatalogPage(items, start, 25, items.size == 25 && start < 225, "Top 250")
        }.highRated()
    }

    /**
     * Apply the homepage rule after cache lookup too, so legacy or stale pages cannot expose
     * low/unrated entries. Keep the upstream page size and cursor even when filtering rows.
     */
    private fun CatalogPage.highRated(): CatalogPage = copy(
        items = items.filter { it.rating != null && it.rating in HomeMinimumRating..10.0 }
            .sortedByDescending { it.rating },
    )

    /**
     * Saves the remaining small homepage catalog after its first successful page.
     * Top 250 is bounded to 250 entries and high-rated TV to the first 300 entries.
     * A stale page or upstream failure stops warming without disturbing visible content.
     */
    internal suspend fun warmCatalog(kind: String, first: CatalogPage) {
        if (database == null || kind !in listOf("top250", "tv") || first.stale) return
        val limit = if (kind == "top250") 250 else 300
        var page = first
        while (page.hasMore && page.nextStart() < limit) {
            delay(1_000)
            try {
                page = list(kind, page.nextStart())
                if (page.stale || page.items.isEmpty()) return
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { return }
        }
    }

    suspend fun browse(filters: BrowseFilters, start: Int = 0): CatalogPage =
        browse(filters, start, minimumRating = 0.0)

    private suspend fun browse(filters: BrowseFilters, start: Int, minimumRating: Double): CatalogPage {
        val types = mapOf(
            "movie" to "电影", "tv" to "电视剧", "documentary" to "纪录片",
            "animation" to "动画", "variety" to "综艺",
        )
        val genres = mapOf("all" to "全部", "drama" to "剧情", "comedy" to "喜剧", "action" to "动作",
            "romance" to "爱情", "scifi" to "科幻", "mystery" to "悬疑", "finance" to "金融",
            "nature" to "自然", "biography" to "传记", "war" to "战争", "hollywood" to "好莱坞")
        val sorts = mapOf("hot" to "U", "rating" to "S", "latest" to "R")
        if (filters.type !in types || filters.genre !in genres || filters.sort !in sorts ||
            filters.country !in BrowseCountries ||
            start !in 0..10000 || start % 20 != 0 ||
            (filters.year != "all" && !Regex("(19|20)\\d{2}").matches(filters.year))) {
            throw ApiError("无效的分类筛选")
        }
        val tags = (listOf(types.getValue(filters.type)) + when (filters.genre) {
            "all" -> emptyList()
            // An explicit region overrides the legacy Hollywood shortcut's implied US tag.
            "hollywood" -> if (filters.country == "all") listOf("美国", "大片") else listOf("大片")
            else -> listOf(genres.getValue(filters.genre))
        } + if (filters.country == "all") emptyList() else listOf(BrowseCountries.getValue(filters.country))).distinct()
        val address = URLBuilder("https://movie.douban.com/j/new_search_subjects").apply {
            parameters.append("sort", sorts.getValue(filters.sort))
            parameters.append("range", if (minimumRating == 0.0) "0,10" else "${minimumRating.toInt()},10")
            parameters.append("tags", tags.joinToString(","))
            parameters.append("start", "$start")
            if (filters.year != "all") parameters.append("year_range", "${filters.year},${filters.year}")
        }.buildString()
        val bundled = filters == BrowseFilters(type = "tv", sort = "rating") && start < 300
        val ttl = when {
            bundled -> 7L * 24 * 60 * 60 * 1000
            filters.sort == "hot" -> 15L * 60 * 1000
            filters.sort == "latest" -> 30L * 60 * 1000
            else -> 24L * 60 * 60 * 1000
        }
        // Preserve the bundled TV key for first-launch offline browsing; list() filters legacy
        // cached scores too. General browse uses the URL key, which includes its score range,
        // so it cannot reuse a restricted homepage result.
        return catalog(if (bundled && minimumRating == HomeMinimumRating) "tv:$start" else address, ttl) {
            val data = try { json.parseToJsonElement(fetch(address)).jsonObject["data"]?.jsonArray }
            catch (error: CancellationException) { throw error }
            catch (error: ApiError) { throw error }
            catch (_: Exception) { throw ApiError("分类页面不可用或需要站点验证") }
                ?: throw ApiError("来源未返回有效的分类列表")
            val items = data.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val title = item.text("title").trim()
                val url = ContentParsers.subjectUrl(item.text("url"))
                val rating = item.text("rate").toDoubleOrNull()?.takeIf { it > 0 && it <= 10 && it >= minimumRating }
                if (title.isBlank() || url.isBlank() || rating == null) null else CatalogItem(
                    title, url, rating = rating, cover = item.text("cover").takeIf(::isWebUrl).orEmpty(),
                    casts = (item["casts"] as? JsonArray)?.take(3)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    kind = filters.type,
                )
            }
            CatalogPage(items, start, 20, data.size == 20, tags.joinToString(" · "))
        }
    }

    suspend fun search(query: String, type: String): SearchResult {
        if (type !in listOf("all", "movie", "tv")) throw ApiError("无效的影视类型")
        val keyword = query.trim().replace("《", "").replace("》", "")
            .replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
        if (keyword.isBlank() || keyword.length > 160) throw ApiError("请输入 1–160 字的影视名称")
        val address = PomoSource.address("auto_search", "wd" to keyword)
        val activeRules = rules()
        return cachedResponse(
            // Parser rules are part of the key, so a rule update cannot reuse an old parse.
            "search:online2:$type:$address:${json.encodeToString(activeRules)}",
            ttl = 15L * 60 * 1000, maxStale = 7L * 24 * 60 * 60 * 1000,
            onStale = { result: SearchResult -> result.copy(stale = true) },
        ) {
            fastestSearch(listOf(
                { PomoSource.search(fetch(address, 8_000), type) },
                { jianpian.search(keyword, type) },
            ))
        }
    }

    /**
     * Resolves a catalog item to one unambiguous playable source.
     *
     * Source titles commonly carry a remake-year suffix (e.g. 美丽人生1997) while the same base
     * title may also belong to unrelated TV dramas, so an exact title filter is not enough.
     * Obvious movie/series mismatches are filtered from source metadata. Candidates are kept
     * when their core title matches and every known year signal
     * agrees with the catalog year, then ranked by year evidence plus any cast overlap. A stream
     * is only auto-played when the strongest candidates identify the same title and release;
     * provider mirrors of that release are interchangeable.
     */
    suspend fun resolve(item: CatalogItem): MatchResult = probeMatch(item).match

    private suspend fun probeMatch(item: CatalogItem): ResourceMatch {
        val target = ContentParsers.normalizedTitle(item.title)
        val wantedYear = item.year
        val search = search(item.title, ContentParsers.searchTypeFor(item.kind))
        val candidates = search.results.mapNotNull { candidate ->
            val exact = ContentParsers.normalizedTitle(candidate.title) == target
            if (!exact && ContentParsers.coreTitle(candidate.title) != target) return@mapNotNull null
            val suffixYear = ContentParsers.titleYear(candidate.title)
            val castScore = ContentParsers.castOverlapScore(item.casts, candidate.cast)
            if (wantedYear.isBlank()) {
                // Without a release year, picking a year-suffixed remake would be a guess.
                if (!exact) return@mapNotNull null
                RankedCandidate(candidate, 1 + castScore)
            } else {
                val knownYears = listOfNotNull(suffixYear, candidate.year.takeIf(String::isNotBlank))
                if (knownYears.any { it != wantedYear }) return@mapNotNull null
                val yearScore = when {
                    suffixYear == wantedYear -> 4
                    exact && candidate.year == wantedYear -> 3
                    candidate.year == wantedYear -> 2
                    exact -> 1
                    else -> 0
                }
                RankedCandidate(candidate, yearScore + castScore)
            }
        }.sortedByDescending { it.score }

        val verified = mutableListOf<RankedCandidate>()
        var uncertain = search.stale
        for (candidate in candidates) {
            // Once a strongest release is verified, weaker matches cannot change the result.
            // Mirror providers remain fallbacks only until that release has a working list.
            if (verified.any { it.score > candidate.score }) break
            if (verified.any {
                it.score == candidate.score &&
                    ContentParsers.normalizedTitle(it.item.title) == ContentParsers.normalizedTitle(candidate.item.title) &&
                    it.item.year == candidate.item.year && it.item.cast == candidate.item.cast
            }) continue
            try {
                val detail = detail(candidate.item.detailUrl)
                if (wantedYear.isNotBlank()) {
                    val detailYear = metadata(detail, candidate.item.detailUrl)?.text("year").orEmpty()
                    if (detailYear.isNotBlank() && detailYear != wantedYear) continue
                }
                // Availability checks only inspect episode lists. Parsing a signed media URL
                // here would multiply slow requests for every catalog row and expire before use.
                verified += candidate.copy(pageUrl = candidate.item.detailUrl)
            } catch (error: CancellationException) { throw error }
            catch (_: ApiError) {
                // A failed request/parser, or an unsupported first line with untested alternate
                // lines, is not proof of absence. Hide only a successfully searched film with
                // no matching resource, never a candidate whose availability is uncertain.
                uncertain = true
            }
        }
        val best = verified.maxByOrNull { it.score } ?: return ResourceMatch(
            MatchResult(false), if (uncertain) ResourceStatus.Unknown else ResourceStatus.Missing,
        )
        // Provider mirrors must not make one release appear ambiguous. Different years or
        // casts at equal scores still require an explicit user choice.
        val distinct = verified.filter { it.score == best.score }.distinctBy {
            listOf(ContentParsers.normalizedTitle(it.item.title), it.item.year, it.item.cast)
        }
        val match = if (distinct.size == 1)
            MatchResult(true, best.pageUrl) else MatchResult(false)
        return ResourceMatch(match, ResourceStatus.Available)
    }

    suspend fun player(request: PlayerRequest): PlaybackInfo {
        val original = ContentParsers.pageUrl(request.page.orEmpty())
        if (Url(original).host == "huolot.com") return resolvePlayer(request)
        try {
            return resolvePlayer(request)
        } catch (error: CancellationException) { throw error }
        catch (failure: ApiError) {
            return fallbackPlayer(request, original, failure)
        }
    }

    private suspend fun fallbackPlayer(request: PlayerRequest, original: String, failure: ApiError): PlaybackInfo {
        val page = original.substringBefore('#')
        val body = try { detail(page) }
        catch (error: CancellationException) { throw error }
        catch (_: ApiError) { null }
        val data = body?.let { metadata(it, page) }
        val title = data?.text(if (JianpianSource.owns(page)) "title" else "name").orEmpty()
            .ifBlank { request.title.orEmpty() }
        if (title.isBlank()) throw failure
        val year = data?.text("year").orEmpty()
        val selected = request.episode ?: original.takeIf { '#' in it }
        val oldEntries = body?.let { episodes(it, page) }.orEmpty()
        val oldEpisode = oldEntries.find { it.first.path == selected }?.first
        // Invalid/cross-title episode IDs must never become an excuse to play a different film.
        if (selected != null && oldEpisode == null) throw failure
        val search = try {
            if (JianpianSource.owns(page))
                PomoSource.search(fetch(PomoSource.address("auto_search", "wd" to title), 8_000), "all")
            else jianpian.search(title, "all")
        } catch (error: CancellationException) { throw error }
        catch (_: ApiError) { throw failure }
        val matches = search.results.filter {
            ContentParsers.coreTitle(it.title) == ContentParsers.normalizedTitle(title) &&
                (year.isBlank() || it.year.isBlank() || it.year == year) &&
                ContentParsers.titleYear(it.title)?.let { suffix -> year.isBlank() || suffix == year } != false
        }
        if (year.isBlank() && matches.map { it.year }.filter(String::isNotBlank).distinct().size > 1)
            throw failure
        for (candidate in matches.take(8)) {
            try {
                val candidateBody = detail(candidate.detailUrl)
                val candidateYear = metadata(candidateBody, candidate.detailUrl)?.text("year").orEmpty()
                if (year.isNotBlank() && candidateYear.isNotBlank() && candidateYear != year) continue
                val alternatives = episodes(candidateBody, candidate.detailUrl)
                val next = if (oldEpisode == null) null else {
                    alternatives.firstOrNull {
                        sameEpisode(oldEpisode, it.first) ||
                            oldEntries.count { entry -> entry.first.line == oldEpisode.line } == 1 &&
                            alternatives.count { entry -> entry.first.line == it.first.line } == 1
                    }?.first ?: continue
                }
                return resolvePlayer(request.copy(page = candidate.detailUrl, episode = next?.path))
            } catch (error: CancellationException) { throw error }
            catch (_: ApiError) { /* A backup failure must not replace the original useful error. */ }
        }
        throw failure
    }

    private fun sameEpisode(first: Episode, second: Episode): Boolean {
        fun number(label: String): String? = Regex("(?:第\\s*)?(\\d{1,4})\\s*集")
            .find(label)?.groupValues?.get(1)?.toIntOrNull()?.toString()
        val a = number(first.label)
        val b = number(second.label)
        return if (a != null && b != null) a == b
        else ContentParsers.normalizedTitle(first.label) == ContentParsers.normalizedTitle(second.label)
    }

    private suspend fun resolvePlayer(request: PlayerRequest): PlaybackInfo {
        val original = ContentParsers.pageUrl(request.page.orEmpty())
        if (Url(original).host == "huolot.com") {
            val title = request.title?.takeIf { it.isNotBlank() } ?: throw ApiError("请重新搜索影片后播放")
            val match = resolve(CatalogItem(title, ""))
            if (!match.playable) throw ApiError("新来源暂未找到此影片，请重新搜索")
            return resolvePlayer(PlayerRequest(page = match.pageUrl, title = title))
        }
        val page = original.substringBefore('#')
        val body = detail(page)
        val entries = episodes(body, page)
        val selected = request.episode ?: original.takeIf { '#' in it }
        if (selected != null && entries.none { it.first.path == selected })
            throw ApiError("该剧集不在当前影片的播放列表中")
        val selectedEpisode = entries.find { it.first.path == selected }?.first
        val choices = if (selectedEpisode != null) entries.filter {
            it.first.path == selected || sameEpisode(selectedEpisode, it.first)
        }.sortedBy { if (it.first.path == selected) 0 else 1 }
            // Retry the first episode on alternate lines, never a later episode of the same
            // series. A failed first stream must not silently skip part of the story.
            else entries.filter { it.first.path.endsWith("-0") }
                .sortedBy { if (Url(it.second).encodedPath.endsWith(".m3u8")) 0 else 1 }
        var failure: ApiError? = null
        for ((episode, address) in choices.distinctBy { it.second }) {
            try {
                if (address in request.rejectedMedia) continue
                val media = if (JianpianSource.owns(page)) {
                    val kind = JianpianSource.mediaKind(address) ?: throw ApiError("此线路无法直接播放")
                    probeMedia(address, kind)
                    address to kind
                } else PomoSource.media(fetch(PomoSource.address("parse", "url" to address), 5_000))
                if (media.first in request.rejectedMedia) continue
                val data = metadata(body, page)
                return PlaybackInfo(request.title ?: data?.text(if (JianpianSource.owns(page)) "title" else "name")
                    .orEmpty().ifBlank { "影视播放" },
                    media.first, media.second, entries.map { it.first }, episode.path, page,
                    if (JianpianSource.owns(page)) "荐片" else "Pomo · ${Url(page).parameters["site"]}",
                    data?.text(if (JianpianSource.owns(page)) "description" else "content").orEmpty())
            } catch (error: CancellationException) { throw error }
            catch (error: ApiError) { failure = error }
        }
        throw failure ?: ApiError("原站暂无可播放剧集")
    }

    /** Reads a bounded prefix, rejecting dead hosts, HTML error pages and login responses. */
    private suspend fun probeMedia(address: String, kind: String) {
        val available = try { withTimeoutOrNull(5_000) {
            var target = address
            repeat(5) {
                val response = client.get(target) { header(HttpHeaders.Range, "bytes=0-8191") }
                val channel = response.bodyAsChannel()
                try {
                    if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                        val location = response.headers[HttpHeaders.Location] ?: return@withTimeoutOrNull false
                        target = URLBuilder(target).apply { takeFrom(location) }.buildString()
                        if (!isWebUrl(target)) return@withTimeoutOrNull false
                    } else {
                        if (!response.status.isSuccess()) return@withTimeoutOrNull false
                        val bytes = ByteArray(8192)
                        var count = 0
                        while (count < bytes.size) {
                            val read = channel.readAvailable(bytes, count, bytes.size - count)
                            if (read == -1) break
                            count += read
                        }
                        return@withTimeoutOrNull if (kind == "hls")
                            bytes.decodeToString(endIndex = count).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                                .startsWith("#EXTM3U")
                        else count >= 12 && bytes.decodeToString(startIndex = 4, endIndex = 8) == "ftyp"
                    }
                } finally { channel.cancel(null) }
            }
            false
        } ?: false }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
        if (!available) throw ApiError("此播放线路暂时不可用")
    }

    fun close() { pages.clear(); client.close() }
}

/** A search candidate kept by [ContentRepository.resolve], ordered from strongest to weakest year evidence. */
private data class RankedCandidate(val item: SearchItem, val score: Int, val pageUrl: String = "")
private data class ResourceMatch(val match: MatchResult, val status: ResourceStatus)

internal const val OnlineOrigin = "https://pomo.mom"
internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Reads bounded upstream markup and JSON without executing JavaScript. */
internal object ContentParsers {
    private val json = Json { ignoreUnknownKeys = true }
    private val playPath = Regex("/vodplay/(\\d+)-(\\d+)-(\\d+)\\.html")
    fun subjectUrl(value: String): String = try {
        val url = Url(value)
        if (isWebUrl(value) && url.host == "movie.douban.com" && Regex("/subject/\\d+/?").matches(url.encodedPath))
            "https://movie.douban.com${url.encodedPath.trimEnd('/')}/" else ""
    } catch (_: Exception) { "" }

    fun pageUrl(value: String): String {
        val url = try { Url(value) } catch (_: Exception) { throw ApiError("无效的播放页面") }
        if (url.host == "pomo.mom") return PomoSource.page(value)
        if (JianpianSource.owns(value)) return JianpianSource.page(value)
        if (!isWebUrl(value) || url.protocol != URLProtocol.HTTPS || url.host != "huolot.com" ||
            url.port != 443 || !url.parameters.isEmpty() || url.fragment.isNotEmpty() ||
            !Regex("/vod(?:detail/\\d+|play/\\d+-\\d+-\\d+)\\.html").matches(url.encodedPath)) {
            throw ApiError("无效的播放页面")
        }
        return "https://huolot.com" + url.encodedPath
    }

    fun normalizedTitle(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
    private val trailingYear = Regex("^(.*?)((?:19|20)\\d{2})$")
    /** Title with a trailing remake-year marker removed after normalization, e.g. 美丽人生（1997） -> 美丽人生. */
    fun coreTitle(value: String): String {
        val normalized = normalizedTitle(value)
        val match = trailingYear.find(normalized) ?: return normalized
        return match.groupValues[1].ifBlank { normalized }
    }
    /** Release year embedded at the end of a source title, e.g. 美丽人生1997 -> 1997; null when the digits are the title itself. */
    fun titleYear(value: String): String? {
        val match = trailingYear.find(normalizedTitle(value)) ?: return null
        return match.groupValues[2].takeIf { match.groupValues[1].isNotBlank() }
    }
    fun searchTypeFor(kind: String) = when (kind) {
        "tv" -> "tv"
        "movie", "documentary", "animation" -> "movie"
        else -> "all"
    }
    fun castOverlapScore(expected: List<String>, actual: String): Int {
        val expectedNames = expected.map { normalizedTitle(it) }.filter { it.isNotBlank() }
        val actualText = normalizedTitle(actual)
        if (expectedNames.isEmpty() || actualText.isBlank()) return 0
        return if (expectedNames.any { it in actualText || actualText in it }) 2 else 0
    }
    fun year(html: String) = Regex("年份\\s*[:：]\\s*((?:18|19|20)\\d{2})")
        .find(HtmlNode.parse(html).text())?.groupValues?.get(1).orEmpty()

    fun synopsis(html: String, rules: ParserRules = ParserRules()): String {
        val nodes = HtmlNode.parse(html).descendants()
        val panel = nodes.firstOrNull { node ->
            node.hasClass("stui-pannel") && node.descendants().any {
                it.tag in listOf("h2", "h3") && it.text() == "剧情简介"
            }
        }
        val paragraph = panel?.descendants()?.firstOrNull { it.hasClass("col-pd") }
            ?: nodes.firstOrNull { it.hasClass(rules.synopsisClass) || it.hasClass("vod_content") }
            ?: return ""
        val text = paragraph.text()
        // This template prefixes the plot with generated cast/keyword copy.
        val prefix = Regex("影片的关键词是[^,，]*[,，]").find(text)
        return (if (prefix != null) text.substring(prefix.range.last + 1) else text).trim()
    }

    fun top250(html: String): List<CatalogItem> = HtmlNode.parse(html).descendants().filter {
        it.tag == "div" && it.hasClass("item")
    }.mapNotNull { card ->
        val children = card.descendants()
        val image = children.firstOrNull { it.tag == "img" }
        val title = image?.attr("alt")?.trim().orEmpty()
        val url = children.firstNotNullOfOrNull { subjectUrl(it.attr("href")).takeIf(String::isNotBlank) }.orEmpty()
        val rating = children.firstOrNull { it.hasClass("rating_num") }?.text()?.toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }
        if (title.isBlank() || url.isBlank() || rating == null) null else CatalogItem(
            title, url, Regex("\\b((?:18|19|20)\\d{2})\\s*/").find(card.text())?.groupValues?.get(1).orEmpty(),
            rating, image?.attr("src")?.takeIf(::isWebUrl).orEmpty(),
            kind = "movie",
        )
    }.distinctBy { it.url }

    fun search(html: String, type: String, rules: ParserRules = ParserRules()): List<SearchItem> = HtmlNode.parse(html).descendants().filter {
        it.tag == "ul" && it.hasClass(rules.searchListClass)
    }.flatMap { list -> list.descendants().filter { it.tag == "li" }.mapNotNull { card ->
        val anchor = card.descendants().firstOrNull { it.tag == "a" && it.hasClass(rules.thumbnailClass) } ?: return@mapNotNull null
        val title = anchor.attr("title").trim().take(160)
        val href = anchor.attr("href")
        val address = if (href.startsWith("/")) "https://huolot.com" + href else href
        val page = try { pageUrl(address) } catch (_: ApiError) { return@mapNotNull null }
        if (title.isBlank() || !Url(page).encodedPath.startsWith("/voddetail/")) return@mapNotNull null
        val category = Regex("类型\\s*[:：]\\s*(.*?)(?=地区\\s*[:：]|年份\\s*[:：]|$)").find(card.text())?.groupValues?.get(1)?.replace(Regex("\\s"), "").orEmpty()
        val tv = setOf("国产", "国产剧", "香港", "香港剧", "港剧", "台湾", "台湾剧", "台剧",
            "美国", "欧美", "欧美剧", "美剧", "韩国", "韩国剧", "韩剧", "日本", "日本剧", "日剧",
            "海外", "海外剧", "泰国", "泰国剧", "泰剧", "连续剧", "电视剧", "短剧", "其他剧")
        val movies = setOf("电影", "动作", "喜剧", "爱情", "科幻", "恐怖", "剧情", "战争", "惊悚",
            "悬疑", "犯罪", "纪录", "纪录片", "动画", "动画片", "动画电影", "奇幻", "冒险", "家庭",
            "武侠", "灾难", "音乐", "西部", "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片", "战争片")
        if (type == "tv" && category !in tv || type == "movie" && category !in movies) return@mapNotNull null
        val cardText = card.text()
        val year = Regex("年份\\s*[:：]\\s*((?:18|19|20)\\d{2})").find(cardText)?.groupValues?.get(1).orEmpty()
        val region = Regex("地区\\s*[:：]\\s*(.*?)(?=年份\\s*[:：]|$)").find(cardText)?.groupValues?.get(1)
            ?.trim().orEmpty().take(20)
        val genre = Regex("类型\\s*[:：]\\s*(.*?)(?=地区\\s*[:：]|年份\\s*[:：]|$)").find(cardText)?.groupValues?.get(1)
            ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.joinToString("、").orEmpty().take(40)
        val cast = Regex("主演\\s*[:：]\\s*(.*?)(?=类型\\s*[:：]|地区\\s*[:：]|年份\\s*[:：]|$)")
            .find(cardText)?.groupValues?.get(1)?.trim().orEmpty().take(80)
        val remark = anchor.descendants().firstOrNull { it.hasClass("pic-text") }?.text()?.trim().orEmpty().take(20)
        SearchItem(title, "阳光电影", "ygdy:${Url(page).encodedPath}", true, page,
            anchor.attr("data-original").takeIf(::isWebUrl).orEmpty(),
            year, region, genre, cast, remark)
    } }.distinctBy { it.detailUrl }

    fun episodes(html: String, movie: String): List<Episode> {
        val items = linkedMapOf<String, Episode>()
        HtmlNode.parse(html).descendants().filter { it.tag == "a" }.forEach { anchor ->
            val path = anchor.attr("href")
            val match = playPath.matchEntire(path)
            if (match != null && match.groupValues[1] == movie) {
                items[path] = Episode(path, anchor.text().take(80).ifBlank { "播放" }, match.groupValues[2].toInt())
            }
        }
        return items.values.toList()
    }

    fun media(html: String, rules: ParserRules = ParserRules()): Pair<String, String> {
        val marker = Regex("\\bvar\\s+${Regex.escape(rules.playerVariablePrefix)}\\w+\\s*=\\s*").find(html) ?: throw ApiError("原站未提供可读取的视频地址")
        val remaining = html.substring(marker.range.last + 1)
        // Find the JSON object boundary while respecting escaped quotes and nested objects.
        var quoted = false
        var escaped = false
        var depth = 0
        var end = -1
        if (!remaining.startsWith("{")) throw ApiError("原站播放信息格式异常")
        for ((index, char) in remaining.withIndex()) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = index + 1; break } }
            }
        }
        if (end < 0) throw ApiError("原站播放信息格式异常")
        val config = try { json.parseToJsonElement(remaining.substring(0, end)).jsonObject }
        catch (_: Exception) { throw ApiError("原站播放信息格式异常") }
        if (listOf("points", "trysee").any { config.text(it) !in listOf("", "0", "false", "null") })
            throw ApiError("此资源需要在原站完成观看授权")
        val url = try {
            when (config.text("encrypt").ifBlank { "0" }) {
                "0" -> config.text("url")
                "1" -> config.text("url").decodeURLPart()
                "2" -> Base64.decode(config.text("url")).decodeToString(throwOnInvalidSequence = true).decodeURLPart()
                else -> throw ApiError("原站播放信息格式异常")
            }
        } catch (_: Exception) { throw ApiError("原站播放信息格式异常") }
        if (!isWebUrl(url)) throw ApiError("视频地址无效")
        val path = Url(url).encodedPath.lowercase()
        val kind = when {
            path.endsWith(".m3u8") -> "hls"
            listOf(".mp4", ".webm", ".ogg", ".m4v", ".mov").any { path.endsWith(it) } -> "video"
            else -> throw ApiError("此播放线路暂不支持内置播放，请使用原站观看")
        }
        return url to kind
    }
}
