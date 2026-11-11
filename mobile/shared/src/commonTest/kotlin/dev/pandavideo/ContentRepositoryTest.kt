package dev.pandavideo

import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.app.HomeCategoryOptions
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.ApiError
import dev.pandavideo.data.repository.ContentParsers
import dev.pandavideo.data.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.io.encoding.Base64
import kotlin.test.*

internal val searchFixture = """
阳光电影 相关搜索结果
<a class="v-thumb" title="推荐" href="/voddetail/9.html"></a>
<ul class="stui-vodlist__media">
<li><a class="v-thumb" title="测试 &amp; 电影" href="/voddetail/1.html" data-original="https://image/1.jpg"><span class="pic-text text-right">HD</span></a>
<p>主演：张三 李四</p><p>类型：剧情</p><p>地区：中国</p><p>年份：2020</p></li>
<li><a class="v-thumb" title="测试剧" href="/voddetail/2.html"></a><p>类型：国产剧</p></li>
<li><a class="v-thumb" title="外站" href="https://other/voddetail/3.html"></a></li>
</ul>
"""
internal val detailFixture = """
<p>年份：2020</p>
<div class="stui-pannel"><div class="stui-pannel-box">
<div class="stui-pannel_hd"><h3 class="title">剧情简介</h3></div>
<div class="stui-pannel_bd"><p class="col-pd">测试电影是大导演<a href="/director">导演</a>的一部优秀作品，主演有演员。影片的关键词是未知,一位旅人 &amp; 同伴踏上旅程。<br>他们发现了<span>新的世界</span>。</p></div>
</div></div>
<div class="stui-pannel"><h3>猜你喜欢</h3><p class="col-pd">推荐内容，不能作为简介。</p></div>
<a href="/vodplay/1-1-1.html">立即播放</a>
<ul><li><a href="/vodplay/1-1-1.html"><span>第 1 集</span></a></li>
<li><a href="/vodplay/1-2-1.html">备用 1</a></li><li><a href="/vodplay/1-1-2.html">第 2 集</a></li></ul>
<a href="/vodplay/2-1-1.html">其他影片</a>
"""
internal val topFixture = """
<div class="item"><div class="pic"><img alt="测试电影" src="https://image/poster.jpg"></div>
<div class="info"><a href="https://movie.douban.com/subject/123/"><span>测试电影</span></a>
<p>导演<br>2020 / 中国</p><span class="rating_num">9.1</span></div></div>
"""

