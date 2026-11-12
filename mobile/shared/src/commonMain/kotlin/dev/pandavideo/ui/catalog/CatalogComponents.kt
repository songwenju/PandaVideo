package dev.pandavideo.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.app.CatalogState
import dev.pandavideo.app.HomeCategoryOptions
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.Poster
import dev.pandavideo.ui.common.SkeletonBox
import dev.pandavideo.ui.common.posterSharedTransition
import dev.pandavideo.ui.common.rememberSkeletonAlpha
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun HomeCategories(value: String, select: (String) -> Unit) {
    val row = rememberLazyListState()
    LaunchedEffect(value) {
        val index = HomeCategoryOptions.indexOfFirst { it.first == value }
        if (index >= 0) row.animateScrollToItem(index)
    }
    LazyRow(
        state = row,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(HomeCategoryOptions) { (key, label) ->
            val selected = key == value
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .selectable(selected, role = Role.Tab) { select(key) }
                        .padding(horizontal = 8.dp, vertical = 10.dp))
                Box(Modifier.width(18.dp).height(3.dp).background(if (selected) Accent else Color.Transparent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CatalogContent(state: CatalogState, model: AppState, refresh: () -> Unit, more: () -> Unit, play: (CatalogItem) -> Unit, home: Boolean = false, modifier: Modifier = Modifier) {
    val grid = rememberLazyGridState()
    val latestState by rememberUpdatedState(state)
    val loadMore by rememberUpdatedState(more)
    LaunchedEffect(grid) {
        snapshotFlow {
            val current = latestState
            val page = current.page ?: return@snapshotFlow null
            val lastVisible = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            page.nextStart().takeIf {
                !current.loading && current.error == null && page.hasMore && !page.stale &&
                    (current.items.isEmpty() || lastVisible >= (current.items.size - 6).coerceAtLeast(0))
            }
        }.distinctUntilChanged().collect { next -> if (next != null) loadMore() }
    }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { if (!state.loading) refresh() }, modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
        if (!home) {
            Text(
                state.page?.label ?: "影视精选",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                fontWeight = FontWeight.Bold,
            )
        }
        state.error?.let { ErrorText(it) }
        if (state.page?.stale == true) Text("正在展示缓存数据", Modifier.padding(horizontal = 16.dp))
        if (state.loading && state.items.isEmpty() && state.error == null) {
            CatalogSkeleton(Modifier.weight(1f).fillMaxWidth(), home)
        } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        LazyVerticalGrid(state = grid, modifier = Modifier.fillMaxSize(), columns = if (home) GridCells.Fixed(if (maxWidth < 600.dp) 2 else if (maxWidth < 840.dp) 3 else 4) else GridCells.Adaptive(120.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(state.items, key = { it.url }) { item ->
                Column {
                    Poster(model.poster(item), item.title, Modifier.fillMaxWidth().aspectRatio(0.7f).posterSharedTransition(item.url).clip(RoundedCornerShape(8.dp)).clickable { play(item) })
                    Text(item.title, color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                    Row(
                        Modifier.padding(top = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.year.takeIf { it.isNotBlank() }?.let { year ->
                            Text(year, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                            if (item.rating != null) Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        item.rating?.let { rating ->
                            Text("$rating 分", color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    if (!home && item.casts.isNotEmpty()) {
                        Text(item.casts.joinToString(" / "), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (state.loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (!state.loading && state.items.isEmpty() && state.error == null) Text("暂无影视条目")
                else if (state.page?.hasMore == true) OutlinedButton(more, Modifier.fillMaxWidth(), enabled = !state.loading) { Text("加载更多") }
                else if (state.items.isNotEmpty()) Text("已加载全部 ${state.items.size} 部")
            }
        }
        }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
}

/** Poster-grid placeholder shown while the first catalog page is loading. */
@Composable
internal fun CatalogSkeleton(modifier: Modifier = Modifier, home: Boolean = false) {
    val alpha = rememberSkeletonAlpha()
    BoxWithConstraints(modifier) {
        val columns = when {
            maxWidth < 600.dp -> 2
            maxWidth < 840.dp -> 3
            else -> 4
        }
        LazyVerticalGrid(
            columns = if (home) GridCells.Fixed(columns) else GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(10) {
                Column {
                    SkeletonBox(Modifier.fillMaxWidth().aspectRatio(0.7f), alpha, radius = 8)
                    SkeletonBox(Modifier.padding(top = 7.dp).width(72.dp).height(14.dp), alpha)
                    SkeletonBox(Modifier.padding(top = 6.dp).width(48.dp).height(10.dp), alpha)
                }
            }
        }
    }
}
