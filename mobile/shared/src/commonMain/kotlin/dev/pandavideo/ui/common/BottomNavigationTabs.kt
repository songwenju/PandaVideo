package dev.pandavideo.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bottom destinations respond only to taps, retaining the user's previously saved order. */
@Composable
internal fun BottomNavigationTabs(
    tabs: List<Pair<String, String>>,
    savedOrder: List<String>,
    selectedRoute: String?,
    onSelect: (String) -> Unit,
) {
    val orderedTabs = (savedOrder + tabs.map { it.first }).distinct()
        .mapNotNull { route -> tabs.find { it.first == route } }
    Row(
        Modifier.fillMaxWidth().height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        orderedTabs.forEach { (target, label) ->
            val tint = if (selectedRoute == target) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected = selectedRoute == target, role = Role.Tab) { onSelect(target) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(NavigationIcons.forRoute(target), null, Modifier.size(22.dp), tint = tint)
                Text(label, color = tint, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}
