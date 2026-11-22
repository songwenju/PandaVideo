package dev.pandavideo

import dev.pandavideo.core.player.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Runs the production sender against a real receiver over an explicit adb-forwarded URL. */
class PandaCastReceiverInteropTest {
    @Test fun productionSenderControlsRealMedia3Receiver() = runBlocking {
        val endpoint = System.getenv("PANDA_CAST_TEST_URL")
        assumeTrue("Set PANDA_CAST_TEST_URL for live receiver verification", !endpoint.isNullOrBlank())
        val engine = MockEngine { request ->
            withContext(Dispatchers.IO) {
                val connection = URI(request.url.toString()).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 6000
                connection.requestMethod = request.method.value
                request.headers.forEach { key, values -> connection.setRequestProperty(key, values.joinToString(",")) }
                if (request.body is TextContent) {
                    val content = request.body as TextContent
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", content.contentType.toString())
                    connection.outputStream.use { it.write(content.text.encodeToByteArray()) }
                }
                try {
                    val status = connection.responseCode
                    val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    respond(body, HttpStatusCode.fromValue(status))
                } finally { connection.disconnect() }
            }
        }
        val base = HttpClient(engine)
        val client = DlnaClient(base)
        val device = client.device("$endpoint/description.xml")
        try {
            val target = assertNotNull(device)
            assertContains(target.name, "熊猫投屏")
            // Descriptor advertises the LAN address. ADB forwards only localhost on the host.
            val forwarded = target.copy(controlUrl = "$endpoint/control")
            val mediaUrl = System.getenv("PANDA_CAST_MEDIA_URL") ?: "http://127.0.0.1:8765/probe.mp4"
            client.setMedia(forwarded, MediaSource(1, mediaUrl, "video", "协议联调 & 测试"))
            client.play(forwarded)
            var time = DlnaPosition(0, 0)
            withTimeout(15_000) {
                while (time.duration == 0L) { delay(250); time = client.position(forwarded) }
            }
            assertTrue(time.duration >= 29_000, "Receiver must report native fixture duration")
            assertTrue(client.playing(forwarded))
            client.pause(forwarded)
            assertFalse(client.playing(forwarded))
            client.seek(forwarded, 12_000)
            delay(300)
            assertEquals(12_000, client.position(forwarded).position)
            client.play(forwarded)
            // A real LAN seek can buffer before playback advances. Wait for native
            // progress with a deadline instead of assuming 1.2 seconds is sufficient.
            withTimeout(15_000) {
                while (client.position(forwarded).position < 13_000) delay(250)
            }
            client.stop(forwarded)
            assertFalse(client.playing(forwarded))
        } finally {
            device?.let { runCatching { client.stop(it.copy(controlUrl = "$endpoint/control")) } }
            client.close(); base.close()
        }
    }
}
