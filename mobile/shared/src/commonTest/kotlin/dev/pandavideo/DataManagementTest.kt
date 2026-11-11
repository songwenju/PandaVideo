package dev.pandavideo

import dev.pandavideo.data.repository.ContentParsers
import dev.pandavideo.data.repository.ParserRules
import dev.pandavideo.data.repository.ParserRulesStore
import dev.pandavideo.data.repository.UpdateStore
import dev.pandavideo.data.user.UserStore
import dev.pandavideo.data.user.YouthStore
import kotlin.test.*

class DataManagementTest {
    @Test fun invalidRulesNeverReplaceLastKnownGood() {
        val storage = FakeStorage()
        val store = ParserRulesStore(storage)
        store.import("""{"revision":2,"synopsisClass":"new-description"}""")
        assertFails { store.import("""{"revision":3,"synopsisClass":"x.*"}""") }
        assertFails { store.import("""{"revision":1}""") }
        assertEquals(2, ParserRulesStore(storage).rules.value.revision)
        assertEquals("new-description", store.rules.value.synopsisClass)
    }
    @Test fun importedRulesAffectParserAndKeepAuthorizationGuards() {
        val rules = ParserRules(playerVariablePrefix = "video_", synopsisClass = "plot")
        assertEquals("简介", ContentParsers.synopsis("""<div class="plot">简介</div>""", rules))
        val html = """var video_config = {"url":"https://media.test/v.mp4","encrypt":0};"""
        assertEquals("video", ContentParsers.media(html, rules).second)
        assertFails { ContentParsers.media("""var video_config = {"url":"https://media.test/v.mp4","points":1};""", rules) }
        assertFails { ContentParsers.pageUrl("https://evil.test/voddetail/1.html") }
    }
    @Test fun corruptRulesFallBackToBuiltIn() {
        val storage = FakeStorage()
        storage.putString("parser_rules", "broken")
        assertEquals(ParserRules(), ParserRulesStore(storage).rules.value)
    }
    @Test fun backupRoundTripMergesHistoryFavoritesAndResume() {
        val source = UserStore(FakeStorage())
        val page = "https://huolot.com/voddetail/1.html"
        source.updateProfile("影迷", "喜欢电影")
        source.toggleFavorite("影片", page, "", "2026")
        source.recordHistory("影片", page, "", "2026")
        source.saveProgress(page, "/vodplay/1-1-1.html", "第一集", 42_000, 120_000)
        val storage = FakeStorage()
        val target = UserStore(storage)
        target.importBackup(source.exportBackup())
        target.importBackup(source.exportBackup())
        assertEquals(1, target.favorites.value.size)
        assertEquals(1, target.history.value.size)
        assertEquals(42_000L, UserStore(storage).latestProgress(page)?.position)
        assertEquals("影迷", UserStore(storage).profile.value.nickname)
    }
    @Test fun invalidBackupDoesNotModifyExistingData() {
        val store = UserStore(FakeStorage())
        store.updateProfile("原资料", "简介")
        assertFails { store.importBackup("""{"schema":1,"profile":{"nickname":"新资料"},"favorites":[{"title":"片名","page_url":"https://evil.test/1"}]}""") }
        assertEquals("原资料", store.profile.value.nickname)
        assertTrue(store.favorites.value.isEmpty())
        assertFails { store.importBackup("""{"schema":2}""") }
    }
    @Test fun oldEpisodePageKeysMergeWithoutLosingPerEpisodeProgress() {
        val storage = FakeStorage()
        storage.putString("history", """[
            {"title":"影片","page_url":"https://huolot.com/vodplay/1-1-1.html","updated_at":1},
            {"title":"影片","page_url":"https://huolot.com/vodplay/1-1-2.html","updated_at":2}
        ]""")
        storage.putString("progress", """{
            "https://huolot.com/vodplay/1-1-1.html":[{"episode_path":"/vodplay/1-1-1.html","position":10000,"duration":100000}],
            "https://huolot.com/vodplay/1-1-2.html":[{"episode_path":"/vodplay/1-1-2.html","position":20000,"duration":100000}]
        }""")
        val store = UserStore(storage)
        val page = "https://huolot.com/voddetail/1.html"
        assertEquals(listOf(page), store.history.value.map { it.pageUrl })
        assertEquals(10_000L, store.progressFor(page, "/vodplay/1-1-1.html")?.position)
        assertEquals(20_000L, store.progressFor(page, "/vodplay/1-1-2.html")?.position)
    }
    @Test fun youthBudgetSurvivesRestartAndResetsAtNextDay() {
        val storage = FakeStorage()
        var now = 10 * 86_400_000L
        val store = YouthStore(storage) { now }
        store.enable("123456")
        repeat(YouthStore.DAILY_LIMIT_SECONDS) { store.tick() }
        assertFalse(store.canPlay())
        val restored = YouthStore(storage) { now }
        assertTrue(restored.enabled.value)
        assertFalse(restored.canPlay())
        now += 86_400_000
        assertTrue(restored.canPlay())
        restored.disable("123456")
        assertFalse(restored.enabled.value)
        assertFalse(storage.values.getValue("youth").contains("123456"))
    }
    @Test fun youthExitRejectsWrongPinAndRateLimitsAttempts() {
        var now = 100_000L
        val store = YouthStore(FakeStorage()) { now }
        assertFails { store.enable("123") }
        store.enable("123456")
        repeat(5) { assertFails { store.disable("000000") } }
        assertFails { store.disable("123456") }
        assertTrue(store.enabled.value)
        now += 60_000
        store.disable("123456")
        assertFalse(store.enabled.value)
    }
    @Test fun updatesUseRealVersionNumbersAndHttpsLinks() {
        val store = UpdateStore(FakeStorage())
        store.accept("""{"versionCode":2,"version":"0.2.0","url":"https://releases.test/app"}""", "https://releases.test/feed")
        assertEquals("0.2.0", store.notice.value?.version)
        assertFails { store.accept("""{"versionCode":3,"version":"0.3.0","url":"javascript:alert(1)"}""", "") }
        assertEquals("0.2.0", store.notice.value?.version)
        store.accept("""{"versionCode":1,"version":"0.1.0","url":"https://releases.test/app"}""", "")
        assertNull(store.notice.value)
    }
}
