package dev.pandavideo.data.repository

import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.BrowseCountries
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.model.HomeCatalogFilters
import dev.pandavideo.core.model.isTelevisionDrama
import dev.pandavideo.core.model.HomeMinimumRating
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.MatchResult
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.LineProbe
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import dev.pandavideo.core.platform.currentEpochMillis
import okio.ByteString.Companion.encodeUtf8

open class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An explicit server rejection or invalid media prefix, never a transport timeout. */
private class MediaUnavailable : ApiError("此播放线路暂时不可用")

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
    private val doubanSession: dev.pandavideo.core.platform.DoubanSession =
        dev.pandavideo.core.platform.UnsupportedDoubanSession,
    private val lineHasAds: (String, Episode) -> Boolean = { _, _ -> false },
    private val playbackCacheNowMs: () -> Long = ::currentEpochMillis,
    private val rules: () -> ParserRules = { ParserRules() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val actors = ActorSource { address -> fetch(address) }
    private val bilibili = BilibiliSource { address, headers -> fetch(address, 10_000, headers) }

    /** Queries source-owned person identities independently from playback search. */
    internal suspend fun searchActors(query: String): dev.pandavideo.core.model.ActorSuggestions = cachedResponse(
        "actors:iqiyi:suggest:v1:${query.trim()}", 7L * 24 * 60 * 60 * 1000, 30L * 24 * 60 * 60 * 1000,
        onStale = { it.copy(stale = true) },
    ) { actors.search(query.trim()) }

    /** Caches complete source properties separately so legacy search snapshots cannot hide them. */
    internal suspend fun actorProfile(
        actor: dev.pandavideo.core.model.ActorRef,
        onCached: suspend (dev.pandavideo.core.model.ActorRef) -> Unit = {},
    ): dev.pandavideo.core.model.ActorRef =
        cachedResponse("actors:iqiyi:profile:v1:${actor.id}", 7L * 24 * 60 * 60 * 1000,
            30L * 24 * 60 * 60 * 1000, onCached = onCached) { actors.profile(actor) }

    /** Reads explicit casts from local catalogs and cached subject details, including offline pages. */
    internal suspend fun actorCatalogSnapshot(): List<CatalogItem> = database?.actorCatalogSnapshot().orEmpty()

    /** Loads verified cast statements, with bounded offline fallback and explicit coverage. */
    internal suspend fun actorWorks(
        actor: dev.pandavideo.core.model.ActorRef,
        onCached: suspend (dev.pandavideo.core.model.ActorFilmography) -> Unit = {},
    ): dev.pandavideo.core.model.ActorFilmography =
        cachedResponse("actors:iqiyi:works:v2:${actor.id}", 7L * 24 * 60 * 60 * 1000, 30L * 24 * 60 * 60 * 1000,
            onStale = { it.copy(stale = true) }, onCached = onCached,
            legacyKey = "actors:iqiyi:works:v1:${actor.id}",
            freshWhen = { snapshot ->
                snapshot.works.none { Regex("[（(](?:普通话|国语|粤语|国配|粤配)[）)]$").containsMatchIn(it.title) }
            }) {
            actors.filmography(actor)
        }

    /** Resolves optional catalog metadata or an independently verified playback detail page. */
    internal suspend fun actorCatalogWork(work: dev.pandavideo.core.model.ActorWork): dev.pandavideo.core.model.ActorWork {
        ContentParsers.playbackPageOrNull(work.playbackPageUrl)?.let {
            // A confirmed playback identity must survive an optional artwork failure.
            val poster = ActorSource.externalPoster(work.cover)
                .ifBlank { actorPlaybackPoster(it) }
                .ifBlank { actorSearchPoster(work, it) }
            return work.copy(playbackPageUrl = it, cover = poster)
        }
        // Refresh missing/protected artwork even inside the identity cache's normal TTL.
        // Legacy identities remain bounded offline fallback, with protected posters stripped.
        val clean = work.copy(cover = ActorSource.externalPoster(work.cover))
        return cachedResponse("actors:iqiyi:catalog:v4:${work.id}:${work.title}:${work.year}:${work.kind}", 7L * 24 * 60 * 60 * 1000,
            30L * 24 * 60 * 60 * 1000,
            legacyKey = "actors:iqiyi:catalog:v1:${work.id}:${work.title}:${work.year}:${work.kind}",
            onStale = { it.copy(cover = ActorSource.externalPoster(it.cover)) },
            freshWhen = { it.cover.isNotBlank() && ActorSource.externalPoster(it.cover) == it.cover },
            cacheWhen = { it.verifiedCatalog || it.playbackPageUrl.isNotBlank() }) {
            val mapped = try { actors.catalogWork(clean.copy(playbackPageUrl = "")) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { clean.copy(playbackPageUrl = "") }
            val verified = if (mapped.verifiedCatalog && mapped.subjectId.isNotBlank()) mapped
            else {
                // Person credits are independent of Douban suggestions. An empty suggestion
                // response is not evidence of missing playback; verify title/year/type against
                // the same source search and episode lists used by normal catalog playback.
                val probe = probeMatch(mapped.catalog())
                if (probe.status == ResourceStatus.Unknown) throw ApiError("参演作品资源暂时无法检查")
                val page = ContentParsers.playbackPageOrNull(probe.match.pageUrl)
                if (probe.match.playable && page != null) mapped.copy(playbackPageUrl = page,
                    cover = ActorSource.externalPoster(probe.poster)) else mapped
            }
            if (verified.cover.isNotBlank()) verified else {
                val poster = try {
                    if (verified.verifiedCatalog && verified.subjectId.isNotBlank())
                        artworkDetail(verified.catalog(), verified.title, verified.year)?.cover.orEmpty()
                    else ContentParsers.playbackPageOrNull(verified.playbackPageUrl)
                        ?.let { actorPlaybackPoster(it) }.orEmpty()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { "" }
                verified.copy(cover = ActorSource.externalPoster(poster)
                    .ifBlank { actorSearchPoster(verified, verified.playbackPageUrl.takeIf(String::isNotBlank)) })
            }
        }
    }

    /** Uses artwork from a uniquely verified release, never an unrelated search hit. */
    private suspend fun actorSearchPoster(work: dev.pandavideo.core.model.ActorWork, page: String?): String = try {
        // Clear the detail identity only for this optional artwork lookup. Playback itself
        // continues to use the confirmed page, including when search/artwork is unavailable.
        val probe = probeMatch(work.catalog().copy(url = ""))
        if (probe.match.playable && (page == null || probe.match.pageUrl == page))
            ActorSource.externalPoster(probe.poster) else ""
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { "" }

    /** Reads artwork only from the already verified release, preserving playback on failure. */
    private suspend fun actorPlaybackPoster(page: String): String = try {
        val data = metadata(detail(page), page)
        listOf("pic", "cover", "thumbnail", "tvimg", "thumbnail_url", "image", "vod_pic")
            .firstNotNullOfOrNull { key ->
                ActorSource.externalPoster(data?.text(key).orEmpty()).takeIf(String::isNotBlank)
            }.orEmpty()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { "" }

    /**
     * Loads Douban's plot summary and cast for the player details panel. Playback sources
     * frequently omit both, and history entries carry no catalog item, so this resolves a
     * subject by title when the caller has no Douban URL. Failures never affect playback.
     */
    internal suspend fun artworkDetail(item: CatalogItem?, title: String, year: String): CatalogItem? {
        val name = title.ifBlank { item?.title.orEmpty() }
        if (name.isBlank()) return null
        val kind = item?.kind.orEmpty()
        val known = item?.url?.let(DoubanArtwork::subjectId)
        val suggestion = if (known != null) null else DoubanArtwork.suggestion(
            fetch(DoubanArtwork.suggest(name)), name, year, kind,
        )
        val id = known ?: suggestion?.id ?: return null
        val tv = if (known != null) kind in setOf("tv", "variety") else suggestion?.tv == true
        // Cache the source response, never the merged row: the same title may be opened from
        // a catalog card (rating/year already known) and from history (only a title).
        // Previous snapshots truncated cast lists. Refresh them even while their TTL is fresh,
        // retaining the old metadata only as bounded offline fallback.
        val details = cachedResponse("artwork:douban:v2:$id", 7L * 24 * 60 * 60 * 1000,
            30L * 24 * 60 * 60 * 1000, legacyKey = "artwork:douban:v1:$id") {
            val body = try { fetch(DoubanArtwork.detail(id, tv)) }
            catch (error: CancellationException) { throw error }
            catch (error: ApiError) {
                // Genre cards do not always reveal movie/TV shape, and type endpoints can
                // be unavailable independently. The generic endpoint keeps the exact ID.
                if (known == null) throw error
                fetch("https://m.douban.com/rexxar/api/v2/subject/$id")
            }
            DoubanArtwork.detail(body, id, kind)
        } ?: return null
        return item?.mergeArtwork(details) ?: details
    }
    private val pages = linkedMapOf<String, CatalogPage>()
    private val jianpian = JianpianClient(fetch = { address, budget, headers -> fetch(address, budget, headers) })
    private val availability = CatalogAvailability(database, { "online:6:${json.encodeToString(rules())}" },
        check = { probeMatch(it).status })

    internal suspend fun visibleCatalog(page: CatalogPage): CatalogPage = availability.visible(page)

    internal suspend fun inspectCatalog(items: List<CatalogItem>, onMissing: (CatalogItem) -> Unit) =
        availability.inspect(items.filterNot { it.kind == "xiaopin" && SketchSource.owns(it.url) }, onMissing)

    /** Loads one page from each public sketch source, retaining independent offline snapshots. */
    internal suspend fun sketches(
        start: Int = 0, refresh: Boolean = false, onCached: (suspend (CatalogPage) -> Unit)? = null,
    ): CatalogPage = coroutineScope {
        require(start in 0..10_000)
        // Publish one interleaved snapshot before either source starts its refresh. Publishing
        // per-source callbacks would temporarily replace one site's rows with the other's.
        if (!refresh && onCached != null) {
            val snapshots = SketchSource.catalogHosts.map { host ->
                val saved = try { database?.read("sketch:v1:$host:$start") }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
                val page = saved?.page ?: pages["sketch:v1:$host:$start"]
                page?.let {
                    Result.success(it.copy(stale = saved == null ||
                        currentEpochMillis() - saved.savedAt !in 0 until 6L * 60 * 60 * 1000))
                } ?: Result.failure(ApiError("小品缓存尚未建立"))
            }
            if (snapshots.any { it.isSuccess }) onCached(mergeSketches(snapshots, start))
        }
        val results = SketchSource.catalogHosts.map { host ->
            async {
                try {
                    val previous = if (start == 0) null else pages["sketch:v1:$host:${start - 1}"] ?: try {
                        database?.read("sketch:v1:$host:${start - 1}")?.page
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null }
                    // The sites end at different page counts. A completed source contributes
                    // no more rows, rather than turning every later page into a partial error.
                    if (previous?.hasMore == false && !previous.stale) {
                        val finished = CatalogPage(emptyList(), start, 1, false)
                        pages["sketch:v1:$host:$start"] = finished
                        if (pages.size > 64) pages.remove(pages.keys.first())
                        return@async Result.success(finished)
                    }
                    Result.success(catalog("sketch:v1:$host:$start", ttl = 6L * 60 * 60 * 1000, forceRefresh = refresh) {
                        SketchSource.catalog(fetch(SketchSource.address(host, start + 1)), host, start + 1)
                    })
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { Result.failure<CatalogPage>(error) }
            }
        }.awaitAll()
        mergeSketches(results, start)
    }

    private fun mergeSketches(results: List<Result<CatalogPage>>, start: Int): CatalogPage {
        val pages = results.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) throw ApiError("无法访问小品来源，请检查网络后重试")
        // Interleave sources so both are represented before the user scrolls. Never merge
        // equal titles across sites: different recordings may legitimately share a name.
        val items = (0 until pages.maxOf { it.items.size }).flatMap { index ->
            pages.mapNotNull { it.items.getOrNull(index) }
        // Apply the visible-title rule at the merge boundary as well: older persisted
        // pages must not briefly show download promotions during startup or offline fallback.
        }.filter { SketchSource.listed(it.url) && SketchSource.catalogTitle(it.title) }.distinctBy { it.url }
        return CatalogPage(items, start, 1, pages.any { it.hasMore } || results.any { it.isFailure },
            label = "小品", stale = pages.any { it.stale } || results.any { it.isFailure })
    }

    /** Measures persisted, regenerable content without touching user or video storage. */
    internal suspend fun cacheUsage(): ContentCacheUsage = database?.cacheUsage() ?: ContentCacheUsage()

    /** Clears fetched content and transient lookup results while retaining built-in catalogs. */
    internal suspend fun clearCache() {
        database?.clearCache()
        pages.clear()
        detailMutex.withLock { recentDetails.clear(); playbackSources.clear() }
        mediaProbeMutex.withLock { mediaProbes.clear() }
        availability.clearMemory()
    }

    /** Fetches an explicitly configured HTTPS JSON document with the same redirect bounds as source pages. */
    internal suspend fun configuration(address: String): String {
        val url = try { Url(address) } catch (_: Exception) { throw ApiError("请输入有效的 HTTPS 地址") }
        if (!isWebUrl(address) || url.protocol != URLProtocol.HTTPS || url.port != 443 ||
            url.fragment.isNotEmpty()) throw ApiError("请输入有效的 HTTPS 地址")
        return fetch(address).also { if (it.length > 65_536) throw ApiError("配置文件过大") }
    }

    /** Verifies identity from the authenticated source response rather than cookie presence. */
    internal suspend fun doubanIdentity(): String? {
        var destination = ""
        val html = fetch("https://www.douban.com/mine/", finalAddress = { destination = it })
        if (!dev.pandavideo.core.platform.isDoubanSessionUrl(destination) ||
            Url(destination).host != "www.douban.com" ||
            !Regex("^/people/[^/]+/?$").matches(Url(destination).encodedPath)) return null
        val nodes = HtmlNode.parse(html).descendants()
        val account = nodes.firstOrNull { it.hasClass("nav-user-account") } ?: return null
        val links = account.descendants().filter { it.tag == "a" }
        if (links.none { it.attr("href").startsWith("https://www.douban.com/accounts/logout") ||
                it.attr("href").startsWith("/accounts/logout") }) return null
        return links.firstOrNull { it.attr("class").split(' ').contains("bn-more") }
            ?.text()?.removeSuffix("的帐号")?.removeSuffix("的账号")?.trim()?.takeIf { it.isNotBlank() }
            ?: "豆瓣账号"
    }

    private suspend fun fetch(address: String, budget: Long = 15_000, headers: Map<String, String> = emptyMap(),
        finalAddress: (String) -> Unit = {}): String {
        try {
            return withTimeoutOrNull(budget) {
                val origin = Url(address)
                var target = address
                var redirects = 0
                // Re-evaluate each redirect. Playback mirrors and configured external URLs
                // must never acquire the user's cookies, even if they redirect to Douban.
                val authenticated = dev.pandavideo.core.platform.isDoubanSessionUrl(address)
                while (true) {
                    val trusted = authenticated && dev.pandavideo.core.platform.isDoubanSessionUrl(target)
                    val cookie = if (trusted) doubanSession.cookieHeader(target) else null
                    val response = client.get(target) {
                        header(HttpHeaders.UserAgent, if (trusted) doubanSession.userAgent ?: "Mozilla/5.0" else "Mozilla/5.0")
                        header(HttpHeaders.Referrer, headers.entries.firstOrNull {
                            it.key.equals(HttpHeaders.Referrer, true)
                        }?.value ?: "${origin.protocol.name}://${origin.host}/")
                        headers.filterKeys { !it.equals(HttpHeaders.Referrer, true) }
                            .forEach { (name, value) -> header(name, value) }
                        if (!cookie.isNullOrBlank()) header(HttpHeaders.Cookie, cookie)
                    }
                    if (trusted) doubanSession.acceptCookies(target,
                        response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
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
                        if ((headers.containsKey("signature") || headers.keys.any { it.equals(HttpHeaders.Cookie, true) }) &&
                            (next.protocol != origin.protocol || next.host != origin.host || next.port != origin.port))
                            throw ApiError("受保护接口跳转到其他来源")
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
                    finalAddress(target)
                    return@withTimeoutOrNull bytes.decodeToString(endIndex = count)
                }
                @Suppress("UNREACHABLE_CODE")
                ""
            } ?: throw ApiError("来源请求超时，请稍后重试")
        } catch (error: CancellationException) { throw error }
        catch (error: ApiError) { throw error }
        catch (error: Exception) { throw ApiError("无法访问影视来源，请检查网络后重试", error) }
    }

    internal suspend fun cctvPrograms(column: String, start: Int = 0, period: String = ""): CatalogPage {
        val address = CctvSource.listAddress(column, start, period)
        // Version the key so older rows without broadcast metadata are reloaded.
        return catalog("cctv:v2:$column:$period:$start", ttl = 30L * 60 * 1000) {
            CctvSource.programs(fetch(address), column, start)
        }
    }

    private suspend fun catalog(
        key: String, ttl: Long = 7L * 24 * 60 * 60 * 1000, forceRefresh: Boolean = false,
        freshWhen: (CatalogPage) -> Boolean = { true },
        onCached: (suspend (CatalogPage) -> Unit)? = null, load: suspend () -> CatalogPage,
    ): CatalogPage {
        val stored = try {
            database?.read(key)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        val fresh = stored != null && currentEpochMillis() - stored.savedAt in 0 until ttl && freshWhen(stored.page)
        // Expiration controls revalidation, not whether a useful list can be displayed.
        // Publish before starting any network work; cancellation still belongs to the caller.
        if (!forceRefresh) (stored?.page ?: pages[key])?.let { cached ->
            onCached?.invoke(if (fresh) cached else cached.copy(stale = true))
        }
        if (!forceRefresh && stored != null && fresh) {
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
        key: String, ttl: Long, maxStale: Long, onStale: (T) -> T = { it },
        legacyKey: String? = null, cacheWhen: (T) -> Boolean = { true },
        freshWhen: (T) -> Boolean = { true },
        noinline onCached: (suspend (T) -> Unit)? = null, load: () -> T,
    ): T {
        val primary = try { database?.readResponse(key) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        // Old parser/matcher snapshots are offline fallback only, even when their timestamp
        // is fresh. Refresh into the new key so a cached omission cannot conceal this fix.
        val saved = primary ?: try { legacyKey?.let { database?.readResponse(it) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        val cached = saved?.let {
            try { json.decodeFromString<T>(it.payload) } catch (_: Exception) { null }
        }
        val age = saved?.let { currentEpochMillis() - it.savedAt }
        if (primary != null && cached != null && age != null && age in 0 until ttl && freshWhen(cached)) return cached
        // Publish bounded stale snapshots before revalidation, rather than making the UI
        // wait for a timeout. Cancellation from the consumer must stop the request as well.
        if (cached != null && age != null && age in 0..maxStale) onCached?.invoke(onStale(cached))
        try {
            // Parse and validate before persisting. Captcha/error pages must never replace
            // useful data; a storage failure must not turn a successful request into an error.
            val result = load()
            try { if (cacheWhen(result)) database?.writeResponse(key, json.encodeToString(result), currentEpochMillis()) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { }
            return result
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (cached != null && age != null && age in 0..maxStale) return onStale(cached)
            throw error
        }
    }

    private val detailLocks = mutableMapOf<String, Mutex>()
    private val recentDetails = linkedMapOf<String, Pair<Long, String>>()
    private val detailMutex = Mutex()
    private data class ValidatedMedia(val at: Long, val media: Pair<String, String>, val probe: LineProbe)
    private data class PlaybackSource(
        val body: String, var entries: List<Pair<Episode, String>>, val metadata: JsonObject?,
        val media: LinkedHashMap<Pair<String, String>, ValidatedMedia> = linkedMapOf(),
    )
    private val playbackSources = linkedMapOf<String, PlaybackSource>()

    /** Keeps stable source lists separate from short-lived signed URLs; refresh replaces changed lists. */
    private suspend fun playbackSource(page: String, reuse: Boolean): PlaybackSource {
        if (reuse) detailMutex.withLock { playbackSources[page] }?.let { return it }
        val body = detail(page)
        val savedMirrors = try {
            database?.readResponse("playback-mirrors:v1:$page")?.takeIf {
                currentEpochMillis() - it.savedAt in 0 until 7L * 24 * 60 * 60_000
            }?.let { json.decodeFromString<List<Pair<Episode, String>>>(it.payload) }.orEmpty()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { emptyList() }
        return detailMutex.withLock {
            playbackSources[page]?.takeIf { it.body == body } ?: PlaybackSource(
                body, episodes(body, page) + (playbackSources[page]?.entries ?: savedMirrors).filter {
                    it.first.path.substringBefore('#') != page
                }, metadata(body, page),
            ).also {
                playbackSources[page] = it
                if (playbackSources.size > 48) playbackSources.remove(playbackSources.keys.first())
            }
        }
    }

    /** Failed/cancelled validation never populates this cache, and evicted pages cannot be resurrected. */
    private suspend fun cachePlaybackMedia(
        page: String, source: PlaybackSource, episode: Episode, address: String,
        media: Pair<String, String>, probe: LineProbe,
    ) = detailMutex.withLock {
        if (playbackSources[page] === source && probe.available == true) {
            source.media[episode.path to address] = ValidatedMedia(playbackCacheNowMs(), media, probe)
            if (source.media.size > 128) source.media.remove(source.media.keys.first())
        }
    }

    /** Shares verified detail bodies between availability checks and player resolution. */
    private suspend fun detail(address: String): String {
        val page = address.substringBefore('#')
        val lock = detailMutex.withLock { detailLocks.getOrPut(page) { Mutex() } }
        return lock.withLock {
            detailMutex.withLock { recentDetails[page] }?.let { (at, body) ->
                if (currentEpochMillis() - at in 0 until 10 * 60_000L) return@withLock body
            }
            val body = loadDetail(page)
            detailMutex.withLock {
                recentDetails[page] = currentEpochMillis() to body
                if (recentDetails.size > 48) recentDetails.remove(recentDetails.keys.first())
                // Only remove idle locks; an active or queued reader must retain its lock.
                detailLocks.entries.removeAll { it.key != page && !it.value.isLocked }
            }
            body
        }
    }

    private suspend fun loadDetail(address: String): String = cachedResponse(
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

    /** Queries both providers concurrently, retaining raw mirrors for catalog verification. */
    private suspend fun mergedSearch(loads: List<suspend () -> SearchResult>): SearchResult = coroutineScope {
        val results = loads.map { load ->
            async {
                try { Result.success(load()) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { Result.failure(error) }
            }
        }.awaitAll()
        val loaded = results.mapNotNull { it.getOrNull() }
        if (loaded.isEmpty()) {
            val failure = results.last().exceptionOrNull()!!
            throw (failure as? ApiError ?: ApiError("所有播放来源暂时不可用，请稍后重试", failure))
        }
        // A successful empty response cannot prove absence while another provider is
        // unavailable. Presentation merges releases later; matching keeps all mirrors.
        SearchResult(
            results = loaded.flatMap { it.results }.distinctBy { it.detailUrl },
            sources = loaded.flatMap { it.sources },
            stale = results.any { it.isFailure } || loaded.any { it.stale },
        )
    }

    /**
     * Combines high-rated source catalogs; only the selected ranking is limited to Top 250.
     * [onCached] receives a filtered local snapshot before revalidation, except on explicit refresh.
     */
    suspend fun list(
        kind: String, start: Int = 0, country: String = "all", refresh: Boolean = false,
        onCached: (suspend (CatalogPage) -> Unit)? = null,
    ): CatalogPage {
        if (country !in BrowseCountries) throw ApiError("无效的地区")
        if (kind != "top250" && kind !in HomeCatalogFilters) throw ApiError("无效的榜单")
        if (kind == "top250" && country == "all") return top250Page(start, refresh, onCached)
        val filters = (HomeCatalogFilters[kind] ?: BrowseFilters(sort = "rating")).copy(country = country)
        if (kind == "top250") return browse(filters, start, HomeMinimumRating, refresh,
            onCached = onCached?.let { publish -> { page -> publish(page.highRated()) } }).highRated()
        if (start !in 0..10_000 || start % 20 != 0) throw ApiError("无效的榜单页码")
        var snapshotPublished = false
        return catalog("home:merged:v1:$kind:$country:$start", ttl = 24 * 60 * 60_000L,
            forceRefresh = refresh, freshWhen = { !it.stale },
            onCached = onCached?.let { publish -> { page -> snapshotPublished = true; publish(page) } }) {
            coroutineScope {
                val loads: List<suspend () -> CatalogPage> = listOf(
                    { homeSourceCatalog(filters, start, refresh) },
                    { browse(filters, start, HomeMinimumRating, refresh,
                        onCached = onCached?.takeIf { !refresh && start == 0 }?.let { publish ->
                            { page ->
                                if (!snapshotPublished) {
                                    snapshotPublished = true
                                    publish(page.highRated().copy(hasMore = false, stale = true))
                                }
                            }
                        }) },
                )
                val results = loads.map { load -> async {
                    try { Result.success(load()) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { Result.failure<CatalogPage>(error) }
                } }.awaitAll()
                val loaded = results.mapNotNull { it.getOrNull() }
                if (loaded.isEmpty()) throw ApiError("精选来源暂时不可用，请稍后重试")
                CatalogPage(mergeHotCatalog(loaded.flatMap { it.items }), start, 20,
                    start < 10_000 && (loaded.any { it.hasMore } || results.any { it.isFailure }),
                    label = loaded.lastOrNull()?.label.orEmpty(),
                    stale = results.any { it.isFailure } || loaded.any { it.stale }).highRated()
            }
        }.highRated()
    }

    /** Enriches source cards before regional/genre filtering without resolving media URLs. */
    private suspend fun homeSourceCatalog(filters: BrowseFilters, start: Int, refresh: Boolean): CatalogPage {
        val sourceStart = start / 20 * 24
        if (sourceStart > 9_984) return CatalogPage(emptyList(), start, 20, false)
        return catalog("home:jianpian:v1:${filters.type}:${filters.genre}:${filters.country}:$start",
            ttl = 24 * 60 * 60_000L, forceRefresh = refresh, freshWhen = { !it.stale }) {
            val page = jianpian.catalog(filters.type, sourceStart, "rating")
            val permits = Semaphore(4)
            val incomplete = MutableStateFlow(false)
            val items = coroutineScope { page.items.map { item -> async {
                permits.withPermit {
                    try {
                        val enriched = jianpianCatalogMetadata(item)
                        enriched.takeIf { JianpianSource.matchesFilters(it, filters) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        // Sparse cards cannot establish a region or genre. Let the page
                        // cache supply its prior verified snapshot instead of mixing scopes.
                        if (filters.country != "all" || filters.genre != "all") throw error
                        incomplete.value = true
                        item
                    }
                }
            } }.awaitAll().filterNotNull() }
            page.copy(items = items, start = start, pageSize = 20, stale = incomplete.value)
        }
    }

    /** Reuses release metadata without replacing a current card's score, artwork or badges. */
    private suspend fun jianpianCatalogMetadata(item: CatalogItem): CatalogItem {
        val metadata = cachedResponse<CatalogItem>(
            "catalog:jianpian:metadata:v1:${item.url}", 24 * 60 * 60_000L,
            7L * 24 * 60 * 60 * 1000,
        ) { JianpianSource.catalogMetadata(jianpian.detail(item.url), item) }
        return item.copy(year = metadata.year, countries = metadata.countries,
            genres = metadata.genres, casts = metadata.casts)
    }

    /** Restores the original hot catalog after Jianpian rows, with independent source offsets. */
    internal suspend fun hotCatalog(
        type: String, start: Int = 0, refresh: Boolean = false,
        onCached: (suspend (CatalogPage) -> Unit)? = null,
    ): CatalogPage = catalog("hot:merged:v2:$type:$start", ttl = 15 * 60_000L,
        forceRefresh = refresh, freshWhen = { !it.stale }, onCached = onCached) {
        require(start in 0..9_984 && start % 24 == 0)
        coroutineScope {
            val primary = async {
                try {
                    Result.success(catalog("hot:jianpian:v2:$type:$start:metadata-v1", ttl = 30 * 60_000L,
                        forceRefresh = refresh, freshWhen = { !it.stale }) {
                        val page = jianpian.catalog(type, start, "hot")
                        val permits = Semaphore(4)
                        val incomplete = MutableStateFlow(false)
                        val items = page.items.map { item -> async {
                            permits.withPermit {
                                try { jianpianCatalogMetadata(item) }
                                catch (error: CancellationException) { throw error }
                                catch (_: Exception) {
                                    // Optional dates must never remove a playable hot title.
                                    incomplete.value = true
                                    item
                                }
                            }
                        } }.awaitAll()
                        page.copy(items = items, stale = incomplete.value)
                    })
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { Result.failure<CatalogPage>(error) }
            }
            val original = async {
                try {
                    // Jianpian's page number advances every logical 24 slots; the original
                    // Douban endpoint advances by 20. Display filtering changes neither cursor.
                    Result.success(browse(BrowseFilters(type = type, sort = "hot"),
                        start / 24 * 20, refresh = refresh))
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { Result.failure<CatalogPage>(error) }
            }
            val first = primary.await()
            if (!refresh && start == 0) first.getOrNull()?.takeIf { it.items.isNotEmpty() }?.let {
                // Show working source rows while the older catalog revalidates. Do not allow
                // automatic pagination to cancel that still-pending supplement request.
                onCached?.invoke(it.copy(hasMore = false, stale = true))
            }
            val results = listOf(first, original.await())
            val loaded = results.mapNotNull { it.getOrNull() }
            if (loaded.isEmpty()) throw ApiError("热播来源暂时不可用，请稍后重试")
            CatalogPage(mergeHotCatalog(loaded.flatMap { it.items }), start, 24,
                start < 9_984 && (loaded.any { it.hasMore } || results.any { it.isFailure }),
                stale = loaded.any { it.stale } || results.any { it.isFailure })
        }
    }

    /** Loads regional recommendations with the homepage score floor for every content type. */
    internal suspend fun recommendationCatalog(type: String, country: String, start: Int = 0): CatalogPage =
        browse(BrowseFilters(type = type, country = country, sort = "rating"), start,
            minimumRating = HomeMinimumRating).highRated()

    private suspend fun top250Page(
        start: Int, refresh: Boolean = false, onCached: (suspend (CatalogPage) -> Unit)? = null,
    ): CatalogPage {
        if (start !in 0..225 || start % 25 != 0) throw ApiError("无效的榜单页码")
        return catalog("top250:$start", forceRefresh = refresh,
            onCached = onCached?.let { publish -> { page -> publish(page.highRated()) } }) {
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
        items = mergeHighRatedCatalog(items.filter {
            it.rating != null && it.rating in HomeMinimumRating..10.0
        }),
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

    /**
     * Loads the selected category and optionally publishes its local snapshot before networking.
     * Explicit [refresh] skips [onCached]; the returned page is the revalidated or offline result.
     */
    suspend fun browse(
        filters: BrowseFilters, start: Int = 0, refresh: Boolean = false,
        onCached: (suspend (CatalogPage) -> Unit)? = null,
    ): CatalogPage = browse(filters, start, minimumRating = 0.0, refresh = refresh, onCached = onCached)

    private suspend fun browse(
        filters: BrowseFilters, start: Int, minimumRating: Double, refresh: Boolean = false,
        onCached: (suspend (CatalogPage) -> Unit)? = null,
    ): CatalogPage {
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
        fun CatalogPage.forCategory(): CatalogPage = copy(
            // Filter before normalizing legacy row kinds. A TV source tag alone does not
            // prove a drama identity: the same pool contains events and factual programs.
            // Apply on every cache read/publication too, without changing the upstream cursor.
            items = items.filter { filters.type != "tv" || it.isTelevisionDrama() }
                .map { item ->
                    // The regional request is positive provenance for sparse upstream rows.
                    // Keep explicit production countries, including co-production metadata.
                    item.copy(kind = filters.type, countries = item.countries.ifEmpty {
                        if (filters.country == "all") emptyList()
                        else listOf(BrowseCountries.getValue(filters.country))
                    })
                },
        )
        return catalog(if (bundled && minimumRating == HomeMinimumRating) "tv:$start" else address, ttl, forceRefresh = refresh,
            onCached = onCached?.let { publish -> { page -> publish(page.forCategory()) } }) {
            val data = try { json.parseToJsonElement(fetch(address)).jsonObject["data"]?.jsonArray }
            catch (error: CancellationException) { throw error }
            catch (error: ApiError) { throw error }
            catch (error: Exception) { throw ApiError("分类页面不可用或需要站点验证", error) }
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
                    genres = (item["genres"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    countries = (item["countries"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                        .filter(String::isNotBlank).distinct(),
                )
            }
            CatalogPage(items, start, 20, data.size == 20, tags.joinToString(" · "))
        }.let { page ->
            // Legacy bundled TV rows have no kind and decode to CatalogItem's movie default.
            // The requested catalog category owns this metadata on fresh, cached and stale
            // paths alike; restore it without rewriting the seed or rebuilding unchanged lists.
            page.forCategory()
        }
    }

    private fun searchKeyword(query: String): String =
        query.trim().replace("《", "").replace("》", "")
            .replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")

    /** Presents one row per verified release, ordered by title relevance and then verified rating. */
    suspend fun search(query: String, type: String): SearchResult {
        if (type == "bilibili" || BilibiliSource.reference(query) != null) return cachedResponse(
            "search:bilibili:v2:${query.trim()}", 15L * 60 * 1000, 7L * 24 * 60 * 60 * 1000,
            onStale = { result: SearchResult -> result.copy(stale = true) },
        ) { bilibili.search(query) }
        return SearchPresentation.apply(sourceSearch(query, type), searchKeyword(query))
    }

    /**
     * Supplements search scores from verified catalog identities without delaying first results.
     * Existing scores and completed lookups survive optional lookup failures or the total budget.
     */
    internal suspend fun searchRatings(result: SearchResult, query: String): SearchResult {
        if (result.results.isEmpty() || result.results.any { it.sourceId.startsWith("bilibili:") }) return result
        val catalog = try { database?.searchCatalogSnapshot().orEmpty() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { emptyList() }
        val snapshots = (catalog + pages.values.flatMap { it.items }).distinctBy { it.url }
        val rated = MutableStateFlow(result.results.map { item ->
            val match = snapshots.filter { SearchPresentation.matchesCatalog(item, it) }.singleOrNull()
            item.copy(rating = SearchPresentation.rating(match?.rating) ?: item.rating)
        })
        val requests = Semaphore(4)
        // A public metadata outage must not leave search loading, fail playback, or launch
        // dozens of sequential timeouts. Preserve completed scores when the budget expires.
        withTimeoutOrNull(5_000) {
            coroutineScope {
                val lookupScope = this
                val suggestions = mutableMapOf<String, kotlinx.coroutines.Deferred<String?>>()
                val suggestionLock = Mutex()
                rated.value.forEachIndexed { index, item ->
                    if (item.rating == null && SearchPresentation.year(item).isNotBlank()) launch {
                        requests.withPermit {
                            val match = try {
                                cachedResponse<CatalogItem?>(
                                    "search-rating:v1:${SearchPresentation.title(item)}:${SearchPresentation.year(item)}:${SearchPresentation.kind(item)}",
                                    7L * 24 * 60 * 60 * 1000, 30L * 24 * 60 * 60 * 1000,
                                    cacheWhen = { it != null && SearchPresentation.rating(it.rating) != null &&
                                        SearchPresentation.matchesCatalog(item, it) },
                                ) {
                                    withTimeoutOrNull(1_500) {
                                        val title = SearchPresentation.title(item)
                                        val hint = snapshots.filter {
                                            ContentParsers.normalizedTitle(it.title) == title &&
                                                (it.year.isBlank() || it.year == SearchPresentation.year(item))
                                        }.singleOrNull()
                                        // Bundled categories can omit years but retain a verified
                                        // subject URL. Resolve that identity, then check its actual
                                        // year before assigning its score to this search version.
                                        val known = hint?.let {
                                            try { artworkDetail(it, it.title, SearchPresentation.year(item)) }
                                            catch (error: CancellationException) { throw error }
                                            catch (_: Exception) { null }
                                        }?.takeIf { SearchPresentation.matchesCatalog(item, it) }
                                        if (known != null) return@withTimeoutOrNull known
                                        // All versions of one title share one subject query. Empty
                                        // challenge responses never overwrite a useful saved list.
                                        val lookup = suggestionLock.withLock {
                                            suggestions.getOrPut(title) {
                                                lookupScope.async {
                                                    try {
                                                        cachedResponse<String>("search-subjects:v1:$title",
                                                            24L * 60 * 60 * 1000, 7L * 24 * 60 * 60 * 1000) {
                                                            val body = fetch(DoubanArtwork.suggest(title))
                                                            if (json.parseToJsonElement(body).jsonArray.isEmpty())
                                                                throw ApiError("评分资料暂时不可用")
                                                            body
                                                        }
                                                    } catch (error: CancellationException) { throw error }
                                                    catch (_: Exception) { null }
                                                }
                                            }
                                        }
                                        val body = lookup.await() ?: return@withTimeoutOrNull null
                                        val subject = DoubanArtwork.searchSuggestion(body, item)
                                            ?: return@withTimeoutOrNull null
                                        DoubanArtwork.detail(fetch(DoubanArtwork.detail(subject.id, subject.tv)),
                                            subject.id, if (subject.tv) "tv" else "movie")
                                            ?.takeIf { SearchPresentation.matchesCatalog(item, it) }
                                    }
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { null }
                            if (match != null && SearchPresentation.matchesCatalog(item, match)) {
                                rated.update { rows -> rows.toMutableList().also {
                                    it[index] = item.copy(rating = SearchPresentation.rating(match.rating))
                                } }
                            }
                        }
                    }
                }
            }
        }
        return SearchPresentation.apply(result.copy(results = rated.value), searchKeyword(query))
    }

    private suspend fun sourceSearch(query: String, type: String): SearchResult {
        if (type !in listOf("all", "movie", "tv")) throw ApiError("无效的影视类型")
        val keyword = searchKeyword(query)
        if (keyword.isBlank() || keyword.length > 160) throw ApiError("请输入 1–160 字的影视名称")
        val address = PomoSource.address("auto_search", "wd" to keyword)
        val activeRules = rules()
        return cachedResponse(
            // Refresh single-provider snapshots so their omissions cannot hide new sources.
            "search:online5:$type:$address:${json.encodeToString(activeRules)}",
            ttl = 15L * 60 * 1000, maxStale = 7L * 24 * 60 * 60 * 1000,
            onStale = { result: SearchResult -> result.copy(stale = true) },
            legacyKey = "search:online4:$type:$address:${json.encodeToString(activeRules)}",
            freshWhen = { !it.stale },
        ) {
            mergedSearch(listOf(
                { jianpian.search(keyword, type) },
                { PomoSource.search(fetch(address, 8_000), type) },
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

    /** Resolves a playback entry while keeping unavailable sources distinct from absent releases. */
    internal suspend fun playbackMatch(item: CatalogItem): MatchResult {
        val result = probeMatch(item)
        if (result.match.playable && result.match.pageUrl.isNotBlank()) return result.match
        throw ApiError(when (result.status) {
            ResourceStatus.Unknown -> "播放来源暂时无法确认此影片的资源，请稍后重试"
            ResourceStatus.Available -> "找到多个同名版本，暂时无法确认当前影片的播放资源"
            ResourceStatus.Missing -> "暂未找到与此影片匹配的播放资源"
        })
    }

    private suspend fun probeMatch(original: CatalogItem): ResourceMatch {
        val result = findMatch(original)
        if (result.match.playable) {
            // Confirmed release identities outlive search/detail caches. A database
            // write failure must not delay or invalidate a successfully resolved video.
            try { database?.pinPlaybackIdentity(original, result.match.pageUrl) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Playback remains usable without optional persistence. */ }
        }
        return result
    }

    private suspend fun findMatch(original: CatalogItem): ResourceMatch {
        val item = database?.playbackIdentity(original) ?: original
        if (JianpianSource.owns(item.url)) {
            val page = ContentParsers.pageUrl(item.url)
            return try {
                detail(page)
                ResourceMatch(MatchResult(true, page), ResourceStatus.Available)
            } catch (error: CancellationException) { throw error }
            catch (error: ApiError) {
                // A valid source detail with only native/P2P routes is positive evidence
                // of missing mobile resources. Transport failures remain visible/unknown.
                ResourceMatch(MatchResult(false),
                    if (error.message == "原站暂无可播放剧集") ResourceStatus.Missing else ResourceStatus.Unknown)
            }
        }
        ContentParsers.playbackPageOrNull(item.playbackPageUrl)?.let { page ->
            try {
                val entries = episodes(detail(page), page).map { it.first }
                if (entries.isNotEmpty() &&
                    !(item.isSeries == true && ContentParsers.movieOnlyEpisodes(entries)))
                    return ResourceMatch(MatchResult(true, page), ResourceStatus.Available)
            } catch (error: CancellationException) { throw error }
            catch (_: ApiError) { /* A retired pinned provider must still allow normal source search. */ }
        }
        ContentParsers.playbackPageOrNull(item.url)?.let { page ->
            // Actor rows already carry a verified detail identity. Recheck that page instead
            // of searching again, which can return an unrelated release or an empty provider.
            return try {
                detail(page)
                ResourceMatch(MatchResult(true, page), ResourceStatus.Available)
            } catch (error: CancellationException) { throw error }
            catch (_: ApiError) { ResourceMatch(MatchResult(false), ResourceStatus.Unknown) }
        }
        // Series is a release property, not the provider's TV category. Documentary and
        // animation series may live in dedicated categories excluded by a TV-only query.
        // Search all categories and retain the title/year/episode verification below.
        val type = if (item.kind in setOf("documentary", "animation")) "all" else when (item.isSeries) {
            true -> "tv"
            false -> "movie"
            null -> ContentParsers.searchTypeFor(item.kind)
        }
        // UI de-duplication cannot remove a mirror that might be the only playable resource.
        val search = sourceSearch(item.title, type)
        val first = matchCandidates(item, search.copy(
            results = search.results.filter { JianpianSource.owns(it.detailUrl) }))
        // Prefer Jianpian's direct lines only after release and episode verification.
        // An ambiguous verified release still requires a choice; Pomo remains fallback
        // for unrelated, retired or native-only Jianpian entries, without repeating search.
        if (first.status == ResourceStatus.Available || search.results.isEmpty()) return first
        val second = matchCandidates(item, search.copy(
            results = search.results.filterNot { JianpianSource.owns(it.detailUrl) }))
        return if (second.status == ResourceStatus.Missing && first.status == ResourceStatus.Unknown)
            ResourceMatch(second.match, ResourceStatus.Unknown) else second
    }

    private suspend fun matchCandidates(
        original: CatalogItem, search: SearchResult,
        onVerified: (List<String>) -> Unit = {},
        collectMirrors: Boolean = false,
    ): ResourceMatch {
        // Resolve ambiguous yearless subjects against their catalog ID, never against
        // a source title that could refer to a remake. Metadata failures retain the
        // conservative existing matcher rather than guessing a release.
        val targetTitle = ContentParsers.normalizedTitle(original.title)
        val years = search.results.filter {
            ContentParsers.normalizedTitle(it.title) == targetTitle ||
                ContentParsers.coreTitle(it.title) == targetTitle
        }.mapNotNull { it.year.takeIf(String::isNotBlank) }.distinct()
        val item = if (original.year.isBlank() && years.size > 1 &&
            DoubanArtwork.subjectId(original.url) != null) {
            try { artworkDetail(original, original.title, "") ?: original }
            catch (error: CancellationException) { throw error }
            catch (_: ApiError) { original }
        } else original
        val target = ContentParsers.normalizedTitle(item.title)
        val wantedYear = item.year
        val candidates = search.results.mapNotNull { candidate ->
            val exact = ContentParsers.normalizedTitle(candidate.title) == target
            if (!exact && ContentParsers.coreTitle(candidate.title) != target) return@mapNotNull null
            val suffixYear = ContentParsers.titleYear(candidate.title)
            val castScore = ContentParsers.castOverlapScore(item.casts, candidate.cast)
            if (wantedYear.isBlank()) {
                // Browse responses usually omit years. A suffix must not hide the only
                // release, or make an unsuffixed remake win by default. Keep both at the
                // same rank and require an unambiguous release after detail verification.
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
            if (!collectMirrors && verified.any { it.score > candidate.score }) break
            if (!collectMirrors && verified.any {
                it.score == candidate.score &&
                    ContentParsers.normalizedTitle(it.item.title) == ContentParsers.normalizedTitle(candidate.item.title) &&
                    it.item.year == candidate.item.year && it.item.cast == candidate.item.cast
            }) continue
            try {
                val detail = detail(candidate.item.detailUrl)
                // Sparse providers label both a TV series and its same-name film "已完结".
                // Inspect each line independently: several HD mirrors are still one film,
                // and must not win the rank cutoff before a numbered series is examined.
                if ((item.kind == "tv" || item.isSeries == true) && ContentParsers.movieOnlyEpisodes(
                        episodes(detail, candidate.item.detailUrl).map { it.first })) continue
                if (collectMirrors && item.isSeries == false && ContentParsers.serialEpisodes(
                        episodes(detail, candidate.item.detailUrl).map { it.first })) continue
                val knownYears = listOfNotNull(
                    ContentParsers.titleYear(candidate.item.title),
                    candidate.item.year.takeIf(String::isNotBlank),
                    metadata(detail, candidate.item.detailUrl)?.text("year")?.takeIf(String::isNotBlank),
                ).distinct()
                if (knownYears.size > 1 || wantedYear.isNotBlank() && knownYears.any { it != wantedYear }) continue
                // Availability checks only inspect episode lists. Parsing a signed media URL
                // here would multiply slow requests for every catalog row and expire before use.
                verified += candidate.copy(
                    item = candidate.item.copy(year = knownYears.singleOrNull().orEmpty()),
                    pageUrl = candidate.item.detailUrl,
                )
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
        val referenceCast = item.casts.ifEmpty {
            best.item.cast.split(Regex("[/、,，\\s]+")).filter(String::isNotBlank)
        }
        fun compatibleCast(candidate: SearchItem): Boolean =
            candidate.cast.isBlank() || referenceCast.isEmpty() ||
                ContentParsers.castOverlapScore(referenceCast, candidate.cast) > 0
        val distinct = verified.filter { it.score == best.score }.distinctBy {
            // Mirrors commonly publish different subsets of the same cast. Once the
            // target release year is known, incomplete cast strings are not new releases.
            listOf(ContentParsers.coreTitle(it.item.title), it.item.year,
                if (collectMirrors && wantedYear.isNotBlank() && it.item.year == wantedYear &&
                    compatibleCast(it.item)) "" else it.item.cast)
        }
        val match = if (distinct.size == 1)
            MatchResult(true, best.pageUrl) else MatchResult(false)
        if (match.playable) onVerified(verified.filter {
            // Background aggregation needs positive release evidence even for lower-ranked
            // mirrors. A yearless hit cannot silently attach a same-name remake.
            it.item.year.isNotBlank() && it.item.year == best.item.year && compatibleCast(it.item)
        }.map { it.pageUrl }.distinct())
        return ResourceMatch(match, ResourceStatus.Available,
            if (match.playable) best.item.thumbnailUrl else "")
    }

    /**
     * Searches verified mirrors after initial playback and appends their stable episode paths.
     * Each appended line retains its provider identity; media parsing never uses the root
     * page's provider for an episode belonging to another provider. Failures are optional.
     */
    internal suspend fun supplementPlayback(info: PlaybackInfo, catalog: CatalogItem? = null): PlaybackInfo {
        val page = info.pageUrl
        if (!JianpianSource.owns(page) && !page.startsWith("https://pomo.mom/")) return info
        val source = playbackSource(page, reuse = true)
        val data = source.metadata
        val search = sourceSearch(catalog?.title ?: info.title, "all")
        val year = catalog?.year?.takeIf(String::isNotBlank)
            ?: data?.text("year")?.takeIf(String::isNotBlank)
            ?: search.results.firstOrNull { it.detailUrl == page }?.year.orEmpty()
        if (year.isBlank()) return info
        val series = ContentParsers.serialEpisodes(source.entries.map { it.first })
        val item = catalog?.copy(year = year, isSeries = catalog.isSeries ?: series) ?: CatalogItem(
            title = info.title, url = "", year = year,
            kind = if (series) "tv" else "movie", isSeries = series,
        )
        var pages = emptyList<String>()
        matchCandidates(item, search,
            onVerified = { pages = it }, collectMirrors = true)
        for (mirror in pages.filter { it != page }) {
            val extra = try { playbackSource(mirror, reuse = true).entries.filter {
                it.first.path.substringBefore('#') == mirror
            } } catch (error: CancellationException) { throw error }
            catch (_: Exception) { continue }
            detailMutex.withLock {
                if (playbackSources[page] !== source) return@withLock
                var nextLine = maxOf(100_000, (source.entries.maxOfOrNull { it.first.line } ?: 0) + 1)
                val previous = source.entries.filter { it.first.path.substringBefore('#') == mirror }
                val lines = extra.map { it.first.line }.distinct().associateWith { originalLine ->
                    previous.firstOrNull {
                        it.first.path.substringAfter('#').substringBefore('-') == originalLine.toString()
                    }?.first?.line ?: nextLine++
                }
                source.entries = source.entries.filter { it.first.path.substringBefore('#') != mirror } +
                    extra.map { (episode, address) ->
                    episode.copy(line = lines.getValue(episode.line),
                        lineName = "${if (JianpianSource.owns(mirror)) "荐片" else "Pomo"} · ${episode.lineName}") to address
                }
            }
        }
        try {
            val mirrors = detailMutex.withLock { source.entries.filter { it.first.path.substringBefore('#') != page } }
            if (mirrors.isNotEmpty()) database?.writeResponse("playback-mirrors:v1:$page",
                json.encodeToString(mirrors), currentEpochMillis())
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Optional mirror caching must never interrupt playback. */ }
        return info.copy(episodes = detailMutex.withLock { source.entries.map { it.first } })
    }

    /** Checks the explicit embed before choosing native navigation or the browser fallback. */
    internal suspend fun sketchHasNative(page: String): Boolean =
        SketchPlayback.videoId(page, fetch(page, 10_000)) != null

    /**
     * Resolves a validated episode and its playable media address.
     *
     * @param request Explicit title, episode and rejected-source identities.
     * @param selectEpisode Optional resume selection from the freshly loaded episode list.
     * Invalid remembered entries are ignored; invalid explicit requests still fail.
     */
    suspend fun player(
        request: PlayerRequest,
        selectEpisode: ((String, List<Episode>) -> String?)? = null,
    ): PlaybackInfo {
        if (CctvSource.owns(request.page.orEmpty())) return CctvSource.resolve(request) { fetch(it, 10_000) }
        if (BilibiliSource.reference(request.page.orEmpty()) != null) return bilibili.resolve(request)
        val original = ContentParsers.pageUrl(request.page.orEmpty())
        if (SketchSource.owns(original)) return SketchPlayback { fetch(it, 10_000) }.resolve(request)
        if (Url(original).host == "huolot.com") return resolvePlayer(request, selectEpisode)
        try {
            return resolvePlayer(request, selectEpisode)
        } catch (error: CancellationException) { throw error }
        catch (failure: ApiError) {
            if (!request.allowLineFallback) throw failure
            return fallbackPlayer(request, original, failure, selectEpisode)
        }
    }

    private suspend fun fallbackPlayer(
        request: PlayerRequest, original: String, failure: ApiError,
        selectEpisode: ((String, List<Episode>) -> String?)?,
    ): PlaybackInfo {
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
                // A failed series stream must not become a same-title film when upstream
                // detail responses omit the release year and fallback starts at episode one.
                if (ContentParsers.serialEpisodes(oldEntries.map { it.first }) &&
                    ContentParsers.movieOnlyEpisodes(alternatives.map { it.first })) continue
                val next = if (oldEpisode == null) null else {
                    alternatives.firstOrNull {
                        sameEpisode(oldEpisode, it.first) ||
                            oldEntries.count { entry -> entry.first.line == oldEpisode.line } == 1 &&
                            alternatives.count { entry -> entry.first.line == it.first.line } == 1
                    }?.first ?: continue
                }
                return resolvePlayer(request.copy(page = candidate.detailUrl, episode = next?.path), selectEpisode)
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

    private suspend fun resolvePlayer(
        request: PlayerRequest, selectEpisode: ((String, List<Episode>) -> String?)? = null,
    ): PlaybackInfo {
        val original = ContentParsers.pageUrl(request.page.orEmpty())
        if (Url(original).host == "huolot.com") {
            val title = request.title?.takeIf { it.isNotBlank() } ?: throw ApiError("请重新搜索影片后播放")
            val match = resolve(CatalogItem(title, ""))
            if (!match.playable) throw ApiError("新来源暂未找到此影片，请重新搜索")
            return resolvePlayer(PlayerRequest(page = match.pageUrl, title = title), selectEpisode)
        }
        val page = original.substringBefore('#')
        val source = playbackSource(page, request.reuseSourceInfo)
        val entries = source.entries
        val explicit = request.episode ?: original.takeIf { '#' in it }
        if (explicit != null && entries.none { it.first.path == explicit })
            throw ApiError("该剧集不在当前影片的播放列表中")
        // Select history from fresh identities before parsing/probing a stream. Resolving
        // the default first stream and then the saved line adds an entire network round trip.
        val selected = selectEpisode?.invoke(page, entries.map { it.first })
            ?.takeIf { target -> entries.any { it.first.path == target } } ?: explicit
        val selectedEpisode = entries.find { it.first.path == selected }?.first
        // Movie routes often call the same feature "国语", "HD" or "正片". Only a
        // singleton on every line permits this fallback; series must match their episode
        // label and must never move from a later episode into a singleton movie route.
        val singleFeature = ContentParsers.movieOnlyEpisodes(entries.map { it.first })
        val candidates = if (selectedEpisode != null) entries.filter {
            it.first.path == selected || sameEpisode(selectedEpisode, it.first) || singleFeature
        }.sortedBy { if (it.first.path == selected) 0 else 1 }
            // Retry the first episode on alternate lines, never a later episode of the same
            // series. A failed first stream must not silently skip part of the story.
            else entries.filter { it.first.path.endsWith("-0") }
                .sortedBy { if (Url(it.second).encodedPath.endsWith(".m3u8")) 0 else 1 }
        // Automatic entry must compare this attempt's real response times, including
        // when reopening within the normal probe TTL. Never pin playback to yesterday's
        // fastest route. Probe only candidates for the requested episode, using the same
        // bounded concurrency/retry policy as background checks. Keep validated media
        // locally so choosing a winner does not parse or download its prefix twice.
        // A manual selection skips this batch and keeps its explicit route.
        val checkedMedia = mutableMapOf<String, Pair<String, String>>()
        if (!request.reuseSourceInfo || request.rejectedMedia.isNotEmpty() || request.rejectedEpisodes.isNotEmpty())
            detailMutex.withLock {
                candidates.forEach { source.media.remove(it.first.path to it.second) }
            }
        val probes = if (request.preferFastestLine && request.allowLineFallback)
            probeLineEntries(page, candidates.filter {
                it.first.path !in request.rejectedEpisodes && it.second !in request.rejectedMedia
            }, refresh = true, onResolved = { episode, media, probe ->
                checkedMedia[episode.path] = media
                candidates.firstOrNull {
                    it.first.path == episode.path && media.first !in request.rejectedMedia
                }?.let {
                    cachePlaybackMedia(page, source, episode, it.second, media, probe)
                }
            })
        else emptyMap()
        val choices = if (!request.allowLineFallback) candidates.filter { it.first.path == selected }
        else if (request.preferFastestLine) candidates.sortedWith(compareBy<Pair<Episode, String>> {
            lineHasAds(page, it.first)
        }.thenBy {
            val probe = probes[it.first.line]
            if (probe?.available == true) probe.elapsedMs?.takeIf { elapsed -> elapsed >= 0 } ?: Long.MAX_VALUE
            else Long.MAX_VALUE
        }) else if (request.reuseSourceInfo) candidates.sortedBy { lineHasAds(page, it.first) }
        else candidates
        var failure: ApiError? = null
        for ((episode, address) in choices.distinctBy { it.second }) {
            try {
                // Parser endpoints may issue a fresh signed URL for the same broken route.
                // Reject its stable episode identity before parsing, not only the last URL.
                if (episode.path in request.rejectedEpisodes) continue
                if (address in request.rejectedMedia) continue
                if (probes[episode.line]?.available == false) continue
                val probeStarted = currentEpochMillis()
                var probeElapsed: Long? = null
                val cached = if (request.reuseSourceInfo) detailMutex.withLock {
                    source.media[episode.path to address]?.takeIf {
                        playbackCacheNowMs() - it.at in 0 until 5 * 60_000L &&
                            it.media.first !in request.rejectedMedia
                    }
                } else null
                val media = cached?.media ?: checkedMedia[episode.path] ?: if (JianpianSource.owns(episode.path)) {
                    val kind = JianpianSource.mediaKind(address) ?: throw ApiError("此线路无法直接播放")
                    probeElapsed = probeMedia(address, kind)
                    address to kind
                } else {
                    // A direct HLS/MP4 route already contains its media address. Validate
                    // that endpoint before asking a parser to wrap it again: slow or broken
                    // parsers otherwise add up to five seconds to a healthy direct route.
                    // Keep parsing as recovery for expired, indirect or rejected addresses.
                    val directKind = JianpianSource.mediaKind(address)
                    try {
                        if (directKind == null) throw ApiError("此线路需要解析")
                        // Bound this speculative shortcut: a dead original endpoint must
                        // not delay a parser that can supply a fresh working address.
                        probeElapsed = withTimeoutOrNull(1_000) { probeMedia(address, directKind) }
                            ?: throw ApiError("直接线路响应较慢，尝试解析")
                        address to directKind
                    } catch (error: CancellationException) { throw error }
                    catch (_: ApiError) {
                        PomoSource.media(
                            fetch(PomoSource.address("parse", "url" to address), 5_000),
                        ).also { probeElapsed = probeMedia(it.first, it.second) }
                    }
                }
                if (media.first in request.rejectedMedia) continue
                val measured = cached?.probe ?: probes[episode.line]?.takeIf { it.available == true }
                    ?: LineProbe((currentEpochMillis() - probeStarted).coerceAtLeast(probeElapsed ?: 0), true)
                saveLineProbe(page, episode, address, measured)
                // Reusing a URL must not extend its lifetime indefinitely across repeated taps.
                if (cached == null) cachePlaybackMedia(page, source, episode, address, media, measured)
                val data = source.metadata
                return PlaybackInfo(request.title ?: data?.text(if (JianpianSource.owns(page)) "title" else "name")
                    .orEmpty().ifBlank { "影视播放" },
                    media.first, media.second, entries.map { it.first }, episode.path, page,
                    if (JianpianSource.owns(page)) "荐片" else "Pomo · ${Url(page).parameters["site"]}",
                    data?.text(if (JianpianSource.owns(page)) "description" else "content").orEmpty(),
                    // Only 荐片's detail payload carries the area label; skipping falls back
                    // to the 90-second reference when the field is absent.
                    data?.text("area").orEmpty().trim().take(40),
                    lineProbes = cachedLineProbes(page, entries, episode) + probes + (episode.line to measured),
                    resumeFromEpisode = selectedEpisode?.path?.takeIf {
                        (request.preferFastestLine || request.reuseSourceInfo) && it != episode.path
                    })
            } catch (error: CancellationException) { throw error }
            catch (error: ApiError) { failure = error }
        }
        throw failure ?: ApiError("原站暂无可播放剧集")
    }

    /**
     * Probes alternate direct resources after playback resolution, never on its critical path.
     * Prefer the same episode, then manual line selection's index/first-episode fallback.
     * A successful prefix is not a first-frame claim.
     */
    internal suspend fun probeLines(
        info: PlaybackInfo, onMeasured: suspend (Map<Int, LineProbe>) -> Unit = {},
    ): Map<Int, LineProbe> {
        val selected = info.episodes.firstOrNull { it.path == info.selected } ?: return emptyMap()
        val source = playbackSource(info.pageUrl, reuse = true)
        val entries = source.entries
        val results = cachedLineProbes(info.pageUrl, entries, selected).toMutableMap()
        // Unknown results from a previous attempt must not block pending-line retries.
        results.putAll(info.lineProbes.filterValues { it.available != null })
        return probeLineEntries(info.pageUrl, compatibleLineEntries(entries, selected),
            initialProbes = results, onMeasured = onMeasured, onResolved = { episode, media, probe ->
                entries.firstOrNull { it.first.path == episode.path }?.let { entry ->
                    cachePlaybackMedia(info.pageUrl, source, episode, entry.second, media,
                        probe)
                }
            })
    }

    /** Shares bounded route checks between fresh automatic entry and pending background probes. */
    private suspend fun probeLineEntries(
        page: String, targets: List<Pair<Episode, String>>,
        initialProbes: Map<Int, LineProbe> = emptyMap(), refresh: Boolean = false,
        onMeasured: suspend (Map<Int, LineProbe>) -> Unit = {},
        onResolved: suspend (Episode, Pair<String, String>, LineProbe) -> Unit = { _, _, _ -> },
    ): Map<Int, LineProbe> {
        val results = initialProbes.toMutableMap()
        val knownLines = results.keys.toSet()
        val lock = Mutex()
        val permits = Semaphore(3)
        // Each line owns its budget. A shared deadline used to cancel queued lines and
        // leave them pending forever, especially when a provider exposed many mirrors.
        coroutineScope {
            targets.map { entry ->
                async {
                    val line = entry.first.line
                    if (line in knownLines) return@async
                    var resolvedMedia: Pair<String, String>? = null
                    val result = permits.withPermit {
                        repeat(2) { attempt ->
                            if (attempt > 0) delay(500)
                            val started = currentEpochMillis()
                            val measured = try {
                                withTimeoutOrNull(10_000) {
                                    val kind = JianpianSource.mediaKind(entry.second)
                                    val elapsed = if (JianpianSource.owns(entry.first.path)) {
                                        if (kind == null) throw MediaUnavailable()
                                        probeMedia(entry.second, kind, refresh).also {
                                            resolvedMedia = entry.second to kind
                                        }
                                    } else {
                                        try {
                                            if (kind == null) throw ApiError("此线路需要解析")
                                            (withTimeoutOrNull(1_000) { probeMedia(entry.second, kind, refresh) }
                                                ?: throw ApiError("直接线路响应较慢，尝试解析")).also {
                                                resolvedMedia = entry.second to kind
                                            }
                                        } catch (error: CancellationException) { throw error }
                                        catch (_: ApiError) {
                                            val body = fetch(PomoSource.address("parse", "url" to entry.second), 5_000)
                                            val media = try { PomoSource.media(body) }
                                            catch (_: ApiError) { throw MediaUnavailable() }
                                            probeMedia(media.first, media.second, refresh).also {
                                                resolvedMedia = media
                                            }
                                        }
                                    }
                                    LineProbe((currentEpochMillis() - started).coerceAtLeast(elapsed), true)
                                } ?: LineProbe()
                            } catch (error: CancellationException) { throw error }
                            catch (_: MediaUnavailable) { LineProbe(available = false) }
                            catch (_: Exception) { LineProbe() }
                            if (measured.available != null) return@withPermit measured
                        }
                        // After a bounded retry, a route that cannot respond is unusable
                        // for this session. The short failure TTL allows later recovery.
                        LineProbe(available = false)
                    }
                    lock.withLock {
                        results[line] = result
                        if (result.available == true) resolvedMedia?.let { onResolved(entry.first, it, result) }
                        onMeasured(mapOf(line to result))
                    }
                    saveLineProbe(page, entry.first, entry.second, result)
                }
            }.awaitAll()
        }
        return results.toMap()
    }

    /**
     * Reuses SQLite's bounded response store for per-episode measurements. Media fingerprints
     * invalidate rotated addresses without storing signed URLs; failures expire sooner so an
     * upstream repair is promptly retried. Unknown transport results are never persisted.
     */
    internal fun lineProbeKey(page: String, episode: Episode, address: String): String =
        "line-probe:v1:${page.substringBefore('#')}:${episode.path}:${episode.lineName}:${address.encodeUtf8().sha256().hex()}"

    private suspend fun saveLineProbe(page: String, episode: Episode, address: String, probe: LineProbe) {
        if (probe.available == null) return
        try {
            database?.writeResponse(lineProbeKey(page, episode, address), json.encodeToString(probe), currentEpochMillis())
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Optional persistence must not fail an otherwise playable route. */ }
    }

    private suspend fun cachedLineProbes(
        page: String, entries: List<Pair<Episode, String>>, selected: Episode,
    ): Map<Int, LineProbe> = buildMap {
        for (entry in compatibleLineEntries(entries, selected)) {
            val stored = try { database?.readResponse(lineProbeKey(page, entry.first, entry.second)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null } ?: continue
            val probe = runCatching { json.decodeFromString<LineProbe>(stored.payload) }.getOrNull() ?: continue
            val ttl = if (probe.available == true) 2 * 60_000L else 30_000L
            if (probe.available != null && currentEpochMillis() - stored.savedAt in 0 until ttl) put(entry.first.line, probe)
        }
    }

    private fun compatibleLineEntries(entries: List<Pair<Episode, String>>, selected: Episode): List<Pair<Episode, String>> {
        val index = entries.filter { it.first.line == selected.line }.indexOfFirst { it.first.path == selected.path }
        return entries.groupBy { it.first.line }.values.mapNotNull { choices ->
            choices.firstOrNull { sameEpisode(selected, it.first) }
                ?: choices.singleOrNull()?.takeIf {
                    ContentParsers.movieOnlyEpisodes(listOf(selected, it.first))
                }
                // Sparse/differently labelled lines remain selectable through the same
                // fallback as selectLine; they must receive a measurement instead of
                // staying pending forever. Cache by the actual probed episode identity.
                ?: choices.getOrNull(index) ?: choices.firstOrNull()
        }
    }

    private val mediaProbeMutex = Mutex()
    private val mediaProbes = linkedMapOf<String, Pair<Long, Long>>()

    /** Reads a bounded prefix; short successful probes also prevent repeated route checks. */
    private suspend fun probeMedia(address: String, kind: String, refresh: Boolean = false): Long {
        val key = "$kind:$address"
        val started = currentEpochMillis()
        mediaProbeMutex.withLock { mediaProbes[key].takeUnless { refresh } }?.let { (at, elapsed) ->
            if (started - at in 0 until 2 * 60_000L) return elapsed
        }
        val available = try { withTimeoutOrNull(5_000) {
            var target = address
            repeat(5) {
                // get() saves the full response before returning it. A server ignoring Range
                // would download the video until timeout, despite our bounded read below.
                // execute keeps the response streaming and cancels transport at the prefix.
                val checked: Boolean? = client.prepareGet(target) {
                    header(HttpHeaders.Range, "bytes=0-8191")
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    try {
                        if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                            val location = response.headers[HttpHeaders.Location] ?: return@execute false
                            target = URLBuilder(target).apply { takeFrom(location) }.buildString()
                            if (!isWebUrl(target)) return@execute false
                            null
                        } else {
                            if (response.status.value >= 500 || response.status.value in listOf(408, 429))
                                throw ApiError("线路检查暂时失败")
                            if (!response.status.isSuccess()) return@execute false
                            val bytes = ByteArray(8192)
                            var count = 0
                            while (count < bytes.size) {
                                val read = channel.readAvailable(bytes, count, bytes.size - count)
                                if (read == -1) break
                                count += read
                                // Both supported signatures live at the beginning. Once
                                // confirmed, there is no reason to wait for all 8KB either.
                                if (kind == "hls" && bytes.decodeToString(endIndex = count)
                                    .trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U"))
                                    return@execute true
                                if (kind != "hls" && count >= 12) break
                            }
                            if (kind == "hls")
                                bytes.decodeToString(endIndex = count).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                                    .startsWith("#EXTM3U")
                            else count >= 12 && bytes.decodeToString(startIndex = 4, endIndex = 8) == "ftyp"
                        }
                    } finally { channel.cancel(null) }
                }
                if (checked != null) return@withTimeoutOrNull checked
            }
            false
        } ?: throw ApiError("线路检查超时") }
        catch (error: CancellationException) { throw error }
        catch (error: ApiError) { throw error }
        catch (error: Exception) { throw ApiError("线路检查暂时失败", error) }
        if (!available) throw MediaUnavailable()
        val elapsed = (currentEpochMillis() - started).coerceAtLeast(0)
        mediaProbeMutex.withLock {
            mediaProbes[key] = currentEpochMillis() to elapsed
            if (mediaProbes.size > 64) mediaProbes.remove(mediaProbes.keys.first())
        }
        return elapsed
    }

    fun close() { pages.clear(); client.close() }
}

/** A search candidate kept by [ContentRepository.resolve], ordered from strongest to weakest year evidence. */
private data class RankedCandidate(val item: SearchItem, val score: Int, val pageUrl: String = "")
private data class ResourceMatch(val match: MatchResult, val status: ResourceStatus, val poster: String = "")

internal const val OnlineOrigin = "https://pomo.mom"
internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Keeps the caller's identity and only fills fields the metadata source actually states. */
private fun CatalogItem.mergeArtwork(details: CatalogItem): CatalogItem = details.copy(
    url = url, title = title.ifBlank { details.title }, year = year.ifBlank { details.year },
    rating = rating ?: details.rating, cover = cover.ifBlank { details.cover },
    // Catalog rails may carry only a few lead names. The detail source owns the complete,
    // ordered list; a nonempty card preview must not keep suppressing later cast members.
    casts = details.casts.ifEmpty { casts }, kind = kind.ifBlank { details.kind },
    countries = details.countries.ifEmpty { countries },
    genres = details.genres.ifEmpty { genres },
)

/** Reads Douban's public subject JSON, the app's existing source for scores and subject pages. */
internal object DoubanArtwork {
    private val json = Json { ignoreUnknownKeys = true }
    private val subject = Regex("^https?://movie\\.douban\\.com/subject/([0-9]{1,12})/?(?:\\?.*)?$")

    fun subjectId(url: String): String? = subject.find(url)?.groupValues?.get(1)

    fun suggest(title: String): String =
        "https://movie.douban.com/j/subject_suggest?q=${title.encodeURLParameter()}"

    fun detail(id: String, tv: Boolean): String =
        "https://m.douban.com/rexxar/api/v2/${if (tv) "tv" else "movie"}/$id"

    data class Suggestion(val id: String, val tv: Boolean)

    /** Search aliases require a stated release year and one unambiguous subject identity. */
    fun searchSuggestion(body: String, item: SearchItem): Suggestion? = runCatching {
        val year = SearchPresentation.year(item)
        if (year.isBlank()) return@runCatching null
        json.parseToJsonElement(body).jsonArray.map { it.jsonObject }.filter { row ->
            ContentParsers.normalizedTitle(row.text("title")) == SearchPresentation.title(item) &&
                row.text("year") == year && row.text("type") == "movie" &&
                row.text("id").matches(Regex("[0-9]{1,12}")) && when (SearchPresentation.kind(item)) {
                    "tv", "variety" -> row.text("episode").isNotBlank()
                    "movie" -> row.text("episode").isBlank()
                    else -> true
                }
        }.distinctBy { it.text("id") }.singleOrNull()?.let {
            Suggestion(it.text("id"), it.text("episode").isNotBlank())
        }
    }.getOrNull()

    /** Accepts one exact title with consistent year and episode shape, never a same-name remake. */
    fun suggestion(body: String, title: String, year: String, kind: String): Suggestion? = runCatching {
        json.parseToJsonElement(body).jsonArray.map { it.jsonObject }.filter { row ->
            val episodic = row.text("episode").isNotBlank()
            row.text("title") == title.trim() && row.text("type") == "movie" &&
                (year.isBlank() || row.text("year") == year) &&
                row.text("id").matches(Regex("[0-9]{1,12}")) && when (kind) {
                    "tv", "variety" -> episodic
                    "movie" -> !episodic
                    else -> true
                }
        }.singleOrNull()?.let { Suggestion(it.text("id"), it.text("episode").isNotBlank()) }
    }.getOrNull()

    /** Maps only fields the response states; absent values stay empty rather than invented. */
    fun detail(body: String, id: String, fallbackKind: String): CatalogItem? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val title = root.text("title").trim()
        if (title.isBlank()) return@runCatching null
        CatalogItem(
            title, "https://movie.douban.com/subject/$id/", root.text("year"),
            (root["rating"] as? JsonObject)?.text("value")?.toDoubleOrNull()
                ?.takeIf { it > 0 && it <= 10 },
            root.text("cover_url").takeIf(::isWebUrl).orEmpty(),
            (root["actors"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.text("name")?.trim()?.takeIf(String::isNotBlank)
            }.distinct(),
            if (root["is_tv"]?.jsonPrimitive?.booleanOrNull == true) "tv" else fallbackKind.ifBlank { "movie" },
            root.text("intro").trim().take(2_000),
            genres = (root["genres"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            },
            countries = (root["countries"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            }.distinct(),
            isSeries = root["is_tv"]?.jsonPrimitive?.booleanOrNull,
        )
    }.getOrNull()
}

/** Reads bounded upstream markup and JSON without executing JavaScript. */
internal object ContentParsers {
    private val json = Json { ignoreUnknownKeys = true }
    private val playPath = Regex("/vodplay/(\\d+)-(\\d+)-(\\d+)\\.html")
    /** Accepts supported detail identities only; official actor pages and media URLs are excluded. */
    fun playbackPageOrNull(value: String): String? =
        try { pageUrl(value) } catch (_: Exception) { null }
    fun subjectUrl(value: String): String = try {
        val url = Url(value)
        if (isWebUrl(value) && url.host == "movie.douban.com" && Regex("/subject/\\d+/?").matches(url.encodedPath))
            "https://movie.douban.com${url.encodedPath.trimEnd('/')}/" else ""
    } catch (_: Exception) { "" }

    fun pageUrl(value: String): String {
        BilibiliSource.reference(value)?.let { return it.page + if (it.part > 1) "?p=${it.part}" else "" }
        val url = try { Url(value) } catch (_: Exception) { throw ApiError("无效的播放页面") }
        if (SketchSource.owns(value)) return value
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
        "movie" -> "movie"
        // These catalog genres contain both films and episodic shows. Restricting them
        // to the movie API category drops resources that an ordinary search can play.
        else -> "all"
    }
    /** Detects episodic evidence without counting duplicate provider lines as separate episodes. */
    fun serialEpisodes(episodes: List<Episode>): Boolean =
        episodes.groupBy { it.line }.values.any { it.size > 1 } ||
            episodes.any { Regex("(?:第\\s*)?\\d{1,4}\\s*集").containsMatchIn(it.label) }

    /**
     * Recognizes single-film quality labels only when every line has one non-episodic entry.
     * Missing or generic labels remain unknown; an incomplete series must not be discarded.
     */
    fun movieOnlyEpisodes(episodes: List<Episode>): Boolean =
        episodes.isNotEmpty() && !serialEpisodes(episodes) && episodes.all {
            Regex("HD|BD|正片|高清|超清|蓝光|中字|国语|粤语|电影", RegexOption.IGNORE_CASE)
                .containsMatchIn(it.label)
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
        val info = children.firstOrNull { it.tag == "p" }?.text().orEmpty()
        val year = Regex("\\b(?:18|19|20)\\d{2}").find(info)
        // Some ranking cards list multiple release years, each followed by a country in
        // parentheses. Skip all release segments before reading production regions/genres.
        val metadata = year?.let { info.substring(it.range.first).split(Regex("\\s*/\\s*")) }
            .orEmpty().dropWhile { Regex("^(?:18|19|20)\\d{2}").containsMatchIn(it) }
        val countries = metadata.getOrNull(0)?.split(Regex("\\s+"))?.filter(String::isNotBlank).orEmpty()
        val genres = metadata.getOrNull(1)?.split(Regex("\\s+"))?.filter(String::isNotBlank).orEmpty()
        if (title.isBlank() || url.isBlank() || rating == null) null else CatalogItem(
            title, url, year?.value.orEmpty(),
            rating, image?.attr("src")?.takeIf(::isWebUrl).orEmpty(),
            kind = when { "动画" in genres -> "animation"; "纪录片" in genres -> "documentary"; else -> "movie" },
            genres = genres, countries = countries,
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
