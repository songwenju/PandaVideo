@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pandavideo

import dev.pandavideo.core.model.*
import dev.pandavideo.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import dev.pandavideo.core.platform.DownloadNetwork
import kotlinx.coroutines.test.*
import kotlin.test.*

class OfflineDownloadsTest {
    @Test fun mobileBatchWaitsForOneConsentBeforeResolvingAnyMedia() = runTest {
        val network = MutableStateFlow(DownloadNetwork.Other)
        val engine = Engine()
        var requests = 0
        val confirmations = mutableListOf<() -> Unit>()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope,
            network = network, onNetworkWarning = { proceed, _ -> confirmations += proceed }) {
            requests++; info(it.episode!!)
        }
        val ids = store.enqueueEpisodes(info(), listOf("e1", "e2"))
        runCurrent()
        assertEquals(1, confirmations.size)
        assertEquals(0, requests)
        assertTrue(store.tasks.value.all { it.status == DownloadStatus.Paused })
        confirmations.single()(); runCurrent()
        engine.finish(ids[0]); runCurrent()
        assertEquals(2, requests)
        assertEquals(1, confirmations.size)
        engine.finish(ids[1]); runCurrent()
    }

    @Test fun wifiLossPreservesProgressAndWifiReturnDoesNotResumeManualPause() = runTest {
        val network = MutableStateFlow(DownloadNetwork.Wifi)
        val engine = Engine()
        val confirmations = mutableListOf<() -> Unit>()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope,
            network = network, onNetworkWarning = { proceed, _ -> confirmations += proceed }) { info(it.episode!!) }
        val ids = store.enqueueEpisodes(info(), listOf("e1", "e2"))
        runCurrent()
        engine.callbacks.getValue(ids[0])(0.4f); runCurrent()
        store.pause(ids[1])
        network.value = DownloadNetwork.Other; runCurrent()
        assertEquals(0.4f, store.tasks.value.first { it.id == ids[0] }.progress)
        assertTrue(store.tasks.value.all { it.status == DownloadStatus.Paused })
        assertEquals(1, confirmations.size)
        network.value = DownloadNetwork.Wifi; runCurrent()
        assertEquals(listOf(ids[0], ids[0]), engine.started)
        assertEquals(DownloadStatus.Paused, store.tasks.value.first { it.id == ids[1] }.status)
        confirmations.single()() // A stale dialog cannot approve a later cellular connection.
        network.value = DownloadNetwork.Other; runCurrent()
        assertEquals(2, confirmations.size)
        confirmations.first()(); runCurrent()
        assertTrue(store.tasks.value.all { it.status == DownloadStatus.Paused })
    }

    @Test fun offlineWaitAndExplicitPauseNeverStartOnReconnect() = runTest {
        val network = MutableStateFlow(DownloadNetwork.Offline)
        val engine = Engine()
        var warnings = 0
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope,
            network = network, onNetworkWarning = { _, _ -> warnings++ }) { info() }
        val id = store.enqueue(info()); runCurrent()
        assertTrue(engine.started.isEmpty())
        assertEquals(0, warnings)
        store.pause(id)
        network.value = DownloadNetwork.Wifi; runCurrent()
        assertTrue(engine.started.isEmpty())
        store.resume(id); runCurrent()
        assertEquals(listOf(id), engine.started)
    }

    @Test fun cancellingWaitingTaskInvalidatesConsentAndRetryRequiresConsentAgain() = runTest {
        val network = MutableStateFlow(DownloadNetwork.Other)
        val engine = Engine()
        var available: (() -> Boolean)? = null
        var confirmations = 0
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope,
            network = network, onNetworkWarning = { _, valid -> confirmations++; available = valid }) { info() }
        val id = store.enqueue(info()); runCurrent()
        assertTrue(available!!())
        store.pause(id)
        assertFalse(available())
        store.resume(id); runCurrent()
        assertEquals(2, confirmations)
        assertTrue(engine.started.isEmpty())
    }

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

    @Test fun batchSelectionResolvesEachEpisodeInSourceOrderAndKeepsPlaybackSnapshot() = runTest {
        val engine = Engine()
        val requests = mutableListOf<String>()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope) {
            requests += it.episode!!
            info(it.episode)
        }
        val playing = info()
        val ids = store.enqueueEpisodes(playing, listOf("e2", "e1", "e2"), "poster")
        assertEquals(2, ids.size)
        assertEquals("e1", playing.selected)
        assertTrue(store.tasks.value.all { it.info.url.isEmpty() && it.cover == "poster" })
        runCurrent()
        assertEquals(listOf("e1"), requests)
        engine.finish(ids[0]); runCurrent()
        assertEquals(listOf("e1", "e2"), requests)
        engine.finish(ids[1]); runCurrent()
        val local = assertNotNull(store.offline(PlayerRequest(page = playing.pageUrl, episode = "e1")))
        assertEquals(listOf("e1", "e2"), local.episodes.map { it.path })
        assertEquals(ids, store.enqueueEpisodes(playing, listOf("e1", "e2")))
        assertEquals(2, store.tasks.value.size)
        assertEquals(2, engine.started.size)
    }

    @Test fun invalidBatchSelectionNeverQueuesAPartialDownload() = runTest {
        val engine = Engine()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope) { info() }
        assertFailsWith<IllegalArgumentException> {
            store.enqueueEpisodes(info(), listOf("e1", "missing"))
        }
        assertFailsWith<IllegalArgumentException> { store.enqueueEpisodes(info(), emptyList()) }
        assertTrue(store.tasks.value.isEmpty())
        runCurrent()
        assertTrue(engine.started.isEmpty())
    }

    @Test fun cachePickerKeepsSingleFilmWithoutAnEpisodeListDownloadable() = runTest {
        val engine = Engine()
        val film = info().copy(episodes = emptyList(), selected = "")
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope) { film }
        val ids = store.enqueueEpisodes(film, listOf(""))
        assertEquals(1, ids.size)
        runCurrent()
        engine.finish(ids.single()); runCurrent()
        assertEquals(DownloadStatus.Completed, store.tasks.value.single().status)
        assertNotNull(store.offline(PlayerRequest(page = film.pageUrl)))
    }

    @Test fun batchSelectionResumesPausedEpisodesWithoutDuplicatingCompletedWork() = runTest {
        val engine = Engine()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope) { info(it.episode!!) }
        val ids = store.enqueueEpisodes(info(), listOf("e1", "e2"))
        runCurrent()
        store.pause(ids[1])
        engine.finish(ids[0]); runCurrent()
        assertEquals(ids, store.enqueueEpisodes(info(), listOf("e1", "e2")))
        runCurrent()
        assertEquals(listOf(ids[0], ids[1]), engine.started)
        assertEquals(2, store.tasks.value.size)
        engine.finish(ids[1]); runCurrent()
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

    @Test fun failedTransferReportsRetryThatResolvesTheSameEpisodeAgain() = runTest {
        val engine = Engine()
        var retry: (() -> Unit)? = null
        val requests = mutableListOf<PlayerRequest>()
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope,
            onFailure = { entry, action ->
                assertEquals("e2", entry.info.selected)
                assertEquals(DownloadStatus.Failed, entry.status)
                retry = action
            }) { request -> requests += request; info(request.episode!!) }
        val id = store.enqueue(info("e2"))
        runCurrent()
        engine.waits.getValue(id).completeExceptionally(IllegalStateException("offline"))
        runCurrent()
        assertEquals(DownloadStatus.Failed, store.tasks.value.single().status)
        assertEquals(1, requests.size)
        assertNotNull(retry).invoke()
        runCurrent()
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.page == info().pageUrl && it.episode == "e2" })
        assertEquals(DownloadStatus.Downloading, store.tasks.value.single().status)
        engine.finish(id); runCurrent()
        assertEquals(DownloadStatus.Completed, store.tasks.value.single().status)
    }

    @Test fun deleteFailureRetriesDeletionInsteadOfDownloadingAgain() = runTest {
        var removingFails = true
        var removed = 0
        var retry: (() -> Unit)? = null
        val engine = object : OfflineEngine {
            override fun supports(kind: String) = true
            override suspend fun download(id: String, info: PlaybackInfo, progress: (Float?) -> Unit) = id
            override fun cancel(id: String) {}
            override suspend fun remove(id: String) {
                removed++
                check(!removingFails)
            }
            override fun asset(id: String, locator: String) = OfflineAsset("file:///$id", "offline")
        }
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope,
            onFailure = { _, action -> retry = action }) { info() }
        val id = store.enqueue(info())
        runCurrent()
        store.remove(id); runCurrent()
        assertEquals(1, removed)
        assertEquals(DownloadStatus.Failed, store.tasks.value.single().status)
        removingFails = false
        assertNotNull(retry).invoke(); runCurrent()
        assertEquals(2, removed)
        assertTrue(store.tasks.value.isEmpty())
    }

    @Test fun pauseDoesNotReportAnErrorPrompt() = runTest {
        val engine = Engine()
        var failures = 0
        val store = OfflineDownloads(FakeStorage(), engine, backgroundScope,
            onFailure = { _, _ -> failures++ }) { info() }
        val id = store.enqueue(info())
        runCurrent()
        store.pause(id); runCurrent()
        assertEquals(0, failures)
        assertEquals(DownloadStatus.Paused, store.tasks.value.single().status)
    }
}
