package dev.pandavideo.core.platform

import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal class AndroidDoubanSession(context: android.content.Context) : DoubanSession {
    override val supported = true
    override val userAgent: String = WebSettings.getDefaultUserAgent(context)
    override suspend fun cookieHeader(address: String): String? = withContext(Dispatchers.Main) {
        if (isDoubanSessionUrl(address)) CookieManager.getInstance().getCookie(address) else null
    }
    override suspend fun acceptCookies(address: String, values: List<String>) = withContext(Dispatchers.Main) {
        val store = CookieManager.getInstance()
        values.filter { isDoubanResponseCookie(address, it) }.forEach { value ->
            suspendCancellableCoroutine<Unit> { continuation ->
                store.setCookie(address, value) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
        store.flush()
    }
    override suspend fun clear() = withContext(Dispatchers.Main) {
        val store = CookieManager.getInstance()
        // Android does not expose cookie enumeration. Expire the cookies visible at
        // every official account root, including both host-only and shared domains.
        for (host in listOf("douban.com", "www.douban.com", "movie.douban.com", "accounts.douban.com")) {
            val address = "https://$host/"
            val names = store.getCookie(address).orEmpty().split(';')
                .map { it.substringBefore('=').trim() }.filter { it.isNotBlank() }
            for (name in names) for (domain in listOf("", "; Domain=$host", "; Domain=.douban.com")) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    store.setCookie(address, "$name=; Max-Age=0; Path=/; Secure$domain") {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
        store.flush()
    }
}

@Composable
internal actual fun DoubanLoginView(modifier: Modifier, onError: () -> Unit, onLoginFinished: () -> Unit) {
    val errorCallback by rememberUpdatedState(onError)
    val loginCallback by rememberUpdatedState(onLoginFinished)
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                private var reported = false
                override fun onPageFinished(view: WebView, url: String) {
                    // A redirect only initiates verification; cookies alone never imply login.
                    if (!reported && isDoubanAccountLanding(url)) {
                        reported = true
                        loginCallback()
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isDoubanLoginNavigation(request.url.toString(), isMainFrame = request.isForMainFrame)
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) errorCallback()
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) errorCallback()
                }
            }
            loadUrl(DoubanLoginUrl)
        }
    }, onRelease = { it.stopLoading(); it.destroy() })
}
