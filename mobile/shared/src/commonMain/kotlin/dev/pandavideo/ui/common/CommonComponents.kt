package dev.pandavideo.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role

/** Insets for text actions; Material buttons retain their standard minimum touch target. */
internal val ActionButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
/** Insets for inline actions beside another control or a short instruction. */
internal val CompactActionButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/** Horizontally scrollable filter row matching the app's 8dp catalog corners. */
@Composable
internal fun ChoiceRow(
    options: List<Pair<String, String>>, value: String, tab: Boolean = false, onSelect: (String) -> Unit,
) {
    val row = rememberLazyListState()
    LaunchedEffect(value, tab) {
        if (tab) {
            val index = options.indexOfFirst { it.first == value }
            if (index >= 0) row.animateScrollToItem(index)
        }
    }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), state = row,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { index ->
            val option = options[index]
            val selected = option.first == value
            Box(
                modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (tab) Modifier.selectable(selected, role = Role.Tab) { onSelect(option.first) }
                        else Modifier.clickable(role = Role.Button) { onSelect(option.first) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(option.second, fontSize = 13.sp, lineHeight = 20.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun playbackTime(milliseconds: Long): String = dev.pandavideo.core.model.formatMinutesSeconds(milliseconds)

/** Shared shimmer alpha used by every skeleton placeholder on the screen. */
@Composable
internal fun rememberSkeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha",
    )
    return alpha
}

/** Rounded placeholder box whose alpha follows the shared skeleton shimmer. */
@Composable
internal fun SkeletonBox(modifier: Modifier, alpha: Float, radius: Int = 6) {
    Box(
        modifier.clip(RoundedCornerShape(radius.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
    )
}
