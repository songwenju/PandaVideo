package dev.pandavideo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pandavideo.core.platform.discoverAndroidDlna
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the production datagram loop against a renderer that ignores device-type searches. */
@RunWith(AndroidJUnit4::class)
class AndroidDlnaDeviceTest {
    @Test fun transportOnlyRendererAnswersDirectedSearchAndDuplicateRepliesAreDeduplicated() = runBlocking {
        exercise("127.0.0.1")
    }

    @Suppress("DEPRECATION")
    @Test fun directedSearchUsesTheConnectedWifiInterface() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = connectivity.allNetworks.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val address = wifi?.let(connectivity::getLinkProperties)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        assumeTrue("A connected IPv4 Wi-Fi network is required", address != null)
        exercise(requireNotNull(address))
    }

    private suspend fun exercise(host: String) = kotlinx.coroutines.coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Use an ephemeral port: the headset may already own UDP/1900 for its system renderer.
        DatagramSocket(0, InetAddress.getByName(host)).use { responder ->
            responder.soTimeout = 8_000
            val location = "http://127.0.0.1:12345/root.xml"
            val reply = "HTTP/1.1 200 OK\r\nLOCATION: $location\r\n\r\n".encodeToByteArray()
            val server = async(Dispatchers.IO) {
                val buffer = ByteArray(2_048)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    responder.receive(packet)
                    val request = packet.data.decodeToString(packet.offset, packet.offset + packet.length)
                    assertTrue("\r\nHOST: 239.255.255.250:1900\r\n" in request)
                    if ("\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\n" in request) {
                        repeat(2) { responder.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
                        break
                    }
                }
            }
            try {
                val discovered = withTimeout(8_000) {
                    discoverAndroidDlna(context, host, responder.localPort)
                }
                server.await()
                assertEquals(listOf(location), discovered)
            } finally {
                responder.close()
                server.cancel()
            }
        }
    }
}
