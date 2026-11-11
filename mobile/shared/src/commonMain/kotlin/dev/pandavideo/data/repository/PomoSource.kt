package dev.pandavideo.data.repository

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.core.model.SearchResult
import dev.pandavideo.core.model.SourceCount
import io.ktor.http.*
import kotlinx.serialization.json.*

/** Parses Pomo's public player API and keeps expiring media URLs out of saved episode keys. */
internal object PomoSource {
    const val endpoint = "https://pomo.mom/content/plugins/plyr_player/api.php"
    private val json = Json { ignoreUnknownKeys = true }

    fun address(type: String, vararg values: Pair<String, String>): String =
        URLBuilder(endpoint).apply {
            parameters.append("type", type)
            values.forEach { (key, value) -> parameters.append(key, value) }
        }.buildString()

    fun page(value: String): String {
        val url = try { Url(value) } catch (_: Exception) { throw ApiError("无效的播放页面") }
        if (!isWebUrl(value) || url.protocol != URLProtocol.HTTPS || url.host != "pomo.mom" ||
            url.port != 443 || url.encodedPath != Url(endpoint).encodedPath ||
            url.parameters.names() != setOf("type", "site", "id") ||
            url.parameters.names().any { url.parameters.getAll(it)?.size != 1 } ||
            url.parameters["type"] != "auto_detail" ||
            !url.parameters["id"].orEmpty().matches(Regex("[1-9]\\d{0,11}")) ||
            url.parameters["site"].orEmpty().let { it.isBlank() || it.length > 40 || it.any(Char::isISOControl) } ||
            url.fragment.isNotEmpty() && !url.fragment.matches(Regex("[1-9]\\d{0,3}-\\d{1,4}"))) {
            throw ApiError("无效的播放页面")
        }
        return address("auto_detail", "site" to url.parameters["site"]!!, "id" to url.parameters["id"]!!) +
            url.fragment.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()
    }

    fun data(body: String): JsonElement {
        val root = try { json.parseToJsonElement(body) as? JsonObject }
        catch (_: Exception) { null } ?: throw ApiError("播放来源返回格式异常")
        if (root.text("code") != "200") throw ApiError("播放来源暂时不可用，请稍后重试")
        return root["data"] ?: throw ApiError("播放来源返回格式异常")
    }

    fun search(body: String, type: String): SearchResult {
        val sources = data(body) as? JsonObject ?: throw ApiError("搜索结果格式异常")
        if (sources.values.any { it !is JsonArray }) throw ApiError("搜索结果格式异常")
        val items = sources.entries.sortedBy { if (it.key == "西瓜") 0 else 1 }.flatMap { (site, rows) ->
            (rows as? JsonArray).orEmpty().mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val title = row.text("name").trim().take(160)
                val remark = row.text("remarks").take(80)
                if (title.isBlank() || Regex("解说|预告|花絮|片段").containsMatchIn(title + remark)) return@mapNotNull null
                val genre = row.text("type_name")
                val tv = Regex("电视剧|连续剧|国产剧|欧美剧|港剧|台剧|韩剧|日剧|泰剧|短剧|第\\d+集|更新.*集|全\\d+集")
                    .containsMatchIn(genre + remark)
                val movie = Regex("电影|片$|HD|BD|正片").containsMatchIn(genre + remark)
                if (type == "movie" && tv || type == "tv" && movie && !tv) return@mapNotNull null
                val page = try { page(address("auto_detail", "site" to site, "id" to row.text("id"))) }
                catch (_: ApiError) { return@mapNotNull null }
                SearchItem(title, "Pomo · $site", "$site:${row.text("id")}", row.text("play_url").isNotBlank(),
                    page, row.text("cover").takeIf(::isWebUrl).orEmpty(), row.text("year"),
                    row.text("area"), genre, row.text("actor"), remark)
            }
        }.distinctBy { it.detailUrl }.take(80)
        return SearchResult(items, sources.map { (site, _) ->
            SourceCount("Pomo · $site", items.count { it.source == "Pomo · $site" }, "可在线播放")
        })
    }

    /** Retains upstream line/index order; identifiers remain stable when signed URLs rotate. */
    fun episodes(body: String, page: String): List<Pair<Episode, String>> {
        val detail = data(body) as? JsonObject ?: throw ApiError("影片详情格式异常")
        return (detail["routes"] as? JsonArray).orEmpty().take(100).flatMapIndexed { line, route ->
            ((route as? JsonObject)?.get("episodes") as? JsonArray).orEmpty().take(1000)
                .mapIndexedNotNull { index, entry ->
                    val row = entry as? JsonObject ?: return@mapIndexedNotNull null
                    val media = row.text("url").takeIf(::isWebUrl) ?: return@mapIndexedNotNull null
                    Episode("$page#${line + 1}-$index", row.text("name").take(80).ifBlank { "播放" }, line + 1) to media
                }
        }
    }

    /** Uses the resolved direct stream when available, with Pomo's HLS proxy as a fallback. */
    fun media(body: String): Pair<String, String> {
        val root = try { json.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { throw ApiError("播放地址解析失败") }
        data(body)
        for (value in listOf(root.text("direct"), root.text("data"))) {
            if (!isWebUrl(value)) continue
            val url = Url(value)
            val path = url.encodedPath.lowercase()
            if (path.endsWith(".m3u8") || url.host == "pomo.mom" &&
                path == Url(endpoint).encodedPath && url.parameters["type"] == "proxy")
                return value to "hls"
            if (path.endsWith(".mp4") || path.endsWith(".m4v")) return value to "video"
        }
        throw ApiError("此线路暂未提供可直接播放的视频")
    }
}
