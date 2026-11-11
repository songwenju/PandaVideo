package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.platform.currentEpochMillis
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.BundledPosters
import dev.pandavideo.data.repository.CatalogAvailability
import dev.pandavideo.data.repository.ResourceStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

class CatalogDatabaseTest {
    @Test fun resourceChecksPersistWithoutModifyingCatalogSnapshots() = runTest {
        withDatabase { path ->
            val database = CatalogDatabase(path)
            val catalog = page()
            database.write("top250:0", catalog, currentEpochMillis())
            val cache = CatalogAvailability(database, { "rules" }, check = { ResourceStatus.Missing })
            cache.inspect(catalog.items.take(1)) {}
            val reopened = CatalogAvailability(CatalogDatabase(path), { "rules" },
                check = { error("Must not request the network after restart") })
            assertEquals(catalog.items.drop(1), reopened.visible(catalog).items)
            reopened.inspect(catalog.items.take(1)) {}
            assertEquals(catalog, database.read("top250:0")?.page)
        }
    }
    @Test fun extraCatalogPagesAreBoundedWithoutEvictingBundledPages() = runTest {
        withDatabase { path ->
            val db = CatalogDatabase(path)
            db.write("top250:0", page(), 1)
            db.write("tv:0", page(), 1)
            repeat(257) { db.write("filter:$it", page(titles = listOf("$it")), 2L + it) }
            assertNull(db.read("filter:0"))
            assertNotNull(db.read("filter:256"))
            assertEquals(page(), db.read("top250:0")?.page)
            assertEquals(page(), db.read("tv:0")?.page)
        }
    }
    private suspend fun withDatabase(block: suspend (String) -> Unit) {
        val file = File.createTempFile("pandavideo-catalog-", ".db")
        try { block(file.absolutePath) } finally { file.delete() }
    }

    private fun page(start: Int = 0, titles: List<String> = listOf("甲", "乙")) = CatalogPage(
        titles.mapIndexed { index, title -> CatalogItem(title, "https://movie.douban.com/subject/${start + index}/", rating = 8.5) },
        start, 25, true, "Top 250",
    )

    @Test fun reopeningPreservesOrderedItemsAndPaging() = runTest {
        withDatabase { path ->
            CatalogDatabase(path).write("top250:0", page(), 123)
            CatalogDatabase(path).write("top250:25", page(25, listOf("丙")), 456)
            val reopened = CatalogDatabase(path)
            assertEquals(page(), reopened.read("top250:0")?.page)
            assertEquals(123L, reopened.read("top250:0")?.savedAt)
            assertEquals(page(25, listOf("丙")), reopened.read("top250:25")?.page)
            assertNull(reopened.read("top250:50"))
        }
    }

    @Test fun replacingAPageRemovesOldRowsWithoutAffectingOtherPages() = runTest {
        withDatabase { path ->
            val db = CatalogDatabase(path)
            db.write("top250:0", page(), 1)
            db.write("top250:25", page(25), 2)
            db.write("top250:0", page(titles = listOf("更新")), 3)
            assertEquals(listOf("更新"), db.read("top250:0")?.page?.items?.map { it.title })
            assertEquals(page(25), db.read("top250:25")?.page)
        }
    }

    @Test fun emptyOrStaleResponsesCannotEraseSavedCatalog() = runTest {
        withDatabase { path ->
            val db = CatalogDatabase(path)
            db.write("top250:0", page(), 1)
            db.write("top250:0", page(titles = emptyList()), 2)
            db.write("top250:0", page(titles = listOf("旧")).copy(stale = true), 3)
            assertEquals(page(), db.read("top250:0")?.page)
            assertEquals(1L, db.read("top250:0")?.savedAt)
        }
    }

