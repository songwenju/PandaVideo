package dev.pandavideo.data.repository

import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.html.HtmlNode
import io.ktor.http.Url

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

    suspend fun resolve(request: PlayerRequest): PlaybackInfo {
        val page = request.page.orEmpty()
        if (!SketchSource.owns(page)) throw ApiError("无效的小品播放页面")
        val bvid = videoId(page, fetch(page)) ?: throw ApiError("此小品需在原站播放")
        return BilibiliSource { address, _ -> fetch(address) }
            .resolveVideo(request, bvid, page, source = "碟音网 · 哔哩哔哩")
    }
}
