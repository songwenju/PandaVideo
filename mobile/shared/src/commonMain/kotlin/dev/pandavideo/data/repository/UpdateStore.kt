package dev.pandavideo.data.repository

import dev.pandavideo.core.platform.KeyValueStorage
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Release metadata; installation is always an explicit platform link action. */
@Serializable
data class UpdateNotice(
    val versionCode: Int, val version: String, val notes: String = "", val url: String,
)

class UpdateStore(private val storage: KeyValueStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow<UpdateNotice?>(null)
    val notice = mutable.asStateFlow()
    val sourceUrl: String get() = storage.getString("update_source").orEmpty()
    fun accept(text: String, source: String) {
        require(text.length <= 65_536) { "更新信息过大" }
        val next = json.decodeFromString<UpdateNotice>(text)
        require(next.versionCode > 0 && next.version.length in 1..40 && next.notes.length <= 4_000 &&
            isWebUrl(next.url) && Url(next.url).protocol.name == "https") { "更新信息格式无效" }
        storage.putString("update_source", source)
        mutable.value = next.takeIf { it.versionCode > CURRENT_VERSION_CODE }
    }
    companion object {
        const val CURRENT_VERSION_CODE = 1
        const val CURRENT_VERSION = "0.1.0"
    }
}