    @Test fun freshCatalogWorksAfterRestartWithoutAnyNetworkRequest() = runTest {
        withDatabase { path ->
            val first = ContentRepository(HttpClient(mockEngine { respond(topFixture) }), CatalogDatabase(path))
            val expected = try { first.list("top250") } finally { first.close() }
            var requests = 0
            val restarted = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("", HttpStatusCode.Forbidden)
            }), CatalogDatabase(path))
            try {
                assertEquals(expected, restarted.list("top250"))
                assertEquals(0, requests)
            } finally { restarted.close() }
        }
    }

    @Test fun expiredCatalogSurvivesForbiddenAfterRestart() = runTest {
        withDatabase { path ->
            CatalogDatabase(path).write("top250:0", page(), 1)
            val repo = ContentRepository(HttpClient(mockEngine { respond("", HttpStatusCode.Forbidden) }), CatalogDatabase(path))
            try {
                val result = repo.list("top250")
                assertTrue(result.stale)
                assertEquals(page().items, result.items)
                assertEquals(page(), CatalogDatabase(path).read("top250:0")?.page)
            } finally { repo.close() }
        }
    }

    @Test fun highRatedTvPersistsAndReadsTheSamePagingKey() = runTest {
        withDatabase { path ->
            val first = ContentRepository(HttpClient(mockEngine {
                respond("""{"data":[{"title":"剧集","url":"https://movie.douban.com/subject/123/","rate":"9.5","cover":"https://image/123.jpg"}]}""")
            }), CatalogDatabase(path))
            val expected = try { first.list("tv") } finally { first.close() }
            val restarted = ContentRepository(HttpClient(mockEngine { error("Fresh TV pages must not use the network") }), CatalogDatabase(path))
            try { assertEquals(expected, restarted.list("tv")) } finally { restarted.close() }
        }
    }

    @Test fun legacyHomepageCachesFilterScoresWithoutDiscardingStoredRows() = runTest {
        withDatabase { path ->
            val legacy = page().copy(items = listOf(
                CatalogItem("高分", "https://movie.douban.com/subject/1/", rating = 8.0),
                CatalogItem("低分", "https://movie.douban.com/subject/2/", rating = 7.9),
                CatalogItem("暂无评分", "https://movie.douban.com/subject/3/"),
            ))
            val db = CatalogDatabase(path)
            db.write("top250:0", legacy, currentEpochMillis())
            db.write("tv:0", legacy, currentEpochMillis())
            val repo = ContentRepository(HttpClient(mockEngine { error("Fresh cached pages must not request") }), db)
            try {
                listOf("top250", "tv").forEach { key ->
                    assertEquals(listOf("高分"), repo.list(key).items.map { it.title })
                    assertEquals(legacy, db.read("$key:0")?.page)
                }
            } finally { repo.close() }
        }
    }

    @Test fun successfulRefreshReplacesExpiredRows() = runTest {
        withDatabase { path ->
            CatalogDatabase(path).write("top250:0", page(), 1)
            val repo = ContentRepository(HttpClient(mockEngine { respond(topFixture) }), CatalogDatabase(path))
            try {
                val result = repo.list("top250")
                assertFalse(result.stale)
                val stored = CatalogDatabase(path).read("top250:0")!!
                assertEquals(result, stored.page)
                assertTrue(currentEpochMillis() - stored.savedAt < 10_000)
            } finally { repo.close() }
        }
    }

    @Test fun bundledDatabaseSupportsAll550SubjectsOnFirstOfflineLaunch() = runTest {
        withDatabase { path ->
            var requests = 0
            val repo = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("", HttpStatusCode.Forbidden)
            }), CatalogDatabase(path, initializeFromBundle = true))
            try {
                val films = (0..225 step 25).flatMap { repo.list("top250", it).items }
                val tv = (0..280 step 20).flatMap { repo.list("tv", it).items }
                assertEquals(250, films.size)
                assertEquals(300, tv.size)
                assertEquals(250, films.map { it.url }.distinct().size)
                assertEquals(300, tv.map { it.url }.distinct().size)
                assertEquals("肖申克的救赎", films.first().title)
                assertFalse(repo.list("top250", 225).hasMore)
                assertFalse(repo.list("tv", 280).hasMore)
                assertEquals(0, requests)
                assertNotNull(BundledPosters.read(films.first().cover))
                assertNotNull(BundledPosters.read(tv.first().cover))
            } finally { repo.close() }
        }
    }

    @Test fun bundledSnapshotNeverOverwritesAnExistingDatabase() = runTest {
        withDatabase { path ->
            CatalogDatabase(path).write("top250:0", page(), currentEpochMillis())
            val existing = CatalogDatabase(path, initializeFromBundle = true)
            assertEquals(page(), existing.read("top250:0")?.page)
        }
    }
}
