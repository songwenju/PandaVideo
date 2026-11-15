package dev.pandavideo.core.player

import io.ktor.http.Url

/** Restricts provider-specific transport options to official HTTPS media CDN URLs. */
internal object BilibiliMedia {
    const val userAgent = "Mozilla/5.0"

    fun owns(address: String): Boolean = try {
        val url = Url(address)
        url.protocol.name == "https" && url.port == 443 && url.user == null &&
            url.password == null && url.fragment.isEmpty() &&
            url.host.endsWith(".bilivideo.com") && url.encodedPath.endsWith(".mp4")
    } catch (_: Exception) { false }
}
