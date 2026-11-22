package dev.pandavideo.core.player

internal data class BootstrapHttpResponse(val header: String, val body: ByteArray)

/** Minimal GET/HEAD and single byte-range support for the two loopback HLS resources. */
internal fun bootstrapHttpResponse(request: String, manifest: ByteArray, segment: ByteArray): BootstrapHttpResponse {
    val lines = request.split("\r\n")
    val parts = lines.first().split(" ")
    val method = parts.getOrNull(0)
    val path = parts.getOrNull(1)
    fun response(code: String, body: ByteArray, type: String = "text/plain", extra: String = "",
        head: Boolean = false) = BootstrapHttpResponse(
        "HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Accept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n",
        if (head) byteArrayOf() else body,
    )
    if (method !in listOf("GET", "HEAD")) return response("405 Method Not Allowed", byteArrayOf())
    val bytes = when (path) {
        "/index.m3u8" -> manifest
        "/first.ts" -> segment
        else -> return response("404 Not Found", byteArrayOf())
    }
    val type = if (path == "/index.m3u8") "application/vnd.apple.mpegurl" else "video/mp2t"
    val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
    if (range != null) {
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
        val a = match?.groupValues?.get(1)?.toLongOrNull()
        val b = match?.groupValues?.get(2)?.toLongOrNull()
        val start = if (a == null && b != null) (bytes.size.toLong() - b).coerceAtLeast(0) else a
        val end = if (a == null) bytes.lastIndex.toLong() else (b ?: bytes.lastIndex.toLong()).coerceAtMost(bytes.lastIndex.toLong())
        if (match == null || start == null || start > end || start >= bytes.size ||
            (a == null && (b == null || b == 0L))) {
            return response("416 Range Not Satisfiable", byteArrayOf(), extra = "Content-Range: bytes */${bytes.size}\r\n")
        }
        return response("206 Partial Content", bytes.copyOfRange(start.toInt(), end.toInt() + 1), type,
            "Content-Range: bytes $start-$end/${bytes.size}\r\n", method == "HEAD")
    }
    return response("200 OK", bytes, type, head = method == "HEAD")
}
