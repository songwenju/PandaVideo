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
            fun repo() = ContentRepository(HttpClient(mockEngine { requests++; respond(pomoSearchFixture) }), CatalogDatabase(path))
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
