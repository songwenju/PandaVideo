package dev.pandavideo.data.repository

import dev.pandavideo.core.model.*
import dev.pandavideo.core.player.BilibiliMedia
import dev.pandavideo.data.html.HtmlNode
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Resolves public user videos through anonymous Bilibili APIs; account credentials are never used. */
internal class BilibiliSource(private val fetch: suspend (String, Map<String, String>) -> String) {
    data class Reference(val bvid: String, val part: Int = 1) {
        val page: String get() = "https://www.bilibili.com/video/$bvid"
    }

    companion object {
        private val id = Regex("BV[0-9A-Za-z]{10}")

        /** Accepts exact BV identities or official HTTPS video pages, never text inside arbitrary URLs. */
        fun reference(value: String): Reference? {
            val text = value.trim()
            if (id.matches(text)) return Reference(text)
            return try {
                val url = Url(text)
                if (!isWebUrl(text) || url.protocol != URLProtocol.HTTPS || url.port != 443 ||
                    url.host !in setOf("www.bilibili.com", "m.bilibili.com", "bilibili.com")) return null
                val bvid = Regex("^/video/(BV[0-9A-Za-z]{10})/?$").matchEntire(url.encodedPath)
                    ?.groupValues?.get(1) ?: return null
                val part = if ("p" in url.parameters.names()) {
                    url.parameters.getAll("p")?.singleOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                } else 1
                Reference(bvid, part)
            } catch (_: Exception) { null }
        }
    }

    private val visitorLock = Mutex()
    private var visitorCookie: String? = null
    private val headers = mapOf(HttpHeaders.Referrer to "https://www.bilibili.com/")

    private fun payload(body: String): JsonObject {
        val root = try { Json.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { throw ApiError("B站接口格式已变更，请稍后重试") }
        if ((root["code"] as? JsonPrimitive)?.intOrNull != 0)
            throw ApiError("此视频无法匿名访问，可能已删除或需要登录、付费，请在B站查看")
        return root["data"] as? JsonObject ?: throw ApiError("B站视频信息缺失")
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun publicView(view: JsonObject, bvid: String) {
        if (view.text("bvid") != bvid) throw ApiError("B站视频身份不匹配")
        val rights = view["rights"] as? JsonObject
        if (listOf("pay", "is_upower_exclusive").any {
                (rights?.get(it) as? JsonPrimitive)?.intOrNull?.let { flag -> flag != 0 } == true
            }) throw ApiError("此视频需要付费或充电专享，请在B站查看")
    }

    private suspend fun view(bvid: String): JsonObject =
        payload(fetch("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", headers))
            .also { publicView(it, bvid) }

    /** Holds only ordinary anonymous visitor IDs in memory, scoped by the caller to this API origin. */
    private suspend fun visitor(): String = visitorLock.withLock {
        visitorCookie ?: run {
            val data = payload(fetch("https://api.bilibili.com/x/frontend/finger/spi", headers))
            fun visitorId(key: String): String = data.text(key).takeIf {
                // buvid4 is an opaque ID and may contain Base64 padding. Cookie delimiters,
                // whitespace and control characters remain forbidden.
                it.length in 1..200 && Regex("[A-Za-z0-9_+/=-]+").matches(it)
            } ?: throw ApiError("B站匿名访问暂时不可用，请重试")
            "buvid3=${visitorId("b_3")}; buvid4=${visitorId("b_4")}".also { visitorCookie = it }
        }
    }

    private fun item(data: JsonObject, ref: Reference): SearchItem {
        val pic = data.text("pic").let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("http://") -> "https://${it.removePrefix("http://")}"
                else -> it
            }
        }
        val poster = try {
            val url = Url(pic)
            pic.takeIf { isWebUrl(it) && url.protocol == URLProtocol.HTTPS && url.port == 443 &&
                (url.host == "hdslb.com" || url.host.endsWith(".hdslb.com")) }.orEmpty()
        } catch (_: Exception) { "" }
        // Search highlights split a title into inline <em> nodes. Remove only those markers
        // before decoding text so the HTML tree's block spacing does not alter the video title.
        val title = HtmlNode.parse(data.text("title").replace(Regex("</?em\\b[^>]*>", RegexOption.IGNORE_CASE), "")).text()
        return SearchItem(title = title,
            detailUrl = ref.page + if (ref.part > 1) "?p=${ref.part}" else "",
            thumbnailUrl = poster, genre = "公开视频", source = "哔哩哔哩",
            sourceId = "bilibili:${ref.bvid}", playable = true, uploader = data.text("author")
                .ifBlank { (data["owner"] as? JsonObject)?.text("name").orEmpty() },
            remark = data.text("duration").let { value ->
                value.toLongOrNull()?.takeIf { it in 1..604800 }?.let { formatMinutesSeconds(it * 1000) } ?: value
            })
    }

