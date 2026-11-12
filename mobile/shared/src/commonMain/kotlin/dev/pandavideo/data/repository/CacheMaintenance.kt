package dev.pandavideo.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CacheUsage(
    val posterBytes: Long = 0,
    val contentBytes: Long = 0,
    val contentEntries: Long = 0,
) {
    val totalBytes: Long get() = posterBytes + contentBytes
}

internal data class CacheMaintenanceState(
    val usage: CacheUsage? = null,
    val scanning: Boolean = false,
    val cleaning: Boolean = false,
    val error: String? = null,
    val cleanedBytes: Long? = null,
) {
    val busy: Boolean get() = scanning || cleaning
}

/**
 * Serializes user-requested scans and cleanup across settings screen recreation.
 * Only expendable stores are supplied; this component cannot delete preferences or video files.
 */
internal class CacheMaintenance(
    private val scope: CoroutineScope,
    private val posterBytes: suspend () -> Long,
    private val contentUsage: suspend () -> ContentCacheUsage,
    private val clearPosters: suspend () -> Unit,
    private val clearContent: suspend () -> Unit,
    private val onFailure: (String, () -> Unit) -> Unit = { _, _ -> },
) {
    private val mutable = MutableStateFlow(CacheMaintenanceState())
    val state = mutable.asStateFlow()

    private suspend fun measure(): CacheUsage {
        val posters = posterBytes().coerceAtLeast(0)
        val content = contentUsage()
        return CacheUsage(posters, content.bytes.coerceAtLeast(0), content.entries.coerceAtLeast(0))
    }

    /** Scans managed caches; failures remain retryable and never appear as zero-byte results. */
    fun scan() {
        if (state.value.busy) return
        mutable.value = state.value.copy(scanning = true, error = null, cleanedBytes = null)
        scope.launch {
            try {
                mutable.value = CacheMaintenanceState(usage = measure())
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutable.value = CacheMaintenanceState(error = "扫描失败，请重试")
                onFailure("缓存扫描失败，请重试") { scan() }
            } finally {
                mutable.value = state.value.copy(scanning = false)
            }
        }
    }

    /** Clears both managed categories, then rescans instead of assuming all bytes were freed. */
    fun clear() {
        if (state.value.busy) return
        val before = state.value.usage?.totalBytes ?: return
        mutable.value = state.value.copy(cleaning = true, error = null, cleanedBytes = null)
        scope.launch {
            var failed = false
            try {
                // A failure in one store must not prevent the other from being cleaned.
                // Cancellation still propagates, and partial cleanup is explicitly reported.
                for (clear in listOf(clearPosters, clearContent)) {
                    try { clear() }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { failed = true }
                }
                val remaining = measure()
                mutable.value = CacheMaintenanceState(
                    usage = remaining,
                    error = if (failed) "部分缓存清理失败，请重试" else null,
                    cleanedBytes = if (failed) null else (before - remaining.totalBytes).coerceAtLeast(0),
                )
                if (failed) onFailure("部分缓存清理失败，请重试") { clear() }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutable.value = CacheMaintenanceState(error = "清理后扫描失败，请重新扫描")
                onFailure("清理后扫描失败，请重新扫描") { scan() }
            } finally {
                mutable.value = state.value.copy(cleaning = false)
            }
        }
    }
}

/** Formats a byte estimate using binary units without rounding small caches down to zero. */
internal fun formatCacheBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    if (value < 1024) return "$value B"
    val unit = when {
        value >= 1024L * 1024 * 1024 -> 1024L * 1024 * 1024 to "GB"
        value >= 1024L * 1024 -> 1024L * 1024 to "MB"
        else -> 1024L to "KB"
    }
    val whole = value / unit.first
    val decimal = (value % unit.first) * 10 / unit.first
    return "$whole.$decimal ${unit.second}"
}
