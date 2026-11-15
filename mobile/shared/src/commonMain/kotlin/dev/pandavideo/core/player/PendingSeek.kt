package dev.pandavideo.core.player

/**
 * Keeps an asynchronous seek target visible until the host acknowledges that request.
 * Native timeline samples can still contain the old position while buffering a seek.
 */
internal class PendingSeek {
    data class Request(val sequence: Long, val mediaId: Long, val position: Long)
    private var sequence = 0L
    private var request: Request? = null

    fun begin(mediaId: Long, position: Long): Request =
        Request(++sequence, mediaId, position).also { request = it }

    fun active(mediaId: Long): Boolean = request?.mediaId == mediaId

    fun position(mediaId: Long, nativePosition: Long): Long =
        request?.takeIf { it.mediaId == mediaId }?.position ?: nativePosition

    /** An interrupted older seek must not clear a newer request's target. */
    fun complete(value: Request): Boolean {
        if (request != value) return false
        request = null
        return true
    }

    fun clear() { request = null }
}