    suspend fun search(query: String): SearchResult {
        val keyword = query.trim()
        if (keyword.isEmpty() || keyword.length > 160) throw ApiError("请输入 1–160 字的关键词、BV号或视频链接")
        val ref = reference(keyword)
        val items = if (ref != null) listOf(item(view(ref.bvid), ref)) else {
            if (keyword.startsWith("https://") || keyword.startsWith("http://"))
                throw ApiError("请输入B站官方视频链接或BV号")
            val data = payload(fetch("https://api.bilibili.com/x/web-interface/search/type?" +
                "search_type=video&keyword=${keyword.encodeURLParameter()}&page=1",
                headers + (HttpHeaders.Cookie to visitor())))
            (data["result"] as? JsonArray).orEmpty().mapNotNull { row ->
                val obj = row as? JsonObject ?: return@mapNotNull null
                val identity = reference(obj.text("bvid")) ?: return@mapNotNull null
                item(obj, identity).takeIf { it.title.isNotBlank() }
            }.distinctBy { it.detailUrl }
        }
        return SearchResult(results = items, sources = listOf(SourceCount("哔哩哔哩", items.size, "ok")))
    }

    suspend fun resolve(request: PlayerRequest): PlaybackInfo {
        val ref = reference(request.page.orEmpty()) ?: throw ApiError("无效的B站视频页面")
        return resolveVideo(request, ref.bvid, ref.page, ref.part, "哔哩哔哩")
    }

    /** Reuses the same fresh MP4 resolver for explicitly verified source-owned sketch embeds. */
    suspend fun resolveVideo(request: PlayerRequest, bvid: String, page: String, selectedPart: Int = 1,
        source: String): PlaybackInfo {
        val view = view(bvid)
        val pages = (view["pages"] as? JsonArray).orEmpty()
        val parts = pages.mapIndexed { index, element ->
            val part = element as? JsonObject ?: throw ApiError("B站分集信息异常")
            val cid = (part["cid"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                ?: throw ApiError("B站分集信息异常")
            Episode("$page#bili-$cid", if (pages.size == 1) "正片" else
                part.text("part").ifBlank { "第 ${index + 1} 集" }, 1) to cid
        }
        if (parts.isEmpty() || parts.map { it.second }.distinct().size != parts.size)
            throw ApiError("B站暂无有效播放分集")
        val selected = if (request.episode == null) parts.getOrNull(selectedPart - 1)
            else parts.firstOrNull { it.first.path == request.episode }
        if (selected == null) throw ApiError("该分集不在当前视频的播放列表中")
        val data = payload(fetch("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=${selected.second}" +
            "&qn=64&fnval=0&platform=html5", headers))
        // durl can contain concatenated segments. Both native engines accept one muxed MP4;
        // rejecting multiple segments avoids silently playing a truncated video.
        val segment = (data["durl"] as? JsonArray)?.singleOrNull() as? JsonObject
            ?: throw ApiError("此视频暂不支持完整的内部播放，请在B站查看")
        val candidates = listOf(segment.text("url")) +
            (segment["backup_url"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val media = candidates.firstOrNull { BilibiliMedia.owns(it) && it !in request.rejectedMedia }
            ?: throw ApiError("B站播放地址暂时不可用，请重试")
        return PlaybackInfo(request.title ?: view.text("title").ifBlank { "B站视频" }, media, "video",
            parts.map { it.first }, selected.first.path, page, source)
    }
}
