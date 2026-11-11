package dev.pandavideo.data.user

import dev.pandavideo.core.platform.KeyValueStorage
import dev.pandavideo.core.platform.currentEpochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.random.Random

@Serializable
private data class YouthPreferences(
    val enabled: Boolean = false, val salt: String = "", val pinHash: String = "",
    val day: Long = 0, val usedSeconds: Int = 0, val failures: Int = 0, val lockedUntil: Long = 0,
)

/** Local parental duration guard, independent of restorable profile data. */
class YouthStore(private val storage: KeyValueStorage, private val now: () -> Long = ::currentEpochMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var value = runCatching {
        storage.getString("youth")?.let { json.decodeFromString<YouthPreferences>(it) }
    }.getOrNull() ?: YouthPreferences()
    private val enabledMutable = MutableStateFlow(value.enabled)
    val enabled = enabledMutable.asStateFlow()
    private val remainingMutable = MutableStateFlow(remaining())
    val remainingSeconds = remainingMutable.asStateFlow()
    private fun remaining(): Int = if (value.day == now() / DAY_MS)
        (DAILY_LIMIT_SECONDS - value.usedSeconds).coerceIn(0, DAILY_LIMIT_SECONDS) else DAILY_LIMIT_SECONDS
    fun enable(pin: String) {
        require(Regex("\\d{6}").matches(pin)) { "请设置 6 位数字家长密码" }
        require(!value.enabled) { "青少年模式已开启" }
        val salt = Random.nextBytes(32).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        value = value.copy(enabled = true, salt = salt, pinHash = hash(pin, salt), failures = 0, lockedUntil = 0)
        persist()
    }
    fun disable(pin: String) {
        require(now() >= value.lockedUntil) { "密码尝试过多，请稍后再试" }
        if (hash(pin, value.salt) != value.pinHash) {
            val failures = value.failures + 1
            value = value.copy(failures = failures, lockedUntil = if (failures >= 5) now() + 60_000 else 0)
            persist()
            error("家长密码不正确")
        }
        value = value.copy(enabled = false, failures = 0, lockedUntil = 0)
        persist()
    }
    /** Returns false when the daily budget is exhausted; callers pause every resumed playback. */
    fun canPlay(): Boolean {
        remainingMutable.value = remaining()
        return !value.enabled || remainingMutable.value > 0
    }
    fun tick() {
        if (!value.enabled) return
        val day = now() / DAY_MS
        value = value.copy(day = day, usedSeconds = (if (value.day == day) value.usedSeconds else 0)
            .plus(1).coerceAtMost(DAILY_LIMIT_SECONDS))
        persist()
    }
    private fun persist() {
        storage.putString("youth", json.encodeToString(value))
        enabledMutable.value = value.enabled
        remainingMutable.value = remaining()
    }
    private fun hash(pin: String, salt: String): String {
        var digest = "$salt:$pin"
        repeat(1_000) { digest = digest.encodeUtf8().sha256().hex() }
        return digest
    }
    companion object {
        const val DAILY_LIMIT_SECONDS = 40 * 60
        private const val DAY_MS = 86_400_000L
    }
}
