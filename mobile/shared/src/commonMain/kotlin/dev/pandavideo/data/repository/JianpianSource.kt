package dev.pandavideo.data.repository

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.BrowseCountries
import dev.pandavideo.core.model.HomeMinimumRating
import dev.pandavideo.core.model.isTelevisionDrama
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.SearchResult
import dev.pandavideo.core.model.SourceCount
import dev.pandavideo.core.platform.currentEpochMillis
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8

/** Keeps saved references independent of the mirror currently serving the public API. */
internal object JianpianSource {
    const val origin = "https://japi.zxfmj.com"
    val initialOrigins = listOf(origin, "https://pv2026.zxfmj.com")
    private val json = Json { ignoreUnknownKeys = true }

    fun address(id: String): String = "$origin/api/video/detailv2?id=$id"

    fun owns(value: String): Boolean = try { Url(value).host == Url(origin).host } catch (_: Exception) { false }

    fun page(value: String): String {
        val url = try { Url(value) } catch (_: Exception) { throw ApiError("无效的播放页面") }
        if (!isWebUrl(value) || url.protocol != URLProtocol.HTTPS || url.host != Url(origin).host ||
            url.port != 443 || url.encodedPath != "/api/video/detailv2" ||
            url.parameters.names() != setOf("id") || url.parameters.getAll("id")?.size != 1 ||
            !url.parameters["id"].orEmpty().matches(Regex("[1-9]\\d{0,11}")) ||
            url.fragment.isNotEmpty() && !url.fragment.matches(Regex("[1-9]\\d{0,3}-\\d{1,4}")))
            throw ApiError("无效的播放页面")
        return address(url.parameters["id"]!!) + url.fragment.takeIf(String::isNotEmpty)?.let { "#$it" }.orEmpty()
    }

    fun data(body: String): JsonElement {
        val root = try { json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
            ?: throw ApiError("荐片来源返回格式异常")
        if (root.text("code") != "1") throw ApiError("荐片来源暂时不可用")
        return root["data"] ?: throw ApiError("荐片来源返回格式异常")
    }

    private fun JsonObject.names(key: String, field: String = "name"): String =
        (this[key] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text(field)
        }.joinToString("、")

    fun search(body: String, type: String, imageOrigin: String?): SearchResult {
        val rows = data(body) as? JsonArray ?: throw ApiError("荐片搜索结果格式异常")
        val items = rows.take(80).mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val title = row.text("title").trim().take(160)
            val id = row.text("id")
            val category = (row["top_category"] as? JsonObject)?.text("id").orEmpty()
            if (title.isBlank() || !id.matches(Regex("[1-9]\\d{0,11}")) ||
                Regex("解说|预告|花絮|片段").containsMatchIn(title + row.text("mask")) ||
                type == "movie" && category in setOf("2", "67", "4") ||
                type == "tv" && category in setOf("1", "50")) return@mapNotNull null
            val cover = row.text("thumbnail").ifBlank { row.text("tvimg") }
            SearchItem(title, "荐片", "jianpian:$id", true, address(id),
                posterUrl(cover, imageOrigin),
                row.names("years", "year"), row.names("areas", "area"),
                row.names("types"), row.names("actors"), row.text("mask").take(80),
                rating = SearchPresentation.rating(row.text("douban_score").toDoubleOrNull()))
        }.distinctBy { it.detailUrl }
        return SearchResult(items, listOf(SourceCount("荐片", items.size, "可在线播放")))
    }

