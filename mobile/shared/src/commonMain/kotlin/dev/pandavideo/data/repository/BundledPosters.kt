package dev.pandavideo.data.repository

import dev.pandavideo.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Original poster bytes bundled with the catalog; missing images fall back to the disk-cached loader. */
@OptIn(ExperimentalResourceApi::class)
internal object BundledPosters {
    private val lock = Mutex()
    private var paths: Map<String, String>? = null

    suspend fun read(url: String?): ByteArray? = withContext(Dispatchers.Default) {
        if (url.isNullOrBlank()) return@withContext null
        try {
            val path = lock.withLock {
                val map = paths ?: Json.decodeFromString<Map<String, String>>(
                    Res.readBytes("files/catalog-posters.json").decodeToString(),
                ).also { paths = it }
                map[url]
            } ?: return@withContext null
            Res.readBytes(path)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
    }
}
