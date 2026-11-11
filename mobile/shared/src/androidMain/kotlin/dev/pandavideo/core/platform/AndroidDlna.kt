package dev.pandavideo.core.platform

import android.content.Context
import android.net.wifi.WifiManager
import dev.pandavideo.core.player.ssdpLocation
import dev.pandavideo.core.player.ssdpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/** SSDP uses an ephemeral reply port; a multicast lock prevents Wi-Fi filtering during discovery. */
internal suspend fun discoverAndroidDlna(context: Context, host: String?): List<String> = withContext(Dispatchers.IO) {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = wifi?.createMulticastLock("suixinkan-dlna")?.apply { setReferenceCounted(false) }
    val locations = linkedSetOf<String>()
    try {
        if (host == null) lock?.acquire()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val destination = InetAddress.getByName(host ?: "239.255.255.250")
            val request = ssdpRequest(host ?: "239.255.255.250")
            repeat(2) { socket.send(DatagramPacket(request, request.size, destination, 1900)) }
            val started = TimeSource.Monotonic.markNow()
            val buffer = ByteArray(16_384)
            while (started.elapsedNow() < 4.seconds) {
                ensureActive()
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
