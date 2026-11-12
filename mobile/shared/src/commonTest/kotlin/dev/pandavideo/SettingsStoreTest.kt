package dev.pandavideo

import dev.pandavideo.data.user.SettingsStore
import dev.pandavideo.data.user.SearchHistoryStore
import kotlin.test.*

class SettingsStoreTest {
    @Test fun searchHistoryDeduplicatesBoundsAndSupportsRemoval() {
        val storage = FakeStorage()
        val store = SearchHistoryStore(storage)
        repeat(25) { store.add("影片$it") }
        store.add("  影片20  ")
        assertEquals(20, store.history.value.size)
        assertEquals("影片20", store.history.value.first())
        store.remove("影片20")
        assertFalse("影片20" in SearchHistoryStore(storage).history.value)
        store.clear()
        assertTrue(SearchHistoryStore(storage).history.value.isEmpty())
    }
    @Test fun oldSettingsLoadWithSafeDefaults() {
        val storage = FakeStorage()
        storage.putString("settings", """{"theme_mode":"dark","future":true}""")
        val value = SettingsStore(storage).settings.value
        assertEquals(1f, value.defaultRate)
        assertFalse(value.rememberVolume)
        assertFalse(value.rememberBrightness)
        assertEquals(listOf("hot", "discover", "browse", "profile"), value.tabOrder)
    }
    @Test fun ratesAndLevelsAreValidatedAndPersisted() {
        val storage = FakeStorage()
        val store = SettingsStore(storage)
        store.setDefaultRate(1.5f)
        store.setRememberVolume(true, 2f)
        store.setRememberBrightness(true, -1f)
        val restored = SettingsStore(storage).settings.value
        assertEquals(1.5f, restored.defaultRate)
        assertEquals(1f, restored.volume)
        assertEquals(0f, restored.brightness)
        store.setDefaultRate(Float.NaN)
        assertEquals(1f, store.settings.value.defaultRate)
    }
    @Test fun disabledMemoryDoesNotSaveLevels() {
        val store = SettingsStore(FakeStorage())
        store.saveLevels(0.9f, 0.2f)
        assertEquals(0.5f, store.settings.value.volume)
        assertEquals(0.5f, store.settings.value.brightness)
    }
    @Test fun corruptedSettingsFallBack() {
        val storage = FakeStorage()
        storage.putString("settings", "broken")
        assertEquals(1f, SettingsStore(storage).settings.value.defaultRate)
    }
    @Test fun reorderedTabsSurviveReloadAndOtherPreferenceChanges() {
        val storage = FakeStorage()
        val store = SettingsStore(storage)
        val order = listOf("profile", "browse", "hot", "discover")
        store.setTabOrder(order)
        store.setDefaultRate(1.5f)
        assertEquals(order, SettingsStore(storage).settings.value.tabOrder)
        assertEquals(1.5f, SettingsStore(storage).settings.value.defaultRate)
    }
    @Test fun invalidStoredOrderRetainsKnownTabsAndRestoresMissingDestinations() {
        val storage = FakeStorage()
        storage.putString("settings", """{"tabOrder":["profile","removed","profile","discover"]}""")
        val store = SettingsStore(storage)
        assertEquals(listOf("profile", "discover", "hot", "browse"), store.settings.value.tabOrder)
        store.setTabOrder(emptyList())
        assertEquals(listOf("hot", "discover", "browse", "profile"), SettingsStore(storage).settings.value.tabOrder)
    }
}
