package dev.pandavideo.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.ui.common.NavigationIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchSuggestions(model: AppState, query: String, modifier: Modifier, submit: (String) -> Unit) {
    val history by model.searchHistory.history.collectAsState()
    val discover by model.discover.collectAsState()
    val browse by model.browse.collectAsState()
    val titles = (discover.items + browse.items).map { it.title }.distinct()
    val suggestions = (history + titles).distinct()
        .filter { it.contains(query.trim(), ignoreCase = true) }.take(8)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (query.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton({ model.searchHistory.clear() }, enabled = history.isNotEmpty()) {
                        Icon(NavigationIcons.Delete, contentDescription = "清空搜索历史", modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (history.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("暂无搜索历史，输入片名开始搜索", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.forEach { title ->
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, Modifier.clickable(role = Role.Button) { submit(title) }
                                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton({ model.searchHistory.remove(title) }, Modifier.size(26.dp)) {
                                Icon(NavigationIcons.Close, contentDescription = "删除历史：$title",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("搜索联想", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
            if (suggestions.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("暂无搜索联想", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
            items(suggestions, key = { it }, span = { GridItemSpan(maxLineSpan) }) { title ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { submit(title) }.padding(vertical = 14.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(NavigationIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                }
            }
        }
    }
}
