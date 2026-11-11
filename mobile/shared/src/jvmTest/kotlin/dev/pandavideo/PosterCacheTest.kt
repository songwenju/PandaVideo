package dev.pandavideo

import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import dev.pandavideo.ui.common.posterImageLoader
import dev.pandavideo.ui.common.prefetchPosters
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class PosterCacheTest {
    @Test fun prefetchCachesEveryReturnedCardBeforeItIsDisplayed() = runBlocking {
        val directory = Files.createTempDirectory("pandavideo-prefetch-").toFile()
        val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
        )
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = HttpClient(io.ktor.client.engine.mock.MockEngine {
            requests.incrementAndGet()
            assertEquals("https://pomo.mom/", it.headers["Referer"])
            respond(bytes, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
        })
        val loader = posterImageLoader(PlatformContext.INSTANCE, directory.absolutePath).newBuilder()
            .components { add(KtorNetworkFetcherFactory(client)) }.build()
        val urls = listOf("https://image.example/card1.png", "https://image.example/card2.png")
        try {
            prefetchPosters(loader, PlatformContext.INSTANCE, urls + urls.first())
            assertEquals(2, requests.get())
            urls.forEach { url ->
                val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(url)
                    .memoryCachePolicy(CachePolicy.DISABLED).build())
                if (result is ErrorResult) throw result.throwable
                assertEquals(DataSource.DISK, assertIs<SuccessResult>(result).dataSource)
            }
            assertEquals(2, requests.get())
        } finally { loader.shutdown(); client.close(); directory.deleteRecursively() }
    }
    @Test fun downloadedPosterSurvivesLoaderRestartWithoutNetwork() = runBlocking {
        val directory = Files.createTempDirectory("pandavideo-posters-").toFile()
        val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
        )
        var requests = 0
        // This test uses the production loader/cache directory configuration. The only
        // replacement is transport, making a second upstream request a hard failure.
        val client = HttpClient(io.ktor.client.engine.mock.MockEngine {
            requests++
            if (requests > 1) error("The second loader must read the persisted poster")
            respond(bytes, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
        })
        fun loader() = posterImageLoader(PlatformContext.INSTANCE, directory.absolutePath).newBuilder()
            .components { add(KtorNetworkFetcherFactory(client)) }
            .build()
        val request = ImageRequest.Builder(PlatformContext.INSTANCE)
            .data("https://image.example/poster.png")
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        try {
            val first = loader()
            try {
                val result = first.execute(request)
                if (result is ErrorResult) throw result.throwable
                assertIs<SuccessResult>(result)
            } finally { first.shutdown() }
            val second = loader()
            try {
                val result = second.execute(request)
                if (result is ErrorResult) throw result.throwable
                assertEquals(DataSource.DISK, assertIs<SuccessResult>(result).dataSource)
                assertEquals(1, requests)
            } finally { second.shutdown() }
        } finally {
            client.close()
            directory.deleteRecursively()
        }
    }
}
