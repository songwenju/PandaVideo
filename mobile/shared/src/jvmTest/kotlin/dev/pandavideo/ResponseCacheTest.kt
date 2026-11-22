package dev.pandavideo

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.platform.currentEpochMillis
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.ParserRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

class ResponseCacheTest {
    private suspend fun database(block: suspend (String) -> Unit) {
        val file = File.createTempFile("pandavideo-response-", ".db")
        try { block(file.absolutePath) } finally { file.delete() }
    }

    private fun expireResponses(path: String, age: Long) {
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("UPDATE response_cache SET saved_at = ?").use {
                it.bindLong(1, currentEpochMillis() - age)
                it.step()
            }
        }
    }

    @Test fun searchRatingsUseExactLocalCatalogAndPersistVerifiedOnlineScores() = runTest {
        database { path ->
            val old = dev.pandavideo.core.model.SearchItem("西游记86版", "西瓜",
                detailUrl = pomoPage, year = "1986", playable = true)
            val newer = old.copy(title = "西游记2010", year = "2010", detailUrl = "$pomoPage#1-0")
            val db = CatalogDatabase(path)
            db.write("tv:0", dev.pandavideo.core.model.CatalogPage(listOf(
                dev.pandavideo.core.model.CatalogItem("西游记", "https://movie.douban.com/subject/2156663/",
                    year = "1986", rating = 9.7, kind = "tv"),
            ), 0, 20, false), currentEpochMillis())
            var requests = 0
            val first = ContentRepository(HttpClient(mockEngine { request ->
                requests++
                if (request.url.encodedPath.endsWith("subject_suggest"))
                    respond("""[{"id":"2","type":"movie","title":"西游记","year":"2010","episode":"52"}]""")
                else respond("""{"title":"西游记","year":"2010","is_tv":true,"rating":{"value":6.0}}""")
            }), db)
            val input = dev.pandavideo.core.model.SearchResult(listOf(newer, old))
            val expected = try {
                // SQLite runs on a real dispatcher. Keep the optional lookup budget on a
                // real clock too, so virtual time cannot expire it before disk I/O resumes.
                withContext(Dispatchers.Default) { first.searchRatings(input, "西游记") }.also {
                    assertEquals(listOf(9.7, 6.0), it.results.map { row -> row.rating })
                    assertEquals(2, requests)
                }
            } finally { first.close() }
            val restarted = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("", HttpStatusCode.Forbidden)
            }), CatalogDatabase(path))
            try {
                assertEquals(expected, withContext(Dispatchers.Default) { restarted.searchRatings(input, "西游记") })
                assertEquals(2, requests, "Local catalog and persisted score must survive restart offline")
            } finally { restarted.close() }
        }
    }

    @Test fun yearlessSeedSubjectDetailsSupplyOnlyTheMatchingReleaseScore() = runTest {
        database { path ->
            val db = CatalogDatabase(path)
            db.write("tv:0", dev.pandavideo.core.model.CatalogPage(listOf(
                dev.pandavideo.core.model.CatalogItem("西游记", "https://movie.douban.com/subject/2156663/",
                    rating = 9.7),
            ), 0, 20, false), currentEpochMillis())
            val repo = ContentRepository(HttpClient(mockEngine { request ->
                if (request.url.encodedPath.endsWith("subject_suggest")) respond("[]")
                else {
                    assertEquals("/rexxar/api/v2/tv/2156663", request.url.encodedPath)
                    respond("""{"title":"西游记","year":"1986","is_tv":true,"rating":{"value":9.7}}""")
                }
            }), db)
            try {
                val first = dev.pandavideo.core.model.SearchItem("西游记86版", "西瓜",
                    detailUrl = pomoPage, year = "1986", playable = true)
                val input = dev.pandavideo.core.model.SearchResult(listOf(first,
                    first.copy(title = "西游记2010", year = "2010", detailUrl = "$pomoPage#1-0")))
                val result = withContext(Dispatchers.Default) { repo.searchRatings(input, "西游记") }
                assertEquals(listOf(9.7, null), result.results.map { it.rating })
            } finally { repo.close() }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun expiredCatalogAppearsBeforeNetworkAfterRestart() = runTest {
        database { path ->
            val filters = BrowseFilters(type = "tv", sort = "hot")
            val first = ContentRepository(HttpClient(mockEngine {
                respond("""{"data":[{"title":"旧剧集","url":"https://movie.douban.com/subject/123/","rate":"8.5"}]}""")
            }), CatalogDatabase(path))
            val old = try { first.browse(filters) } finally { first.close() }
            BundledSQLiteDriver().open(path).use { db ->
                db.prepare("UPDATE catalog_pages SET saved_at = ?").use {
                    it.bindLong(1, currentEpochMillis() - 16L * 60 * 1000)
                    it.step()
                }
            }
            val networkStarted = CompletableDeferred<Unit>()
            val releaseNetwork = CompletableDeferred<Unit>()
            val cached = CompletableDeferred<dev.pandavideo.core.model.CatalogPage>()
            val second = ContentRepository(HttpClient(mockEngine {
                assertTrue(cached.isCompleted, "Disk snapshot must be published before the network starts")
                networkStarted.complete(Unit)
                releaseNetwork.await()
                respond("""{"data":[{"title":"新剧集","url":"https://movie.douban.com/subject/456/","rate":"9.0"}]}""")
            }), CatalogDatabase(path))
            try {
                val request = async { second.browse(filters, onCached = { cached.complete(it) }) }
                networkStarted.await()
                assertEquals(old.copy(stale = true), cached.await())
                assertEquals("tv", cached.await().items.single().kind)
                assertFalse(request.isCompleted)
                releaseNetwork.complete(Unit)
                assertEquals("新剧集", request.await().items.single().title)
            } finally { releaseNetwork.complete(Unit); second.close() }
        }
    }

    @Test fun explicitCatalogRefreshSkipsSnapshotAndFreshRestartSkipsNetwork() = runTest {
        database { path ->
            var requests = 0
            fun repo() = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("""{"data":[{"title":"影片$requests","url":"https://movie.douban.com/subject/123/","rate":"8.5"}]}""")
            }), CatalogDatabase(path))
            val first = repo()
            val old = try { first.browse(BrowseFilters()) } finally { first.close() }
            val second = repo()
            try {
                var snapshot: dev.pandavideo.core.model.CatalogPage? = null
                assertEquals(old, second.browse(BrowseFilters(), onCached = { snapshot = it }))
                assertEquals(old, snapshot)
                assertEquals(1, requests)
                snapshot = null
                val refreshed = second.browse(BrowseFilters(), refresh = true, onCached = { snapshot = it })
                assertNull(snapshot)
                assertEquals("影片2", refreshed.items.single().title)
                assertEquals(2, requests)
            } finally { second.close() }
        }
    }

    @Test fun cachedHomepageStillFiltersLegacyScoresAndRestoresCategory() = runTest {
        database { path ->
            val db = CatalogDatabase(path)
            db.write("tv:0", dev.pandavideo.core.model.CatalogPage(listOf(
                dev.pandavideo.core.model.CatalogItem("高分剧", "https://movie.douban.com/subject/123/", rating = 9.0),
                dev.pandavideo.core.model.CatalogItem("低分剧", "https://movie.douban.com/subject/456/", rating = 7.0),
            ), 0, 20, false), currentEpochMillis())
            val repo = ContentRepository(HttpClient(mockEngine { error("Fresh cache must not access the network") }), db)
            try {
                var snapshot: dev.pandavideo.core.model.CatalogPage? = null
                val page = repo.list("tv", onCached = { snapshot = it })
                assertEquals(page.items, snapshot?.items)
                assertTrue(snapshot!!.stale)
                assertFalse(snapshot.hasMore, "Legacy publication cannot page past pending source merging")
                assertEquals("高分剧", snapshot!!.items.single().title)
                assertEquals("tv", snapshot.items.single().kind)
            } finally { repo.close() }
        }
    }

    @Test fun allCategoriesHotListsAndFiltersSurviveRestart() = runTest {
        database { path ->
            var requests = 0
            val selections = listOf(
                BrowseFilters(genre = "comedy", sort = "rating"),
                BrowseFilters(type = "animation", sort = "rating"),
                BrowseFilters(type = "documentary", genre = "nature", sort = "rating"),
                BrowseFilters(type = "tv", sort = "hot"),
                BrowseFilters(sort = "hot"),
                BrowseFilters(genre = "scifi", year = "2020", sort = "latest"),
            )
            val first = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("""{"data":[{"title":"影片","url":"https://movie.douban.com/subject/123/","rate":"8.5"}]}""")
            }), CatalogDatabase(path))
            val saved = try { selections.map { first.browse(it) } } finally { first.close() }
            val second = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("", HttpStatusCode.Forbidden)
            }), CatalogDatabase(path))
            try {
                assertEquals(saved, selections.map { second.browse(it) })
                assertEquals(selections.size, requests)
            } finally { second.close() }
        }
    }

    @Test fun normalizedSearchPersistsAndTypesRemainSeparate() = runTest {
        database { path ->
            var requests = 0
            fun repo() = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
                when {
                    request.url.host == "pomo.mom" -> { requests++; respond(pomoSearchFixture) }
                    request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                    request.url.encodedPath.endsWith("/resourceDomainConfig") ->
                        respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
                    else -> respond("""{"code":1,"data":[]}""")
                }
            }), CatalogDatabase(path))
            val first = repo()
            val expected = try { first.search("《测试 电 影》", "all") } finally { first.close() }
            val second = repo()
            try {
                assertEquals(expected, second.search("测试电影", "all"))
                assertEquals(1, requests)
                second.search("测试电影", "tv")
                assertEquals(2, requests)
            } finally { second.close() }
        }
    }

    @Test fun expiredSearchFallsBackOnForbiddenAndChallengeWithoutOverwritingGoodData() = runTest {
        database { path ->
            var mode = 0
            val repo = ContentRepository(HttpClient(mockEngine {
                when (mode) {
                    0 -> respond(pomoSearchFixture)
                    1 -> respond("", HttpStatusCode.Forbidden)
                    else -> respond("<html>captcha</html>")
                }
            }), CatalogDatabase(path))
            try {
                val expected = repo.search("测试电影", "all")
                expireResponses(path, 16L * 60 * 1000)
                mode = 1
                assertEquals(expected.copy(stale = true), repo.search("测试电影", "all"))
                mode = 2
                assertEquals(expected.copy(stale = true), repo.search("测试电影", "all"))
            } finally { repo.close() }
        }
    }

    @Test fun parserRuleUpdatesNeverReuseOldSearchParses() = runTest {
        database { path ->
            var rules = ParserRules()
            var requests = 0
            val repo = ContentRepository(HttpClient(mockEngine {
                requests++
                respond(pomoSearchFixture)
            }), CatalogDatabase(path)) { rules }
            try {
                repo.search("测试电影", "all")
                rules = rules.copy(revision = 2)
                repo.search("测试电影", "all")
                assertEquals(2, requests)
            } finally { repo.close() }
        }
    }

    @Test fun detailsPersistButSignedPlaybackAddressesAlwaysRefresh() = runTest {
        database { path ->
            var details = 0
            var streams = 0
            fun repo() = ContentRepository(HttpClient(mockEngine { request ->
                if (request.url.parameters["type"] == "auto_detail") {
                    details++
                    respond(pomoDetailFixture)
                } else {
                    streams++
                    respond(pomoMedia(streams.toString()))
                }
            }), CatalogDatabase(path))
            val request = PlayerRequest(page = pomoPage)
            val first = repo()
            try {
                assertTrue(first.player(request).url.endsWith("signature=1"))
                assertTrue(first.player(request).url.endsWith("signature=2"))
            } finally { first.close() }
            val second = repo()
            try {
                assertTrue(second.player(request).url.endsWith("signature=3"))
                assertEquals(1, details)
                assertEquals(3, streams)
            } finally { second.close() }
        }
    }

    @Test fun responseCacheEnforcesBothCountAndByteLimits() = runTest {
        database { path ->
            val db = CatalogDatabase(path)
            repeat(257) { db.writeResponse("entry:$it", "payload", it.toLong()) }
            assertNull(db.readResponse("entry:0"))
            assertNotNull(db.readResponse("entry:256"))
            val payload = "x".repeat(2 * 1024 * 1024)
            repeat(9) { db.writeResponse("large:$it", payload, 1000L + it) }
            assertNull(db.readResponse("large:0"))
            assertEquals(payload, db.readResponse("large:8")?.payload)
        }
    }
}