@OptIn(ExperimentalCoroutinesApi::class)
class ContentRepositoryTest {
    @Test fun resourceFilteringHidesOnlySuccessfulSearchMisses() = runTest {
        val item = CatalogItem("测试电影", "https://movie.douban.com/subject/123/", "2020")
        val page = dev.pandavideo.core.model.CatalogPage(listOf(item), 20, 20, true, "分类")
        for (mode in listOf("missing", "searchHttp", "searchChallenge", "detailHttp", "playHttp", "unsupported", "playable")) {
            val requests = mutableListOf<String>()
            val repo = ContentRepository(HttpClient(mockEngine { request ->
                requests += request.url.host
                when (request.url.parameters["type"]) {
                    "auto_search" -> when (mode) {
                        "missing" -> respond(pomoSearch())
                        "searchHttp" -> respond("", HttpStatusCode.ServiceUnavailable)
                        "searchChallenge" -> respond("<html>captcha</html>")
                        else -> respond(pomoSearchFixture)
                    }
                    "auto_detail" -> if (mode == "detailHttp")
                        respond("", HttpStatusCode.ServiceUnavailable) else respond(pomoDetailFixture)
                    else -> when (mode) {
                        "playHttp" -> respond("", HttpStatusCode.ServiceUnavailable)
                        "unsupported" -> respond("""var player_aaaa={"url":"https://media/embed","encrypt":0};""")
                        else -> respond("""var player_aaaa={"url":"https://media/stream.m3u8","encrypt":0};""")
                    }
                }
            }))
            try {
                val missing = mutableListOf<CatalogItem>()
                repo.inspectCatalog(page.items) { missing += it }
                // An empty Pomo search cannot prove absence while its backup is offline.
                assertEquals(emptyList(), missing, mode)
                assertEquals(page.items, repo.visibleCatalog(page).items, mode)
                assertEquals(40, repo.visibleCatalog(page).nextStart(), mode)
                assertTrue(requests.all { it == "pomo.mom" }, "Resource filtering must not fetch media: $mode")
            } finally { repo.close() }
        }
    }
    @Test fun directSearchEncodingAndCardIsolation() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals("pomo.mom", request.url.host)
            assertEquals("测试电影", request.url.parameters["wd"])
            assertEquals("auto_search", request.url.parameters["type"])
            assertEquals(HttpMethod.Get, request.method)
            respond(pomoSearchFixture)
        }))
        try {
            val result = repo.search("《测试 电 影》", "movie")
            assertEquals(listOf("测试电影"), result.results.map { it.title })
            assertEquals(1, result.sources.single().count)
            val item = result.results.single()
            assertTrue(item.playable)
            assertEquals("2020", item.year)
            assertEquals("中国", item.region)
            assertEquals("剧情", item.genre)
            assertEquals("张三 李四", item.cast)
            assertEquals("HD", item.remark)
        } finally { repo.close() }
    }
    @Test fun searchCardMetadataIsParsedForResultUi() {
        val html = """
            阳光电影 相关搜索结果
            <ul class="stui-vodlist__media"><li>
            <a class="v-thumb" title="小城故事" href="/voddetail/7.html" data-original="https://image/7.jpg">
            <span class="pic-text">更新至10集</span></a>
            <p>主演：王五 赵六</p><p>类型：剧情 喜剧</p><p>地区：中国大陆</p><p>年份：2021</p>
            </li></ul>
        """.trimIndent()
        val item = ContentParsers.search(html, "all").single()
        assertEquals("2021", item.year)
        assertEquals("中国大陆", item.region)
        assertEquals("剧情、喜剧", item.genre)
        assertEquals("王五 赵六", item.cast)
        assertEquals("更新至10集", item.remark)
    }
    @Test fun sourceChallengeIsNotAnEmptySearch() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { respond("<html>captcha</html>") }))
        try { assertFailsWith<ApiError> { repo.search("电影", "all") } }
        finally { repo.close() }
    }
    @Test fun topCatalogAndStaleFallback() = runTest {
        var fail = false
        val repo = ContentRepository(HttpClient(mockEngine {
            if (fail) respond("", HttpStatusCode.Forbidden) else respond(topFixture)
        }))
        try {
            val page = repo.list("top250")
            assertEquals("2020", page.items.single().year)
            assertEquals(9.1, page.items.single().rating)
            assertFalse(page.hasMore)
            fail = true
            assertTrue(repo.list("top250").stale)
            assertFailsWith<ApiError> { repo.list("top250", 25) }
        } finally { repo.close() }
    }
    @Test fun addedHotCategoriesKeepTheirOwnArtworkAndOfflineCache() = runTest {
        var offline = false
        val tags = mutableListOf<String>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (offline) error("offline")
            val tag = request.url.parameters["tags"].orEmpty()
            tags += tag
            assertEquals("U", request.url.parameters["sort"])
            val id = when (tag) { "综艺" -> 1; "动画" -> 2; "纪录片" -> 3; else -> error("Unexpected tag") }
            respond("""{"data":[{"title":"$tag","url":"https://movie.douban.com/subject/$id/","rate":"8.5","cover":"https://image/$id.jpg"}]}""")
        }))
        try {
            val pages = listOf("variety", "animation", "documentary").associateWith { type ->
                repo.browse(BrowseFilters(type = type)).also { page ->
                    assertEquals(type, page.items.single().kind)
                    assertTrue(page.items.single().cover.startsWith("https://image/"))
                }
            }
            assertEquals(listOf("综艺", "动画", "纪录片"), tags)
            offline = true
            for ((type, page) in pages) {
                assertEquals(page.copy(stale = true), repo.browse(BrowseFilters(type = type)))
            }
        } finally { repo.close() }
    }
    @Test fun browseQueryAndUpstreamPaging() = runTest {
        val data = (1..20).joinToString(",") { """{"title":"电影$it","url":"https://movie.douban.com/subject/$it","rate":"${if (it == 1) "0" else "8.5"}"}""" }
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals("movie.douban.com", request.url.host)
            assertEquals("电影,美国,大片", request.url.parameters["tags"])
            assertEquals("2020,2020", request.url.parameters["year_range"])
            assertEquals("S", request.url.parameters["sort"])
            assertEquals("20", request.url.parameters["start"])
            respond("""{"data":[$data]}""")
        }))
        try {
            val page = repo.browse(BrowseFilters(genre = "hollywood", year = "2020", sort = "rating"), 20)
            assertEquals(19, page.items.size)
            assertTrue(page.hasMore)
            assertEquals(40, page.nextStart())
        } finally { repo.close() }
    }
    @Test fun everyHomeCategoryRequestsHighScoresAndKeepsUpstreamPaging() = runTest {
        val expectedTags = mapOf(
            "tv" to "电视剧", "comedy" to "电影,喜剧", "anime" to "动画", "nature" to "纪录片,自然",
            "movie" to "电影", "documentary" to "纪录片", "drama" to "电影,剧情",
            "action" to "电影,动作", "romance" to "电影,爱情", "scifi" to "电影,科幻",
            "mystery" to "电影,悬疑", "finance" to "电影,金融", "biography" to "电影,传记",
            "war" to "电影,战争", "hollywood" to "电影,美国,大片",
        )
        var expectedTag = ""
        val data = (1..20).joinToString(",") {
            val score = when (it) { 1 -> "7.9"; 2 -> "0"; 3 -> "8.0"; 4 -> "9.5"; else -> "" }
            """{"title":"影片$it","url":"https://movie.douban.com/subject/$it/","rate":"$score"}"""
        }
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals(expectedTag, request.url.parameters["tags"])
            assertEquals("8,10", request.url.parameters["range"])
            assertEquals("S", request.url.parameters["sort"])
            assertEquals("20", request.url.parameters["start"])
            respond("""{"data":[$data]}""")
        }))
        try {
            HomeCategoryOptions.filter { it.first != "top250" }.forEach { (key, _) ->
                expectedTag = expectedTags.getValue(key)
                val page = repo.list(key, 20)
                assertEquals(listOf(9.5, 8.0), page.items.map { it.rating }, key)
                assertTrue(page.hasMore)
                assertEquals(40, page.nextStart())
            }
        } finally { repo.close() }
    }
    @Test fun homepageScoreRangeCacheIsSeparateFromBrowseAndSurvivesOffline() = runTest {
        var offline = false
        val repo = ContentRepository(HttpClient(mockEngine {
            if (offline) error("offline")
            respond("""{"data":[
                {"title":"低分","url":"https://movie.douban.com/subject/1/","rate":"7.9"},
                {"title":"高分","url":"https://movie.douban.com/subject/2/","rate":"8.0"}
            ]}""")
        }))
        try {
            val browse = repo.browse(BrowseFilters(genre = "comedy", sort = "rating"))
            val home = repo.list("comedy")
            assertEquals(2, browse.items.size)
            assertEquals(listOf("高分"), home.items.map { it.title })
            offline = true
            assertEquals(home.copy(stale = true), repo.list("comedy"))
            assertEquals(browse.copy(stale = true), repo.browse(BrowseFilters(genre = "comedy", sort = "rating")))
        } finally { repo.close() }
    }
    @Test fun selectedHomepageAlsoEnforcesScoreThresholdOnStaleResults() = runTest {
        var offline = false
        val html = topFixture + topFixture.replace("123", "456").replace("9.1", "7.9")
        val repo = ContentRepository(HttpClient(mockEngine {
            if (offline) error("offline")
            respond(html)
        }))
        try {
            val page = repo.list("top250")
            assertEquals(listOf(9.1), page.items.map { it.rating })
            offline = true
            assertEquals(page.copy(stale = true), repo.list("top250"))
        } finally { repo.close() }
    }
    @Test fun browseCountryCombinesFiltersAndPreservesPaging() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals("电视剧,剧情,日本", request.url.parameters["tags"])
            assertEquals("2021,2021", request.url.parameters["year_range"])
            assertEquals("S", request.url.parameters["sort"])
            assertEquals("20", request.url.parameters["start"])
            respond("""{"data":[{"title":"日剧","url":"https://movie.douban.com/subject/1/","rate":"8.5"}]}""")
        }))
        try {
            val page = repo.browse(BrowseFilters(type = "tv", genre = "drama", year = "2021", sort = "rating", country = "jp"), 20)
            assertEquals("日剧", page.items.single().title)
            assertEquals(40, page.nextStart())
            assertEquals("电视剧 · 剧情 · 日本", page.label)
        } finally { repo.close() }
    }
    @Test fun browseCountryCachesStaySeparateAndInvalidCountryDoesNotRequest() = runTest {
        val tags = mutableListOf<String>()
        var offline = false
        var attempts = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            attempts++
            if (offline) error("offline")
            val tag = request.url.parameters["tags"].orEmpty()
            tags += tag
            respond("""{"data":[{"title":"$tag","url":"https://movie.douban.com/subject/1/","rate":"8.5"}]}""")
        }))
        try {
            val all = repo.browse(BrowseFilters())
            val japan = repo.browse(BrowseFilters(country = "jp"))
            assertNotEquals(all.items.single().title, japan.items.single().title)
            // This harness has no SQLite path; its memory cache is an offline fallback.
            offline = true
            assertEquals(japan.copy(stale = true), repo.browse(BrowseFilters(country = "jp")))
            assertEquals(all.copy(stale = true), repo.browse(BrowseFilters()))
            assertFailsWith<ApiError> { repo.browse(BrowseFilters(country = "unknown")) }
            assertEquals(listOf("电影", "电影,日本"), tags)
            assertEquals(4, attempts)
        } finally { repo.close() }
    }
    @Test fun explicitCountryOverridesHollywoodShortcutRegion() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals("电影,大片,中国大陆", request.url.parameters["tags"])
            respond("""{"data":[]}""")
        }))
        try {
            repo.browse(BrowseFilters(genre = "hollywood", country = "cn"))
        } finally { repo.close() }
    }
    @Test fun playerLoadsDirectlyAndRejectsForeignEpisode() = runTest {
        val paths = mutableListOf<String>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals("pomo.mom", request.url.host)
            paths += request.url.parameters["type"].orEmpty()
            respond(if (request.url.parameters["type"] == "auto_detail") pomoDetailFixture else pomoMedia())
        }))
        try {
            val info = repo.player(PlayerRequest(page = pomoPage, episode = "$pomoPage#1-1"))
            assertEquals("hls", info.kind)
            assertEquals(listOf("auto_detail", "parse"), paths)
            assertEquals(3, info.episodes.size)
            assertEquals("一位旅人和同伴踏上旅程。", info.synopsis)
            assertFailsWith<ApiError> { repo.player(PlayerRequest(page = info.pageUrl, episode = "/vodplay/2-1-1.html")) }
        } finally { repo.close() }
    }
    @Test fun episodeLabelsAndLineOrder() {
        val episodes = ContentParsers.episodes(detailFixture, "1")
        assertEquals("第 1 集", episodes.first().label)
        assertEquals(listOf(1, 2, 1), episodes.map { it.line })
        assertEquals("2020", ContentParsers.year(detailFixture))
    }
    @Test fun synopsisUsesOnlyThePlotSection() {
        assertEquals("", ContentParsers.synopsis("<h3>猜你喜欢</h3><p class=\"col-pd\">其他影片</p>"))
        assertEquals("旅人走进森林。", ContentParsers.synopsis(
            """<div class="stui-pannel"><h3>剧情简介</h3><p class="col-pd">旅人走进森林。</p></div>""",
        ))
        assertEquals("旅人 & 同伴。", ContentParsers.synopsis(
            """<div class="stui-content__desc">旅人 &amp; 同伴。<script>广告()</script></div>""",
        ))
        assertEquals("", ContentParsers.synopsis(
            """<div class="stui-pannel"><h3>剧情简介</h3><p class="col-pd">演员资料。影片的关键词是未知, </p></div>""",
        ))
    }
    @Test fun encryptedMediaAndJsonBoundary() {
        val encoded = "https%3A%2F%2Fmedia%2Fvideo.mp4%3Fv%3Da%2Bb"
        assertEquals("https://media/video.mp4?v=a+b", ContentParsers.media("""var player_x={"encrypt":1,"url":"$encoded","other":{"x":"} \""}};next();""").first)
        val base64 = Base64.encode(encoded.encodeToByteArray())
        assertEquals("video", ContentParsers.media("""var player_x={"encrypt":2,"url":"$base64"}""").second)
        listOf(
            """{"url":"https://media/v.mp4","points":1}""",
            """{"url":"https://media/v.mp4","trysee":10}""",
            """{"url":"https://media/page.html"}""",
            """{"url":"file:///video.mp4"}""",
            """{"url":"https://user:pass@media/v.mp4"}""",
            """{"url":"not-base64","encrypt":2}""",
            """{"url":"https://media/v.mp4","encrypt":9}""",
        ).forEach { assertFailsWith<ApiError> { ContentParsers.media("var player_x=$it;") } }
    }
    @Test fun invalidPageRejectedBeforeNetwork() {
        listOf("http://huolot.com/voddetail/1.html", "https://other/voddetail/1.html",
            "https://huolot.com/voddetail/1.html?x=1", "https://huolot.com/voddetail/1.html#x",
            "https://user@huolot.com/voddetail/1.html", "https://huolot.com/other").forEach {
            assertFailsWith<ApiError> { ContentParsers.pageUrl(it) }
        }
    }
    @Test fun crossSiteRedirectIsFollowed() = runTest {
        var calls = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (++calls == 1) {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other/search"))
            } else {
                assertEquals("other", request.url.host)
                assertEquals("/search", request.url.encodedPath)
                respond(pomoSearchFixture)
            }
        }) { followRedirects = false })
        try {
            assertEquals(2, repo.search("电影", "all").results.size)
            assertEquals(2, calls)
        } finally { repo.close() }
    }
    @Test fun httpRedirectWithCustomPortIsFollowed() = runTest {
        var calls = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (++calls == 1) {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://other:8080/search"))
            } else {
                assertEquals("other", request.url.host)
                assertEquals(URLProtocol.HTTP, request.url.protocol)
                assertEquals(8080, request.url.port)
                respond(pomoSearchFixture)
            }
        }) { followRedirects = false })
        try {
            assertEquals(2, repo.search("电影", "all").results.size)
            assertEquals(2, calls)
        } finally { repo.close() }
    }
    @Test fun nonWebRedirectIsRejectedBeforeRequest() = runTest {
        var calls = 0
        val repo = ContentRepository(HttpClient(mockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "file:///tmp/source.html"))
        }) { followRedirects = false })
        try {
            assertFailsWith<ApiError> { repo.search("电影", "all") }
            assertEquals(1, calls)
        } finally { repo.close() }
    }
    @Test fun redirectLoopStopsAfterFourHops() = runTest {
        var calls = 0
        val repo = ContentRepository(HttpClient(mockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/loop"))
        }) { followRedirects = false })
        try {
            val error = assertFailsWith<ApiError> { repo.search("电影", "all") }
            assertEquals("来源跳转次数过多", error.message)
            assertEquals(5, calls)
        } finally { repo.close() }
    }
    @Test fun relativeRedirectStaysOnSource() = runTest {
        var calls = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (++calls == 1) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/vodsearch/result.html"))
            else {
                assertEquals("pomo.mom", request.url.host)
                assertEquals("/vodsearch/result.html", request.url.encodedPath)
                respond(pomoSearchFixture)
            }
        }) { followRedirects = false })
        try { assertEquals(2, repo.search("电影", "all").results.size) }
        finally { repo.close() }
    }
    @Test fun oversizedSourceIsRejected() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { respond("x".repeat(2 * 1024 * 1024 + 1)) }))
        try {
            val error = assertFailsWith<ApiError> { repo.search("电影", "all") }
            assertEquals("来源页面过大", error.message)
        } finally { repo.close() }
    }
    @Test fun yearMatchIsVerifiedBeforeAutomaticPlayback() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(when {
                request.url.parameters["type"] == "auto_search" -> pomoSearchFixture
                request.url.parameters["type"] == "auto_detail" -> pomoDetailFixture
                else -> pomoMedia()
            })
        }))
        try {
            assertTrue(repo.resolve(CatalogItem("测试电影", "https://movie.douban.com/subject/123/", "2020")).playable)
            assertFalse(repo.resolve(CatalogItem("测试电影", "https://movie.douban.com/subject/123/", "2019")).playable)
        } finally { repo.close() }
    }
    @Test fun cancellationPropagates() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { awaitCancellation() }))
        try {
            val job = async { repo.search("剧集", "all") }
            runCurrent()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        } finally { repo.close() }
    }
    @Test fun ambiguousTitleDoesNotAutoPlay() = runTest {
        val html = pomoSearch(pomoRow("测试电影", year = "2020"), pomoRow("测试电影", 2, "2021"))
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(if (request.url.parameters["type"] == "auto_search") html else pomoDetailFixture.replace("\"year\":\"2020\"", "\"year\":\"${if (request.url.parameters["id"] == "1") "2020" else "2021"}\""))
        }))
        try { assertFalse(repo.resolve(CatalogItem("测试电影", "https://movie.douban.com/subject/123/")).playable) }
        finally { repo.close() }
    }
    @Test fun coreTitleAndTitleYearParseRemakeSuffixes() {
        assertEquals("美丽人生", ContentParsers.coreTitle("美丽人生1997"))
        assertEquals("美丽人生", ContentParsers.coreTitle("美丽人生（1997）"))
        assertEquals("美丽人生", ContentParsers.coreTitle("美丽人生 1997"))
        assertEquals("美丽人生", ContentParsers.coreTitle("美丽人生"))
        // A numeric-only title is not treated as a year suffix.
        assertEquals("2012", ContentParsers.coreTitle("2012"))
        assertEquals("1997", ContentParsers.titleYear("美丽人生1997"))
        assertEquals("1997", ContentParsers.titleYear("美丽人生（1997）"))
        assertNull(ContentParsers.titleYear("美丽人生"))
        assertNull(ContentParsers.titleYear("2012"))
    }
    @Test fun resolveSearchTypeFollowsCatalogKind() {
        assertEquals("movie", ContentParsers.searchTypeFor("movie"))
        assertEquals("tv", ContentParsers.searchTypeFor("tv"))
        assertEquals("movie", ContentParsers.searchTypeFor("documentary"))
        assertEquals("movie", ContentParsers.searchTypeFor("animation"))
        assertEquals("all", ContentParsers.searchTypeFor("variety"))
    }
    @Test fun castOverlapScoreUsesNormalizedNames() {
        assertEquals(2, ContentParsers.castOverlapScore(listOf("罗伯托·贝尼尼", "尼可莱塔 布拉斯基"), "主演：罗伯托贝尼尼 尼可莱塔·布拉斯基"))
        assertEquals(0, ContentParsers.castOverlapScore(listOf("张三"), "李四 王五"))
        assertEquals(0, ContentParsers.castOverlapScore(emptyList(), "张三"))
    }
    @Test fun yearSuffixWinsOverConflictingSameTitleSources() = runTest {
        val searchHtml = pomoSearch(pomoRow("美丽人生2023", 1, "2023"),
            pomoRow("美丽人生1997", 4, "1997"), pomoRow("美丽人生", 5, "2008"))
        val detail1997 = pomoDetailFixture.replace("2020", "1997")
        val requests = mutableListOf<String>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            requests += request.url.parameters["id"].orEmpty()
            respond(when {
                request.url.parameters["type"] == "auto_search" -> searchHtml
                request.url.parameters["id"] == "4" -> detail1997
                else -> error("unexpected request ${request.url.encodedPath}")
            })
        }))
        try {
            val result = repo.resolve(CatalogItem("美丽人生", "https://movie.douban.com/subject/9/", "1997"))
            assertTrue(result.playable)
            assertEquals(pomoPage.replace("id=1", "id=4"), result.pageUrl)
            // Conflicting-year and language-variant entries must never be fetched.
            assertTrue(requests.none { it == "1" || it == "5" })
        } finally { repo.close() }
    }
    @Test fun blankYearNeverGuessesBetweenYearSuffixedRemakes() = runTest {
        val searchHtml = pomoSearch(pomoRow("美丽人生1997", 4, "1997"), pomoRow("美丽人生2023", 1, "2023"))
        val requests = mutableListOf<String>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            requests += request.url.parameters["type"].orEmpty()
            respond(searchHtml)
        }))
        try {
            val result = repo.resolve(CatalogItem("美丽人生", "https://movie.douban.com/subject/9/"))
            assertFalse(result.playable)
            assertTrue(requests.none { it == "auto_detail" })
        } finally { repo.close() }
    }
    @Test fun castOverlapBreaksSameYearTitleTie() = runTest {
        val searchHtml = pomoSearch(pomoRow("美丽人生", 1, "1997", "其他演员"),
            pomoRow("美丽人生", 2, "1997", "罗伯托·贝尼尼 尼可莱塔·布拉斯基"))
        val detail = pomoDetailFixture.replace("2020", "1997")
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(when {
                request.url.parameters["type"] == "auto_search" -> searchHtml
                request.url.parameters["type"] == "auto_detail" -> detail
                else -> error("unexpected request ${request.url.encodedPath}")
            })
        }))
        try {
            val result = repo.resolve(CatalogItem(
                "美丽人生",
                "https://movie.douban.com/subject/9/",
                "1997",
                casts = listOf("罗伯托·贝尼尼", "尼可莱塔·布拉斯基"),
                kind = "movie",
            ))
            assertTrue(result.playable)
            assertEquals(pomoPage.replace("id=1", "id=2"), result.pageUrl)
        } finally { repo.close() }
    }
}
