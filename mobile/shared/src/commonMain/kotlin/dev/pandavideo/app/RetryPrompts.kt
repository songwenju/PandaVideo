package dev.pandavideo.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Formats a bounded cause chain without rendering request URLs or authentication values. */
internal fun failureDetails(error: Throwable): String {
    val visited = mutableListOf<Throwable>()
    val reasons = mutableListOf<String>()
    var current: Throwable? = error
    while (current != null && visited.size < 4 && visited.none { it === current }) {
        val cause = current
        visited += cause
        val message = cause.message?.trim().takeUnless { it.isNullOrEmpty() }
            ?: cause::class.simpleName ?: "未知异常"
        // Native network errors and parser errors can embed the entire request or response.
        // Keep useful status/timeout descriptions while removing URLs and named credentials.
        val safe = message
            .replace(Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE), "[地址已隐藏]")
            .replace(Regex("""(?i)\b(authorization|cookie)\s*[:=]\s*[^\r\n]+"""), "$1=[已隐藏]")
            .replace(Regex("""(?i)\b(token|access_token|refresh_token|password|secret|signature|api_key)\b["']?\s*[:=]\s*["']?[^,\s"'}]+"""), "$1=[已隐藏]")
            .take(500)
        if (safe !in reasons) reasons += safe
        current = cause.cause
    }
    return "具体原因：\n" + reasons.joinToString("\n原因：")
}

/** An in-memory failure action; request parameters and URLs are never rendered or persisted. */
internal data class RetryPrompt(
    val id: Long,
    val key: String,
    val title: String,
    val message: String,
    val destination: String?,
    val available: () -> Boolean,
    val action: () -> Unit,
)

/** Keeps one prompt per operation and lets the host display only one dialog at a time. */
internal class RetryPrompts {
    private var nextId = 0L
    private val mutable = MutableStateFlow<List<RetryPrompt>>(emptyList())
    val pending = mutable.asStateFlow()

    fun offer(
        key: String, title: String, message: String, destination: String? = null,
        available: () -> Boolean = { true }, action: () -> Unit,
    ) {
        discardUnavailable()
        if (pending.value.any { it.key == key }) return
        mutable.value += RetryPrompt(++nextId, key, title, message, destination, available, action)
    }

    /** Starting a replacement request invalidates every prompt belonging to its old attempt. */
    fun clear(key: String) {
        mutable.value = pending.value.filterNot { it.key == key }
    }

    fun discardUnavailable() {
        mutable.value = pending.value.filter { it.available() }
    }

    fun dismiss(id: Long) {
        mutable.value = pending.value.filterNot { it.id == id }
    }

    /** Remove before running: a synchronous retry failure must be able to show a fresh prompt. */
    fun retry(id: Long) {
        val prompt = pending.value.firstOrNull { it.id == id } ?: return
        dismiss(id)
        if (prompt.available()) prompt.action()
    }
}
