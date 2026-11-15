package dev.pandavideo

import dev.pandavideo.data.repository.ApiError
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.SketchSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.*

internal fun sketchFixture(id: Int = 2491, more: Boolean = true) = """
<ul><li class="multi cate2 auth1">
<div class="cateimg"><a href="https://www.xiaopin.tv/?id=$id"><img src="https://www.xiaopin.tv/poster.png"></a></div>
<div class="catename"><a href="https://www.xiaopin.tv/?id=$id">小品《羊肉串》 &amp; 陈佩斯</a></div>
</li></ul>
${if (more) """<a href="?cate=2&amp;page=2">下一页</a>""" else ""}
"""

internal fun dieyinSketchFixture(id: Int = 407962, more: Boolean = true, nextPage: Int = 2) = """
<article class="post post-grid"><div class="entry-media">
<a href="/video/$id.html"><img data-src="/pic/poster.jpg" src="/placeholder.gif"></a></div>
<h2 class="entry-title"><a href="/video/$id.html" title="又来了下载">又来了</a></h2>
<div class="entry-yy">贾冰,何欢</div>
<a href="/down/$id-0-0.html">下载</a></article>
${if (more) """<a href="/xiaopin/index_$nextPage.html">下一页</a>""" else ""}
"""

@OptIn(ExperimentalCoroutinesApi::class)
class SketchSourceTest {
    @Test fun parsesBothCatalogsWithoutDownloadLinks() {
        val first = SketchSource.catalog(sketchFixture(), "www.xiaopin.tv", 1)
        val second = SketchSource.catalog(dieyinSketchFixture(), "www.dieyin.cn", 1)
        assertEquals("小品《羊肉串》 & 陈佩斯", first.items.single().title)
        assertEquals("https://www.xiaopin.tv/?id=2491", first.items.single().url)
        assertEquals("又来了", second.items.single().title)
        assertEquals("https://www.dieyin.cn/pic/poster.jpg", second.items.single().cover)
        assertEquals(listOf("贾冰", "何欢"), second.items.single().casts)
        assertTrue((first.items + second.items).all { it.kind == "xiaopin" && it.rating == null })
        assertTrue(first.hasMore && second.hasMore)
        assertFalse(SketchSource.catalog(sketchFixture(more = false), "www.xiaopin.tv", 25).hasMore)
        assertFalse(SketchSource.catalog(dieyinSketchFixture(more = false), "www.dieyin.cn", 1003).hasMore)
    }

    @Test fun validatesDetailAddressesAndRejectsChallengePages() {
        listOf(
            "https://www.dieyin.cn/down/407962-0-0.html",
            "https://www.dieyin.cn/video/407962.html?redirect=evil",
            "https://www.dieyin.cn.evil/video/407962.html",
            "http://www.xiaopin.tv/?id=2491",
            "https://user@www.xiaopin.tv/?id=2491",
            "https://www.xiaopin.tv/?id=2491&id=2492",
            "https://www.xiaopin.tv/?cate=2",
        ).forEach { assertFalse(SketchSource.owns(it), it) }
        assertFailsWith<ApiError> { SketchSource.catalog("<html>captcha</html>", "www.dieyin.cn", 1) }
        assertEquals("https://www.xiaopin.tv/?cate=2&page=2", SketchSource.address("www.xiaopin.tv", 2))
        assertEquals("https://www.dieyin.cn/xiaopin/index_2.html", SketchSource.address("www.dieyin.cn", 2))
    }

    @Test fun loadsOnlyActiveSourceAndSkipsNativeAvailabilitySearch() = runTest {
        val addresses = mutableListOf<String>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            addresses += request.url.toString()
            respond(if (request.url.host == "www.xiaopin.tv") sketchFixture() else dieyinSketchFixture())
        }))
        try {
            val first = repo.sketches()
            assertEquals(listOf("www.dieyin.cn"), first.items.map { io.ktor.http.Url(it.url).host })
            assertEquals(1, first.nextStart())
            assertEquals(first, repo.visibleCatalog(first))
            repo.inspectCatalog(first.items) { fail("Browser entries must not be classified by native movie search") }
            assertEquals(1, addresses.size)
            val second = repo.sketches(first.nextStart())
            assertEquals(1, second.start)
            assertEquals(2, second.nextStart())
            assertTrue(addresses.none { io.ktor.http.Url(it).host == "www.xiaopin.tv" })
            assertTrue(addresses.any { it.endsWith("/xiaopin/index_2.html") })
            assertFalse(second.hasMore)
        } finally { repo.close() }
    }

    @Test fun removedSourceIsNeverRequested() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.host == "www.xiaopin.tv") respond("", HttpStatusCode.ServiceUnavailable)
            else respond(dieyinSketchFixture(more = false))
        }))
        try {
            val page = repo.sketches()
            assertEquals("又来了", page.items.single().title)
            assertFalse(page.stale)
            assertFalse(page.hasMore)
        } finally { repo.close() }
    }

    @Test fun activeSourcePaginatesWithoutRemovedProvider() = runTest {
        var firstRequests = 0
        var secondRequests = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.host == "www.xiaopin.tv") {
                firstRequests++
                respond(sketchFixture(more = false))
            } else {
                secondRequests++
                respond(dieyinSketchFixture(id = 407962 + secondRequests, nextPage = secondRequests + 1))
            }
        }))
        try {
            val first = repo.sketches()
            val next = repo.sketches(first.nextStart())
            repo.sketches(next.nextStart())
            assertEquals(0, firstRequests)
            assertEquals(3, secondRequests)
            assertEquals(1, next.items.size)
            assertFalse(next.stale)
        } finally { repo.close() }
    }

    @Test fun refreshUsesActiveSourceMemoryFallbackWhenOffline() = runTest {
        var offline = false
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (offline) respond("", HttpStatusCode.ServiceUnavailable)
            else respond(if (request.url.host == "www.xiaopin.tv") sketchFixture() else dieyinSketchFixture())
        }))
        try {
            val loaded = repo.sketches()
            offline = true
            val cached = repo.sketches(refresh = true)
            assertEquals(loaded.items, cached.items)
            assertTrue(cached.stale)
        } finally { repo.close() }
    }
}
