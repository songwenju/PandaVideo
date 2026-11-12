package dev.pandavideo.data.repository

import dev.pandavideo.core.model.Episode
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
                row.names("types"), row.names("actors"), row.text("mask").take(80))
        }.distinctBy { it.detailUrl }
        return SearchResult(items, listOf(SourceCount("荐片", items.size, "可在线播放")))
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

    fun episodes(body: String, page: String): List<Pair<Episode, String>> {
        val detail = data(body) as? JsonObject ?: throw ApiError("荐片详情格式异常")
        // Native FTP/P2P resources are intentionally excluded: mobile has no JPEngine2.
        // Line/index keys preserve upstream order without persisting expiring stream URLs.
        return (detail["source_list_source"] as? JsonArray).orEmpty().take(100).flatMapIndexed { line, entry ->
            ((entry as? JsonObject)?.get("source_list") as? JsonArray).orEmpty().take(1000)
                .mapIndexedNotNull { index, value ->
                    val row = value as? JsonObject ?: return@mapIndexedNotNull null
                    val media = row.text("url").takeIf { mediaKind(it) != null } ?: return@mapIndexedNotNull null
                    Episode("$page#${line + 1}-$index",
                        row.text("source_name").take(80).ifBlank { "播放" }, line + 1) to media
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
                    if (path.startsWith("/v2/search/") && data !is JsonArray ||
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
