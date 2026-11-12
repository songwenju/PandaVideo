package dev.pandavideo.data.repository

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.platform.currentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ResourceStatus { Available, Missing, Unknown }

/**
 * Keeps confirmed resource checks separate from catalog snapshots.
 *
 * Unknown is deliberately visible: a timeout, CAPTCHA, parser failure, or offline launch
 * is not evidence that a film has no resource. Only successful checks are persisted.
 * No media URL is cached here, and resource checks never download video data.
 */
internal class CatalogAvailability(
    private val database: CatalogDatabase?,
    private val ruleKey: () -> String,
    private val check: suspend (CatalogItem) -> ResourceStatus,
    private val now: () -> Long = ::currentEpochMillis,
) {
    private val requests = Semaphore(3)
    private val lock = Mutex()
    private val memory = linkedMapOf<String, StoredResponse>()

    /** Discards transient resource checks after the persistent response cache is cleared. */
    suspend fun clearMemory() = lock.withLock { memory.clear() }

    private fun key(item: CatalogItem) =
        "availability:v1:${item.url}:${item.title}:${item.year}:${item.kind}:${ruleKey()}"

    private suspend fun cached(key: String): ResourceStatus {
        val saved = lock.withLock { memory[key] } ?: try {
            database?.readResponse(key)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null } ?: return ResourceStatus.Unknown
        val status = ResourceStatus.entries.firstOrNull { it.name == saved.payload }
            ?: return ResourceStatus.Unknown
        // Newly added releases should become visible promptly; positives can be reused longer.
        val ttl = if (status == ResourceStatus.Missing) 15L * 60 * 1000 else 6L * 60 * 60 * 1000
        return status.takeIf { now() - saved.savedAt in 0 until ttl }
            ?: ResourceStatus.Unknown
    }

    suspend fun visible(page: CatalogPage): CatalogPage {
        val items = page.items.filter { cached(key(it)) != ResourceStatus.Missing }
        return page.copy(items = items)
    }

    suspend fun inspect(items: List<CatalogItem>, onMissing: (CatalogItem) -> Unit) = coroutineScope {
        items.distinctBy { key(it) }.forEach { item ->
            launch {
                requests.withPermit {
                    val key = key(item)
                    var status = cached(key)
                    if (status == ResourceStatus.Unknown) {
                        status = try {
                            withTimeoutOrNull(5_000) { check(item) } ?: ResourceStatus.Unknown
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { ResourceStatus.Unknown }
                        if (status != ResourceStatus.Unknown) {
                            val saved = StoredResponse(status.name, now())
                            lock.withLock {
                                memory[key] = saved
                                if (memory.size > 512) memory.remove(memory.keys.first())
                            }
                            try { database?.writeResponse(key, saved.payload, saved.savedAt) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { /* A cache write cannot invalidate a completed check. */ }
                        }
                    }
                    if (status == ResourceStatus.Missing) onMissing(item)
                }
            }
        }
    }
}
