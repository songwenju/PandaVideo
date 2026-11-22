package dev.pandavideo.data.repository

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.MediaSegment
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import kotlinx.serialization.json.*

/** Resolves publicly available CCTV VOD without storing expiring CDN addresses in history. */
internal object CctvSource {
    val columns = linkedMapOf(
        "TOPC1451464665008914" to "今日说法",
        "TOPC1451464884159276" to "开讲啦",
        "TOPC1451528971114112" to "新闻联播",
        "TOPC1451558976694518" to "焦点访谈",
        "TOPC1451557052519584" to "百家讲坛",
        "TOPC1451533652476962" to "经济半小时",
        "TOPC1451557893544236" to "探索·发现",
        "TOPC1451557421544786" to "地理·中国",
        "TOPC1451378967257534" to "动物世界",
        "TOPC1451525103989666" to "人与自然",
        "TOPC1451557646802924" to "健康之路",
        "TOPC1451558819463311" to "新闻调查",
        "TOPC1451559038345600" to "面对面",
        "TOPC1451559066181661" to "新闻1+1",
        "TOPC1451543228296920" to "天网",
        "TOPC1451543462858283" to "一线",
        "TOPC1451542784285432" to "道德观察",
        "TOPC1451530259915198" to "第一时间",
        "TOPC1451467630488780" to "星光大道",
        "TOPC1451467829851129" to "越战越勇",
        "TOPC1451557523542854" to "读书",
        "TOPC1451558190239536" to "走近科学",
        "TOPC1451534118159896" to "生财有道",
        "TOPC1451534775834896" to "消费主张",
    )
    const val pageSize = 20

    /** Official channel memberships; simulcasts may appear under multiple channels. */
    val channels = linkedMapOf(
        "CCTV-1 综合" to listOf("今日说法", "开讲啦", "新闻联播", "焦点访谈", "动物世界", "人与自然"),
        "CCTV-2 财经" to listOf("经济半小时", "第一时间", "生财有道", "消费主张"),
        "CCTV-3 综艺" to listOf("星光大道", "越战越勇"),
        "CCTV-10 科教" to listOf("百家讲坛", "探索·发现", "地理·中国", "健康之路", "读书", "走近科学"),
        "CCTV-12 社会与法" to listOf("天网", "一线", "道德观察"),
        "CCTV-13 新闻" to listOf("焦点访谈", "新闻调查", "面对面", "新闻1+1"),
    )

    fun listAddress(column: String, start: Int, period: String = ""): String {
        if (column !in columns || start < 0 || start % pageSize != 0)
            throw ApiError("央视栏目无效")
        if (period.isNotEmpty() && !Regex("(?:19|20)\\d{2}(?:0[1-9]|1[0-2])?").matches(period))
            throw ApiError("央视节目年月无效")
        return "https://api.cntv.cn/NewVideo/getVideoListByColumn?id=$column&n=$pageSize&sort=desc&p=${start / pageSize + 1}&mode=0&serviceId=tvcctv" +
            if (period.isEmpty()) "" else "&d=$period"
    }

