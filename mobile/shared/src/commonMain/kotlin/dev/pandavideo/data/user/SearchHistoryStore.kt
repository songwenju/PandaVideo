package dev.pandavideo.data.user

import dev.pandavideo.core.platform.KeyValueStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local, bounded search history. No search terms are logged or synced. */
class SearchHistoryStore(private val storage: KeyValueStorage) {
    private val json = Json
    private fun normalize(values: List<String>) =
        values.map { it.trim().take(100) }.filter { it.isNotEmpty() }.distinct().take(20)
    private val mutable = MutableStateFlow(try {
        normalize(storage.getString("search_history")?.let {
            json.decodeFromString<List<String>>(it)
        }.orEmpty())
    } catch (_: Exception) { emptyList() })
    val history = mutable.asStateFlow()
    fun add(query: String) = save(normalize(listOf(query) + mutable.value))
    fun remove(query: String) = save(mutable.value.filter { it != query })
    fun clear() = save(emptyList())
    private fun save(values: List<String>) {
        storage.putString("search_history", json.encodeToString(values))
        mutable.value = values
    }
}
