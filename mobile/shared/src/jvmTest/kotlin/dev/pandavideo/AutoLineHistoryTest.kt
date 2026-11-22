package dev.pandavideo

import dev.pandavideo.app.AppState
import dev.pandavideo.core.model.LineProbe
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.platform.currentEpochMillis
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutoLineHistoryTest {
    @Test fun remeasuredHistoryEntryKeepsSavedPositionAndLoadsOnlyOnce() = runTest {
        val file = File.createTempFile("auto-line-history-", ".db")
        val platform = AppStateTest.TestPlatform(this, databasePath = file.absolutePath)
        platform.detailFixture = pomoDetailFixture.replace(
            """{"name":"备用 1","url":"https://media/embed"}""",
            """{"name":"第 1 集","url":"https://media/b1.m3u8"},{"name":"第 2 集","url":"https://media/b2.m3u8"}""")
        val database = CatalogDatabase(file.absolutePath)
        val repository = ContentRepository(platform.httpClient(), database)
        val model = AppState(platform, backgroundScope, resolveDispatcher = StandardTestDispatcher(testScheduler))
        try {
            val initial = repository.player(PlayerRequest(page = pomoPage, episode = "$pomoPage#1-1"))
            val original = initial.episodes.first { it.path == "$pomoPage#1-1" }
            val alternate = initial.episodes.first { it.path == "$pomoPage#2-1" }
            database.writeResponse(repository.lineProbeKey(pomoPage, original, "https://media/2.m3u8"),
                Json.encodeToString(LineProbe(500, true)), currentEpochMillis())
            database.writeResponse(repository.lineProbeKey(pomoPage, alternate, "https://media/b2.m3u8"),
                Json.encodeToString(LineProbe(7, true)), currentEpochMillis() - 24 * 60 * 60_000L)
            model.rememberPlayed("测试电影", pomoPage, null, "2020", "第 2 集", original.path)
            model.saveProgress(pomoPage, original.path, "第 2 集", 120_000, 600_000)
            platform.parsedMedia.clear()
            model.reopenSaved(pomoPage, "测试电影")
            runCurrent()
            // SQLite uses real I/O dispatchers; a virtual scheduler turn alone cannot
            // establish resolution completion, and a virtual timeout can outrun disk I/O.
            val ready = withContext(Dispatchers.Default) {
                withTimeout(10_000) { model.session!!.state.first { !it.loading } }
            }
            assertNull(ready.error)
            val info = assertNotNull(ready.info)
            val selected = info.episodes.first { it.path == info.selected }
            assertEquals(original.label, selected.label)
            assertEquals(original.path, selected.path)
            assertNull(info.resumeFromEpisode)
            assertEquals(listOf("https://media/2.m3u8"), platform.parsedMedia,
                "Reopening starts the remembered episode without measuring every alternate first")
            assertEquals(120_000L, platform.players.single().loads.single().startPosition)
            assertEquals(selected.path, model.user.history.value.first().episodePath)
        } finally {
            model.close()
            repository.close()
            file.delete()
            File("${file.absolutePath}.playback.db").delete()
        }
    }
}