    fun programs(body: String, column: String, start: Int): CatalogPage {
        listAddress(column, start)
        val data = runCatching { Json.parseToJsonElement(body).jsonObject["data"] as? JsonObject }
            .getOrNull() ?: throw ApiError("央视栏目数据无效")
        val rows = data["list"] as? JsonArray ?: throw ApiError("央视栏目数据无效")
        val total = (data["total"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it >= 0 } ?: throw ApiError("央视栏目数据无效")
        val items = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val id = text("guid")
            // mode=0 requests full programs; still reject clips if the upstream ignores it.
            if (!Regex("[a-fA-F0-9]{32}").matches(id) || text("title").isBlank() ||
                text("mode") != "0") return@mapNotNull null
            val identity = page("https://$host$path?pid=${id.lowercase()}")
            CatalogItem(text("title"), identity, cover = text("image"), kind = "variety",
                synopsis = text("brief").trim(), playbackPageUrl = identity, remark = text("length"),
                broadcastDate = text("time").take(10).takeIf {
                    Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)
                }.orEmpty(), programColumn = columns.getValue(column))
        }.distinctBy { it.url }
        if (rows.isNotEmpty() && items.isEmpty()) throw ApiError("央视栏目暂无可用整期节目")
        return CatalogPage(items, start, pageSize,
            rows.isNotEmpty() && start + pageSize < total, columns.getValue(column))
    }
    private const val host = "vdn.apps.cntv.cn"
    private const val path = "/api/getHttpVideoInfo.do"
    const val samplePage = "https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=d70ba25b901c4acdad2c22384c9b2d12&client=androidapp"

    fun owns(page: String): Boolean = runCatching { Url(page).host == host }.getOrDefault(false)

    fun page(address: String): String {
        val url = runCatching { Url(address) }.getOrNull() ?: throw ApiError("央视节目地址无效")
        val ids = url.parameters.getAll("pid").orEmpty()
        if (url.protocol != URLProtocol.HTTPS || url.host != host || url.port != 443 ||
            url.user != null || url.password != null || url.fragment.isNotEmpty() ||
            url.encodedPath != path || ids.size != 1 || !Regex("[a-fA-F0-9]{32}").matches(ids.single())) {
            throw ApiError("央视节目地址无效")
        }
        return "https://$host$path?pid=${ids.single()}&client=androidapp"
    }

    suspend fun resolve(request: PlayerRequest, fetch: suspend (String) -> String): PlaybackInfo {
        val page = page(request.page.orEmpty())
        if (request.episode != null && request.episode != page || page in request.rejectedEpisodes)
            throw ApiError("央视节目线路不可用，请重试")
        val data = try { Json.parseToJsonElement(fetch(page)) as? JsonObject }
            catch (_: kotlinx.serialization.SerializationException) { null }
            ?: throw ApiError("央视节目数据无效")
        fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (text("ack") != "yes") throw ApiError("央视节目暂不可播放")
        if (text("is_preview") == "1") throw ApiError("该央视节目不是公开完整点播")
        // hls_url is the full-program single-URL fallback. Audio and encrypted alternatives in
        // manifest must never become fallback video routes. The observed CDN can change
        // between resolves, so retain the stable PID and accept the two verified hosts.
        val media = text("hls_url")
        val url = runCatching { Url(media) }.getOrNull()
        if (url == null || url.protocol != URLProtocol.HTTPS || url.port != 443 ||
            url.user != null || url.password != null ||
            url.host !in setOf("hls.cntv.lxdns.com", "newcntv.qcloudcdn.com") ||
            !url.encodedPath.startsWith("/asp/hls/") || !url.encodedPath.endsWith(".m3u8") ||
            media in request.rejectedMedia) throw ApiError("央视公开视频线路不可用，请重试")
        return PlaybackInfo(text("title").ifBlank { request.title ?: "央视点播" }, media, "hls",
            episodes = listOf(Episode(page, "正片", 0, "央视")),
            selected = page, pageUrl = page, source = "央视", segments = publicSegments(data, request))
    }

    /**
     * Accepts only complete verified public HD chapter tiers, never reconstructing missing URLs.
     * Millisecond rounding tolerates upstream decimal precision, but cannot hide a missing chapter.
     */
    private fun publicSegments(data: JsonObject, request: PlayerRequest): List<MediaSegment> {
        val video = data["video"] as? JsonObject ?: return emptyList()
        fun millis(element: JsonElement?): Long? {
            val seconds = (element as? JsonPrimitive)?.doubleOrNull ?: return null
            if (!seconds.isFinite() || seconds <= 0 || seconds > 86_400) return null
            return kotlin.math.round(seconds * 1_000).toLong().takeIf { it > 0 }
        }
        val total = millis(video["totalLength"]) ?: return emptyList()
        for (tier in listOf("chapters4", "chapters3")) {
            val chapters = video[tier] as? JsonArray ?: continue
            if (chapters.isEmpty() || chapters.size > 720) continue
            val segments = chapters.mapNotNull { element ->
                val chapter = element as? JsonObject ?: return@mapNotNull null
                val address = (chapter["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val url = runCatching { Url(address) }.getOrNull() ?: return@mapNotNull null
                val duration = millis(chapter["duration"]) ?: return@mapNotNull null
                if (url.protocol != URLProtocol.HTTPS || url.port != 443 || url.user != null ||
                    url.password != null || url.fragment.isNotEmpty() || url.host != "vod.cntv.lxdns.com" ||
                    !url.encodedPath.startsWith("/flash/mp4video") || !url.encodedPath.endsWith(".mp4") ||
                    address in request.rejectedMedia) return@mapNotNull null
                MediaSegment(address, duration)
            }
            if (segments.size == chapters.size && segments.map { it.url }.distinct().size == segments.size &&
                kotlin.math.abs(segments.sumOf { it.durationMs } - total) <= maxOf(100L, chapters.size.toLong()))
                return segments
        }
        return emptyList()
    }
}
