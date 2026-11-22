package dev.pandavideo.core.player

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import okio.Buffer

/** A complete, immutable VOD bootstrap; only the first TS URI is replaced at publication. */
internal data class HlsBootstrap(val playlist: String, val segment: ByteArray) {
    fun manifest(firstSegment: String): String = playlist.lines().joinToString("\n") {
        if (it == FIRST_SEGMENT) firstSegment else it
    }
    companion object { const val FIRST_SEGMENT = "pandavideo-bootstrap-first.ts" }
}

/**
 * Owns one bounded speculative download. Playback awaits the same deferred instead of issuing
 * another request. This is a session cache: URLs (including signed query parameters) are exact
 * keys, entries expire after two minutes, and a miss never delays ordinary cold playback.
 * Call on the owning player's main dispatcher. Cancellation of a waiter leaves the shared
 * download alive; replacing the candidate or closing the owner cancels the download itself.
 */
internal class HlsBootstrapCache(
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private var address: String? = null
    private var started = 0L
    private var pending: Deferred<HlsBootstrap?>? = null

    fun preload(source: MediaSource?) {
        val next = source?.takeIf { it.segments.isEmpty() && it.kind == "hls" && web(it.url) }?.url
        if (next != null && address == next && now() - started < TTL_MS) return
        pending?.cancel()
        pending = null
        address = next
        if (next == null) return
        started = now()
        pending = scope.async {
            try {
                withTimeout(12_000) {
                    val (base, bytes) = fetch(next, MAX_PLAYLIST)
                    val parsed = parseHlsBootstrap(base, bytes.decodeToString()) ?: return@withTimeout null
                    val (_, segment) = fetch(parsed.second, MAX_SEGMENT)
                    // A successful HTTP response can still be an HTML error. This initial phase
                    // supports MPEG-TS only; require sync bytes at consecutive packet boundaries.
                    if (segment.size < 377 || listOf(0, 188, 376).any { segment[it] != 0x47.toByte() })
                        return@withTimeout null
                    HlsBootstrap(parsed.first, segment)
                }
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // Speculative failure is silent; the native player retains the original URL.
            }
        }
    }

    suspend fun await(source: MediaSource): HlsBootstrap? {
        if (source.segments.isNotEmpty() || source.kind != "hls" || address != source.url || now() - started >= TTL_MS) return null
        return try { pending?.await() }
        catch (e: CancellationException) {
            // A candidate may be withdrawn while playback is joining it. Only propagate the
            // caller's cancellation; cancellation of speculative work is an ordinary miss.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    fun close() { pending?.cancel(); pending = null; address = null }

    private suspend fun fetch(address: String, limit: Int): Pair<String, ByteArray> {
        var current = address
        repeat(6) {
            val response = client.get(current)
            if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                val location = response.headers["Location"] ?: error("Missing redirect")
                response.bodyAsChannel().cancel(null)
                current = absoluteHlsUrl(current, location) ?: error("Unsupported redirect")
            } else {
                check(response.status.value == 200)
                response.headers["Content-Length"]?.toLongOrNull()?.let { check(it <= limit) }
                val channel = response.bodyAsChannel()
                try {
                    val output = Buffer()
                    val chunk = ByteArray(8_192)
                    while (true) {
                        val count = channel.readAvailable(chunk, 0, chunk.size)
                        if (count < 0) break
                        if (count == 0) { yield(); continue }
                        check(output.size + count <= limit)
                        output.write(chunk, 0, count)
                    }
                    return response.call.request.url.toString() to output.readByteArray()
                } finally { channel.cancel(null) }
            }
        }
        error("Redirect limit")
    }

    companion object {
        const val TTL_MS = 120_000L
        private const val MAX_PLAYLIST = 512 * 1024
        private const val MAX_SEGMENT = 16 * 1024 * 1024
    }
}

private fun web(address: String): Boolean = runCatching {
    val url = Url(address)
    url.protocol.name in setOf("http", "https") && url.host.isNotBlank() &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
}.getOrDefault(false)

internal fun absoluteHlsUrl(base: String, value: String): String? = runCatching {
    URLBuilder(base).apply {
        // Ktor merges parameters on takeFrom; URI references replace the base query when
        // they supply a path or query. Inheriting a signed manifest token breaks TS requests.
        if (value.isNotEmpty() && !value.startsWith("#")) encodedParameters.clear()
        fragment = ""
        takeFrom(value)
        val normalized = mutableListOf<String>()
        encodedPathSegments.forEach { part ->
            when (part) {
                "." -> Unit
                ".." -> if (normalized.size > 1) normalized.removeAt(normalized.lastIndex)
                else -> normalized += part
            }
        }
        encodedPathSegments = normalized
    }.buildString().takeIf(::web)
}.getOrNull()

/**
 * Accept only unencrypted, finite MPEG-TS media playlists. Reject tags with additional URI
 * dependencies, variable substitution, partial segments, maps and byte ranges rather than
 * publishing a subtly different presentation. Resolve every remote URI against the final
 * playlist URL, so redirects and nested relative paths keep their original meaning.
 */
internal fun parseHlsBootstrap(base: String, text: String): Pair<String, String>? {
    val lines = text.removePrefix("\uFEFF").lines().map { it.trim() }
    if (lines.firstOrNull() != "#EXTM3U" || "#EXT-X-ENDLIST" !in lines) return null
    if (lines.any {
            it.startsWith("#EXT-X-KEY") && it != "#EXT-X-KEY:METHOD=NONE" ||
                listOf("#EXT-X-STREAM-INF", "#EXT-X-I-FRAME", "#EXT-X-MEDIA:", "#EXT-X-MAP",
                    "#EXT-X-BYTERANGE", "#EXT-X-PART", "#EXT-X-PRELOAD", "#EXT-X-DEFINE",
                    "#EXT-X-SKIP", "#EXT-X-SESSION").any(it::startsWith) ||
                (it.startsWith("#") && "URI=" in it)
        }) return null
    var first: String? = null
    var duration = false
    val rewritten = mutableListOf<String>()
    for (line in lines) {
        if (line.startsWith("#EXTINF:")) duration = true
        if (line.isNotEmpty() && !line.startsWith("#")) {
            if (!duration) return null
            duration = false
            val absolute = absoluteHlsUrl(base, line) ?: return null
            if (first == null) {
                if (!Url(absolute).encodedPath.endsWith(".ts", ignoreCase = true)) return null
                first = absolute
                rewritten += HlsBootstrap.FIRST_SEGMENT
            } else rewritten += absolute
        } else rewritten += line
    }
    if (duration) return null
    return first?.let { rewritten.joinToString("\n", postfix = "\n") to it }
}
