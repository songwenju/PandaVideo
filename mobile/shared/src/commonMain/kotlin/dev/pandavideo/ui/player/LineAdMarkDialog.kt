package dev.pandavideo.ui.player

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.pandavideo.core.model.Episode
import dev.pandavideo.data.user.LineAdStore

/** One reversible report action, shared by inline and fullscreen line controls. */
@Composable
internal fun LineAdMarkDialog(
    store: LineAdStore, page: String, episode: Episode,
    onDismiss: () -> Unit, onFailure: () -> Unit, onSaved: () -> Unit = {},
) {
    val marks by store.marks.collectAsState()
    val marked = marks.let { store.hasAds(page, episode) }
    val name = episode.lineName.ifBlank { "线路 ${episode.line}" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (marked) "取消广告标记" else "标记插播广告") },
        text = { Text(if (marked) "取消“$name”的有广告标记？"
            else "将“$name”标记为有插播广告？标记会长期保存，自动选路时优先使用未标记广告的可用线路。") },
        confirmButton = {
            TextButton(onClick = {
                if (store.setHasAds(page, episode, !marked)) {
                    onSaved()
                    onDismiss()
                } else onFailure()
            }) { Text(if (marked) "取消广告标记" else "标记有广告") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}
