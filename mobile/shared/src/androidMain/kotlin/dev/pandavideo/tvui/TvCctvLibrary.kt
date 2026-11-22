package dev.pandavideo.tvui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.pandavideo.app.AppState
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.data.repository.CctvSource
import dev.pandavideo.ui.common.rememberSkeletonAlpha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Searches provider titles without changing their stable playback identity. */
internal fun tvCctvMatches(items: List<CatalogItem>, query: String): List<CatalogItem> =
    if (query.isBlank()) items else TvTitleIndex(items).search(query, "variety")

/** Uses the topic as the caption while retaining the complete provider title for search. */
internal fun tvCctvProgramTitle(item: CatalogItem): String =
    item.title.replace(Regex("^《[^》]+》\\s*\\d{8}\\s*"), "").ifBlank { item.title }

/** Keeps each column's loaded pages and remote focus while details own the player. */
@Composable
internal fun TvCctvLibrary(model: AppState, navigation: TvLibraryState,
    open: (TvDetailSelection) -> Unit, modifier: Modifier, keepNavigationFocus: Boolean) {
    BackHandler { navigation.tab = "精选"; navigation.restoreFocus = true }
    val column = navigation.cctvColumn
    val period = navigation.cctvYear + navigation.cctvMonth
    val query = navigation.cctvQuery
    val cacheKey = "$column:$period"
    val focusKey = "$cacheKey:$query"
    val columns = CctvSource.columns.entries.filter {
        navigation.cctvChannel == "全部" || it.value in CctvSource.channels[navigation.cctvChannel].orEmpty()
    }
    val columnTabs = rememberLazyListState(initialFirstVisibleItemIndex =
        columns.indexOfFirst { it.key == column }.coerceAtLeast(0))
    val grid = navigation.cctvGrids.getOrPut(focusKey) { LazyGridState() }
    var page by remember(cacheKey) { mutableStateOf(navigation.cctvPages[cacheKey]) }
    var next by remember(cacheKey) { mutableIntStateOf(page?.nextStart() ?: 0) }
    var request by remember(focusKey) { mutableIntStateOf(0) }
    var loading by remember(focusKey) { mutableStateOf(page == null) }
    var error by remember(focusKey) { mutableStateOf<String?>(null) }
    var searchDialog by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val restored = remember { FocusRequester() }
    var initialized by remember(focusKey, navigation.cctvChannel) { mutableStateOf(false) }
    LaunchedEffect(focusKey, request) {
        if (page != null && (request == 0 && query.isBlank() || page?.hasMore != true)) return@LaunchedEffect
        loading = true
        try {
            // The public column endpoint has a date filter but no verified keyword API.
            // Search successive pages in bounded batches, publishing matches immediately.
            // Explicit continuation avoids scanning decades of daily programs in one request.
            var fetched = 0
            do {
                val result = model.televisionCctvPrograms(column, next, period)
                page = if (next == 0) result else result.copy(
                    items = (page?.items.orEmpty() + result.items).distinctBy { it.url })
                navigation.cctvPages[cacheKey] = page!!
                next = result.nextStart()
                fetched++
                val matches = if (query.isBlank()) 0 else withContext(Dispatchers.Default) {
                    tvCctvMatches(page!!.items, query).size
                }
            } while (query.isNotBlank() && page?.hasMore == true && page?.stale != true &&
                fetched < 10 && matches < 60)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "请检查网络后重试" }
        finally { loading = false }
    }
    val allItems = page?.items.orEmpty()
    val items = remember(allItems, query) { tvCctvMatches(allItems, query) }
    val saved = navigation.cctvFocused[focusKey]
    LaunchedEffect(focusKey, navigation.cctvChannel, items) {
        // A restored column may be outside the lazy row; attach its focus target first.
        val selectedIndex = columns.indexOfFirst { it.key == column }
        if (!initialized && columnTabs.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex })
            columnTabs.scrollToItem(selectedIndex)
        withFrameNanos { }
        if (navigation.restoreFocus && items.any { it.url == saved }) {
            grid.scrollToItem(items.indexOfFirst { it.url == saved })
            withFrameNanos { }
            restored.requestFocus()
            navigation.restoreFocus = false
        } else if (!initialized && !keepNavigationFocus) {
            first.requestFocus()
            navigation.restoreFocus = false
        }
        initialized = true
    }
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TvChoice("频道", (listOf("全部") + CctvSource.channels.keys).map { it to it },
                navigation.cctvChannel) { channel ->
                navigation.cctvChannel = channel
                if (channel != "全部" && CctvSource.columns[column] !in CctvSource.channels.getValue(channel))
                    navigation.cctvColumn = CctvSource.columns.entries.first {
                        it.value in CctvSource.channels.getValue(channel)
                    }.key
            }
            val year = Calendar.getInstance().get(Calendar.YEAR)
            TvChoice("年份", listOf("" to "全部") + (year downTo 2000).map { "$it" to "${it}年" },
                navigation.cctvYear) {
                navigation.cctvYear = it
                if (it.isBlank()) navigation.cctvMonth = ""
            }
            if (navigation.cctvYear.isNotBlank())
                TvChoice("月份", listOf("" to "全年") + (1..12).map { it.toString().padStart(2, '0') to "${it}月" },
                    navigation.cctvMonth) { navigation.cctvMonth = it }
            TvAction(if (query.isBlank()) "搜索节目" else "搜索：$query", compact = true) { searchDialog = true }
            if (query.isNotBlank()) TvAction("清除搜索", compact = true) { navigation.cctvQuery = "" }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(state = columnTabs, modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(columns, key = { it.key }) { (id, title) ->
                TvAction(title, id == column, Modifier.height(36.dp)
                    .then(if (id == column) Modifier.focusRequester(first) else Modifier), compact = true) {
                    navigation.cctvColumn = id
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(if (page?.stale == true) "已缓存节目 · 网络恢复后可继续加载"
            else if (query.isNotBlank()) "已检索 ${allItems.size} 期 · 找到 ${items.size} 期" +
                if (loading) " · 正在查找" else if (page?.hasMore == true) " · 可继续检索更早节目" else " · 当前范围检索完毕"
            else "${CctvSource.columns[column]} · ${period.ifBlank { "全部年份" }} · 已加载 ${items.size} 期",
            color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        val skeletonAlpha = if (loading) rememberSkeletonAlpha() else 0f
        LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid, modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(items, key = { it.url }) { item ->
                TvPoster(tvCctvProgramTitle(item), item.cover, listOf(item.broadcastDate, item.remark).filter { it.isNotBlank() }.joinToString(" · "),
                    Modifier.then(if (item.url == saved) Modifier.focusRequester(restored) else Modifier)
                        .onFocusChanged { if (it.isFocused) navigation.cctvFocused[focusKey] = item.url },
                    imageRatio = 16f / 9f) {
                    open(TvDetailSelection(item.title, item.cover,
                        PlayerRequest(page = item.url, title = item.title), catalog = item,
                        recommendations = items))
                }
            }
            if (loading) {
                items(if (items.isEmpty()) 6 else 3, key = { "loading-$it" }) {
                    TvPosterSkeleton(skeletonAlpha, imageRatio = 16f / 9f)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (!loading && page?.hasMore == true) TvAction(if (query.isBlank()) "加载更多节目" else "继续搜索更早节目") {
                    next = page!!.nextStart()
                    request++
                }
                else if (!loading && items.isEmpty() && error == null)
                    Text(if (query.isBlank()) "这个年月暂无节目，试试其他年份或月份" else "没有匹配节目，试试其他关键词或年月",
                        color = Muted, fontSize = 16.sp)
            }
        }
    }
    if (searchDialog) TvDialog(onDismissRequest = { searchDialog = false }) {
        var draft by remember { mutableStateOf(query) }
        val keyboard = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; keyboard.requestFocus() }
        Row(Modifier.background(Background, RoundedCornerShape(16.dp)).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            TvKeyboard(draft, keyboard, false, {}, editorLabel = "输入节目主题或关键词") { draft = it }
            Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("查找${CctvSource.columns[column]}", fontSize = 22.sp)
                Text("可输入主题、日期、拼音或首字母。\n仅检索当前栏目和年月；继续搜索可查找更早节目。",
                    color = Muted, fontSize = 16.sp)
                TvAction("开始搜索") { navigation.cctvQuery = draft.trim(); searchDialog = false }
                TvAction("取消") { searchDialog = false }
            }
        }
    }
    error?.let { message ->
        TvDialog(onDismissRequest = { error = null }) {
            Column(Modifier.width(580.dp).background(Background, RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("节目加载失败", fontSize = 22.sp)
                Text(message, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvAction("重试") { error = null; request++ }
                    TvAction("取消") { error = null }
                }
            }
        }
    }
}
