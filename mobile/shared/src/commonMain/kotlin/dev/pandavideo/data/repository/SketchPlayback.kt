package dev.pandavideo.data.repository

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.player.BilibiliMedia
import dev.pandavideo.data.html.HtmlNode
import io.ktor.http.Url
import kotlinx.serialization.json.*

/** Resolves an explicitly embedded public Bilibili video without title search or cached tokens. */
internal class SketchPlayback(private val fetch: suspend (String) -> String) {
    companion object {
        /** Reads only the source-owned embed route, not arbitrary BV text in ads or comments. */
        fun videoId(page: String, html: String): String? {
            if (!SketchSource.owns(page) || Url(page).host != "www.dieyin.cn") return null
            return HtmlNode.parse(html).descendants().filter { it.tag == "iframe" }.mapNotNull {
                val address = it.attr("src")
                val absolute = if (address.startsWith("/")) "https://www.dieyin.cn$address" else address
                try {
                    val url = Url(absolute)
                    url.takeIf {
                        isWebUrl(absolute) && it.protocol.name == "https" && it.port == 443 &&
                            it.host == "www.dieyin.cn" && it.encodedPath == "/down/play/bz.php" &&
                            it.fragment.isEmpty() && it.parameters.names() == setOf("id") &&
                            it.parameters.getAll("id")?.size == 1
                    }?.parameters?.get("id")?.takeIf { Regex("BV[0-9A-Za-z]{10}").matches(it) }
                } catch (_: Exception) { null }
            }.distinct().singleOrNull()
        }
    }

    private fun payload(body: String): JsonObject {
        val root = try { Json.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { throw ApiError("小品播放接口格式已变更") }
        if ((root["code"] as? JsonPrimitive)?.intOrNull != 0)
            throw ApiError("此小品暂时无法获取公开播放资源")
        return root["data"] as? JsonObject ?: throw ApiError("小品播放信息缺失")
    }

    suspend fun resolve(request: PlayerRequest): PlaybackInfo {
        val page = request.page.orEmpty()
        if (!SketchSource.owns(page)) throw ApiError("无效的小品播放页面")
        val bvid = videoId(page, fetch(page)) ?: throw ApiError("此小品需在原站播放")
        val view = payload(fetch("https://api.bilibili.com/x/web-interface/view?bvid=$bvid"))
        if ((view["bvid"] as? JsonPrimitive)?.content != bvid)
            throw ApiError("小品播放身份不匹配")
        val pages = (view["pages"] as? JsonArray).orEmpty()
        val parts = pages.mapIndexed { index, element ->
            val part = element as? JsonObject ?: throw ApiError("小品分集信息异常")
            val cid = (part["cid"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                ?: throw ApiError("小品分集信息异常")
            Episode("$page#bili-$cid", if (pages.size == 1) "正片" else (part["part"] as? JsonPrimitive)?.contentOrNull
                .orEmpty().ifBlank { "第 ${index + 1} 集" }, 1) to cid
        }
        if (parts.isEmpty()) throw ApiError("小品暂无可播放分集")
        val selected = if (request.episode == null) parts.first()
            else parts.firstOrNull { it.first.path == request.episode }
                ?: throw ApiError("该剧集不在当前小品的播放列表中")
        val data = payload(fetch("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=${selected.second}&qn=64&fnval=0&platform=html5"))
        // durl may represent concatenated segments. A single muxed MP4 can be handed to both
        // native engines; never silently play only the first segment of a multi-segment result.
        val segment = (data["durl"] as? JsonArray)?.singleOrNull() as? JsonObject
            ?: throw ApiError("此小品暂不支持完整的内部播放")
        val candidates = listOf((segment["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()) +
            (segment["backup_url"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val media = candidates.firstOrNull { BilibiliMedia.owns(it) && it !in request.rejectedMedia }
            ?: throw ApiError("小品播放地址暂时不可用，请重试")
        return PlaybackInfo(request.title ?: (view["title"] as? JsonPrimitive)?.contentOrNull ?: "小品播放",
            media, "video", parts.map { it.first }, selected.first.path, page, "碟音网 · 哔哩哔哩")
    }
}
