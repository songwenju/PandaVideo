@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo.core.platform

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import io.ktor.http.Url
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.CoreGraphics.CGRectZero
import platform.Foundation.*
import platform.WebKit.*
import platform.darwin.NSObject
import kotlin.coroutines.resume

private const val DoubanIosUserAgent =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

internal class IosDoubanSession : DoubanSession {
    override val supported = true
    override val userAgent: String = DoubanIosUserAgent
    private val store get() = WKWebsiteDataStore.defaultDataStore().httpCookieStore
    private suspend fun cookies(): List<NSHTTPCookie> = suspendCancellableCoroutine { continuation ->
        store.getAllCookies { values ->
            if (continuation.isActive) continuation.resume(values.orEmpty().filterIsInstance<NSHTTPCookie>())
        }
    }
    override suspend fun cookieHeader(address: String): String? = withContext(Dispatchers.Main) {
        if (!isDoubanSessionUrl(address)) return@withContext null
        val url = Url(address)
        cookies().filter { cookie ->
            val domain = cookie.domain.lowercase()
            val matches = if (domain.startsWith(".")) url.host == domain.drop(1) || url.host.endsWith(domain)
                else url.host == domain
            val path = cookie.path
            matches && (cookie.expiresDate?.timeIntervalSince1970?.let { it * 1000 > currentEpochMillis() } != false) &&
                (url.encodedPath == path || (url.encodedPath.startsWith(path) &&
                    (path.endsWith("/") || url.encodedPath.removePrefix(path).startsWith("/"))))
        }.sortedByDescending { it.path.length }.joinToString("; ") { "${it.name}=${it.value}" }.ifBlank { null }
    }
    override suspend fun acceptCookies(address: String, values: List<String>) = withContext(Dispatchers.Main) {
        values.filter { isDoubanResponseCookie(address, it) }.forEach { value ->
            val parsed = NSHTTPCookie.cookiesWithResponseHeaderFields(
                mapOf<Any?, Any?>("Set-Cookie" to value), NSURL(string = address))
            parsed.filterIsInstance<NSHTTPCookie>().forEach { cookie ->
                suspendCancellableCoroutine<Unit> { continuation ->
                    store.setCookie(cookie) { if (continuation.isActive) continuation.resume(Unit) }
                }
            }
        }
    }
    override suspend fun clear() = withContext(Dispatchers.Main) {
        cookies().filter { it.domain.removePrefix(".").let { domain ->
            domain == "douban.com" || domain.endsWith(".douban.com")
        } }.forEach { cookie ->
            suspendCancellableCoroutine<Unit> { continuation ->
                store.deleteCookie(cookie) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
    }
}

@Composable
internal actual fun DoubanLoginView(modifier: Modifier, onError: () -> Unit, onLoginFinished: () -> Unit) {
    val errorCallback by rememberUpdatedState(onError)
    val loginCallback by rememberUpdatedState(onLoginFinished)
    val delegate = remember {
        object : NSObject(), WKNavigationDelegateProtocol {
            private var reported = false
            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                // Do not read login form fields or infer authentication from a cookie.
                // Verify the account through the shared repository after the official redirect.
                if (!reported && isDoubanAccountLanding(webView.URL?.absoluteString.orEmpty())) {
                    reported = true
                    loginCallback()
                }
            }
            override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction,
                decisionHandler: (WKNavigationActionPolicy) -> Unit) {
                // A nil target is a new-window navigation, not a CAPTCHA child frame.
                decisionHandler(if (isDoubanLoginNavigation(
                    decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty(),
                    isMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame != false))
                    WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
            @ObjCSignatureOverride
            override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
                if (withError.code != NSURLErrorCancelled) errorCallback()
            }
            @ObjCSignatureOverride
            override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
                if (withError.code != NSURLErrorCancelled) errorCallback()
            }
        }
    }
    UIKitView(modifier = modifier, factory = {
        WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.defaultDataStore()
        }).apply {
            customUserAgent = DoubanIosUserAgent
            navigationDelegate = delegate
            loadRequest(NSURLRequest.requestWithURL(NSURL(string = DoubanLoginUrl)))
        }
    }, onRelease = { it.stopLoading(); it.navigationDelegate = null },
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true))
}
