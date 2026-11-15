package dev.pandavideo.app

import dev.pandavideo.core.model.ActorSuggestions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent person-search state; a failed film query cannot erase person results. */
internal data class ActorSearchState(
    val result: ActorSuggestions? = null, val loading: Boolean = false, val error: Boolean = false,
)

/** Cancels replaced searches and checks cancellation before publishing source responses. */
internal class ActorBrowser(
    private val scope: CoroutineScope, private val search: suspend (String) -> ActorSuggestions,
) {
    private var job: Job? = null
    private val mutable = MutableStateFlow(ActorSearchState())
    val state = mutable.asStateFlow()

    fun search(query: String) {
        clear()
        if (query.isBlank()) return
        mutable.value = ActorSearchState(loading = true)
        job = scope.launch {
            try {
                val result = search.invoke(query)
                ensureActive()
                mutable.value = ActorSearchState(result)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                ensureActive()
                mutable.value = ActorSearchState(error = true)
            }
        }
    }

    fun clear() {
        job?.cancel()
        mutable.value = ActorSearchState()
    }
}
