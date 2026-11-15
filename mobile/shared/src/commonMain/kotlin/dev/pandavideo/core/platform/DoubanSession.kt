package dev.pandavideo.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.http.*

/** Official login destination; credentials are handled exclusively by the native web view. */
internal const val DoubanLoginUrl =
    "https://accounts.douban.com/passport/login?redir=https%3A%2F%2Fwww.douban.com%2Fmine%2F"

/** Restricts authenticated requests to explicit HTTPS origins owned by Douban. */
internal fun isDoubanSessionUrl(address: String): Boolean = try {
    val url = Url(address)
    url.protocol == URLProtocol.HTTPS && url.port == 443 && url.user == null && url.password == null &&
        url.host in setOf("douban.com", "www.douban.com", "movie.douban.com", "accounts.douban.com")
} catch (_: Exception) { false }

/** Checks browser navigation separately from authenticated repository requests. */
internal fun isDoubanLoginNavigation(address: String, isMainFrame: Boolean): Boolean {
    if (isDoubanSessionUrl(address)) return true
    if (isMainFrame) return false
    // Douban's official login bundle invokes TencentCaptcha, which bootstraps a
    // blank iframe and loads its placeholder from t.captcha.qq.com. Applying the
    // session allowlist to child frames cancels that navigation and leaves the
    // provider's loading dots on screen. These origins are browser-only: never
    // add them to isDoubanSessionUrl or forward Douban cookies to them.
    if (address == "about:blank") return true
    return try {
        val url = Url(address)
        url.protocol == URLProtocol.HTTPS && url.port == 443 && url.user == null && url.password == null &&
            url.host in setOf("t.captcha.qq.com", "ssl.captcha.qq.com", "captcha.gtimg.com",
                "global.captcha.gtimg.com", "sg.captcha.qcloud.com")
    } catch (_: Exception) { false }
}

/** Identifies the official post-login destination; identity still requires server verification. */
internal fun isDoubanAccountLanding(address: String): Boolean {
    if (!isDoubanSessionUrl(address)) return false
    val url = Url(address)
    return url.host in setOf("www.douban.com", "douban.com") &&
        (url.encodedPath == "/mine/" || Regex("^/people/[^/]+/$").matches(url.encodedPath))
}

/** Rejects response cookies that try to escape the trusted origin's domain. */
internal fun isDoubanResponseCookie(address: String, value: String): Boolean {
    if (!isDoubanSessionUrl(address) || '\r' in value || '\n' in value) return false
    val domain = value.split(';').drop(1).firstOrNull {
        it.substringBefore('=').trim().equals("domain", ignoreCase = true)
    }?.substringAfter('=')?.trim()?.lowercase()?.removePrefix(".") ?: return true
    val host = Url(address).host
    return domain in setOf("douban.com", "www.douban.com", "movie.douban.com", "accounts.douban.com") &&
        (host == domain || host.endsWith(".$domain"))
}

/** Native browser session, kept outside profile backups and catalog JSON. */
interface DoubanSession {
    /** Whether the host can present the official embedded login page. */
    val supported: Boolean get() = false
    /** Browser identity reused by authenticated HTTP requests to avoid session fingerprint drift. */
    val userAgent: String? get() = null
    /** Returns cookies applicable to this trusted HTTPS URL, or null for other origins. */
    suspend fun cookieHeader(address: String): String? = null
    /** Saves source response cookies in the native browser's persistent cookie store. */
    suspend fun acceptCookies(address: String, values: List<String>) {}
    /** Deletes the Douban session without touching local user data or other websites. */
    suspend fun clear() {}
}

/** Default implementation for hosts without an embedded login adapter. */
object UnsupportedDoubanSession : DoubanSession

/** Displays the official login page using the same native cookie store as catalog requests. */
@Composable
internal expect fun DoubanLoginView(modifier: Modifier, onError: () -> Unit, onLoginFinished: () -> Unit)
