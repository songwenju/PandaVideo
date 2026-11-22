package dev.pandavideo.tvui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.pandavideo.app.AppState
import dev.pandavideo.core.model.*
import dev.pandavideo.data.repository.CctvSource
import dev.pandavideo.data.repository.playerRecommendationScope
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.rememberSkeletonAlpha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class TvDetailSelection(
    val title: String, val cover: String?, val request: PlayerRequest,
    val catalog: CatalogItem? = null, val search: SearchItem? = null,
    val recommendations: List<CatalogItem>? = null,
)

/** Keeps related CCTV programs on their own PID instead of invoking movie matching. */
internal fun tvRelatedSelection(item: CatalogItem, related: List<CatalogItem>?): TvDetailSelection =
    TvDetailSelection(item.title, item.cover,
        if (CctvSource.owns(item.url)) PlayerRequest(page = item.url, title = item.title)
        else PlayerRequest(title = item.title), catalog = item, recommendations = related)

/** Loads missing search/history recommendations without replacing an originating catalog. */
internal suspend fun tvDetailRecommendations(
    model: AppState, selection: TvDetailSelection, info: PlaybackInfo,
    candidates: List<CatalogItem>,
    onSnapshot: suspend (List<CatalogItem>) -> Unit = {},
): List<CatalogItem> {
    selection.recommendations?.let { return it }
    if (CctvSource.owns(selection.request.page.orEmpty())) return emptyList()
    return model.playerRecommendations(
        playerRecommendationScope(selection.catalog, selection.search, info),
        candidates, originating = null, onSnapshot = onSnapshot,
    )
}

/** Uses the same measured latency and pending state in both television line pickers. */
internal fun tvPlaybackLineLabel(info: PlaybackInfo, line: Int): String {
    val episodes = info.episodes.filter { it.line == line }
    val name = episodes.firstOrNull()?.lineName?.takeIf { it.isNotBlank() } ?: "线路 ${line + 1}"
    val speed = info.lineProbes[line]?.elapsedMs?.let { "${it}ms" } ?: "待测速"
    return "$name · $speed"
}

/** Keeps the reserved preview and persistent player surface at identical bounds. */
internal fun tvDetailPreviewWidth(windowWidth: Dp, windowHeight: Dp): Dp =
    minOf((windowWidth - 100.dp) * 0.54f, (windowHeight - 84.dp) * 0.62f * (16f / 9f))

