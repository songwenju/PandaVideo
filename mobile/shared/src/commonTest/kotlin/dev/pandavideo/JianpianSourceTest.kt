package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.*
import dev.pandavideo.data.user.UserStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.*

private val jpPage = JianpianSource.address("1")
private val jpSearch = """{"code":1,"data":[{"id":1,"title":"测试电影","top_category":{"id":1},"years":[{"year":"2020"}],"actors":["张三"],"types":["剧情"]}]}"""
private fun jpDetail(url: String = "https://media.test/jp.m3u8", year: String = "2020") =
    """{"code":1,"data":{"id":1,"title":"测试电影","year":"$year","area":"美国","source_list_source":[{"source_list":[{"source_name":"第 1 集","url":"$url"},{"source_name":"第 2 集","url":"https://media.test/second.m3u8"}]}]}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class JianpianSourceTest {
    @Test fun backgroundMirrorsRetainProviderPathsAndRejectRemakeYears() = runTest {
        var searchCount = 0
        var parses = 0
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "pomo.mom" -> when (request.url.parameters["type"]) {
                    "auto_search" -> {
                        searchCount++
                        respond(pomoSearch(pomoRow("测试电影"), pomoRow("测试电影", id = 2, year = "2024"),
                            pomoRow("测试电影", id = 3, actor = "王五")))
                    }
                    "auto_detail" -> respond(pomoDetailFixture)
                    "parse" -> { parses++; respond(pomoMedia()) }
                    else -> error("Unexpected Pomo request")
                }
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.encodedPath.contains("search") -> respond(jpSearch)
                request.url.host.endsWith("zxfmj.com") -> respond(jpDetail())
                request.url.host == "media" && request.url.encodedPath != "/stream.m3u8" -> respond("<html>expired</html>")
                else -> respond("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
            }
        }))
        try {
            val initial = repo.player(PlayerRequest(page = jpPage, reuseSourceInfo = true))
            assertEquals(0, searchCount)
            val updated = repo.supplementPlayback(initial,
                CatalogItem("测试电影", "", "2020", casts = listOf("张三"), isSeries = true))
            assertEquals(initial.url, updated.url)
            assertEquals(initial.selected, updated.selected)
            assertTrue(updated.episodes.any { it.path == "$pomoPage#1-1" })
            assertFalse(updated.episodes.any { it.path.contains("id=2") })
            assertFalse(updated.episodes.any { it.path.contains("id=3") })
            assertEquals(updated.episodes.map { it.path }.toSet().size, updated.episodes.size)
            val target = updated.episodes.first { it.path == "$pomoPage#1-1" }
            val switched = repo.player(PlayerRequest(page = jpPage, episode = target.path,
                allowLineFallback = false, reuseSourceInfo = true))
            assertEquals(target.path, switched.selected)
            assertEquals(target.line, switched.episodes.first { it.path == switched.selected }.line)
            assertEquals(1, parses, "A Pomo mirror must use Pomo parsing even under a Jianpian root")
            val repeated = repo.supplementPlayback(switched,
                CatalogItem("测试电影", "", "2020", casts = listOf("张三"), isSeries = true))
            assertEquals(updated.episodes, repeated.episodes, "Discovery never duplicates appended lines")
            val refreshed = repo.player(PlayerRequest(page = jpPage, episode = target.path,
                allowLineFallback = false))
            assertEquals(target.path, refreshed.selected, "Retry retains verified foreign episode identities")
        } finally { repo.close() }
    }

    @Test fun lineMeasurementsUseRealPrefixesAndReuseSelectedProbe() = runTest {
        val probes = mutableListOf<String>()
        val body = """{"code":1,"data":{"title":"测试电影","source_list_source":[
            {"name":"线路一","source_list":[{"source_name":"第 1 集","url":"https://media.test/jp.m3u8"}]},
            {"name":"线路二","source_list":[{"source_name":"第 1 集","url":"https://media.test/alternate.m3u8"}]},
            {"name":"失效线路","source_list":[{"source_name":"第 1 集","url":"https://media.test/broken.m3u8"}]}]}}"""
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host == "media.test" -> {
                    probes += request.url.encodedPath
                    if (request.url.encodedPath == "/broken.m3u8") respond("<html>error</html>")
                    else respond("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
                }
                else -> respond(body)
            }
        }))
        try {
            val info = repo.player(PlayerRequest(page = jpPage))
            assertEquals(true, info.lineProbes[1]?.available)
            assertNotNull(info.lineProbes[1]?.elapsedMs)
            val measured = repo.probeLines(info)
            assertEquals(true, measured[2]?.available)
            assertNotNull(measured[2]?.elapsedMs)
            assertEquals(false, measured[3]?.available)
            assertNull(measured[3]?.elapsedMs)
            repo.player(PlayerRequest(page = jpPage))
            assertEquals(1, probes.count { it == "/jp.m3u8" },
                "Playback and measurements must reuse the same successful prefix")
        } finally { repo.close() }
    }

    @Test fun categoryRowsPreserveSourceIdentityBadgesAndScoreFloor() {
        val body = """{"code":1,"data":[
            {"id":11,"title":"高分剧","score":"8.8","path":"/cover.jpg","mask":"第20集(全)"},
            {"id":12,"title":"低分剧","score":"7.9"},
            {"id":13,"title":"未评分","score":"0.0"},
            {"id":"bad","title":"错误身份","score":"9.5"},
            {"id":14,"title":"预告片","score":"9.5"},
            {"id":11,"title":"重复","score":"8.8"}]}"""
        val item = JianpianSource.catalog(body, "tv", "https://images.test").single()
        assertEquals(JianpianSource.address("11"), item.url)
        assertEquals(item.url, item.playbackPageUrl)
        assertEquals("https://images.test/cover.jpg", item.cover)
        assertEquals("第20集(全)", item.remark)
        assertTrue(item.year.isEmpty() && item.countries.isEmpty())
        assertEquals(true, item.isSeries)
        assertFailsWith<ApiError> { JianpianSource.catalog("""{"code":1,"data":{}}""", "tv", null) }
        val episode = JianpianSource.episodes(jpDetail().replace(
            "\"source_list\":[", "\"name\":\"高速蓝光\",\"source_list\":["), jpPage).first().first
        assertEquals("高速蓝光", episode.lineName)
    }

    @Test fun homepageUsesVerifiedCategoryAndUpdateParametersWithIndependentCursor() = runTest {
        val paths = mutableListOf<Url>()
        val client = JianpianClient(fetch = { address, _, _ ->
            val url = Url(address)
            when {
                url.encodedPath.endsWith("/init") -> """{"code":1,"data":{"secret":"s"}}"""
                url.encodedPath.endsWith("/resourceDomainConfig") -> """{"code":1,"data":{"imgDomain":"images.test"}}"""
                else -> {
                    paths += url
                    """{"code":1,"data":[{"id":11,"title":"高分剧","score":"8.8","path":"/cover.jpg"}]}"""
                }
            }
        }, clock = { testScheduler.currentTime })
        client.catalog("animation", 24, "update")
        val url = paths.single()
        assertEquals("/api/crumb/list", url.encodedPath)
        assertEquals("3", url.parameters["category_id"])
        assertEquals("2", url.parameters["page"])
        assertEquals("update", url.parameters["sort"])
        assertEquals("24", url.parameters["limit"])
    }

    @Test fun hotCategoriesKeepProviderOrderUnratedRowsAndRawPagination() = runTest {
        val paths = mutableListOf<Url>()
        val client = JianpianClient(fetch = { address, _, _ ->
            val url = Url(address)
            when {
                url.encodedPath.endsWith("/init") -> """{"code":1,"data":{"secret":"s"}}"""
                url.encodedPath.endsWith("/resourceDomainConfig") -> """{"code":1,"data":{"imgDomain":"images.test"}}"""
                else -> {
                    paths += url
                    """{"code":1,"data":[${(1..24).joinToString(",") {
                        """{"id":$it,"title":"${if (it == 24) "预告" else "新片$it"}","score":"${if (it == 1) "7.9" else "0"}"}"""
                    }}]}"""
                }
            }
        }, clock = { testScheduler.currentTime })
        for ((kind, category) in listOf("movie" to "1", "tv" to "2", "animation" to "3",
            "variety" to "4", "documentary" to "50")) {
            val page = client.catalog(kind, 24, "hot")
            val url = paths.last()
            assertEquals(category, url.parameters["category_id"])
            assertEquals("hot", url.parameters["sort"])
            assertEquals("2", url.parameters["page"])
            assertEquals(23, page.items.size)
            assertEquals(7.9, page.items.first().rating)
            assertNull(page.items[1].rating)
            assertEquals((1..23).map { JianpianSource.address(it.toString()) }, page.items.map { it.url })
            assertEquals(48, page.nextStart())
            assertTrue(page.hasMore, "Filtered display rows must not alter the provider cursor")
        }
        assertFailsWith<IllegalArgumentException> { client.catalog("movie", 20, "hot") }
    }

    @Test fun sourceDetailsAreSharedAcrossConcurrentResourceChecksAndPlayback() = runTest {
        var details = 0
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.host == "media.test" -> respond("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
                else -> { details++; delay(50); respond(jpDetail()) }
            }
        }))
        try {
            val item = CatalogItem("测试电影", jpPage, playbackPageUrl = jpPage)
            coroutineScope { List(3) { async { repo.playbackMatch(item) } }.awaitAll() }
            repo.player(PlayerRequest(page = jpPage))
            assertEquals(1, details, "Catalog checks and player must reuse one validated detail response")
        } finally { repo.close() }
    }

    @Test fun nativeOnlyDetailIsHiddenButNetworkFailureRetainsSourceCatalogRow() = runTest {
        for (offline in listOf(false, true)) {
            val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
                when {
                    request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                    offline -> respond("", HttpStatusCode.ServiceUnavailable)
                    else -> respond("""{"code":1,"data":{"source_list_source":[{"source_list":[{"source_name":"正片","url":"ftp://native/movie.mp4"}]}]}}""")
                }
            }))
            try {
                val item = CatalogItem("原生资源", jpPage)
                val missing = mutableListOf<CatalogItem>()
                repo.inspectCatalog(listOf(item)) { missing += it }
                assertEquals(if (offline) emptyList() else listOf(item), missing)
            } finally { repo.close() }
        }
    }

    @Test fun posterHostComesFromResourceConfigurationAndIsReused() = runTest {
        var configurations = 0
        val client = JianpianClient(fetch = { address, _, _ ->
            when {
                address.contains("/sys/init") -> """{"code":1,"data":{"secret":"s"}}"""
                address.contains("/settings/resourceDomainConfig") -> {
                    configurations++
                    """{"code":1,"data":{"imgDomain":"bad.test/path,images.test"}}"""
                }
                else -> jpSearch.replace("\"types\":", "\"thumbnail\":\"/upload/cover.jpg\",\"types\":")
            }
        }, clock = { testScheduler.currentTime })
        repeat(2) {
            assertEquals("https://images.test/upload/cover.jpg",
                client.search("测试电影", "movie").results.single().thumbnailUrl)
        }
        assertEquals(1, configurations)
    }

    @Test fun configuredPosterHostReplacesRetiredAbsoluteHostWithoutChangingPath() = runTest {
        val client = JianpianClient(fetch = { address, _, _ ->
            when {
                address.contains("/sys/init") -> """{"code":1,"data":{"secret":"s"}}"""
                address.contains("/settings/resourceDomainConfig") ->
                    """{"code":1,"data":{"imgDomain":"images.test"}}"""
                else -> jpSearch.replace("\"types\":",
                    "\"thumbnail\":\"https://retired.test/upload/cover.jpg?size=large\",\"types\":")
            }
        }, clock = { testScheduler.currentTime })
        assertEquals("https://images.test/upload/cover.jpg?size=large",
            client.search("测试电影", "movie").results.single().thumbnailUrl)
    }

    @Test fun unavailableImageConfigurationKeepsSearchAndCanRecoverOnNextRequest() = runTest {
        var offline = true
        val client = JianpianClient(fetch = { address, _, _ ->
            when {
                address.contains("/sys/init") -> """{"code":1,"data":{"secret":"s"}}"""
                address.contains("/settings/resourceDomainConfig") -> {
                    if (offline) throw ApiError("offline")
                    """{"code":1,"data":{"imgDomain":"images.test"}}"""
                }
                else -> jpSearch.replace("\"types\":", "\"thumbnail\":\"/upload/cover.jpg\",\"types\":")
            }
        }, clock = { testScheduler.currentTime })
        assertEquals("测试电影", client.search("测试电影", "movie").results.single().title)
        offline = false
        assertEquals("https://images.test/upload/cover.jpg",
            client.search("测试电影", "movie").results.single().thumbnailUrl)
    }

    @Test fun catalogRetriesOtherProviderWhenFastSearchCannotMatchOrHasNoEpisodes() = runTest {
        for (mode in listOf("unrelated", "nativeOnly", "primaryEmpty")) {
            val title = "哪吒之魔童闹海"
            val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
                when {
                    request.url.host == "pomo.mom" -> {
                        if (request.url.parameters["type"] == "auto_search") {
                            delay(100)
                            respond(pomoSearch(pomoRow(title, year = "2025")))
                        } else respond(pomoDetailFixture.replace("测试电影", title).replace("2020", "2025"))
                    }
                    request.url.encodedPath.endsWith("/init") ->
                        respond("""{"code":1,"data":{"secret":"s"}}""")
                    request.url.encodedPath.contains("/search/") ->
                        respond(if (mode == "primaryEmpty") """{"code":1,"data":[]}"""
                            else jpSearch.replace("测试电影", if (mode == "unrelated") "哪吒之魔童降世" else title)
                                .replace("2020", "2025"))
                    else -> respond("""{"code":1,"data":{"title":"$title","year":"2025","source_list_source":[]}}""")
                }
            }))
            try {
                val item = CatalogItem(title, "https://movie.douban.com/subject/34780991/", kind = "movie")
                val match = repo.resolve(item)
                assertTrue(match.playable, mode)
                assertEquals(pomoPage, match.pageUrl, mode)
            } finally { repo.close() }
        }
    }

    @Test fun catalogAlsoRetriesJianpianWhenPomoReturnsOnlyRelatedTitles() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "pomo.mom" -> respond(pomoSearch(pomoRow("其他影片")))
                request.url.encodedPath.endsWith("/init") -> {
                    delay(100)
                    respond("""{"code":1,"data":{"secret":"s"}}""")
                }
                request.url.encodedPath.contains("/search/") -> respond(jpSearch)
                else -> respond(jpDetail())
            }
        }))
        try {
            assertEquals(jpPage, repo.resolve(CatalogItem("测试电影", "", "2020")).pageUrl)
        } finally { repo.close() }
    }

    @Test fun stableReferencesRejectForeignHostsPortsAndDuplicateIds() {
        assertEquals("$jpPage#1-1", ContentParsers.pageUrl("$jpPage#1-1"))
        for (value in listOf(jpPage.replace("https:", "http:"), "$jpPage&id=2", "$jpPage#0-1",
            jpPage.replace("japi.zxfmj.com", "other.test"), jpPage.replace(".com/", ".com:5800/"),
            "$jpPage&url=https://other.test"))
            assertFailsWith<ApiError> { ContentParsers.pageUrl(value) }
        assertTrue(JianpianSource.episodes(jpDetail("ftp://server/video.mp4"), jpPage)
            .none { it.second.startsWith("ftp") })
    }

    @Test fun fastestMirrorUsesItsOwnSecretAndRetriesMalformedResponses() = runTest {
        val requests = mutableListOf<String>()
        var broken = false
        val client = JianpianClient(fetch = { address, _, headers ->
            val url = Url(address)
            val secret = if (url.host.startsWith("pv2026")) "fast-secret" else "slow-secret"
            if (url.encodedPath.endsWith("/init")) {
                delay(if (secret == "fast-secret") 10 else 50)
                """{"code":1,"data":{"secret":"$secret"}}"""
            } else {
                assertEquals("/api/v2/search/videoV2", url.encodedPath)
                assertEquals("测试电影", url.parameters["key"])
                requests += url.host
                assertEquals(("504" + headers["timestamp"] + secret).encodeUtf8().md5().hex(), headers["signature"])
                assertEquals("pc", headers["platform"])
                if (broken && secret == "fast-secret") """{"code":1,"data":{}}""" else jpSearch
            }
        }, clock = { testScheduler.currentTime })
        assertEquals(1, client.search("测试电影", "movie").results.size)
        assertEquals(listOf("pv2026.zxfmj.com"), requests)
        broken = true
        assertEquals(1, client.search("测试电影", "movie").results.size)
        assertEquals(listOf("pv2026.zxfmj.com", "pv2026.zxfmj.com", "japi.zxfmj.com"), requests)
    }

    @Test fun searchIncludesJianpianEvenWhenPomoHasResultsAndPrefersDirectRelease() = runTest {
        var backupRequests = 0
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "pomo.mom" -> {
                    delay(500)
                    respond(pomoSearch(pomoRow("其他电影")))
                }
                request.url.encodedPath.endsWith("/init") -> {
                    backupRequests++
                    respond("""{"code":1,"data":{"secret":"s"}}""")
                }
                else -> { backupRequests++; respond(jpSearch) }
            }
        }))
        try {
            val result = repo.search("测试电影", "movie")
            assertEquals(setOf(jpPage, pomoPage), result.results.map { it.detailUrl }.toSet())
            assertTrue(backupRequests > 0, "A successful Pomo search must not suppress Jianpian")
            assertFalse(result.stale)
        } finally { repo.close() }
    }

    @Test fun catalogPrefersVerifiedJianpianAndRetainsEveryDirectLine() = runTest {
        var pomoDetails = 0
        val body = """{"code":1,"data":{"title":"测试电影","year":"2020","source_list_source":[
            {"name":"蓝光","source_list":[{"source_name":"正片","url":"https://media.test/one.m3u8"}]},
            {"name":"备用","source_list":[{"source_name":"正片","url":"https://media.test/two.mp4"}]}]}}"""
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "pomo.mom" -> {
                    if (request.url.parameters["type"] == "auto_search") respond(pomoSearch(pomoRow("测试电影")))
                    else { pomoDetails++; respond(pomoDetailFixture) }
                }
                request.url.host == "media.test" -> respond("#EXTM3U\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.encodedPath.contains("/search/") -> respond(jpSearch)
                else -> respond(body)
            }
        }))
        try {
            assertEquals(jpPage, repo.resolve(CatalogItem("测试电影", "", "2020")).pageUrl)
            val info = repo.player(PlayerRequest(page = jpPage))
            assertEquals(listOf("蓝光", "备用"), info.episodes.map { it.lineName })
            assertEquals(0, pomoDetails)
        } finally { repo.close() }
    }

    @Test fun preferredPomoTimeoutCancelsRequestAndFallsBackToJianpian() = runTest {
        var cancelled = false
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            if (request.url.host == "pomo.mom") {
                try { awaitCancellation() } finally { cancelled = true }
            } else if (request.url.encodedPath.endsWith("/init"))
                respond("""{"code":1,"data":{"secret":"test-secret"}}""")
            else respond(jpSearch)
        }))
        try {
            assertEquals("荐片", repo.search("测试电影", "movie").results.single().source)
            assertTrue(cancelled)
        } finally { repo.close() }
    }
    @Test fun failedApisDiscoverReplacementWithinTheSameOperation() = runTest {
        val hosts = mutableListOf<String>()
        val client = JianpianClient(fetch = { address, _, _ ->
            val url = Url(address)
            when {
                url.encodedPath.endsWith("domain.txt") -> "replacement.test"
                url.host in setOf("dns.alidns.com", "doh.pub", "1.1.1.1") -> """{"Answer":[]}"""
                url.encodedPath.endsWith("/init") -> """{"code":1,"data":{"secret":"s"}}"""
                else -> {
                    hosts += url.host
                    if (url.host == "pv2026.replacement.test") jpSearch
                    else throw ApiError("offline")
                }
            }
        }, clock = { testScheduler.currentTime })
        assertEquals(1, client.search("测试电影", "movie").results.size)
        assertEquals("pv2026.replacement.test", hosts.last())
        assertEquals(3, hosts.size)
    }

    @Test fun emptyPrimaryWaitsForBackupAndPartialFailureDoesNotHideCatalog() = runTest {
        var backupFails = false
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "pomo.mom" -> respond(pomoSearch())
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                backupFails -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> { delay(50); respond(jpSearch) }
            }
        }))
        try {
            assertEquals("荐片", repo.search("测试电影", "movie").results.single().source)
            backupFails = true
            val result = repo.search("其他电影", "movie")
            assertTrue(result.results.isEmpty())
            assertTrue(result.stale)
            val item = CatalogItem("其他电影", "", "2020")
            val missing = mutableListOf<CatalogItem>()
            repo.inspectCatalog(listOf(item)) { missing += it }
            assertTrue(missing.isEmpty())
        } finally { repo.close() }
    }

    @Test fun deadMediaFallsBackToSameEpisodeOfTheSameRelease() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "media.test" -> respond("<html>login required</html>")
                request.url.host == "pomo.mom" -> respond(when (request.url.parameters["type"]) {
                    "auto_search" -> pomoSearch(pomoRow("测试电影"))
                    "auto_detail" -> pomoDetailFixture
                    else -> pomoMedia()
                })
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                else -> respond(jpDetail())
            }
        }))
        try {
            val info = repo.player(PlayerRequest(page = jpPage, episode = "$jpPage#1-1"))
            assertEquals("$pomoPage#1-1", info.selected)
            assertTrue(info.source.startsWith("Pomo"))
            assertEquals(pomoPage, info.pageUrl)
            assertFailsWith<ApiError> { repo.player(PlayerRequest(page = jpPage, episode = "${JianpianSource.address("2")}#1-1")) }
        } finally { repo.close() }
    }

    @Test fun directHlsAndSavedProgressSurviveMirrorChanges() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "media.test" -> {
                    assertEquals("bytes=0-8191", request.headers[HttpHeaders.Range])
                    respond("#EXTM3U\n#EXTINF:6,\nsegment.ts\n")
                }
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                else -> respond(jpDetail())
            }
        }))
        try {
            val info = repo.player(PlayerRequest(page = jpPage))
            assertEquals("hls", info.kind)
            assertEquals("$jpPage#1-0", info.selected)
            assertEquals("美国", info.region)
            val store = UserStore(FakeStorage())
            store.recordHistory("测试电影", jpPage, "", "2020", "第 1 集", info.selected)
            store.saveProgress(jpPage, info.selected, "第 1 集", 1000, 10000)
            val restored = UserStore(FakeStorage())
            restored.importBackup(store.exportBackup())
            assertEquals(store.progress.value, restored.progress.value)
        } finally { repo.close() }
    }

    @Test fun anotherReleaseIsNeverUsedAsPlaybackFallback() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.host == "media.test" -> respond("", HttpStatusCode.ServiceUnavailable)
                request.url.host == "pomo.mom" -> respond(pomoSearch(pomoRow("测试电影", year = "2021")))
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                else -> respond(jpDetail())
            }
        }))
        try { assertFailsWith<ApiError> { repo.player(PlayerRequest(page = jpPage)) } }
        finally { repo.close() }
    }
}
