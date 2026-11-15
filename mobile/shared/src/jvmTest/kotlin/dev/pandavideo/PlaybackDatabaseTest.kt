package dev.pandavideo

import dev.pandavideo.core.model.Episode
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.user.UserStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class PlaybackDatabaseTest {
    private val page = "https://huolot.com/voddetail/1.html"
    private val episodes = listOf(
        Episode("/vodplay/1-1-1.html", "第1集", 1),
        Episode("/vodplay/1-2-1.html", "另一线路第1集", 2),
        Episode("/vodplay/1-1-2.html", "第2集", 1),
    )

    @Test fun completedEpisodeAndOrderedSuccessorSurviveDatabaseRestartAndCacheClear() = runTest {
        val directory = Files.createTempDirectory("pandavideo-playback-").toFile()
        try {
            val path = directory.resolve("playback.db").absolutePath
            val preferences = FakeStorage()
            val store = UserStore(preferences, path)
            store.recordHistory("剧集", page, "https://img.example/a.jpg", "2020", "第1集", episodes[0].path)
            store.rememberEpisodes(page, episodes)
            store.saveProgress(page, episodes[0].path, "第1集", 600_000, 600_000)
            CatalogDatabase(directory.resolve("catalog.db").absolutePath).clearCache()
            val restarted = UserStore(preferences, path)
            assertTrue(restarted.latestProgress(page)!!.finished)
            assertEquals(episodes[2].path, restarted.continueEpisode(page))
            assertEquals("https://img.example/a.jpg", restarted.history.value.single().cover)
            assertNull(preferences.getString("history"))
            assertNull(preferences.getString("progress"))
            restarted.saveProgress(page, episodes[2].path, "第2集", 120_000, 600_000)
            val resumed = UserStore(preferences, path)
            assertEquals(episodes[2].path, resumed.continueEpisode(page))
            assertEquals(120_000L, resumed.latestProgress(page)!!.position)
            resumed.saveProgress(page, episodes[2].path, "第2集", 600_000, 600_000)
            assertEquals(episodes[2].path, UserStore(preferences, path).continueEpisode(page))
        } finally { directory.deleteRecursively() }
    }

    @Test fun preferenceMigrationIsDurableAndClearedHistoryNeverReturns() {
        val directory = Files.createTempDirectory("pandavideo-playback-migration-").toFile()
        try {
            val path = directory.resolve("playback.db").absolutePath
            val preferences = FakeStorage()
            val legacy = UserStore(preferences)
            legacy.recordHistory("剧集", page, "", "2020", "第1集", episodes[0].path)
            legacy.saveProgress(page, episodes[0].path, "第1集", 600_000, 600_000)
            val migrated = UserStore(preferences, path)
            assertTrue(migrated.latestProgress(page)!!.finished)
            assertNull(preferences.getString("history"))
            migrated.rememberEpisodes(page, episodes)
            assertEquals(episodes[2].path, UserStore(preferences, path).continueEpisode(page))
            migrated.clearHistory()
            // Simulate a stale preference being restored by an old app/OS backup.
            legacy.recordHistory("旧记录", page, "", "2020")
            val reopened = UserStore(preferences, path)
            assertTrue(reopened.history.value.isEmpty())
            assertTrue(reopened.progress.value.isEmpty())
            assertNull(reopened.continueEpisode(page))
        } finally { directory.deleteRecursively() }
    }

    @Test fun removingHistoryAlsoRemovesItsEpisodeOrderAndProgress() {
        val directory = Files.createTempDirectory("pandavideo-playback-delete-").toFile()
        try {
            val path = directory.resolve("playback.db").absolutePath
            val store = UserStore(FakeStorage(), path)
            store.recordHistory("剧集", page, "", "", "第1集", episodes[0].path)
            store.rememberEpisodes(page, episodes)
            store.saveProgress(page, episodes[0].path, "第1集", 600_000, 600_000)
            store.removeHistory(page)
            val reopened = UserStore(FakeStorage(), path)
            assertTrue(reopened.history.value.isEmpty())
            assertNull(reopened.latestProgress(page))
            assertNull(reopened.continueEpisode(page))
        } finally { directory.deleteRecursively() }
    }

    @Test fun immediatelySelectedEpisodeWinsBeforeItsFirstCheckpoint() {
        val directory = Files.createTempDirectory("pandavideo-playback-selection-").toFile()
        try {
            val path = directory.resolve("playback.db").absolutePath
            val store = UserStore(FakeStorage(), path)
            store.recordHistory("剧集", page, "", "", "第1集", episodes[0].path)
            store.rememberEpisodes(page, episodes)
            store.saveProgress(page, episodes[0].path, "第1集", 120_000, 600_000)
            store.recordHistory("剧集", page, "", "", "第2集", episodes[2].path)
            val reopened = UserStore(FakeStorage(), path)
            assertEquals(episodes[2].path, reopened.continueEpisode(page))
            assertEquals(0L, reopened.history.value.single().positionMs)
            assertEquals(120_000L, reopened.progressFor(page, episodes[0].path)!!.position)
        } finally { directory.deleteRecursively() }
    }
    @Test fun historyYearAndRatingSurviveDatabaseRestartAndLegacyRecords() {
        val directory = Files.createTempDirectory("pandavideo-history-metadata-").toFile()
        try {
            val path = directory.resolve("playback.db").absolutePath
            val preferences = FakeStorage()
            val store = UserStore(preferences, path)
            store.recordHistory("影片", page, "", "2020", rating = 9.1)
            store.recordHistory("影片", page, "", "", "第二集", episodes[2].path)
            val restored = UserStore(preferences, path).history.value.single()
            assertEquals("2020", restored.year)
            assertEquals(9.1, restored.rating)
            val legacyPage = "$page/legacy"
            store.recordHistory("旧记录", legacyPage, "", "")
            assertNull(UserStore(preferences, path).history.value.first().rating)
            store.fillHistoryMetadata(legacyPage, "1994", 9.7)
            val repaired = UserStore(preferences, path).history.value.first()
            assertEquals("1994", repaired.year)
            assertEquals(9.7, repaired.rating)
        } finally { directory.deleteRecursively() }
    }
}
