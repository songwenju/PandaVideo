@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo.core.platform

import dev.pandavideo.core.player.DlnaException
import dev.pandavideo.core.player.ssdpLocation
import dev.pandavideo.core.player.ssdpRequests
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/** Discovers our Bonjour receiver without requiring Apple's restricted multicast entitlement. */
internal suspend fun discoverIosDlna(host: String?): List<String> = coroutineScope {
    if (host != null) return@coroutineScope discoverIosSsdp(host)
    val bonjour = async { discoverIosBonjour() }
    val ssdp = async {
        try { discoverIosSsdp(null) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: DlnaException) { emptyList() }
    }
    // Bonjour is system-managed and permitted by NSBonjourServices. Ordinary DLNA TVs
    // still use SSDP when provisioning allows multicast, or directed TV IP discovery.
    (bonjour.await() + ssdp.await()).distinct()
}

private suspend fun discoverIosBonjour(): List<String> = withContext(Dispatchers.Main) {
    val locations = linkedSetOf<String>()
    val pending = mutableListOf<NSNetService>()
    var searchFailed = false
    val resolver = object : NSObject(), NSNetServiceDelegateProtocol {
        override fun netServiceDidResolveAddress(sender: NSNetService) {
            // Use the resolved IPv4 address instead of relying on NSURLSession to resolve
            // a .local hostname a second time. The descriptor port comes from Bonjour.
            if (sender.port !in 1..65535) return
            sender.addresses?.forEach { value ->
                val data = value as? NSData ?: return@forEach
                if (data.length < sizeOf<sockaddr_in>().toULong()) return@forEach
                val bytes = data.bytes?.reinterpret<UByteVar>() ?: return@forEach
                if (bytes[1].toInt() != AF_INET) return@forEach
                val ip = (4..7).joinToString(".") { bytes[it].toString() }
                locations.add("http://$ip:${sender.port}/description.xml")
            }
        }
    }
    val delegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {
        override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
            searchFailed = true
        }
        override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
            if (pending.size >= 64) return
            pending.add(didFindService)
            didFindService.delegate = resolver
            didFindService.resolveWithTimeout(4.0)
        }
    }
    val browser = NSNetServiceBrowser()
    browser.delegate = delegate
    try {
        browser.searchForServicesOfType("_pandacast._tcp.", inDomain = "local.")
        delay(5_000)
        if (searchFailed && locations.isEmpty())
            throw DlnaException("无法搜索接收端，请在系统设置中允许熊猫视频访问本地网络")
        locations.toList()
    } finally {
        browser.stop()
        pending.forEach { it.stop(); it.delegate = null }
        browser.delegate = null
    }
}

/**
 * Uses SSDP with unicast replies. iOS multicast transmission requires Apple's multicast
 * entitlement; directed discovery remains available when a user supplies the TV's IPv4 address.
 * The socket timeout bounds both cancellation latency and the time spent holding native memory.
 */
private suspend fun discoverIosSsdp(host: String?): List<String> = withContext(Dispatchers.Default) {
    memScoped {
        val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        if (fd < 0) throw DlnaException("无法搜索电视，请检查本地网络权限")
        try {
            val timeout = alloc<timeval> { tv_sec = 0; tv_usec = 250_000 }
            if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert()) < 0)
                throw DlnaException("无法初始化电视搜索，请重试")
            val destination = alloc<sockaddr_in>()
            memset(destination.ptr, 0, sizeOf<sockaddr_in>().convert())
            destination.sin_len = sizeOf<sockaddr_in>().convert()
            destination.sin_family = AF_INET.convert()
            // Write network-order bytes directly: Darwin's htons macro is not exported by
            // Kotlin/Native, and the optional IP has already been validated in shared code.
            val port = destination.ptr.reinterpret<UByteVar>()
            port[2] = (1900 shr 8).toUByte()
            port[3] = (1900 and 255).toUByte()
            val address = destination.sin_addr.ptr.reinterpret<UByteVar>()
            (host ?: "239.255.255.250").split('.').forEachIndexed { index, part ->
                address[index] = part.toUByte()
            }
            var sent = false
            fun sendQueries() {
                ssdpRequests().forEach { request ->
                    request.usePinned { pinned ->
                        if (sendto(fd, pinned.addressOf(0), request.size.convert(), 0,
                                destination.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) >= 0)
                            sent = true
                    }
                }
            }
            sendQueries()
            val buffer = ByteArray(16_384)
            val locations = linkedSetOf<String>()
            val started = TimeSource.Monotonic.markNow()
            var rounds = 1
            while (started.elapsedNow() < 5.seconds) {
                ensureActive()
                // iOS may deny the first datagram while its local-network prompt is visible.
                // Give the user time to grant access before declaring discovery unavailable.
                if (rounds < 5 && started.elapsedNow() >= rounds.seconds) {
                    sendQueries()
                    rounds++
                }
                val received = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }
                if (received > 0) {
                    ssdpLocation(buffer.decodeToString(0, received.toInt()))?.let(locations::add)
                    if (locations.size >= 64) break
                } else if (received < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                    throw DlnaException("电视搜索失败，请检查本地网络权限")
                }
            }
            if (!sent) throw DlnaException(if (host == null)
                "无法进行 DLNA 自动搜索，请检查本地网络权限，或使用电视 IP 搜索"
                else "无法搜索此电视，请允许本地网络访问并检查电视 IP")
            locations.toList()
        } finally {
            close(fd)
        }
    }
}
