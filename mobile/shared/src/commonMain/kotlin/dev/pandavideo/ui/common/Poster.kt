package dev.pandavideo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import androidx.compose.runtime.staticCompositionLocalOf
import okio.Path.Companion.toPath
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.pandavideo.data.repository.OnlineOrigin
import dev.pandavideo.data.repository.BundledPosters
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.pandavideo.data.repository.isWebUrl

internal val LocalPosterImageLoader = staticCompositionLocalOf<ImageLoader?> { null }
private val posterPrefetchPermits = Semaphore(4)

/** Downloads every poster from a returned page, including cards not yet scrolled into view. */
internal suspend fun prefetchPosters(loader: ImageLoader, context: coil3.PlatformContext, urls: List<String>) = coroutineScope {
    urls.filter(::isWebUrl).distinct().forEach { url ->
        launch {
            posterPrefetchPermits.withPermit {
                if (BundledPosters.read(url) == null) {
                    loader.execute(posterRequest(context, url, url).newBuilder()
                        .memoryCachePolicy(CachePolicy.DISABLED).build())
                }
            }
        }
    }
}

private fun posterRequest(context: coil3.PlatformContext, url: String?, data: Any?): ImageRequest =
    ImageRequest.Builder(context).data(data).memoryCacheKey(url).diskCacheKey(url).httpHeaders(
        NetworkHeaders.Builder().set("User-Agent", "Mozilla/5.0")
            .set("Referer", if (url?.contains("doubanio.com") == true) "https://movie.douban.com/" else "$OnlineOrigin/")
            .build(),
    ).build()

/** Builds one app-owned loader with an expendable, bounded disk cache for original posters. */
internal fun posterImageLoader(context: coil3.PlatformContext, directory: String?): ImageLoader =
    ImageLoader.Builder(context).apply {
        if (directory != null) diskCache {
            DiskCache.Builder().directory(directory.toPath()).maxSizeBytes(256L * 1024 * 1024).build()
        }
        diskCachePolicy(CachePolicy.ENABLED)
        memoryCachePolicy(CachePolicy.ENABLED)
    }.build()

@Composable
internal fun Poster(url: String?, title: String, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val imageLoader = LocalPosterImageLoader.current
    val data by produceState<Any?>(initialValue = null, url) {
        value = BundledPosters.read(url) ?: url
    }
    val request = remember(url, data, context) {
        posterRequest(context, url, data)
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Icon(
            NavigationIcons.Poster, contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
        if (imageLoader != null) {
            AsyncImage(model = request, imageLoader = imageLoader, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            AsyncImage(model = request, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
