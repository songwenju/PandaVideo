package dev.pandavideo.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.app.SearchState
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.Poster
import dev.pandavideo.ui.common.SkeletonBox
import dev.pandavideo.ui.common.posterSharedTransition
import dev.pandavideo.ui.common.rememberSkeletonAlpha

@Composable
internal fun SearchPage(
    model: AppState, query: String, showingResults: Boolean,
    onQueryChange: (String) -> Unit,
    submit: () -> Unit, searchTitle: (String) -> Unit, reset: () -> Unit,
    back: () -> Unit, play: (SearchItem) -> Unit,
) {
    val state by model.search.collectAsState()
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!entered && !showingResults) requester.requestFocus()
        entered = true
    }
    val dismissKeyboard = remember(focus) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f) focus.clearFocus()
                return Offset.Zero
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding().imePadding().nestedScroll(dismissKeyboard),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = back) {
                Icon(NavigationIcons.Back, contentDescription = "返回")
            }
            TextField(
                value = query, onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(requester),
                placeholder = { Text("搜索影视名称", fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                leadingIcon = { Icon(NavigationIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = {
                        reset()
                        requester.requestFocus()
                    }) { Icon(NavigationIcons.Close, contentDescription = "清除搜索内容", modifier = Modifier.size(18.dp)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            TextButton(onClick = submit, enabled = query.isNotBlank()) {
                Text("搜索", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (query.isNotBlank()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showingResults) {
            SearchResults(state, query, model, Modifier.weight(1f),
                onBack = { reset(); requester.requestFocus() }, retry = submit, play = play)
        } else {
            SearchSuggestions(model, query, Modifier.weight(1f), searchTitle)
        }
    }
}

@Composable
internal fun SearchResults(
    state: SearchState, query: String, model: AppState, modifier: Modifier,
    onBack: () -> Unit, retry: () -> Unit, play: (SearchItem) -> Unit,
) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val count = state.result?.results?.size ?: 0
            Text(
                when {
                    state.loading -> "搜索中…"
                    state.result?.stale == true -> "缓存结果 · 共 $count 个"
                    state.result != null && count > 0 -> "共找到 $count 个结果"
                    else -> ""
                },
                modifier = Modifier.weight(1f),
                fontSize = 12.sp, color = Color(0xFF999999), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val result = state.result
        when {
            state.loading && result == null -> SearchSkeleton(Modifier.weight(1f).fillMaxWidth())
            state.error != null && result == null -> SearchStatusMessage(
                Modifier.weight(1f).fillMaxWidth(), NavigationIcons.Poster,
                "搜索失败", state.error, "重新搜索", retry,
            )
            result == null -> Spacer(Modifier.weight(1f))
            result.results.isEmpty() -> SearchStatusMessage(
                Modifier.weight(1f).fillMaxWidth(), NavigationIcons.Search,
                if (query.isBlank()) "没有找到相关影片" else "没有找到「${query.trim()}」相关影片",
                "试试其他片名，或缩短搜索关键词", "重新输入", onBack,
            )
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
            ) {
                if (result.errors.isNotEmpty()) item {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("部分资源暂时不可用，请稍后重试", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                itemsIndexed(result.results, key = { _, item -> item.detailUrl }) { index, item ->
                    SearchRow(item, query, model, play)
                    if (index != result.results.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(0.8.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(item: SearchItem, query: String, model: AppState, play: (SearchItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = item.playable, role = Role.Button) { play(item) }
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(96.dp).height(134.dp)) {
            Poster(model.poster(item), item.title, Modifier.fillMaxSize().posterSharedTransition(item.detailUrl))
            if (item.remark.isNotBlank()) Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text(item.remark, color = Color.White, fontSize = 10.sp, maxLines = 1) }
        }
        Column(Modifier.weight(1f).height(134.dp)) {
            HighlightedTitle(item.title, query, Modifier.fillMaxWidth())
            val tags = listOfNotNull(
                item.genre.substringBefore("、").takeIf { it.isNotBlank() },
                item.region.takeIf { it.isNotBlank() },
                item.year.takeIf { it.isNotBlank() },
            )
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        ) { Text(tag, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                }
            }
            if (item.cast.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "主演：${item.cast}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            if (item.playable) Row(
                Modifier.align(Alignment.CenterHorizontally).width(120.dp)
                    .clip(RoundedCornerShape(50)).background(Accent)
                    .height(32.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                Icon(NavigationIcons.Play, contentDescription = "播放 ${item.title}", modifier = Modifier.size(13.dp), tint = Color.White)
                Text("立即观看", color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium.copy(
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ))
            } else Box(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 15.dp, vertical = 7.dp),
            ) { Text("暂不可播", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun HighlightedTitle(title: String, query: String, modifier: Modifier) {
    val annotated = buildAnnotatedString {
        val keyword = query.trim()
        if (keyword.isBlank()) { append(title); return@buildAnnotatedString }
        val lowerTitle = title.lowercase()
        val lowerKeyword = keyword.lowercase()
        var start = 0
        while (start <= title.length) {
            val index = lowerTitle.indexOf(lowerKeyword, start)
            if (index < 0) { append(title.substring(start)); break }
            append(title.substring(start, index))
            withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Bold)) {
                append(title.substring(index, index + lowerKeyword.length))
            }
            start = index + lowerKeyword.length
        }
    }
    Text(
        annotated, modifier = modifier, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SearchSkeleton(modifier: Modifier) {
    val alpha = rememberSkeletonAlpha()
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(6) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SkeletonBox(Modifier.width(96.dp).height(134.dp), alpha, 10)
                Column(Modifier.weight(1f).height(134.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBox(Modifier.width(150.dp).height(16.dp), alpha)
                    SkeletonBox(Modifier.width(210.dp).height(12.dp), alpha)
                    SkeletonBox(Modifier.width(120.dp).height(12.dp), alpha)
                    Spacer(Modifier.weight(1f))
                    SkeletonBox(Modifier.width(92.dp).height(30.dp), alpha, 50)
                }
            }
        }
    }
}

@Composable
private fun SearchStatusMessage(
    modifier: Modifier, icon: ImageVector, title: String, message: String,
    actionText: String, onAction: () -> Unit,
) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(7.dp))
        Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Accent)
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) { Text(actionText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}
