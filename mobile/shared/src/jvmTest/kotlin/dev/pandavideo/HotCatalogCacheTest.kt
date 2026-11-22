package dev.pandavideo

import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import dev.pandavideo.data.repository.JianpianSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

class HotCatalogCacheTest {
    @Test fun partialCachedHotListRevalidatesMissingOriginalRowsAfterRestart() = runTest {
        val file = File.createTempFile("hot-catalog-", ".db")
        var supplementFails = true
        var originalRequests = 0
        fun repository() = ContentRepository(HttpClient(mockEngine(backupSources = true) { request ->
            when {
                request.url.encodedPath.endsWith("/init") -> respond("""{"code":1,"data":{"secret":"s"}}""")
                request.url.encodedPath.endsWith("/resourceDomainConfig") ->
                    respond("""{"code":1,"data":{"imgDomain":"images.test"}}""")
                request.url.encodedPath.endsWith("/crumb/list") ->
                    respond("""{"code":1,"data":[{"id":1,"title":"荐片电影","score":"7"}]}""")
                request.url.encodedPath.endsWith("/video/detailv2") ->
                    respond("""{"code":1,"data":{"year":"2026"}}""")
                else -> {
                    originalRequests++
                    if (supplementFails) respond("", HttpStatusCode.ServiceUnavailable)
                    else respond("""{"data":[{"title":"改版前电影","url":"https://movie.douban.com/subject/2/","rate":"7"}]}""")
                }
            }
        }), CatalogDatabase(file.absolutePath))
        var repo = repository()
        try {
            assertTrue(repo.hotCatalog("movie").stale)
            val previousRequests = originalRequests
            repo.close()
            supplementFails = false
            repo = repository()
            val restored = repo.hotCatalog("movie")
            assertEquals(previousRequests + 1, originalRequests,
                "A partial snapshot must retry its missing source even inside the normal TTL")
            assertEquals(listOf("荐片电影", "改版前电影"), restored.items.map { it.title })
            assertTrue(JianpianSource.owns(restored.items.first().url))
            assertFalse(restored.stale)
            repo.close()
            supplementFails = true
            repo = repository()
            assertEquals(restored, repo.hotCatalog("movie"), "Complete snapshots survive offline restart")
            assertEquals(previousRequests + 1, originalRequests)
        } finally { repo.close(); file.delete() }
    }
}