    fun catalog(
        body: String, kind: String, imageOrigin: String?, minimumRating: Double? = HomeMinimumRating,
    ): List<CatalogItem> {
        val rows = data(body) as? JsonArray ?: throw ApiError("荐片分类结果格式异常")
        return rows.take(100).mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val id = row.text("id")
            val title = row.text("title").trim().take(160)
            val score = row.text("score").toDoubleOrNull()?.takeIf { it in 0.1..10.0 }
            if (!id.matches(Regex("[1-9]\\d{0,11}")) || title.isBlank() ||
                minimumRating != null && (score == null || score < minimumRating) ||
                Regex("解说|预告|花絮|片段").containsMatchIn(title + row.text("mask")))
                return@mapNotNull null
            CatalogItem(title, address(id), rating = score,
                cover = posterUrl(row.text("path").ifBlank { row.text("tvimg") }, imageOrigin),
                kind = kind, playbackPageUrl = address(id),
                isSeries = if (kind == "tv" || kind == "variety") true else if (kind == "movie") false else null,
                remark = row.text("mask").take(80))
        }.distinctBy { it.url }.filter { kind != "tv" || it.isTelevisionDrama() }
    }

    private fun posterUrl(cover: String, imageOrigin: String?): String {
        if (isWebUrl(cover)) {
            if (imageOrigin == null) return cover
            // The provider rotates image hosts independently of API mirrors, including
            // hosts embedded in absolute thumbnails. Preserve the original path/query.
            return URLBuilder(cover).apply {
                protocol = URLProtocol.HTTPS
                host = Url(imageOrigin).host
                port = 443
            }.buildString()
        }
        return if (imageOrigin != null && cover.startsWith("/") && !cover.startsWith("//"))
            imageOrigin + cover else ""
    }

    /** Adds release metadata omitted by crumb/list without changing the source identity. */
    fun catalogMetadata(body: String, item: CatalogItem): CatalogItem {
        val detail = data(body) as? JsonObject ?: throw ApiError("荐片详情格式异常")
        fun names(key: String) = (detail[key] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.text("name") ?: (it as? JsonPrimitive)?.contentOrNull
        }.filter(String::isNotBlank)
        return item.copy(
            year = detail.text("year"),
            countries = detail.text("area").split(Regex("[、,，/\\s]+")).filter(String::isNotBlank),
            genres = (names("types") + names("tags")).distinct(),
            casts = names("actors"),
        )
    }

    /** Requires explicit source metadata for every selected scope. */
    fun matchesFilters(item: CatalogItem, filters: BrowseFilters): Boolean {
        if (filters.type == "tv" && !item.isTelevisionDrama()) return false
        fun region(value: String) = when (value) {
            "中国", "大陆", "内地" -> "中国大陆"
            "香港" -> "中国香港"
            "台湾" -> "中国台湾"
            else -> value
        }
        if (filters.country != "all" &&
            BrowseCountries[filters.country] !in item.countries.map(::region)) return false
        val genre = mapOf("drama" to "剧情", "comedy" to "喜剧", "action" to "动作",
            "romance" to "爱情", "scifi" to "科幻", "mystery" to "悬疑", "finance" to "金融",
            "nature" to "自然", "biography" to "传记", "war" to "战争")
        if (filters.genre == "hollywood")
            return "大片" in item.genres && (filters.country != "all" || "美国" in item.countries)
        return filters.genre == "all" || genre[filters.genre] in item.genres
    }

    fun episodes(body: String, page: String): List<Pair<Episode, String>> {
        val detail = data(body) as? JsonObject ?: throw ApiError("荐片详情格式异常")
        val routes = (detail["source_list_source"] as? JsonArray).orEmpty().take(100)
        val hasSeries = routes.any { route ->
            ((route as? JsonObject)?.get("source_list") as? JsonArray).orEmpty().any {
                Regex("(?:第\\s*)?\\d{1,4}\\s*集")
                    .containsMatchIn((it as? JsonObject)?.text("source_name").orEmpty())
            }
        }
        // Native FTP/P2P resources are intentionally excluded: mobile has no JPEngine2.
        // Line/index keys preserve upstream order without persisting expiring stream URLs.
        return routes.flatMapIndexed { line, entry ->
            // Provider aggregation can mix the 1986 series with a single animated film,
            // or the 1965 cartoon with the 2021 live-action film. Exclude explicit film
            // lines only when this detail also contains positive numbered-series evidence.
            val route = entry as? JsonObject
            val rows = (route?.get("source_list") as? JsonArray).orEmpty()
            val lineName = route?.text("name").orEmpty()
                .ifBlank { route?.text("source_name").orEmpty() }.take(80)
            if (hasSeries && rows.size == 1 &&
                ContentParsers.movieOnlyEpisodes(listOf(Episode(
                    "", (rows.single() as? JsonObject)?.text("source_name").orEmpty(), line + 1,
                )))) return@flatMapIndexed emptyList()
            rows.take(1000)
                .mapIndexedNotNull { index, value ->
                    val row = value as? JsonObject ?: return@mapIndexedNotNull null
                    val media = row.text("url").takeIf { mediaKind(it) != null } ?: return@mapIndexedNotNull null
                    Episode("$page#${line + 1}-$index",
                        row.text("source_name").take(80).ifBlank { "播放" }, line + 1, lineName) to media
                }
        }
    }

    fun mediaKind(value: String): String? {
        if (!isWebUrl(value)) return null
        return when {
            Url(value).encodedPath.lowercase().endsWith(".m3u8") -> "hls"
            Url(value).encodedPath.lowercase().let { it.endsWith(".mp4") || it.endsWith(".m4v") } -> "video"
            else -> null
        }
    }
}

