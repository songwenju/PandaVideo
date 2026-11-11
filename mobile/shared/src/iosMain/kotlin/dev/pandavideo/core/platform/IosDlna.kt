@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo.core.platform

import dev.pandavideo.core.player.ssdpLocation
import dev.pandavideo.core.player.ssdpRequest
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.posix.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/**
 * Uses SSDP with unicast replies. iOS multicast transmission requires Apple's multicast
 * entitlement; directed discovery remains available when a user supplies the TV's IPv4 address.
 * The socket timeout bounds both cancellation latency and the time spent holding native memory.
 */
internal suspend fun discoverIosDlna(host: String?): List<String> = withContext(Dispatchers.Default) {
    memScoped {
        val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        if (fd < 0) error("无法搜索电视，请检查本地网络权限")
        try {
            val timeout = alloc<timeval> { tv_sec = 0; tv_usec = 250_000 }
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
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
            val request = ssdpRequest(host ?: "239.255.255.250")
            request.usePinned { pinned ->
                repeat(2) {
                    if (sendto(fd, pinned.addressOf(0), request.size.convert(), 0,
                            destination.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
                        error("无法自动搜索，请允许本地网络访问或填写电视 IP")
                    }
                }
            }
            val buffer = ByteArray(16_384)
            val locations = linkedSetOf<String>()
            val started = TimeSource.Monotonic.markNow()
            while (started.elapsedNow() < 4.seconds) {
                ensureActive()
                val received = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }
                if (received > 0) {
                    ssdpLocation(buffer.decodeToString(0, received.toInt()))?.let(locations::add)
                    if (locations.size >= 64) break
                } else if (received < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                    error("电视搜索失败，请检查本地网络权限")
                }
            }
            locations.toList()
        } finally {
            close(fd)
        }
    }
}
