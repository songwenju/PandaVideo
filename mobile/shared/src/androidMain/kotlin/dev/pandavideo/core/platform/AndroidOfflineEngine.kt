package dev.pandavideo.core.platform

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.data.repository.OfflineAsset
import dev.pandavideo.data.repository.OfflineEngine
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/**
 * Media3 owns manifests, keys, byte ranges and segments. A separate non-evicting cache per
 * episode makes deletion independent from other episodes and prevents partial cache playback.
 */
@UnstableApi
internal class AndroidOfflineEngine private constructor(context: Context) : OfflineEngine {
    private val root = File(context.filesDir, "offline-media").apply { mkdirs() }
    private val database = StandaloneDatabaseProvider(context)
    private val caches = mutableMapOf<String, SimpleCache>()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var active: Pair<String, Downloader>? = null
    private val network = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)

    override fun supports(kind: String): Boolean = kind in setOf("hls", "video")
    private fun directory(id: String): File {
        require(id.matches(Regex("[a-z0-9-]+")))
        return File(root, id)
    }
    @Synchronized private fun cache(id: String): SimpleCache =
        caches.getOrPut(id) { SimpleCache(File(directory(id), "media"), NoOpCacheEvictor(), database) }

    private fun factory(id: String, online: Boolean): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache(id)).apply {
            // Offline playback intentionally has no upstream: missing media fails rather than
            // silently reconnecting to the source and claiming to be available offline.
            if (online) setUpstreamDataSourceFactory(network)
        }

    override suspend fun download(id: String, info: PlaybackInfo, progress: (Float?) -> Unit): String {
        require(supports(info.kind))
        val dir = directory(id).apply { mkdirs() }
        val executor = Executors.newSingleThreadExecutor()
        val item = MediaItem.Builder().setUri(info.url)
            .setMimeType(if (info.kind == "hls") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4).build()
        val downloader: Downloader = if (info.kind == "hls") HlsDownloader.Factory(factory(id, true))
            .setExecutor(executor).create(item)
            else ProgressiveDownloader(item, factory(id, true), executor)
        active = id to downloader
        try {
            withContext(Dispatchers.IO) {
                if (info.kind == "hls") {
                    fun playlist(uri: Uri): HlsPlaylist = DataSourceInputStream(
                        network.createDataSource(), DataSpec(uri),
                    ).use { HlsPlaylistParser().parse(uri, it) }
                    val manifest = playlist(Uri.parse(info.url))
                    val media = if (manifest is HlsMultivariantPlaylist)
                        manifest.mediaPlaylistUrls.map(::playlist) else listOf(manifest)
                    // A live-window snapshot is not a complete episode. Check each rendition
                    // before Media3 caches its segments, including separately hosted audio.
                    require(media.all { it is HlsMediaPlaylist && it.hasEndTag }) {
                        "直播流不支持离线缓存"
                    }
                }
                ensureActive()
                // A changed signed URL may leave old segments; Media3 resolves the fresh manifest
                // and fills its missing ranges. Only a successful full download writes the marker.
                downloader.download { _, _, percent ->
                    progress(percent.takeIf { it >= 0 }?.div(100f))
                }
                ensureActive()
                val metadata = File(dir, "source.json.tmp")
                metadata.writeText(json.encodeToString(info))
                check(metadata.renameTo(File(dir, "source.json")))
            }
            return id
        } finally {
            active = null
            executor.shutdownNow()
        }
    }

    override fun cancel(id: String) { active?.takeIf { it.first == id }?.second?.cancel() }
    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        this@AndroidOfflineEngine.cancel(id)
        synchronized(this@AndroidOfflineEngine) { caches.remove(id)?.release() }
        check(directory(id).deleteRecursively())
    }
    override fun asset(id: String, locator: String): OfflineAsset? =
        if (id == locator && File(directory(id), "source.json").isFile)
            OfflineAsset("pandavideo-offline://$id", "offline") else null

    /** Resolves only app-generated locators into a network-disabled Media3 media source. */
    fun mediaSource(id: String, requested: MediaItem): MediaSource {
        val info = json.decodeFromString<PlaybackInfo>(File(directory(id), "source.json").readText())
        val item = requested.buildUpon().setUri(info.url)
            .setMimeType(if (info.kind == "hls") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4).build()
        return DefaultMediaSourceFactory(factory(id, false)).createMediaSource(item)
    }

    companion object {
        @Volatile private var instance: AndroidOfflineEngine? = null
        /** Shares cache locks between the download worker and the media-session service. */
        fun get(context: Context): AndroidOfflineEngine = instance ?: synchronized(this) {
            instance ?: AndroidOfflineEngine(context.applicationContext).also { instance = it }
        }
    }
}

/** Selects private cache sources while leaving ordinary online playback unchanged. */
@UnstableApi
internal class OfflineMediaSourceFactory(context: Context) : MediaSource.Factory {
    private val online = DefaultMediaSourceFactory(context)
    private val offline = AndroidOfflineEngine.get(context)
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        return if (uri?.scheme == "pandavideo-offline") {
            require(uri.path.isNullOrEmpty() && uri.query == null)
            offline.mediaSource(requireNotNull(uri.host), mediaItem)
        } else online.createMediaSource(mediaItem)
    }
    override fun getSupportedTypes(): IntArray = online.supportedTypes
    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
    ): MediaSource.Factory = apply { online.setDrmSessionManagerProvider(drmSessionManagerProvider) }
    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply { online.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }
}