/**
 * Probes compatible HTTPS mirrors, ranks successful responses, and retries read-only API calls.
 * Secrets are scoped to one origin and remain in memory; changing a mirror never changes saved IDs.
 */
internal class JianpianClient(
    private val fetch: suspend (String, Long, Map<String, String>) -> String,
    private val clock: () -> Long = ::currentEpochMillis,
) {
    private data class Server(val origin: String, val secret: String, var latency: Long)
    private val mutex = Mutex()
    private val imageMutex = Mutex()
    private var servers = emptyList<Server>()
    private var checkedAt: Long? = null
    private var discoveredAt: Long? = null
    private var origins = JianpianSource.initialOrigins
    private var imageOrigin: String? = null
    private var imageCheckedAt: Long? = null
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(keyword: String, type: String): SearchResult {
        val parameters = Parameters.build {
            append("key", keyword)
            append("page", "1")
            if (type != "all") append("category_id", if (type == "tv") "2" else "1")
        }
        val path = "/v2/search/videoV2?" + parameters.formUrlEncode()
        val body = request(path)
        val hasPosters = (JianpianSource.data(body) as JsonArray).any {
            val row = it as? JsonObject
            row != null && (row.text("thumbnail").isNotBlank() || row.text("tvimg").isNotBlank())
        }
        return JianpianSource.search(body, type, if (hasPosters) loadImageOrigin() else imageOrigin)
    }

    suspend fun detail(page: String): String =
        request("/video/detailv2?id=${Url(JianpianSource.page(page)).parameters["id"]}")

    suspend fun catalog(kind: String, start: Int, sort: String): CatalogPage {
        val category = when (kind) {
            "movie" -> 1; "tv" -> 2; "animation" -> 3; "variety" -> 4; "documentary" -> 50
            else -> throw ApiError("无效的分类")
        }
        require(start in 0..9_984 && start % 24 == 0 && sort in listOf("rating", "update", "hot"))
        val body = request("/crumb/list?area=0&category_id=$category&page=${start / 24 + 1}" +
            "&type=0&limit=24&sort=$sort&year=0")
        val rows = JianpianSource.data(body) as? JsonArray ?: throw ApiError("荐片分类结果格式异常")
        // The live service caps pages at 15 despite limit=24 and supplies no total.
        // Advance until a raw empty page; score/genre filtering must not end pagination.
        return CatalogPage(JianpianSource.catalog(body, kind, loadImageOrigin(),
            minimumRating = if (sort == "hot") null else HomeMinimumRating), start, 24,
            rows.isNotEmpty() && start < 9_984)
    }

    private fun signedHeaders(server: Server): Map<String, String> {
        val timestamp = (clock() / 1000).toString()
        return mapOf(
            "platform" to "pc", "version" to "504", "timestamp" to timestamp,
            "signature" to ("504" + timestamp + server.secret).encodeUtf8().md5().hex(),
        )
    }

    /** Image configuration failures must not discard useful search results or API mirrors. */
    private suspend fun loadImageOrigin(): String? = imageMutex.withLock {
        if (imageCheckedAt?.let { clock() - it in 0 until 10 * 60_000L } == true)
            return@withLock imageOrigin
        val resolved = withTimeoutOrNull(3_000) {
            for (server in initialize()) {
                try {
                    val body = fetch("${server.origin}/api/v2/settings/resourceDomainConfig",
                        3_000, signedHeaders(server))
                    val data = JianpianSource.data(body) as? JsonObject ?: continue
                    // Accept only hostnames supplied by the resource configuration. Paths,
                    // credentials and ports must not become part of an image origin.
                    val domain = data.text("imgDomain").split(',').map(String::trim).firstOrNull {
                        it.length <= 253 && it.matches(Regex(
                            "(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}",
                        ))
                    }
                    if (domain != null) return@withTimeoutOrNull "https://$domain"
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Keep the current domain and retry on the next search. */ }
            }
            null
        }
        if (resolved != null) {
            imageOrigin = resolved
            imageCheckedAt = clock()
        }
        imageOrigin
    }

    private suspend fun initialize(force: Boolean = false): List<Server> = mutex.withLock {
        if (!force && checkedAt?.let { clock() - it in 0 until 10 * 60_000L } == true)
            return@withLock servers
        suspend fun probe(): List<Server> = coroutineScope {
            origins.take(12).map { origin ->
                async {
                    try {
                        val started = clock()
                        val body = fetch("$origin/api/v2/sys/init", 3_000, mapOf("platform" to "pc"))
                        val data = JianpianSource.data(body) as? JsonObject
                        val secret = data?.text("secret").orEmpty()
                        if (secret.isBlank() || secret.length > 1024) null
                        else Server(origin, secret, (clock() - started).coerceAtLeast(0))
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null }
                }
            }.awaitAll().filterNotNull().sortedBy { it.latency }
        }
        var available = probe()
        if (available.isEmpty() && discoveredAt?.let { clock() - it in 0 until 10 * 60_000L } != true) {
            discover()
            available = probe()
        }
        servers = available
        checkedAt = clock()
        available
    }

    private suspend fun discover() {
        // Discovery sources are metadata, not interchangeable search or playback endpoints.
        // Only DNS hostnames on HTTPS/443 are admitted; no guessed ports or returned IP URLs.
        val domains = coroutineScope {
            listOf(
                "https://jdomain.oss-accelerate.aliyuncs.com/domain.txt",
                "https://dns.alidns.com/resolve?name=pyvemnlu.cc&type=16",
                "https://doh.pub/dns-query?name=pyvemnlu.cc&type=16",
                "https://1.1.1.1/dns-query?name=pyvemnlu.cc&type=16",
            ).mapIndexed { index, url ->
                async {
                    try {
                        val body = fetch(url, 3_000, mapOf(HttpHeaders.Accept to "application/dns-json"))
                        if (body.length > 65_536) emptyList()
                        else if (index == 0) body.split(',')
                        else {
                            val root = json.parseToJsonElement(body) as? JsonObject
                            (root?.get("Answer") as? JsonArray).orEmpty().flatMap {
                                (it as? JsonObject)?.text("data").orEmpty().trim('"').split(',')
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        }
        val valid = domains.map(String::trim).filter {
            it.length <= 253 && it.matches(Regex("(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}"))
        }.distinct().take(10)
        origins = (JianpianSource.initialOrigins + valid.map { "https://pv2026.$it" }).distinct()
        discoveredAt = clock()
    }

    private suspend fun request(path: String): String = withTimeoutOrNull(15_000) {
        var failure: Exception? = null
        val attempted = mutableSetOf<String>()
        repeat(2) { round ->
            val candidates = initialize(force = round > 0).sortedBy { it.latency }
            for (server in candidates.filter { attempted.add(it.origin) }) {
                try {
                    val started = clock()
                    val body = fetch("${server.origin}/api$path", 5_000, signedHeaders(server))
                    val data = JianpianSource.data(body)
                    if ((path.startsWith("/v2/search/") || path.startsWith("/crumb/list")) && data !is JsonArray ||
                        path.startsWith("/video/detailv2") && data !is JsonObject)
                        throw ApiError("荐片接口返回格式异常")
                    mutex.withLock { server.latency = (server.latency + (clock() - started).coerceAtLeast(0)) / 2 }
                    return@withTimeoutOrNull body
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    failure = error
                    mutex.withLock {
                        servers = servers.filter { it !== server }
                        if (servers.isEmpty()) checkedAt = null
                    }
                }
            }
            if (round == 0) mutex.withLock {
                // Refresh discovery within this read operation when all known APIs failed.
                // The timeout bounds discovery and probing together, preventing retry loops.
                if (discoveredAt?.let { clock() - it in 0 until 10 * 60_000L } != true) discover()
            }
        }
        throw ApiError("荐片备用来源暂时不可用", failure)
    } ?: throw ApiError("荐片备用来源请求超时")
}
