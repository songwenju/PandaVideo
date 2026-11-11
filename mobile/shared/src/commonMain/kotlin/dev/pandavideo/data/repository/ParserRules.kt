package dev.pandavideo.data.repository

import dev.pandavideo.core.platform.KeyValueStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Declarative selectors for the supported source; URL and authorization guards stay in code. */
@Serializable
data class ParserRules(
    val schema: Int = 1,
    val revision: Int = 1,
    val searchListClass: String = "stui-vodlist__media",
    val thumbnailClass: String = "v-thumb",
    val synopsisClass: String = "stui-content__desc",
    val playerVariablePrefix: String = "player_",
    val searchMarkers: List<String> = listOf("阳光", "相关搜索结果"),
) {
    internal fun validate(): ParserRules {
        require(schema == 1 && revision > 0) { "不支持的规则版本" }
        require(listOf(searchListClass, thumbnailClass, synopsisClass, playerVariablePrefix).all {
            Regex("[a-zA-Z_][a-zA-Z0-9_-]{0,63}").matches(it)
        }) { "解析选择器格式无效" }
        require(searchMarkers.size in 1..4 && searchMarkers.all { it.isNotBlank() && it.length <= 40 }) {
            "搜索页面标识无效"
        }
        return this
    }
}

/** Keeps only validated configuration, including a last-known-good persistent copy. */
class ParserRulesStore(private val storage: KeyValueStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(runCatching {
        storage.getString("parser_rules")?.let { decode(it) }
    }.getOrNull() ?: ParserRules())
    val rules = mutable.asStateFlow()
    val sourceUrl: String get() = storage.getString("parser_rules_url").orEmpty()
    private fun decode(text: String): ParserRules {
        require(text.length <= 65_536) { "规则文件过大" }
        return json.decodeFromString<ParserRules>(text).validate()
    }
    fun import(text: String, source: String = "") {
        val next = decode(text)
        require(next.revision >= mutable.value.revision) { "不能回退到旧规则" }
        storage.putString("parser_rules", json.encodeToString(next))
        storage.putString("parser_rules_url", source)
        mutable.value = next
    }
    fun export(): String = json.encodeToString(mutable.value)
    fun reset() {
        storage.remove("parser_rules")
        storage.remove("parser_rules_url")
        mutable.value = ParserRules()
    }
}
