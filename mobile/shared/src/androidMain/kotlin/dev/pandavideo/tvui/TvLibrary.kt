package dev.pandavideo.tvui

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import dev.pandavideo.app.AppState
import dev.pandavideo.app.HomeCategoryOptions
import dev.pandavideo.app.HotTypeOptions
import dev.pandavideo.app.BrowseTypeOptions
import dev.pandavideo.app.BrowseGenreOptions
import dev.pandavideo.app.BrowseSortOptions
import dev.pandavideo.app.browseYearOptions
import dev.pandavideo.core.model.*
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.rememberSkeletonAlpha
import dev.pandavideo.ui.catalog.catalogAutoLoadStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

internal val TvMainTabOptions = listOf(
    "热播" to "热播", "精选" to "精选", "央视" to "央视点播", "分类" to "分类",
)

/** Survives detail/player replacement, including remote position and search input. */
internal class TvLibraryState {
    var tab by mutableStateOf("热播")
    private var searchReturnTab = "热播"
    var homeCategory by mutableStateOf("top250")
    var homeCountry by mutableStateOf("all")
    var hotType by mutableStateOf("tv")
    var filters by mutableStateOf(BrowseFilters())
    var searchType by mutableStateOf("all")
    var query by mutableStateOf("")
    var restoreFocus by mutableStateOf(false)
    var initialized = false
    val grids = listOf("精选", "热播", "分类", "搜索", "记录").associateWith { LazyGridState() }
    val focused = mutableMapOf<String, String>()
    val positions = mutableMapOf<String, Int>()
    var titleIndex: TvTitleIndex? = null
    var cctvColumn by mutableStateOf(dev.pandavideo.data.repository.CctvSource.columns.keys.first())
    var cctvChannel by mutableStateOf("全部")
    var cctvYear by mutableStateOf("")
    var cctvMonth by mutableStateOf("")
    var cctvQuery by mutableStateOf("")
    val cctvPages = mutableMapOf<String, CatalogPage>()
    val cctvGrids = mutableMapOf<String, LazyGridState>()
    val cctvFocused = mutableMapOf<String, String>()

    /** Selects a content tab immediately while preserving each tab's filters and position. */
    fun selectMainTab(target: String) {
        if (target != tab && TvMainTabOptions.any { it.first == target }) {
            restoreFocus = false
            tab = target
        }
    }

    /** Opens search without losing the originating page or its saved grid position. */
    fun openSearch() {
        if (tab == "搜索") return
        searchReturnTab = tab
        tab = "搜索"
    }

    /** Leaves search while preserving its query and the originating page's selection. */
    fun closeSearch() {
        if (tab != "搜索") return
        tab = searchReturnTab
        restoreFocus = true
    }
}

@Composable
internal fun TvLibrary(model: AppState, navigation: TvLibraryState, receiveCast: () -> Unit,
    open: (TvDetailSelection) -> Unit) {
    val navFocus = remember {
        (TvMainTabOptions.map { it.first } + "记录").associateWith { FocusRequester() }
    }
    // Start on Hot, but restore an empty page to its own tab so focus activation
    // cannot unexpectedly switch the user's page when returning from search.
    val firstNav = navFocus[navigation.tab] ?: navFocus.getValue("热播")
    var mainTabsFocused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 22.dp)) {
        if (navigation.tab != "搜索") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction("搜索", modifier = Modifier.height(36.dp), compact = true,
                    icon = NavigationIcons.Search) { navigation.openSearch() }
                // Keep content tabs at one composition site, including CCTV.
                // Moving focus activates a tab without replacing its focused button.
                Row(Modifier.onFocusChanged { mainTabsFocused = it.hasFocus }.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvMainTabOptions.forEach { (target, label) ->
                        TvAction(label, navigation.tab == target, Modifier.height(36.dp)
                            .focusRequester(navFocus.getValue(target))
                            .onFocusChanged { if (it.isFocused) navigation.selectMainTab(target) },
                            compact = true) { navigation.selectMainTab(target) }
                    }
                }
                Spacer(Modifier.weight(1f))
                TvAction("记录", navigation.tab == "记录", Modifier.height(36.dp)
                    .focusRequester(navFocus.getValue("记录")), compact = true,
                    icon = NavigationIcons.History) { navigation.tab = "记录" }
                TvAction("投屏", modifier = Modifier.height(36.dp), compact = true,
                    icon = NavigationIcons.Cast, action = receiveCast)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (navigation.tab == "央视") {
            TvCctvLibrary(model, navigation, open, Modifier.weight(1f), mainTabsFocused)
        } else {
            TvCatalogLibrary(model, navigation, firstNav, Modifier.weight(1f), open)
        }
    }
}

