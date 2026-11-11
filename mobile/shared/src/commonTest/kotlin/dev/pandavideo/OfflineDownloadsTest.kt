@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pandavideo

import dev.pandavideo.core.model.*
import dev.pandavideo.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

class OfflineDownloadsTest {
    private fun info(episode: String = "e1") = PlaybackInfo(
        "Film", "https://media.test/expiring-token.m3u8", "hls",
        listOf(Episode("e1", "第一集", 1), Episode("e2", "第二集", 1)),
        episode, "https://source.test/detail/film",
    )
    private class Engine : OfflineEngine {
        val started = mutableListOf<String>()
        val waits = mutableMapOf<String, CompletableDeferred<String>>()
        val completed = mutableSetOf<String>()
        val callbacks = mutableMapOf<String, (Float?) -> Unit>()
        override fun supports(kind: String) = kind == "hls"
        override suspend fun download(id: String, info: PlaybackInfo, progress: (Float?) -> Unit): String {
            started += id
            callbacks[id] = progress
            return CompletableDeferred<String>().also { waits[id] = it }.await()
        }
        fun finish(id: String) { completed += id; waits.getValue(id).complete(id) }
        override fun cancel(id: String) { waits[id]?.cancel() }
        override suspend fun remove(id: String) { completed -= id }
        override fun asset(id: String, locator: String) =
            if (id == locator && id in completed) OfflineAsset("file:///$id.mp4", "offline") else null
    }

    @Test fun queueIsSerialAndDuplicatesUseStableEpisodeIdentity() = runTest {
        val engine = Engine()
        val storage = FakeStorage()
        val store = OfflineDownloads(storage, engine, backgroundScope) { info(it.episode ?: "e1") }
        val first = store.enqueue(info())
        assertEquals(first, store.enqueue(info().copy(url = "https://media.test/new-token")))
        val second = store.enqueue(info("e2"))
        runCurrent()
        assertEquals(listOf(first), engine.started)
        engine.finish(first); runCurrent()
        assertEquals(listOf(first, second), engine.started)
        assertEquals(DownloadStatus.Completed, store.tasks.value.find { it.id == first }?.status)
        assertFalse(storage.getString("offline_downloads_v1").orEmpty().contains("expiring-token"))
        engine.finish(second); runCurrent()
        assertTrue(store.tasks.value.all { it.status == DownloadStatus.Completed })
    }

    @Test fun interruptedWorkRestoresPausedAndNeverPretendsToBeComplete() = runTest {
        val engine = Engine()
        val storage = FakeStorage()
        val store = OfflineDownloads(storage, engine, backgroundScope) { info() }
        store.enqueue(info()); store.enqueue(info("e2")); runCurrent()
        val restored = OfflineDownloads(storage, Engine(), backgroundScope) { error("must not resolve") }
        assertEquals(2, restored.tasks.value.size)
        assertTrue(restored.tasks.value.all { it.status == DownloadStatus.Paused })
    }

    @Test fun pauseIgnoresLateProgressAndResumeStartsAnotherAttempt() = runTest {
        val engine = Engine()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope) { info() }
        val id = store.enqueue(info()); runCurrent()
        val oldProgress = engine.callbacks.getValue(id)
        store.pause(id); runCurrent()
        engine.callbacks.getValue(id)(0.8f); runCurrent()
        assertEquals(DownloadStatus.Paused, store.tasks.value.single().status)
        assertNull(store.tasks.value.single().progress)
        store.resume(id); runCurrent()
        assertEquals(2, engine.started.size)
        oldProgress(0.8f); runCurrent()
        assertNull(store.tasks.value.single().progress)
        engine.finish(id); runCurrent()
        assertEquals(DownloadStatus.Completed, store.tasks.value.single().status)
    }

    @Test fun completedEpisodeResolvesLocallyWithoutNetworkAndOmitsUncachedNextEpisode() = runTest {
        val engine = Engine()
        var requests = 0
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope) { requests++; info() }
        val id = store.enqueue(info()); runCurrent(); engine.finish(id); runCurrent()
        val local = assertNotNull(store.offline(PlayerRequest(page = info().pageUrl, episode = "e1")))
        assertEquals("offline", local.kind)
        assertEquals(listOf("e1"), local.episodes.map { it.path })
        assertEquals(1, requests)
        assertNull(store.offline(PlayerRequest(page = info().pageUrl, episode = "e2")))
        store.remove(id); runCurrent()
        assertTrue(store.tasks.value.isEmpty())
        assertNull(store.offline(PlayerRequest(page = info().pageUrl)))
    }

    @Test fun sourceFailoverCannotCacheAnotherProviderAsTheRequestedEpisode() = runTest {
        val engine = Engine()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope) {
            info().copy(pageUrl = "https://other.test/detail")
        }
        store.enqueue(info()); runCurrent()
        assertEquals(DownloadStatus.Failed, store.tasks.value.single().status)
        assertTrue(engine.started.isEmpty())
    }

    @Test fun lostLocalFilesBecomeRetryableInsteadOfReturningFakeOfflineMedia() = runTest {
        val engine = Engine()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope) { info() }
        val id = store.enqueue(info()); runCurrent(); engine.finish(id); runCurrent()
        engine.completed.clear()
        assertNull(store.offline(PlayerRequest(page = info().pageUrl, episode = "e1")))
        assertEquals(DownloadStatus.Failed, store.tasks.value.single().status)
    }
}
