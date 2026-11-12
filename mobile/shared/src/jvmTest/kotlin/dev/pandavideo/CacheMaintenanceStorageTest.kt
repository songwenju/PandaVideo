package dev.pandavideo

import coil3.PlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.platform.currentEpochMillis
import dev.pandavideo.data.repository.CatalogAvailability
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.ResourceStatus
import dev.pandavideo.ui.common.posterImageLoader
import dev.pandavideo.ui.common.prefetchPosters
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class CacheMaintenanceStorageTest {
    @Test fun clearingFetchedContentReclaimsDiskAndRetainsOfflineCatalogsAndOtherFiles() = runBlocking {
        val directory = Files.createTempDirectory("pandavideo-cache-clean-").toFile()
        val file = directory.resolve("catalog.db")
        val video = directory.resolve("offline-media/video.mp4").apply {
            parentFile.mkdirs()
            writeText("user-downloaded-video")
        }
        val database = CatalogDatabase(file.absolutePath)
        val bundled = CatalogPage(
            listOf(CatalogItem("内置影片", "https://movie.douban.com/subject/1/", rating = 9.0)),
            0, 25, false, "Top 250",
        )
        val client = HttpClient(MockEngine { error("Cleanup must not fetch the network") })
        val repository = ContentRepository(client, database)
        try {
            database.write("top250:0", bundled, currentEpochMillis())
            database.write("tv:0", bundled, currentEpochMillis())
            database.write("browse:war", bundled, currentEpochMillis())
            database.writeResponse("search:test", "临时".repeat(100_000), currentEpochMillis())
            val usage = repository.cacheUsage()
            assertEquals(2L, usage.entries)
            assertTrue(usage.bytes >= "临时".repeat(100_000).encodeToByteArray().size)
            val beforeSize = file.length()
            repository.clearCache()
            assertEquals(0L, repository.cacheUsage().bytes)
            assertNull(database.read("browse:war"))
            assertNull(database.readResponse("search:test"))
            assertEquals(bundled, database.read("top250:0")?.page)
            assertEquals(bundled, database.read("tv:0")?.page)
            assertEquals(bundled, repository.list("top250"))
            assertTrue(file.length() < beforeSize)
            assertEquals("user-downloaded-video", video.readText())
            repository.clearCache()
            assertEquals(0L, repository.cacheUsage().entries)
        } finally {
            repository.close()
            directory.deleteRecursively()
        }
    }

    @Test fun resourceCheckMemoryIsDiscardedAlongsidePersistentCache() = runBlocking {
        val item = CatalogItem("影片", "https://movie.douban.com/subject/2/", rating = 8.5)
        val page = CatalogPage(listOf(item), 0, 25, false, "筛选")
        var status = ResourceStatus.Missing
        val availability = CatalogAvailability(null, { "rules" }, { status })
        availability.inspect(page.items) {}
        assertTrue(availability.visible(page).items.isEmpty())
        availability.clearMemory()
        assertEquals(page, availability.visible(page))
        status = ResourceStatus.Available
        availability.inspect(page.items) {}
        assertEquals(page, availability.visible(page))
    }

    @Test fun imageCacheCanBeClearedAndReusedWithoutDeletingItsOwnerOrSiblingFiles() = runBlocking {
        val directory = Files.createTempDirectory("pandavideo-image-clean-").toFile()
        val retained = directory.resolve("offline.mp4").apply { writeText("keep") }
        val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
        )
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(bytes, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
        })
        val loader = posterImageLoader(PlatformContext.INSTANCE, directory.resolve("posters").absolutePath)
            .newBuilder().components { add(KtorNetworkFetcherFactory(client)) }.build()
        val urls = listOf("https://image.example/cleanup.png")
        try {
            prefetchPosters(loader, PlatformContext.INSTANCE, urls)
            assertTrue(loader.diskCache!!.size > 0)
            loader.memoryCache?.clear()
            loader.diskCache!!.clear()
            assertEquals(0L, loader.diskCache!!.size)
            assertEquals("keep", retained.readText())
            prefetchPosters(loader, PlatformContext.INSTANCE, urls)
            assertEquals(2, requests)
            assertTrue(loader.diskCache!!.size > 0)
        } finally {
            loader.shutdown()
            client.close()
            directory.deleteRecursively()
        }
    }
}
