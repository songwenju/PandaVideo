package dev.pandavideo

import dev.pandavideo.data.user.UserProfile
import dev.pandavideo.data.user.UserStore
import kotlin.test.*

/** Exercises the local profile, favorites and playback history backed by an in-memory store. */
class UserStoreBehaviorTest {
    private lateinit var storage: FakeStorage
    private lateinit var store: UserStore

    @BeforeTest fun setUp() {
        storage = FakeStorage()
        store = UserStore(storage)
    }

    @Test fun defaultsAreUsedBeforeAnyWrite() {
        assertEquals(UserProfile(), store.profile.value)
        assertTrue(store.favorites.value.isEmpty())
        assertTrue(store.history.value.isEmpty())
    }

    @Test fun profileUpdateTrimsLengthAndFallsBackToDefaults() {
        store.updateProfile("   ", "   ")
        assertEquals("影迷朋友", store.profile.value.nickname)
        assertEquals("这个人很懒，还没有填写简介", store.profile.value.bio)
        store.updateProfile("一二三四五六七八九十甲乙丙", "x".repeat(50))
        assertEquals(12, store.profile.value.nickname.length)
        assertEquals(40, store.profile.value.bio.length)
    }

    @Test fun avatarPersistsAfterRestartAndTextOnlyEdits() {
        store.updateProfile("用户", "简介", "avatar-123.jpg")
        val reopened = UserStore(storage)
        assertEquals("avatar-123.jpg", reopened.profile.value.avatar)
        reopened.updateProfile("新名字", "新简介")
        assertEquals("avatar-123.jpg", UserStore(storage).profile.value.avatar)
        reopened.updateProfile("新名字", "新简介", "")
        assertEquals("", UserStore(storage).profile.value.avatar)
    }

    @Test fun legacyProfileLoadsWithDefaultAvatar() {
        storage.putString("profile", """{"nickname":"旧用户","bio":"旧简介"}""")
        assertEquals(UserProfile("旧用户", "旧简介"), UserStore(storage).profile.value)
    }

    @Test fun avatarRejectsPathsAndRemoteUrlsWithoutChangingProfile() {
        listOf("../photo.jpg", "https://example.com/photo.jpg", "/tmp/photo.jpg").forEach {
            assertFailsWith<IllegalArgumentException> { store.updateProfile("用户", "", it) }
        }
        assertEquals(UserProfile(), UserStore(storage).profile.value)
    }

    @Test fun toggleFavoriteAddsRemovesAndPersistsAcrossInstances() {
        val pageUrl = "https://huolot.com/voddetail/1.html"
        assertTrue(store.toggleFavorite("影片", pageUrl, "https://img/1.jpg", "2021"))
        assertTrue(store.isFavorite(pageUrl))
        assertEquals(1, store.favorites.value.size)
        // Reopening the store reloads the persisted JSON.
        val reopened = UserStore(storage)
        assertEquals(1, reopened.favorites.value.size)
        assertEquals("影片", reopened.favorites.value.single().title)
        assertFalse(store.toggleFavorite("影片", pageUrl, "", "2021"))
        assertTrue(store.favorites.value.isEmpty())
        assertFalse(store.isFavorite(pageUrl))
    }

    @Test fun blankPageUrlIsRejectedForFavoritesAndHistory() {
        assertFalse(store.toggleFavorite("影片", "", "", ""))
        store.recordHistory("影片", "", "", "")
        assertTrue(store.favorites.value.isEmpty())
        assertTrue(store.history.value.isEmpty())
    }

    @Test fun favoriteWithoutCoverInheritsArtworkFromHistory() {
        val pageUrl = "https://huolot.com/voddetail/1.html"
        store.recordHistory("影片", pageUrl, "https://img/1.jpg", "2021")
        // The favorite action arrives without an artwork snapshot (e.g. reopened from history).
        assertTrue(store.toggleFavorite("影片", pageUrl, "", "2021"))
        assertEquals("https://img/1.jpg", store.favorites.value.single().cover)
    }

    @Test fun historyPlaybackBackfillsBlankFavoriteCoverButKeepsExistingOne() {
        val pageUrl = "https://huolot.com/voddetail/1.html"
        store.toggleFavorite("影片", pageUrl, "", "2021")
        store.recordHistory("影片", pageUrl, "https://img/1.jpg", "2021")
        assertEquals("https://img/1.jpg", store.favorites.value.single().cover)
        // A later history refresh must never replace an already known cover.
        store.recordHistory("影片", pageUrl, "https://img/other.jpg", "2021")
        assertEquals("https://img/1.jpg", store.favorites.value.single().cover)
        // Non-web values are ignored rather than persisted as broken artwork.
        val other = "https://huolot.com/voddetail/2.html"
        store.toggleFavorite("影片乙", other, "", "2022")
        store.recordHistory("影片乙", other, "not-a-url", "2022")
        assertEquals("", store.favorites.value.first { it.pageUrl == other }.cover)
    }

    @Test fun removeAndClearFavorites() {
        store.toggleFavorite("A", "https://huolot.com/voddetail/1.html", "", "2020")
        store.toggleFavorite("B", "https://huolot.com/voddetail/2.html", "", "2021")
        store.removeFavorite("https://huolot.com/voddetail/1.html")
        assertEquals(listOf("B"), store.favorites.value.map { it.title })
        store.clearFavorites()
        assertTrue(store.favorites.value.isEmpty())
        assertNull(storage.getString("favorites"))
    }

    @Test fun historyStaysNewestFirstAndDedupesWhileKeepingArtwork() {
        store.recordHistory("A", "https://huolot.com/voddetail/1.html", "cover-a", "2020")
        store.recordHistory("B", "https://huolot.com/voddetail/2.html", "cover-b", "2021")
        store.recordHistory("A-更新", "https://huolot.com/voddetail/1.html", "", "", "第3集")
        val history = store.history.value
        assertEquals(2, history.size)
        assertEquals("https://huolot.com/voddetail/1.html", history.first().pageUrl)
        assertEquals("第3集", history.first().episode)
        // Blank cover/year on refresh must not erase previously stored artwork.
        assertEquals("cover-a", history.first().cover)
        assertEquals("2020", history.first().year)
        assertEquals("A-更新", history.first().title)
    }

    @Test fun historyIsCappedAtTheLimit() {
        repeat(UserStore.HISTORY_LIMIT + 5) { index ->
            store.recordHistory("影片$index", "https://huolot.com/voddetail/$index.html", "", "2020")
        }
        assertEquals(UserStore.HISTORY_LIMIT, store.history.value.size)
        assertEquals(
            "https://huolot.com/voddetail/${UserStore.HISTORY_LIMIT + 4}.html",
            store.history.value.first().pageUrl,
        )
    }

    @Test fun removeAndClearHistory() {
        store.recordHistory("A", "https://huolot.com/voddetail/1.html", "", "")
        store.removeHistory("https://huolot.com/voddetail/1.html")
        assertTrue(store.history.value.isEmpty())
        store.recordHistory("B", "https://huolot.com/voddetail/2.html", "", "")
        store.clearHistory()
        assertTrue(store.history.value.isEmpty())
        assertNull(storage.getString("history"))
    }

    @Test fun corruptedJsonFallsBackToDefaultsWithoutCrashing() {
        storage.putString("profile", "{broken")
        storage.putString("favorites", "[broken")
        storage.putString("history", "<not-json>")
        val fresh = UserStore(storage)
        assertEquals(UserProfile(), fresh.profile.value)
        assertTrue(fresh.favorites.value.isEmpty())
        assertTrue(fresh.history.value.isEmpty())
    }
}
