package dev.pandavideo

import dev.pandavideo.core.platform.KeyValueStorage
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** Keeps engine work and request budgets on the same virtual clock. */
fun TestScope.mockEngine(
    backupSources: Boolean = false,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) =
    MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler { request ->
            // Existing single-provider tests model all other providers as offline. Dedicated
            // failover tests opt in and supply complete mirror/search/media responses.
            if (!backupSources && (request.url.host == "japi.zxfmj.com" ||
                request.url.host.startsWith("pv2026.") ||
                request.url.host in setOf("jdomain.oss-accelerate.aliyuncs.com", "dns.alidns.com", "doh.pub", "1.1.1.1")))
                respond("", HttpStatusCode.ServiceUnavailable)
            // Pomo parser fixtures resolve to this dedicated synthetic HLS endpoint.
            // Model its byte-range probe separately from the provider JSON handler.
            else if (request.url.host == "media" && request.url.encodedPath == "/stream.m3u8" &&
                request.headers["Range"] != null)
                respond("#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n")
            else if (request.url.host == "media" && request.headers["Range"] != null)
                respond("", HttpStatusCode.ServiceUnavailable)
            else handler(request)
        }
    })

/** In-memory [KeyValueStorage] so preference and user-store tests need no platform APIs. */
class FakeStorage : KeyValueStorage {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
