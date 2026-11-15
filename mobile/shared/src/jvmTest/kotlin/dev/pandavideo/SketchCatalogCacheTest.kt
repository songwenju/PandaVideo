package dev.pandavideo

import dev.pandavideo.core.platform.DesktopPlatform
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.SketchSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.*

class SketchCatalogCacheTest {
    @Test fun removedYoukuCacheNeverReturnsAfterRestartOrOfflineRefresh() = runTest {
        val file = File.createTempFile("pandavideo-sketch-", ".db")
        var requests = 0
        try {
            CatalogDatabase(file.absolutePath).write("sketch:v1:www.xiaopin.tv:0",
                SketchSource.catalog(sketchFixture(), "www.xiaopin.tv", 1),
                dev.pandavideo.core.platform.currentEpochMillis())
            val first = ContentRepository(HttpClient(mockEngine { request ->
                requests++
                assertEquals("www.dieyin.cn", request.url.host)
                respond(if (request.url.host == "www.xiaopin.tv") sketchFixture() else dieyinSketchFixture())
            }), CatalogDatabase(file.absolutePath))
            val expected = try { first.sketches() } finally { first.close() }
            assertEquals(setOf("www.dieyin.cn"), expected.items.map { Url(it.url).host }.toSet())
            val reopened = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("", HttpStatusCode.ServiceUnavailable)
            }), CatalogDatabase(file.absolutePath))
            try {
                assertEquals(expected, reopened.sketches())
                assertEquals(1, requests)
                val fallback = reopened.sketches(refresh = true)
                assertEquals(expected.items, fallback.items)
                assertTrue(fallback.stale)
                assertEquals(2, requests)
            } finally { reopened.close() }
        } finally { file.delete() }
    }

    /** Opt-in smoke check of the retained public source and its pagination. */
    @Test fun liveSketchCatalogContainsOnlyActiveProviderAndAdvancesPages() = runBlocking {
        assumeTrue(System.getenv("PANDAVIDEO_LIVE_SKETCHES") == "1")
        val repo = ContentRepository(DesktopPlatform().httpClient())
        try {
            val first = repo.sketches()
            assertFalse(first.stale, "The active source must succeed for live verification")
            assertEquals(SketchSource.catalogHosts.toSet(), first.items.map { Url(it.url).host }.toSet())
            assertTrue(first.items.all { SketchSource.owns(it.url) && it.cover.isNotBlank() })
            val second = repo.sketches(first.nextStart())
            assertFalse(second.stale)
            assertTrue(second.items.isNotEmpty())
            assertTrue(second.items.any { row -> first.items.none { it.url == row.url } })
            println("SKETCH_CATALOG_PROBE first_rows=${first.items.size} second_rows=${second.items.size} active_source_only=true")
        } finally { repo.close() }
    }
}