@Composable
private fun TvCatalogLibrary(model: AppState, navigation: TvLibraryState, firstNav: FocusRequester,
    modifier: Modifier, open: (TvDetailSelection) -> Unit) {
    val discover by model.discover.collectAsState()
    val hot by model.hot.collectAsState()
    val browse by model.browse.collectAsState()
    val search by model.search.collectAsState()
    val history by model.user.history.collectAsState()
    val tab = navigation.tab
    BackHandler(enabled = tab == "搜索") { navigation.closeSearch() }
    val catalog = when (tab) { "热播" -> hot; "分类" -> browse; else -> discover }
    val cardFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val keyboardFocus = remember { FocusRequester() }
    val hotTypeFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var localTitles by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var localSearching by remember { mutableStateOf(false) }
    var localSearchError by remember { mutableStateOf(false) }
    val selectionKey = when (tab) {
        "精选" -> "${navigation.homeCategory}:${navigation.homeCountry}"
        "热播" -> navigation.hotType
        "分类" -> navigation.filters.toString()
        "搜索" -> "${navigation.searchType}:${navigation.query}"
        else -> tab
    }
    var previousSelection by remember(tab) { mutableStateOf(selectionKey) }
    val latestCatalog by rememberUpdatedState(catalog)
    val latestSelection by rememberUpdatedState("$tab:$selectionKey")
    LaunchedEffect(tab, selectionKey) {
        if (previousSelection != selectionKey) {
            navigation.focused.remove(tab)
            navigation.positions.remove(tab)
            navigation.grids.getValue(tab).scrollToItem(0)
        }
        previousSelection = selectionKey
    }
    LaunchedEffect(tab, navigation.homeCategory, navigation.homeCountry, navigation.hotType, navigation.filters) {
        val selected = "$tab:$selectionKey"
        val category = navigation.homeCategory
        val country = navigation.homeCountry
        val hotType = navigation.hotType
        val filters = navigation.filters
        val channel = when (tab) {
            "精选" -> model.discover
            "热播" -> model.hot
            "分类" -> model.browse
            else -> return@LaunchedEffect
        }
        when (tab) {
            "精选" -> model.loadDiscover(navigation.homeCategory, country = navigation.homeCountry)
            "热播" -> model.loadHot(navigation.hotType)
            "分类" -> model.loadBrowse(navigation.filters)
        }
        // Start after selecting the source. A composition can still hold the previous
        // filter's snapshot; wait for its subscription to catch up before paging.
        // Each append moves the halfway threshold forward. Source offsets deduplicate
        // layout updates, while loading/error/hasMore stop duplicate or failed requests.
        val grid = navigation.grids.getValue(tab)
        snapshotFlow {
            val state = latestCatalog
            if (latestSelection != selected || state != channel.value) return@snapshotFlow null
            catalogAutoLoadStart(state, true,
                grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                state.items.size, prefetchAtHalfway = true)
        }.distinctUntilChanged().collect { next ->
            if (next != null) when (tab) {
                "精选" -> model.loadDiscover(category, more = true, country = country)
                "热播" -> model.loadHot(hotType, more = true)
                "分类" -> model.loadBrowse(filters, more = true)
            }
        }
    }
    LaunchedEffect(tab, navigation.query, navigation.searchType) {
        if (tab != "搜索") return@LaunchedEffect
        // Clear immediately, before debounce, so cancelled input never shows stale results.
        model.clearSearch()
        localTitles = emptyList()
        localSearching = false
        localSearchError = false
        if (navigation.query.isBlank()) return@LaunchedEffect
        localSearching = true
        try {
            delay(500)
            // Providers also support initials for titles absent from the shipped catalog.
            // Start online search first: optional SQLite/pinyin work must never block it.
            model.search(navigation.query.trim(), tvSearchSourceType(navigation.searchType))
            if (navigation.titleIndex == null) {
                navigation.titleIndex = withContext(Dispatchers.Default) {
                    TvTitleIndex(model.televisionSearchCatalog())
                }
            }
            val matches = withContext(Dispatchers.Default) {
                navigation.titleIndex!!.search(navigation.query, navigation.searchType)
            }
            localTitles = model.inspectTelevisionSearch(matches)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Local coverage is optional. Online results and their retry remain available.
            localSearchError = true
        } finally {
            localSearching = false
        }
    }
    val online = remember(search.result, navigation.searchType, navigation.query) {
        tvSearchResults(search.result?.results.orEmpty(), navigation.searchType, navigation.query)
    }
    val titles = when (tab) {
        "搜索" -> localTitles
        "记录" -> emptyList()
        else -> catalog.items
    }
    val catalogByUrl = remember(titles) { titles.associateBy { it.url } }
    val onlineByUrl = remember(online) { online.associateBy { it.detailUrl } }
    val searchOrder = remember(localTitles, online, navigation.query) {
        tvSearchOrder(localTitles, online, navigation.query)
    }
    val identities = when (tab) {
        "搜索" -> searchOrder
        "记录" -> history.map { it.pageUrl }
        else -> titles.map { it.url }
    }
    val saved = navigation.focused[tab]
    val restoreIndex = identities.indexOf(saved).takeIf { it >= 0 }
        ?: (navigation.positions[tab] ?: 0).coerceIn(0, (identities.size - 1).coerceAtLeast(0))
    val restoreIdentity = identities.getOrNull(restoreIndex)
    LaunchedEffect(tab) {
        // Search has no global navigation. Start on the keyboard, but leave a detail
        // return to the saved result requester instead of stealing its restored focus.
        if (tab == "搜索" && !navigation.restoreFocus) {
            withFrameNanos { }
            keyboardFocus.requestFocus()
        }
    }
    LaunchedEffect(tab, navigation.restoreFocus, identities) {
        if (navigation.restoreFocus && identities.isNotEmpty()) {
            navigation.grids.getValue(tab).scrollToItem(restoreIndex)
            withFrameNanos { }
            cardFocus.requestFocus()
            navigation.restoreFocus = false
        } else if (navigation.restoreFocus && tab != "搜索") {
            firstNav.requestFocus()
            navigation.restoreFocus = false
        } else if (!navigation.initialized) {
            // LazyRow must compose its selected option before requesting its focus.
            withFrameNanos { }
            when (tab) {
                "搜索" -> keyboardFocus.requestFocus()
                "热播" -> hotTypeFocus.requestFocus()
                else -> firstNav.requestFocus()
            }
            navigation.initialized = true
        }
    }
    fun cardModifier(identity: String, index: Int): Modifier = Modifier
        .then(if (identity == restoreIdentity) Modifier.focusRequester(cardFocus) else Modifier)
        .then(if (tab == "搜索" && index == 0) Modifier.focusRequester(firstResultFocus) else Modifier)
        .focusProperties {
            if (tab == "搜索" && index % 3 == 0) left = keyboardFocus
        }.onFocusChanged {
            if (it.isFocused) {
                navigation.focused[tab] = identity
                navigation.positions[tab] = index
            }
        }
    fun catalogSelection(item: CatalogItem) = TvDetailSelection(
        title = item.title, cover = model.poster(item), catalog = item,
        request = PlayerRequest(title = item.title), recommendations = titles,
    )
    Column(modifier.fillMaxWidth()) {
        if (tab != "搜索") {
            // Hot types and browse filters stay visible, matching the phone's rows.
            // Only Featured retains dropdowns for its independent content/region choices.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (tab) {
                    "精选" -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvChoice("内容", HomeCategoryOptions, navigation.homeCategory) { navigation.homeCategory = it }
                        TvChoice("地区", BrowseCountries.toList(), navigation.homeCountry) { navigation.homeCountry = it }
                    }
                    "热播" -> TvFilterRow("类型", HotTypeOptions, navigation.hotType,
                        activateOnFocus = true, selectedFocus = hotTypeFocus) {
                        navigation.hotType = it
                    }
                    "分类" -> {
                        TvFilterRow("类型", BrowseTypeOptions, navigation.filters.type) {
                            navigation.filters = navigation.filters.copy(type = it)
                        }
                        TvFilterRow("题材", BrowseGenreOptions, navigation.filters.genre) { navigation.filters = navigation.filters.copy(genre = it) }
                        TvFilterRow("地区", BrowseCountries.toList(), navigation.filters.country) { navigation.filters = navigation.filters.copy(country = it) }
                        TvFilterRow("年份", browseYearOptions(), navigation.filters.year) { navigation.filters = navigation.filters.copy(year = it) }
                        TvFilterRow("排序", BrowseSortOptions, navigation.filters.sort) { navigation.filters = navigation.filters.copy(sort = it) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (tab == "搜索") TvKeyboard(navigation.query, keyboardFocus, identities.isNotEmpty(),
                enterResults = {
                    // A lazy result can be outside the viewport after scrolling. Compose it
                    // before requesting focus instead of targeting a detached requester.
                    scope.launch {
                        navigation.grids.getValue("搜索").scrollToItem(0)
                        withFrameNanos { }
                        firstResultFocus.requestFocus()
                    }
                }) { navigation.query = it }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when (tab) {
                        "精选" -> "高分佳作 · 豆瓣 8.0+"
                        "记录" -> "继续观看"
                        "搜索" -> "搜索结果"
                        "热播" -> HotTypeOptions.first { it.first == navigation.hotType }.second
                        else -> "分类 · ${BrowseTypeOptions.first { it.first == navigation.filters.type }.second}"
                    }, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (tab == "搜索" && identities.isNotEmpty()) "共 ${identities.size} 部 · 确认查看详情"
                        else "确认查看详情", fontSize = 13.sp, color = Muted)
                }
                Spacer(Modifier.height(10.dp))
                val loading = when (tab) {
                    "搜索" -> search.loading || localSearching
                    "记录" -> false
                    else -> catalog.loading
                }
                val skeletonAlpha = if (loading || catalog.loadingMore) rememberSkeletonAlpha() else 0f
                if (identities.isEmpty() && !loading) {
                    Text(
                        if (tab == "搜索") when {
                            navigation.query.isBlank() -> "输入首字母、全拼或片名，例如 HZW / 海贼王"
                            search.error != null -> search.error.orEmpty()
                            localSearchError -> "本地片名暂时无法读取，在线搜索未找到结果，请重试"
                            else -> "未找到“${navigation.query}”\n试试完整片名或切换到“全部”"
                        }
                        else if (tab == "记录") "播放过的影片会保存在这里" else "暂时没有内容",
                        color = Muted, fontSize = 16.sp)
                }
                val columns = when (tab) { "搜索" -> 3; "分类" -> 8; else -> 6 }
                LazyVerticalGrid(GridCells.Fixed(columns), state = navigation.grids.getValue(tab),
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (identities.isEmpty() && loading) {
                        items(if (tab == "搜索") 6 else 12, key = { "loading-$it" }) {
                            TvPosterSkeleton(skeletonAlpha)
                        }
                    }
                    if (tab != "记录") itemsIndexed(identities, key = { _, identity -> identity }) { index, identity ->
                        val catalogItem = catalogByUrl[identity]
                        if (catalogItem != null) {
                            TvPoster(catalogItem.title, model.poster(catalogItem),
                                listOfNotNull(catalogItem.broadcastDate.ifBlank { catalogItem.year }.ifBlank { "日期未知" },
                                    catalogItem.rating?.let { "★ $it" }).joinToString(" · "),
                                cardModifier(identity, index)) {
                                open(catalogSelection(catalogItem))
                            }
                        } else {
                            val item = onlineByUrl.getValue(identity)
                            TvPoster(item.title, model.poster(item), item.remark.ifBlank { item.year },
                                cardModifier(identity, index)) {
                                open(TvDetailSelection(item.title, model.poster(item),
                                    PlayerRequest(page = item.detailUrl, title = item.title), search = item))
                            }
                        }
                    }
                    if (tab == "记录") itemsIndexed(history, key = { _, item -> item.pageUrl }) { index, item ->
                        TvPoster(item.title, item.cover, item.episode, cardModifier(item.pageUrl, index)) {
                            open(TvDetailSelection(item.title, item.cover,
                                PlayerRequest(page = item.pageUrl, title = item.title, resumeHistory = true)))
                        }
                    }
                    if (tab !in listOf("记录", "搜索") && titles.isNotEmpty() && catalog.loadingMore) {
                        items(columns, key = { "loading-more-$it" }) { TvPosterSkeleton(skeletonAlpha) }
                    }
                    if (tab !in listOf("记录", "搜索") && titles.isNotEmpty() && !catalog.loadingMore)
                        item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(when {
                            catalog.error != null -> "加载失败，请重试"
                            catalog.page?.hasMore == false -> "已加载全部 ${titles.size} 部"
                            else -> "继续浏览，自动加载更多"
                        }, color = Muted, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    }
                }
            }
        }
    }
}

