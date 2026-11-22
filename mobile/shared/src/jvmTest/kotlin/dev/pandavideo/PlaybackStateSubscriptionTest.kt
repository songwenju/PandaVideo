package dev.pandavideo

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.ui.player.collectPlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the first composition after replacing a real StateFlow subscription. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStateSubscriptionTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test fun replacingSessionNeverComposesThePreviousPlayersRenderedFrameState() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val composition = Composition(EmptyApplier(), recomposer)
        val job = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        // Session generations restart at one. The subscription owner, not this numeric ID,
        // must distinguish a new film from the old player's rendered first frame.
        val old = PlayerState(1, PlayerPhase.Playing, position = 60_000, firstFrameRendered = true)
        val upcoming = PlayerState(1, PlayerPhase.Preparing)
        val subscription = mutableStateOf<StateFlow<PlayerState>>(MutableStateFlow(old))
        val composed = mutableListOf<PlayerState>()
        try {
            composition.setContent {
                val player by subscription.value.collectPlaybackState()
                SideEffect { composed += player }
            }
            runCurrent()
            assertEquals(old, composed.last())
            composed.clear()
            subscription.value = MutableStateFlow(upcoming)
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(1)
            runCurrent()
            assertEquals(upcoming, composed.first(),
                "The first replacement composition must not reuse the previous first-frame flag")
        } finally {
            composition.dispose()
            recomposer.cancel()
            job.cancel()
        }
    }
}
