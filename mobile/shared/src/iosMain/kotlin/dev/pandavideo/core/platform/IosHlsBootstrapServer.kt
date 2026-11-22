@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo.core.platform

import dev.pandavideo.core.player.HlsBootstrap
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.darwin.*
import platform.posix.*

/**
 * Serves exactly one immutable bootstrap on loopback. AVPlayer cannot reliably play a local
 * file m3u8, so it receives HTTP while all uncached segments retain their absolute origin URLs.
 * The owning player retains this server through playback/PiP and closes it only after replacing
 * the item. No proxy, arbitrary files or LAN listener are exposed.
 */
internal class IosHlsBootstrapServer private constructor(
    val url: String,
    private val job: Job,
) {
    fun close() {
        // The dispatch cancellation handler closes the descriptor after pending reads finish.
        job.cancel()
    }

    companion object {
        fun create(bootstrap: HlsBootstrap, scope: CoroutineScope): IosHlsBootstrapServer =
                memScoped {
                    val fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
                    check(fd >= 0)
                    var reader: dispatch_source_t = null
                    var resumed = false
                    try {
                        val address = alloc<sockaddr_in>()
                        memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
                        address.sin_len = sizeOf<sockaddr_in>().convert()
                        address.sin_family = AF_INET.convert()
                        address.sin_addr.ptr.reinterpret<UByteVar>().apply {
                            this[0] = 127u; this[1] = 0u; this[2] = 0u; this[3] = 1u
                        }
                        check(bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0)
                        check(listen(fd, 4) == 0)
                        val length = alloc<UIntVar> { value = sizeOf<sockaddr_in>().convert() }
                        check(getsockname(fd, address.ptr.reinterpret(), length.ptr) == 0)
                        val bytes = address.ptr.reinterpret<UByteVar>()
                        val port = bytes[2].toInt() * 256 + bytes[3].toInt()
                        // Drain only pending connections; client reads have independent timeouts.
                        check(fcntl(fd, F_SETFL, O_NONBLOCK) == 0)
                        val manifest = bootstrap.manifest("first.ts").encodeToByteArray()
                        val source = checkNotNull(dispatch_source_create(DISPATCH_SOURCE_TYPE_READ,
                            fd.convert(), 0u, dispatch_queue_create("dev.pandavideo.bootstrap", null)))
                        reader = source
                        dispatch_source_set_cancel_handler(source) { platform.posix.close(fd) }
                        val job = Job(scope.coroutineContext[Job])
                        // A timer previously called accept() every 20ms for the full episode,
                        // even after AVPlayer consumed the bootstrap. Read readiness sleeps in
                        // the kernel until a connection arrives, with no idle polling. Accept
                        // and serve on this serial queue so readiness cannot spin while another
                        // worker is still draining the same listening socket.
                        dispatch_source_set_event_handler(source) {
                            while (job.isActive) {
                                val client = accept(fd, null, null)
                                if (client < 0) break
                                try {
                                    configure(client)
                                    serve(client, manifest, bootstrap.segment)
                                } finally { platform.posix.close(client) }
                            }
                        }
                        job.invokeOnCompletion { dispatch_source_cancel(source) }
                        dispatch_resume(source)
                        resumed = true
                        IosHlsBootstrapServer("http://127.0.0.1:$port/index.m3u8", job)
                    } catch (e: Throwable) {
                        val source = reader
                        if (source == null) platform.posix.close(fd) else {
                            dispatch_source_cancel(source)
                            // A suspended source must be resumed for its cancellation handler.
                            if (!resumed) dispatch_resume(source)
                        }
                        throw e
                    }
                }

        private fun configure(fd: Int) = memScoped {
            // Darwin inherits O_NONBLOCK from the listening socket. Restore blocking reads
            // with a timeout, or recv() can race the client's first HTTP bytes and close it.
            fcntl(fd, F_SETFL, 0)
            val timeout = alloc<timeval> { tv_sec = 1; tv_usec = 0 }
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert())
            val one = alloc<IntVar> { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
        }

        private fun serve(fd: Int, manifest: ByteArray, segment: ByteArray) {
            val buffer = ByteArray(8_192)
            var size = 0
            while (size < buffer.size) {
                val count = buffer.usePinned {
                    recv(fd, it.addressOf(size), (buffer.size - size).convert(), 0)
                }.toInt()
                if (count <= 0) return
                size += count
                if ("\r\n\r\n" in buffer.decodeToString(0, size)) break
            }
            val request = buffer.decodeToString(0, size)
            if ("\r\n\r\n" !in request) return
            val response = dev.pandavideo.core.player.bootstrapHttpResponse(request, manifest, segment)
            sendAll(fd, response.header.encodeToByteArray())
            if (response.body.isNotEmpty()) sendAll(fd, response.body)
        }

        private fun sendAll(fd: Int, bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = bytes.usePinned {
                    send(fd, it.addressOf(offset), (bytes.size - offset).convert(), 0)
                }.toInt()
                if (count <= 0) return
                offset += count
            }
        }
    }
}
