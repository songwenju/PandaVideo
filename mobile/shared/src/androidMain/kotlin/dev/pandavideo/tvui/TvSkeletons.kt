package dev.pandavideo.tvui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.pandavideo.ui.common.SkeletonBox

/** Matches the artwork and two caption lines of a TV poster without adding focus targets. */
@Composable
internal fun TvPosterSkeleton(alpha: Float, imageRatio: Float = 0.72f) {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(imageRatio), alpha, radius = 8)
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SkeletonBox(Modifier.fillMaxWidth(0.85f).height(16.dp), alpha)
            SkeletonBox(Modifier.fillMaxWidth(0.55f).height(13.dp), alpha)
        }
    }
}

/** Reserves the synopsis, actions, line choices and episode choices while detail resolves. */
@Composable
internal fun TvDetailSkeleton(alpha: Float) {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBox(Modifier.fillMaxWidth().height(15.dp), alpha)
        SkeletonBox(Modifier.fillMaxWidth(0.75f).height(15.dp), alpha)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) { SkeletonBox(Modifier.width(92.dp).height(36.dp), alpha, radius = 18) }
        }
        SkeletonBox(Modifier.width(90.dp).height(18.dp), alpha)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { SkeletonBox(Modifier.weight(1f).height(30.dp), alpha, radius = 15) }
        }
        SkeletonBox(Modifier.width(60.dp).height(18.dp), alpha)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(5) { SkeletonBox(Modifier.weight(1f).height(36.dp), alpha, radius = 18) }
        }
    }
}