/** Keeps options visible; focus activation is reserved for the single Hot type row. */
@Composable
internal fun TvFilterRow(title: String, options: List<Pair<String, String>>, selected: String,
    activateOnFocus: Boolean = false, selectedFocus: FocusRequester? = null, change: (String) -> Unit) {
    val scroll = rememberLazyListState(
        initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Muted, fontSize = 14.sp, modifier = Modifier.width(36.dp))
        // Separate rows must allow vertical focus movement even when more choices
        // remain offscreen. A focus group can prioritize those hidden row members.
        LazyRow(Modifier.weight(1f), state = scroll,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            items(options, key = { it.first }) { (key, label) ->
                TvAction(label, key == selected, Modifier.height(32.dp)
                    .then(if (key == selected && selectedFocus != null)
                        Modifier.focusRequester(selectedFocus) else Modifier)
                    .onFocusChanged { if (activateOnFocus && it.isFocused && key != selected) change(key) }
                    .semantics { this.selected = key == selected },
                    compact = true, horizontalPadding = 12.dp) { change(key) }
            }
        }
    }
}

/** Anchors a remote-friendly option list below its filter and restores focus on dismissal. */
@Composable
internal fun TvChoice(title: String, options: List<Pair<String, String>>, selected: String,
    change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var restoreTrigger by remember { mutableStateOf(false) }
    val triggerFocus = remember { FocusRequester() }
    val optionFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val label = options.firstOrNull { it.first == selected }?.second.orEmpty()
    fun dismiss() {
        expanded = false
        restoreTrigger = true
    }
    // Selection changes reload the catalog. Restore the invoking filter after the popup
    // releases its separate focus root, rather than allowing focus to jump to a poster.
    LaunchedEffect(expanded, restoreTrigger) {
        if (!expanded && restoreTrigger) {
            withFrameNanos { }
            triggerFocus.requestFocus()
            restoreTrigger = false
        }
    }
    Box {
        TvAction("$title：$label", compact = true, dropdown = true,
            modifier = Modifier.height(38.dp).focusRequester(triggerFocus).semantics {
                stateDescription = if (expanded) "已展开" else "已收起"
            }) { if (options.isNotEmpty()) expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = ::dismiss,
            modifier = Modifier.width(240.dp).heightIn(max = 360.dp),
            scrollState = scroll, containerColor = Background,
            properties = PopupProperties(focusable = true)) {
            // Long country/year lists must open at the selected item. A single column
            // keeps up/down movement predictable; the menu's position provider also
            // moves it inside the screen when the anchor is near an edge.
            LaunchedEffect(expanded, selectedIndex) {
                if (expanded && options.isNotEmpty()) {
                    withFrameNanos { }
                    scroll.scrollTo(with(density) { 44.dp.roundToPx() } * selectedIndex)
                    withFrameNanos { }
                    optionFocus.requestFocus()
                }
            }
            options.forEachIndexed { index, (key, text) ->
                TvAction(text, key == selected, compact = true,
                    modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp)
                        .then(if (index == selectedIndex) Modifier.focusRequester(optionFocus) else Modifier)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            if (index == 0) up = FocusRequester.Cancel
                            if (index == options.lastIndex) down = FocusRequester.Cancel
                        }.semantics { this.selected = key == selected }) {
                    change(key)
                    dismiss()
                }
                if (index != options.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
internal fun TvKeyboard(query: String, first: FocusRequester, hasResults: Boolean,
    enterResults: () -> Unit, editorLabel: String = "输入完整片名", change: (String) -> Unit) {
    val keys = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".toList()
    val requesters = remember { keys.mapIndexed { index, _ -> if (index == 0) first else FocusRequester() } }
    var editor by remember { mutableStateOf(false) }
    Column(Modifier.width(284.dp)) {
        Text(query.ifBlank { "输入首字母或全拼" }, fontSize = 22.sp,
            color = if (query.isBlank()) Muted else Color.White, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvAction("清空") { change("") }
            TvAction("退格") { change(query.dropLast(1)) }
        }
        Spacer(Modifier.height(12.dp))
        keys.chunked(6).forEachIndexed { row, letters ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                letters.forEachIndexed { col, char ->
                    val index = row * 6 + col
                    // Six cells leave ~44dp each. Normal 16dp text-button padding
                    // would leave only 12dp for a 16sp W, clipping its right stroke.
                    TvAction(char.toString(), compact = true, horizontalPadding = 4.dp,
                        modifier = Modifier.weight(1f).height(34.dp).focusRequester(requesters[index])
                        .onPreviewKeyEvent { event ->
                            if (col == 5 && hasResults &&
                                event.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_DPAD_RIGHT) {
                                if (event.nativeKeyEvent.action == AndroidKey.ACTION_DOWN) enterResults()
                                true
                            } else false
                        }
                        .focusProperties {
                            if (col > 0) left = requesters[index - 1]
                            if (col < 5) right = requesters[index + 1]
                            if (row > 0) up = requesters[index - 6]
                            if (row < 5) down = requesters[index + 6]
                        }) { change(query + char) }
                }
            }
            Spacer(Modifier.height(5.dp))
        }
        TvAction("中文 / 系统键盘") { editor = true }
    }
    if (editor) TvDialog(onDismissRequest = { editor = false }) {
        val editorFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { editorFocus.requestFocus(); keyboard?.show() }
        Column(Modifier.background(Background, RoundedCornerShape(16.dp)).padding(24.dp)) {
            OutlinedTextField(query, change, Modifier.focusRequester(editorFocus),
                label = { Text(editorLabel) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { editor = false }))
            Spacer(Modifier.height(16.dp))
            TvAction("完成") { editor = false }
        }
    }
}
