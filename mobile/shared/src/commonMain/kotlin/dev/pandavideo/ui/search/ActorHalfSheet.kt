package dev.pandavideo.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pandavideo.core.platform.PlayerOverlayBackHandler
import dev.pandavideo.ui.common.NavigationIcons

/** Player overlay with a caller-owned viewport, also shared by the casting device list. */
@Composable
internal fun ActorHalfSheet(
    visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    title: String = "演员资料",
    content: @Composable () -> Unit,
) {
    val dismiss = rememberUpdatedState(onDismiss)
    PlayerOverlayBackHandler(enabled = visible, onBack = onDismiss)
    Box(modifier) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally(tween(250)) { it },
            exit = slideOutHorizontally(tween(250)) { it },
        ) {
            Surface(
                modifier = Modifier.fillMaxSize()
                    .semantics { contentDescription = "${title}面板" },
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 8.dp,
            ) {
                Column {
                    // Restrict drag-to-dismiss to the header so scrolling actor works never
                    // consumes video gestures or accidentally closes the panel.
                    Column(Modifier.fillMaxWidth().pointerInput(Unit) {
                        var horizontal = 0f
                        var vertical = 0f
                        detectDragGestures(
                            onDragStart = { horizontal = 0f; vertical = 0f },
                            onDragEnd = {
                                if (horizontal > 48.dp.toPx() || vertical > 48.dp.toPx()) dismiss.value()
                            },
                            onDragCancel = { horizontal = 0f; vertical = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                horizontal += amount.x
                                vertical += amount.y
                            },
                        )
                    }) {
                        Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(32.dp).height(3.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            IconButton(onDismiss, Modifier.size(40.dp)) {
                                Icon(NavigationIcons.Close, "关闭$title", Modifier.size(20.dp))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                }
            }
        }
    }
}
