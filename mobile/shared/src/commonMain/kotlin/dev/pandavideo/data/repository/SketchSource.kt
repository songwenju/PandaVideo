package dev.pandavideo.data.repository

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.data.html.HtmlNode
import io.ktor.http.Url

/** Reads public sketch catalogs; detail identities are resolved separately from media URLs. */
internal object SketchSource {
    val hosts = listOf("www.xiaopin.tv", "www.dieyin.cn")
    // Keep legacy address parsing separate from active catalogs. Retired Youku
    // provider snapshots must never reintroduce their entries through offline fallback.
    val catalogHosts = listOf("www.dieyin.cn")

    fun listed(address: String): Boolean = owns(address) && Url(address).host in catalogHosts

    /** Filters visible download promotions, not a playable card's tooltip or download link. */
    fun catalogTitle(title: String): Boolean = title.isNotBlank() &&
        !Regex("高清下载|打包下载|全集下载|免费下载|网盘|磁力|迅雷|(?:合集|\\d+部).*下载")
            .containsMatchIn(title)

    fun address(host: String, page: Int): String {
        require(host in hosts && page > 0)
        return if (host == hosts.first()) "https://$host/?cate=2&page=$page"
        else "https://$host/xiaopin/" + if (page == 1) "" else "index_$page.html"
    }

    /** Accepts only source-owned detail pages, never download, userinfo or arbitrary links. */
    fun owns(address: String): Boolean = try {
        val url = Url(address)
        isWebUrl(address) && url.protocol.name == "https" && url.port == 443 &&
            url.fragment.isEmpty() && when (url.host) {
                hosts.first() -> url.encodedPath == "/" && url.parameters.names() == setOf("id") &&
                    url.parameters.getAll("id")?.size == 1 &&
                    url.parameters["id"].orEmpty().matches(Regex("[1-9]\\d*"))
                hosts.last() -> url.parameters.isEmpty() &&
                    Regex("/video/[1-9]\\d*\\.html").matches(url.encodedPath)
                else -> false
            }
    } catch (_: Exception) { false }

    fun label(address: String): String = if (owns(address) && Url(address).host == hosts.first()) "小品网" else "碟音网"

    private fun absolute(host: String, address: String): String = when {
        address.startsWith("//") -> "https:$address"
        address.startsWith("/") -> "https://$host$address"
        address.startsWith("?") -> "https://$host/$address"
        else -> address
    }

    fun catalog(html: String, host: String, page: Int): CatalogPage {
        require(host in hosts && page > 0)
        val nodes = HtmlNode.parse(html).descendants()
        val cards = nodes.filter {
            if (host == hosts.first()) it.tag == "li" && it.hasClass("multi") && it.hasClass("cate2")
            else it.tag == "article" && it.hasClass("post-grid")
        }
        // An empty challenge/error response must not replace a useful offline catalog.
        if (cards.isEmpty()) throw ApiError("小品来源暂无内容或页面格式已变更")
        val items = cards.mapNotNull { card ->
            val children = card.descendants()
            val titleNode = children.firstOrNull {
                it.hasClass(if (host == hosts.first()) "catename" else "entry-title")
            } ?: return@mapNotNull null
            val link = titleNode.descendants().firstOrNull { it.tag == "a" } ?: return@mapNotNull null
            val address = absolute(host, link.attr("href"))
            if (!owns(address) || Url(address).host != host || link.text().isBlank()) return@mapNotNull null
            val image = children.firstOrNull { it.tag == "img" }
            val cover = absolute(host, image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() })
                .takeIf(::isWebUrl).orEmpty()
            val cast = children.firstOrNull { it.hasClass("entry-yy") }?.text().orEmpty()
                .split(Regex("[,，、/]+")).map(String::trim).filter(String::isNotEmpty)
            CatalogItem(link.text(), address, cover = cover, casts = cast, kind = "xiaopin")
        }.distinctBy { it.url }
        if (items.isEmpty()) throw ApiError("小品来源暂无有效条目")
        val hasMore = nodes.filter { it.tag == "a" }.any { node ->
            try {
                val link = Url(absolute(host, node.attr("href")))
                link.host == host && if (host == hosts.first()) {
                    link.parameters["cate"] == "2" && (link.parameters["page"]?.toIntOrNull() ?: 0) > page
                } else {
                    (Regex("/xiaopin/index_(\\d+)\\.html").matchEntire(link.encodedPath)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0) > page ||
                        link.encodedPath == "/e/action/ListInfo/" && link.parameters["classid"] == "2" &&
                        (link.parameters["page"]?.toIntOrNull() ?: 0) >= page
                }
            } catch (_: Exception) { false }
        }
        // The aggregate cursor counts provider pages, not rows (the sites have different sizes).
        // A structurally valid page containing only promotions is still a valid cursor.
        // Keep its pagination so the next page can supply actual performances.
        return CatalogPage(items.filter { catalogTitle(it.title) }, page - 1, 1, hasMore, label = "小品")
    }
}
