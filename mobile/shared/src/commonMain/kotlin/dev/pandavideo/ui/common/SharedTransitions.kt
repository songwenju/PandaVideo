package dev.pandavideo.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Root shared-transition scope, provided once by the app shell around the NavHost. */
internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Per-destination animated scope required to host shared elements. */
internal val LocalNavAnimatedVisibilityScope =
    staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Attaches a poster to the navigation shared-element transition. The same [key] on the
 * source list and the player cover produces a continuous morph; when either scope is
 * missing (desktop hosts, previews) the modifier resolves to a no-op so screens still
 * render without animation.
 */
@Composable
internal fun Modifier.posterSharedTransition(key: String?): Modifier {
    if (key.isNullOrBlank()) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedVisibilityScope.current ?: return this
    val sharedKey = "poster-$key"
    return with(sharedScope) {
        val state = rememberSharedContentState(key = sharedKey)
        this@posterSharedTransition.sharedElement(state, animatedScope)
    }
}
