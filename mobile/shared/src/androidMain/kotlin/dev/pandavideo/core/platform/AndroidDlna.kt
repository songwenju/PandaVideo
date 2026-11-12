package dev.pandavideo.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import dev.pandavideo.core.player.DlnaException
import dev.pandavideo.core.player.ssdpLocation
import dev.pandavideo.core.player.ssdpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/**
 * Sends SSDP on a connected local network instead of the default mobile/VPN route.
 * Replies use an ephemeral local port; the Wi-Fi multicast lock lasts only for this search.
 *
 * @param context Application context providing the network and Wi-Fi services.
 * @param host Optional directed IPv4 destination.
 * @param discoveryPort UDP destination port; device tests use an isolated loopback responder.
 */
@Suppress("DEPRECATION") // A short-lived search only needs a snapshot, not a persistent network callback.
internal suspend fun discoverAndroidDlna(
    context: Context, host: String?, discoveryPort: Int = 1900,
): List<String> = withContext(Dispatchers.IO) {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = wifi?.createMulticastLock("suixinkan-dlna")?.apply { setReferenceCounted(false) }
    val locations = linkedSetOf<String>()
    val destination = InetAddress.getByName(host ?: "239.255.255.250")
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val localNetwork = connectivity?.allNetworks?.firstOrNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }
    // Loopback responders exercise the real datagram code without routing test traffic onto LAN.
    if (localNetwork == null && !destination.isLoopbackAddress)
        throw DlnaException("请连接 Wi-Fi 或有线局域网后搜索投屏设备")
    try {
        if (host == null) lock?.acquire()
        MulticastSocket(null).use { socket ->
            if (!destination.isLoopbackAddress && localNetwork != null) {
                val properties = connectivity.getLinkProperties(localNetwork)
                    ?: throw DlnaException("无法读取局域网连接，请检查 Wi-Fi")
                val address = properties.linkAddresses.firstOrNull { it.address is Inet4Address }?.address
                    ?: throw DlnaException("当前网络没有可用的 IPv4 地址，请检查 Wi-Fi 连接")
                localNetwork.bindSocket(socket)
                socket.bind(InetSocketAddress(address, 0))
                properties.interfaceName?.let(NetworkInterface::getByName)?.let {
                    socket.networkInterface = it
                }
            } else socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            socket.timeToLive = 2
            socket.soTimeout = 250
            val started = TimeSource.Monotonic.markNow()
            fun sendQueries() {
                ssdpRequests().forEach { request ->
                    socket.send(DatagramPacket(request, request.size, destination, discoveryPort))
                }
            }
            sendQueries()
            var repeated = false
            val buffer = ByteArray(16_384)
            while (started.elapsedNow() < 5.seconds) {
                ensureActive()
                // Spacing retries allows a busy renderer to finish its randomized MX reply.
                if (!repeated && started.elapsedNow() >= 1.seconds) {
                    sendQueries()
                    repeated = true
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    ssdpLocation(packet.data.decodeToString(packet.offset, packet.offset + packet.length))
                        ?.let(locations::add)
                    if (locations.size >= 64) break
                } catch (_: SocketTimeoutException) { /* Keep the socket cancellable between replies. */ }
            }
        }
    } finally {
        if (lock?.isHeld == true) lock.release()
    }
    locations.toList()
}
