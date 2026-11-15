package dev.pandavideo

import dev.pandavideo.core.platform.*
import dev.pandavideo.app.DoubanConnection
import dev.pandavideo.data.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.awaitCancellation
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DoubanSessionTest {
    @Test fun captchaChildFramesLoadWithoutReceivingDoubanSession() {
        // The official Douban login bundle invokes TencentCaptcha. Its SDK creates
        // a blank child frame and loads the provider's HTTPS placeholder/CDN.
        val frames = listOf("about:blank",
            "https://t.captcha.qq.com/template/placeholder_v2.html",
            "https://ssl.captcha.qq.com/TCaptcha.js",
            "https://captcha.gtimg.com/static/",
            "https://global.captcha.gtimg.com/static/",
            "https://sg.captcha.qcloud.com/")
        for (address in frames) {
            assertTrue(isDoubanLoginNavigation(address, isMainFrame = false), address)
            assertFalse(isDoubanLoginNavigation(address, isMainFrame = true), address)
            assertFalse(isDoubanSessionUrl(address), address)
            assertFalse(isDoubanAccountLanding(address), address)
            assertFalse(isDoubanResponseCookie(address, "dbcl2=test"), address)
        }
        assertTrue(isDoubanLoginNavigation(DoubanLoginUrl, isMainFrame = true))
        assertTrue(isDoubanLoginNavigation(DoubanLoginUrl, isMainFrame = false))
    }

    @Test fun captchaNavigationRejectsUntrustedOriginsAndSchemes() {
        for (address in listOf("http://t.captcha.qq.com/", "https://t.captcha.qq.com:444/",
            "https://t.captcha.qq.com.evil.org/", "https://evil.qq.com/",
            "https://captcha.gtimg.com.evil.org/", "https://user@t.captcha.qq.com/",
            "https://user:password@t.captcha.qq.com/", "https://external.org/",
            "file:///tmp/captcha.html", "javascript:alert(1)", "data:text/html,test",
            "about:srcdoc", "")) {
            assertFalse(isDoubanLoginNavigation(address, isMainFrame = false), address)
            assertFalse(isDoubanLoginNavigation(address, isMainFrame = true), address)
        }
    }

    @Test fun onlyOfficialAccountLandingInitiatesIdentityVerification() {
        assertTrue(isDoubanAccountLanding("https://www.douban.com/mine/"))
        assertTrue(isDoubanAccountLanding("https://www.douban.com/people/123/"))
        for (address in listOf(DoubanLoginUrl, "https://accounts.douban.com/passport/login",
            "https://movie.douban.com/people/123/", "https://www.douban.com/people/123/photos/",
            "https://www.douban.com.evil.org/mine/", "http://www.douban.com/mine/",
            "https://user@www.douban.com/mine/", "https://www.douban.com:444/mine/"))
            assertFalse(isDoubanAccountLanding(address), address)
    }

    private class Session : DoubanSession {
        override val supported = true
        override val userAgent = "NativeBrowser/Test"
        val reads = mutableListOf<String>()
        val writes = mutableListOf<String>()
        var cleared = false
        override suspend fun cookieHeader(address: String): String {
            reads += address
            return "dbcl2=test-session"
        }
        override suspend fun acceptCookies(address: String, values: List<String>) { writes += address }
        override suspend fun clear() { cleared = true }
    }

    @Test fun onlyOfficialHttpsOriginsReceiveSession() {
        assertTrue(isDoubanSessionUrl("https://movie.douban.com/j/new_search_subjects"))
        for (url in listOf("http://movie.douban.com/", "https://movie.douban.com:444/",
            "https://douban.com.evil.org/", "https://evil.douban.com/", "https://user@movie.douban.com/"))
            assertFalse(isDoubanSessionUrl(url), url)
        assertTrue(isDoubanResponseCookie("https://movie.douban.com/", "ck=test; Domain=.douban.com"))
        assertFalse(isDoubanResponseCookie("https://movie.douban.com/", "ck=test; Domain=accounts.douban.com"))
        assertFalse(isDoubanResponseCookie("https://movie.douban.com/", "ck=test; Domain=.com"))
    }

    @Test fun redirectRecomputesCookiesAndNeverLeaksToThirdParty() = runTest {
        val session = Session()
        val observed = mutableListOf<Pair<String, String?>>()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertEquals(if (request.url.host == "movie.douban.com") "NativeBrowser/Test" else "Mozilla/5.0",
                request.headers[HttpHeaders.UserAgent])
            observed += request.url.host to request.headers[HttpHeaders.Cookie]
            when (request.url.host) {
                "movie.douban.com" -> respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://external.org/config"))
                else -> respond("{}")
            }
        }) { followRedirects = false }, doubanSession = session)
        try {
            repo.configuration("https://movie.douban.com/config")
            assertEquals(listOf("movie.douban.com" to "dbcl2=test-session", "external.org" to null), observed)
            assertEquals(listOf("https://movie.douban.com/config"), session.reads)
            assertEquals(listOf("https://movie.douban.com/config"), session.writes)
        } finally { repo.close() }
    }

    @Test fun externalRequestsCannotAcquireCookiesViaRedirect() = runTest {
        val session = Session()
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            assertNull(request.headers[HttpHeaders.Cookie])
            if (request.url.host == "external.org") respond("", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://movie.douban.com/config"))
            else respond("{}")
        }) { followRedirects = false }, doubanSession = session)
        try {
            repo.configuration("https://external.org/config")
            assertTrue(session.reads.isEmpty())
            assertTrue(session.writes.isEmpty())
        } finally { repo.close() }
    }

    @Test fun cookiePresenceAndChallengeDoNotProveIdentity() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine {
            respond("<html>请完成验证码</html>")
        }) { followRedirects = false }, doubanSession = Session())
        try { assertNull(repo.doubanIdentity()) } finally { repo.close() }
    }

    @Test fun verifiesOfficialAccountNavigationAfterMineRedirect() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.encodedPath == "/mine/") respond("", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://www.douban.com/people/123/"))
            else respond("""<div class="nav-user-account"><a class="bn-more">测试的帐号</a>
                <a href="https://www.douban.com/accounts/logout?ck=hidden">退出</a></div>""")
        }) { followRedirects = false }, doubanSession = Session())
        try { assertEquals("测试", repo.doubanIdentity()) } finally { repo.close() }
    }

    @Test fun successfulVerificationRefreshesAndDisconnectClearsSession() = runTest {
        val session = Session()
        var refreshes = 0
        val connection = DoubanConnection(session, backgroundScope, { "测试" }) { refreshes++ }
        connection.verify()
        runCurrent()
        assertEquals("测试", connection.state.value.nickname)
        assertEquals(1, refreshes)
        connection.disconnect()
        runCurrent()
        assertTrue(session.cleared)
        assertNull(connection.state.value.nickname)
    }

    @Test fun loginRedirectSupersedesPendingStartupVerification() = runTest {
        var calls = 0
        var cancelled = false
        var refreshes = 0
        val connection = DoubanConnection(Session(), backgroundScope, {
            if (++calls == 1) {
                try { awaitCancellation() } finally { cancelled = true }
            }
            "豆瓣里的名字"
        }) { refreshes++ }
        connection.verify(silent = true)
        runCurrent()
        connection.verify()
        runCurrent()
        assertTrue(cancelled)
        assertEquals("豆瓣里的名字", connection.state.value.nickname)
        assertFalse(connection.state.value.checking)
        assertEquals(1, refreshes)
        connection.disconnect()
        runCurrent()
        assertNull(connection.state.value.nickname)
    }

    @Test fun verificationFailureKeepsRetryStateWithoutRefreshing() = runTest {
        var refreshes = 0
        val connection = DoubanConnection(Session(), backgroundScope, { null }) { refreshes++ }
        connection.verify()
        runCurrent()
        assertNull(connection.state.value.nickname)
        assertNotNull(connection.state.value.error)
        assertFalse(connection.state.value.checking)
        assertEquals(0, refreshes)
    }
}
