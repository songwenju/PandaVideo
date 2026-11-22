package dev.pandavideo

import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.platform.KeyValueStorage
import dev.pandavideo.data.user.LineAdStore
import kotlin.test.*

class LineAdStoreTest {
    @Test fun supplementaryMirrorReportsUseOriginalProviderAndLineIdentity() {
        val store = LineAdStore(FakeStorage())
        val original = Episode("$page#1-0", "第 1 集", 1, "PL")
        val mirror = original.copy(line = 100_000, lineName = "Pomo · PL")
        val root = "https://jpyy.com/detail/1.html"
        assertTrue(store.setHasAds(page, original, true))
        assertTrue(store.hasAds(root, mirror))
        assertTrue(store.setHasAds(root, mirror, false))
        assertFalse(store.hasAds(page, original))
        val numeric = original.copy(lineName = "线路1")
        assertTrue(store.setHasAds(page, numeric, true))
        assertTrue(store.hasAds(root, numeric.copy(line = 100_001, lineName = "Pomo · 线路1")))
    }
    private val page = "https://pomo.mom/content/plugins/plyr_player/api.php?type=auto_detail&site=A&id=1"
    private val line = Episode("$page#1-0", "第 1 集", 1, "PL")

    @Test fun namedReportsSurviveRestartEpisodeTitleAndLineOrderChanges() {
        val storage = FakeStorage()
        assertTrue(LineAdStore(storage).setHasAds(page, line, true))
        val reopened = LineAdStore(storage)
        assertTrue(reopened.hasAds(page, line.copy(path = "$page#1-1", label = "第 2 集")))
        assertTrue(reopened.hasAds(page.replace("id=1", "id=2"), line.copy(line = 3)))
        assertFalse(reopened.hasAds(page, line.copy(lineName = "WJ")))
        assertFalse(reopened.hasAds(page.replace("site=A", "site=B"), line))
        assertFalse(reopened.hasAds(page.replace("pomo.mom", "other.test"), line))
    }

    @Test fun unnamedAndOrdinalReportsStayWithinTheirTitleAndLine() {
        val store = LineAdStore(FakeStorage())
        for (name in listOf("", "线路1", "线路 1", "1")) {
            val numeric = line.copy(lineName = name)
            assertTrue(store.setHasAds(page, numeric, true))
            assertTrue(store.hasAds("$page#1-1", numeric.copy(path = "$page#1-1")))
            assertFalse(store.hasAds(page.replace("id=1", "id=2"), numeric))
            assertFalse(store.hasAds(page, numeric.copy(line = 2)))
        }
    }

    @Test fun cancellingReportPersistsWithoutChangingOtherLines() {
        val storage = FakeStorage()
        val store = LineAdStore(storage)
        assertTrue(store.setHasAds(page, line, true))
        assertTrue(store.setHasAds(page, line.copy(lineName = "WJ"), true))
        assertTrue(store.setHasAds(page, line, false))
        val reopened = LineAdStore(storage)
        assertFalse(reopened.hasAds(page, line))
        assertTrue(reopened.hasAds(page, line.copy(lineName = "WJ")))
    }

    @Test fun invalidOrCorruptReportsDoNotCrashOrMarkUnknownLines() {
        val storage = FakeStorage()
        storage.putString("manual_line_ads_v1", "{broken")
        val store = LineAdStore(storage)
        assertTrue(store.marks.value.isEmpty())
        assertFalse(store.setHasAds("", line, true))
        assertFalse(store.setHasAds("file:///private/video.mp4", line, true))
        assertFalse(store.setHasAds(page, line.copy(line = 0), true))
        assertTrue(store.marks.value.isEmpty())
    }

    @Test fun failedPersistenceNeverPublishesAnUnstoredReport() {
        val storage = object : KeyValueStorage {
            override fun getString(key: String): String? = null
            override fun putString(key: String, value: String) { error("Write failed") }
            override fun remove(key: String) = Unit
        }
        val store = LineAdStore(storage)
        assertFalse(store.setHasAds(page, line, true))
        assertTrue(store.marks.value.isEmpty())
    }
}
