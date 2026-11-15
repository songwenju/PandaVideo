package dev.pandavideo.app

import dev.pandavideo.core.platform.DoubanSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Verified account display state; no credentials are copied into shared application state. */
internal data class DoubanConnectionState(
    val nickname: String? = null, val checking: Boolean = false, val error: String? = null,
)

/** Coordinates source verification and catalog refresh without modifying the local profile. */
internal class DoubanConnection(
    private val session: DoubanSession,
    private val scope: CoroutineScope,
    private val identity: suspend () -> String?,
    private val refresh: () -> Unit,
) {
    private val mutable = MutableStateFlow(DoubanConnectionState())
    val state = mutable.asStateFlow()
    val supported get() = session.supported
    private var job: Job? = null
    private var silentCheck = false

    fun dismissError() { mutable.value = mutable.value.copy(error = null) }

    fun verify(silent: Boolean = false) {
        val previous = job
        if (previous?.isActive == true && (silent || !silentCheck)) return
        silentCheck = silent
        job = scope.launch {
            // A login redirect must supersede a startup check that read older cookies.
            // Join cancellation before publishing the new state so the old finally block
            // cannot clear the new request's checking indicator.
            if (previous?.isActive == true) previous.cancelAndJoin()
            mutable.value = mutable.value.copy(checking = true, error = null)
            try {
                if (silent && session.cookieHeader("https://www.douban.com/mine/").isNullOrBlank()) return@launch
                val nickname = identity()
                mutable.value = DoubanConnectionState(nickname = nickname, checking = true,
                    error = if (nickname == null && !silent) "尚未确认豆瓣登录，请完成登录或网页中的安全验证后重试" else null)
                if (nickname != null && !silent) refresh()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                // A challenge or connection error is not evidence of a successful login.
                mutable.value = mutable.value.copy(nickname = null,
                    error = if (silent) null else "暂时无法验证豆瓣账号，请检查网络或完成网页中的安全验证后重试")
            } finally { mutable.value = mutable.value.copy(checking = false) }
        }
    }

    fun disconnect() {
        if (job?.isActive == true) return
        job = scope.launch {
            mutable.value = mutable.value.copy(checking = true, error = null)
            try {
                session.clear()
                mutable.value = DoubanConnectionState(checking = true)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = "断开豆瓣账号失败，请重试") }
            finally { mutable.value = mutable.value.copy(checking = false) }
        }
    }
}