@Composable
internal fun TvDetail(model: AppState, selection: TvDetailSelection, info: PlaybackInfo?,
    fullscreen: Boolean, loaded: (PlaybackInfo) -> Unit, onBack: () -> Unit,
    onFullscreen: () -> Unit, open: (TvDetailSelection) -> Unit) {
    var attempt by remember(selection) { mutableIntStateOf(0) }
    var loading by remember(selection) { mutableStateOf(info == null) }
    var error by remember(selection) { mutableStateOf<String?>(null) }
    var group by remember(selection) { mutableIntStateOf(0) }
    var synopsisExpanded by remember(selection) { mutableStateOf(false) }
    val cctv = CctvSource.owns(selection.request.page.orEmpty())
    val synopsis = info?.synopsis?.takeIf { it.isNotBlank() } ?: selection.catalog?.synopsis.orEmpty()
    val actionFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val favorites by model.user.favorites.collectAsState()
    val discover by model.discover.collectAsState()
    val hot by model.hot.collectAsState()
    val browse by model.browse.collectAsState()
    // AppState asynchronously fills verified catalog metadata for search/history sessions.
    // Some providers omit the region, so recommendations must also observe that completion.
    val recommendationSelection = selection.copy(catalog = model.playingCatalog ?: selection.catalog)
    val recommendationScope = playerRecommendationScope(
        recommendationSelection.catalog, selection.search, info)
    var fetchedRelated by remember(selection, recommendationScope) {
        mutableStateOf<List<CatalogItem>>(emptyList())
    }
    // Detail metadata supplies the region/type missing from history. Loading belongs to
    // this selection, remains alive in fullscreen, and is cancelled when leaving it.
    LaunchedEffect(selection, recommendationScope, info?.pageUrl) {
        val detail = info ?: return@LaunchedEffect
        if (selection.recommendations != null || cctv) return@LaunchedEffect
        fetchedRelated = tvDetailRecommendations(model, recommendationSelection, detail,
            discover.items + hot.items + browse.items) { fetchedRelated = it }
    }
    val session = model.session
    BackHandler(enabled = !fullscreen, onBack = onBack)
    LaunchedEffect(selection, attempt) {
        if (info != null) return@LaunchedEffect
        loading = true
        error = null
        try { loaded(model.televisionDetail(selection.catalog, selection.request)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "详情加载失败，请检查网络后重试" }
        finally { loading = false }
    }
    LaunchedEffect(info?.pageUrl, error, fullscreen) {
        if (!fullscreen) {
            if (info != null || error != null) actionFocus.requestFocus() else backFocus.requestFocus()
        }
    }
    val line = info?.episodes?.firstOrNull { it.path == info.selected }?.line ?: 0
    val episodes = info?.episodes.orEmpty().filter { it.line == line }
    LaunchedEffect(line, info?.selected) {
        group = (episodes.indexOfFirst { it.path == info?.selected }.coerceAtLeast(0) / 20)
    }
    // Keep detail state alive while removing its focus targets beneath fullscreen.
    if (fullscreen) return
    val skeletonAlpha = if (loading) rememberSkeletonAlpha() else 0f
    BoxWithConstraints(Modifier.fillMaxSize().background(TvBackdrop).padding(horizontal = 36.dp, vertical = 24.dp)) {
        val previewWidth = tvDetailPreviewWidth(maxWidth + 72.dp, maxHeight + 48.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.height(44.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TvAction("返回", modifier = Modifier.height(36.dp).focusRequester(backFocus),
                    compact = true, icon = NavigationIcons.Back, action = onBack)
                Spacer(Modifier.weight(1f))
                TvBrand()
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                // AndroidTvApp places the persistent video surface over this reserved area.
                Box(Modifier.width(previewWidth).aspectRatio(16f / 9f).background(Color.Black),
                    contentAlignment = Alignment.Center) {
                    if (loading) TvVideoLoading()
                    else Text(error.orEmpty(), color = Muted, fontSize = 16.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (cctv) selection.catalog?.let(::tvCctvProgramTitle) ?: selection.title else selection.title,
                        fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        maxLines = if (cctv) 2 else 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text(if (cctv) listOf(selection.catalog?.programColumn.orEmpty(),
                        selection.catalog?.broadcastDate.orEmpty(), selection.catalog?.remark.orEmpty())
                        .filter { it.isNotBlank() }.joinToString("  ·  ")
                        else listOfNotNull(selection.catalog?.rating?.let { "★ $it" }
                        ?: selection.search?.rating?.let { "★ $it" },
                        (selection.catalog?.year ?: selection.search?.year)?.takeIf { it.isNotBlank() },
                        info?.region?.takeIf { it.isNotBlank() }).joinToString("  ·  "), color = Orange,
                        fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    if (loading) {
                        TvDetailSkeleton(skeletonAlpha)
                    } else {
                    Text(synopsis.ifBlank { if (cctv) "央视公开整期节目，可在下方选择同栏目其他期。" else "" },
                        fontSize = 15.sp, color = Muted,
                        maxLines = if (cctv) 4 else 2, lineHeight = 20.sp, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (error != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvAction("重试", modifier = Modifier.focusRequester(actionFocus)) { attempt++ }
                        TvAction("取消", action = onBack)
                    } else if (info != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvAction("全屏", modifier = Modifier.height(36.dp).focusRequester(actionFocus),
                            compact = true, icon = NavigationIcons.Fullscreen, action = onFullscreen)
                        TvAction(if (favorites.any { it.pageUrl == info.pageUrl }) "已收藏" else "收藏",
                            modifier = Modifier.height(36.dp), compact = true, icon = NavigationIcons.Heart) {
                            model.toggleFavorite(selection.title, info.pageUrl, selection.cover,
                                selection.catalog?.year ?: selection.search?.year.orEmpty())
                        }
                        if (cctv && synopsis.isNotBlank())
                            TvAction("完整简介", modifier = Modifier.height(36.dp), compact = true) {
                                synopsisExpanded = true
                            }
                    }
                    Spacer(Modifier.height(8.dp))
                    info?.let { detail ->
                        if (cctv) {
                            Text("央视 · 公开整期", fontSize = 14.sp, color = Muted)
                        } else {
                        val lines = visiblePlaybackLines(detail.episodes.map { it.line }.distinct(), detail.lineProbes)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("播放线路", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(18.dp))
                            Text(detail.episodes.firstOrNull { it.path == detail.selected }?.label.orEmpty(),
                                fontSize = 14.sp, color = Muted)
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(6.dp)) {
                            items(lines, key = { it }) { value ->
                                TvAction(tvPlaybackLineLabel(detail, value), value == line,
                                    Modifier.height(30.dp), compact = true) {
                                    session?.selectLine(value)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("选集", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(18.dp))
                            Text("共 ${episodes.size} 集", fontSize = 14.sp, color = Muted)
                            if (episodes.size > 20) {
                                Spacer(Modifier.width(18.dp))
                                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(6.dp)) {
                                    items(episodes.chunked(20).indices.toList()) { index ->
                                        TvAction("${index * 20 + 1}-${minOf((index + 1) * 20, episodes.size)}",
                                            group == index, compact = true) { group = index }
                                    }
                                }
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(6.dp)) {
                            items(episodes.drop(group * 20).take(20), key = { it.path }) { ep ->
                                TvAction(ep.label, ep.path == detail.selected, Modifier.height(36.dp),
                                    compact = true) { session?.select(ep) }
                            }
                        }
                        }
                    }
                    }
                }
            }
            val related = (selection.recommendations ?: fetchedRelated).filter { it.url != selection.catalog?.url }
            if (related.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(if (cctv) "同栏目节目" else "相关推荐", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Fit portrait artwork, two caption lines and focus padding into the
                    // remaining space without clipping the card on shorter TV windows.
                    val posterWidth = if (cctv) minOf(244.dp, (maxHeight - 76.dp).coerceAtLeast(0.dp) * (16f / 9f))
                        else minOf(144.dp, (maxHeight - 76.dp).coerceAtLeast(0.dp) * 0.72f)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(8.dp)) {
                        items(related, key = { it.url }) { item ->
                            TvPoster(if (cctv) tvCctvProgramTitle(item) else item.title, model.poster(item), if (cctv) item.broadcastDate else item.year,
                                Modifier.width(posterWidth), imageRatio = if (cctv) 16f / 9f else 0.72f) {
                                // Null retains automatic loading for search/history follow-up titles.
                                open(tvRelatedSelection(item, selection.recommendations).copy(cover = model.poster(item)))
                            }
                        }
                    }
                }
            }
        }
    }
    if (synopsisExpanded) TvDialog(onDismissRequest = { synopsisExpanded = false }) {
        val close = remember { FocusRequester() }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { withFrameNanos { }; close.requestFocus() }
        Column(Modifier.width(780.dp).heightIn(max = 480.dp)
            .background(Background, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("节目简介", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(synopsis, Modifier.weight(1f, fill = false).verticalScroll(scroll),
                fontSize = 18.sp, lineHeight = 28.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // A television has no touch gesture for long provider summaries.
                // Keep scrolling available through ordinary remote focus targets.
                if (scroll.maxValue > 0) {
                    TvAction("上一页") { scope.launch { scroll.animateScrollTo((scroll.value - 300).coerceAtLeast(0)) } }
                    TvAction("下一页") { scope.launch { scroll.animateScrollTo((scroll.value + 300).coerceAtMost(scroll.maxValue)) } }
                }
                TvAction("关闭", modifier = Modifier.focusRequester(close)) { synopsisExpanded = false }
            }
        }
    }
}
