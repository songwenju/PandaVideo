package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.AppState
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.nextEpisode
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.core.platform.PlayerSurface
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.core.player.MediaSource
import dev.pandavideo.core.player.PlaybackState
import dev.pandavideo.core.player.PlayerPhase
import dev.pandavideo.core.player.PlayerState
import dev.pandavideo.core.player.PreviewFrame
import dev.pandavideo.core.player.SeekPreview
import dev.pandavideo.core.player.previewPosition
import dev.pandavideo.data.user.progressFraction
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.ActionButtonPadding
import dev.pandavideo.ui.common.CompactActionButtonPadding
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.Poster
import dev.pandavideo.ui.common.playbackTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlin.math.roundToInt

/** Window before the end in which the quick "next episode" pill appears. */
internal const val NEAR_END_WINDOW_MS = 20_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModernPlayerScreen(
    session: PlaybackSession, model: AppState, platform: PlatformServices,
    search: (String) -> Unit, back: () -> Unit,
    home: () -> Unit = back,
) {
    val state by session.state.collectAsState()
    val casting = remember(session, platform) { DlnaCasting(platform, session.controller) }
    DisposableEffect(casting) { onDispose { casting.close() } }
    LaunchedEffect(state.mediaId) { casting.mediaChanged(state.mediaId) }
    DisposableEffect(session) {
        model.restorePlayerLevels()
        onDispose { model.minimizePlayer(session) }
    }
    val player by session.controller.state.collectAsState()
    val discover by model.discover.collectAsState()
    val browse by model.browse.collectAsState()
    val hot by model.hot.collectAsState()
    val results by model.search.collectAsState()
    var fullscreen by remember { mutableStateOf(false) }
    var episodePickerRequested by remember { mutableStateOf(false) }
    val pipActive by platform.pictureInPictureActive.collectAsState()
    val pipWindow = pipActive && platform.pipResizesAppWindow
    val catalog = (discover.items + browse.items + hot.items).distinctBy { it.url }
    val artwork = model.playingCatalog ?: catalog.find { it.title == state.info?.title }
    val cover = artwork?.let(model::poster) ?: model.playingSearch?.let(model::poster) ?: results.result?.results
        ?.find { it.detailUrl == state.info?.pageUrl }?.let(model::poster) ?: model.playingCover
    // Catalog sessions keep the exact list that opened them, including region, genre, year
    // and score constraints. Never fill a short filtered list with unrelated global titles.
    // Search/history sessions without a catalog scope use same-type suggestions from the pool.
    val playingKind = artwork?.kind
        ?: if ((state.info?.episodes?.size ?: 0) > 1) "tv" else "movie"
    val related = (model.playingRecommendations ?: catalog)
        .filter { it.url != artwork?.url && it.kind == playingKind }.take(6)
    val title = state.info?.title ?: artwork?.title ?: model.playingSearch?.title ?: "正在加载影片"
    val detailsBackgroundColor = MaterialTheme.colorScheme.surface
    LaunchedEffect(fullscreen) {
        platform.fullscreen(fullscreen)
    }
    LaunchedEffect(state.audioOnly) {
        if (state.audioOnly) fullscreen = false
    }
    // Entering picture-in-picture must leave immersive fullscreen first, otherwise the app
    // keeps the fullscreen state and UI flags while the video shrinks into a floating window.
    LaunchedEffect(pipActive) {
        if (pipActive && fullscreen) fullscreen = false
    }
    LaunchedEffect(state.info?.pageUrl, state.info?.selected) {
        val info = state.info
        if (info != null && info.pageUrl.isNotBlank()) {
            val episode = info.episodes.find { it.path == info.selected }?.label.orEmpty()
            model.rememberPlayed(info.title, info.pageUrl, cover, artwork?.year.orEmpty(), episode, info.selected)
        }
    }
    DisposableEffect(Unit) { onDispose { platform.fullscreen(false) } }
    // Refresh failures keep the playing title; surface them as a transient snackbar rather
    // than the full-screen error state, which is reserved for initial resolution failures.
    var lastRefreshError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.error) {
        val error = state.error
        if (error != null && state.info != null && error != lastRefreshError) {
            lastRefreshError = error
            model.emitMessage("刷新失败，请稍后重试")
        }
        if (error == null) lastRefreshError = null
    }
    Box(Modifier.fillMaxSize().background(Color.Black)
        .then(if (!fullscreen && !pipWindow) Modifier.statusBarsPadding() else Modifier)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val dualPane = maxWidth >= 840.dp && maxHeight >= 360.dp && !fullscreen && !pipWindow
            if (dualPane) {
                Row(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.weight(1.65f).fillMaxHeight().background(Color.Black),
                        contentAlignment = Alignment.Center) {
                        VideoFrame(session, player, state, false, title, platform,
                            if (platform.persistentPlayerControls) Modifier.fillMaxSize()
                            else Modifier.width(minOf(maxWidth, maxHeight * (16f / 9f))).aspectRatio(16f / 9f), model, cover,
                            back = back,
                            casting = casting,
                            home = home,
                            search = { search(title) },
                            toggleFullscreen = { fullscreen = true },
                            openDetails = {}, pipMode = pipActive)
                    }
                    PullToRefreshBox(state.refreshing, session::refresh,
                        Modifier.weight(1f).fillMaxHeight().background(detailsBackgroundColor)) {
                        PlayerDetails(session, state, model, cover, artwork, related, search,
                            Modifier.fillMaxSize(), panel = true,
                            scrollToEpisodes = episodePickerRequested,
                            onEpisodesShown = { episodePickerRequested = false })
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
            val videoModifier = when {
                pipWindow -> Modifier.fillMaxSize()
                fullscreen -> Modifier.fillMaxSize()
                else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            }
            VideoFrame(session, player, state, fullscreen, title, platform, videoModifier, model, cover,
                back = { if (fullscreen) fullscreen = false else back() },
                home = home,
                casting = casting,
                search = { search(artwork?.title ?: model.playingSearch?.title ?: state.info?.title.orEmpty()) },
                toggleFullscreen = { fullscreen = !fullscreen },
                openDetails = {
                    fullscreen = false
                    episodePickerRequested = true
                },
                pipMode = pipActive)
            if (!fullscreen && !pipWindow) PullToRefreshBox(
                isRefreshing = state.refreshing, onRefresh = session::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                PlayerDetails(session, state, model, cover, artwork, related, search,
                    Modifier.fillMaxSize().background(detailsBackgroundColor),
                    panel = false, scrollToEpisodes = episodePickerRequested,
                    onEpisodesShown = { episodePickerRequested = false })
            }
            }
        }
    }
    DlnaDevicePicker(casting, state.info?.takeUnless { state.loading }?.let {
        MediaSource(state.mediaId, it.url, it.kind, it.title,
            episodeLabel = it.episodes.find { episode -> episode.path == it.selected }?.label.orEmpty())
    }, platform)
}

@Composable
private fun PlayerDetails(
    session: PlaybackSession, state: PlaybackState, model: AppState,
    cover: String?, artwork: CatalogItem?, related: List<CatalogItem>,
    search: (String) -> Unit, modifier: Modifier, panel: Boolean,
    scrollToEpisodes: Boolean = false,
    onEpisodesShown: () -> Unit = {},
) {
    val info = state.info
    val favorites by model.user.favorites.collectAsState()
    val downloads by model.downloads.tasks.collectAsState()
    val favoriteUrl = info?.pageUrl ?: model.playingSearch?.detailUrl.orEmpty()
    val saved = favoriteUrl.isNotBlank() && favorites.any { it.pageUrl == favoriteUrl }
    var synopsisExpanded by remember(info?.title) { mutableStateOf(false) }
    var synopsisOverflows by remember(info?.synopsis) { mutableStateOf(false) }
    val lines = info?.episodes?.groupBy { it.line }.orEmpty()
    var selectedLine by remember(info?.pageUrl) { mutableStateOf<Int?>(null) }
    var episodeRange by remember(info?.pageUrl) { mutableStateOf(0) }
    LaunchedEffect(info?.selected) {
        val episode = info?.episodes?.find { it.path == info.selected }
        selectedLine = episode?.line
        episodeRange = (lines[episode?.line]?.indexOf(episode)?.coerceAtLeast(0) ?: 0) / 30
    }
    val line = selectedLine?.takeIf { it in lines } ?: lines.keys.firstOrNull()
    val episodes = lines[line].orEmpty()
    val ranges = episodes.chunked(30)
    val titleSize = 18.sp
    val sectionTitleSize = if (panel) 17.sp else 16.sp
    val bodySize = 12.5.sp
    val supportingSize = 11.sp
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToEpisodes, info, panel) {
        if (scrollToEpisodes && lines.isNotEmpty()) {
            // The picker follows the header, recommendation rail, expanded synopsis and error. Exiting
            // fullscreen creates this list again, so scroll only after it enters composition.
            val headingIndex = (if (!panel) 1 else 0) +
                (if (!panel && related.isNotEmpty()) 1 else 0) +
                (if (!panel && info != null && synopsisExpanded) 1 else 0) +
                (if (state.error != null && !state.loading && info != null) 1 else 0)
            listState.scrollToItem(headingIndex)
            onEpisodesShown()
        }
    }
    LazyColumn(
        modifier, state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A wide-window supporting pane contains the picker; compact details show the title
        // and metadata in a full-width header, leaving artwork in the recommendation rail.
        if (!panel) item {
            // Set the header's family and tracking explicitly: changing fontSize alone retains
            // the ambient Material body style, including its letter spacing and line height.
            val headerTitleStyle = TextStyle(
                fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
                fontSize = titleSize, lineHeight = 24.sp, letterSpacing = 0.sp,
            )
            val headerTitle = artwork?.title?.takeIf(String::isNotBlank)
                ?: info?.title?.takeIf(String::isNotBlank)
                ?: model.playingSearch?.title?.takeIf(String::isNotBlank) ?: "正在加载影片"
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(headerTitle, style = headerTitleStyle, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(
                        onClick = { synopsisExpanded = !synopsisExpanded },
                        enabled = info != null,
                        contentPadding = CompactActionButtonPadding,
                    ) {
                        Text(if (synopsisExpanded) "收起" else "详情",
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(NavigationIcons.Chevron, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                            )
                    }
                    IconButton(
                        onClick = {
                            val savedNow = model.toggleFavorite(
                                headerTitle, favoriteUrl, cover, artwork?.year.orEmpty(),
                            )
                            if (savedNow) model.platform.haptics.success() else model.platform.haptics.tick()
                        },
                        enabled = favoriteUrl.isNotBlank(), modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            if (saved) NavigationIcons.HeartFilled else NavigationIcons.Heart,
                            contentDescription = if (saved) "取消收藏" else "收藏",
                            tint = if (saved) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (info != null && model.downloads.supports(info.kind)) {
                    val cached = downloads.firstOrNull {
                        it.info.pageUrl == info.pageUrl && it.info.selected == info.selected
                    }
                    TextButton(
                        onClick = { model.cacheCurrentEpisode() },
                        enabled = !state.loading && cached?.status !in setOf(
                            dev.pandavideo.data.repository.DownloadStatus.Completed,
                            dev.pandavideo.data.repository.DownloadStatus.Queued,
                            dev.pandavideo.data.repository.DownloadStatus.Downloading,
                        ),
                        contentPadding = ActionButtonPadding,
                    ) {
                        Icon(
                            if (cached?.status == dev.pandavideo.data.repository.DownloadStatus.Completed)
                                NavigationIcons.Check else NavigationIcons.Download,
                            contentDescription = null, modifier = Modifier.size(18.dp), tint = Accent,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(when (cached?.status) {
                            dev.pandavideo.data.repository.DownloadStatus.Completed -> "本集已缓存"
                            dev.pandavideo.data.repository.DownloadStatus.Queued -> "等待缓存"
                            dev.pandavideo.data.repository.DownloadStatus.Downloading -> "本集缓存中"
                            else -> "缓存本集"
                        }, color = Accent)
                    }
                }
                // Use only available metadata. Search results may supply region/genre while
                // catalog results supply rating and cast; never invent awards or series.
                val metadata = buildList {
                    artwork?.rating?.let { add("豆瓣 $it 分") }
                    (artwork?.year?.takeIf(String::isNotBlank)
                        ?: model.playingSearch?.year?.takeIf(String::isNotBlank))?.let(::add)
                    addAll(artwork?.casts?.takeIf { it.isNotEmpty() }?.take(2)
                        ?: model.playingSearch?.cast.orEmpty().split(Regex("[/、,，]"))
                            .map(String::trim).filter(String::isNotBlank).take(2))
                    model.playingSearch?.region?.takeIf(String::isNotBlank)?.let(::add)
                    model.playingSearch?.genre?.takeIf(String::isNotBlank)?.let(::add)
                }.distinct()
                if (metadata.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(metadata) { text ->
                        Text(
                            text, fontSize = 12.sp, lineHeight = 18.sp,
                            fontWeight = if (text.startsWith("豆瓣")) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        if (!panel && related.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("相关推荐", fontSize = sectionTitleSize, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (cover != null) item {
                        PlayerRecommendation(cover, artwork?.title ?: info?.title.orEmpty(),
                            artwork?.rating, playing = true)
                    }
                    items(related, key = { it.url }) { item ->
                        PlayerRecommendation(model.poster(item), item.title, item.rating,
                            onClick = { model.match(item) {} })
                    }
                }
            }
        }
        if (!panel && info != null && synopsisExpanded) item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("剧情简介", fontSize = sectionTitleSize, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    info.synopsis.ifBlank { "暂无简介" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = bodySize, lineHeight = 20.sp,
                    maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!synopsisExpanded) synopsisOverflows = it.hasVisualOverflow },
                )
                if (synopsisOverflows || synopsisExpanded) Text(
                    if (synopsisExpanded) "收起" else "展开",
                    fontSize = supportingSize, color = Accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { synopsisExpanded = !synopsisExpanded }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
        if (state.error != null && !state.loading && info != null) item {
            Text("刷新失败，请稍后重试", color = MaterialTheme.colorScheme.error, fontSize = supportingSize)
        }
        if (lines.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", fontSize = sectionTitleSize, lineHeight = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("共 ${episodes.size} 集", fontSize = supportingSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (lines.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lines.keys.toList()) { target ->
                        DetailChip("线路 $target", target == line) { selectedLine = target; episodeRange = 0 }
                    }
                }
            }
            if (ranges.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ranges.size) { index ->
                        DetailChip("${index * 30 + 1}–${minOf((index + 1) * 30, episodes.size)}",
                            index == episodeRange) { episodeRange = index }
                    }
                }
            }
            item {
                EpisodeGrid(
                    episodes = ranges.getOrNull(episodeRange).orEmpty(),
                    selectedPath = info?.selected,
                    enabled = !state.loading,
                    progressFor = { episode -> info?.let { model.progressFor(it.pageUrl, episode.path) } },
                    onSelect = { episode ->
                        model.platform.haptics.tick()
                        session.select(episode)
                    },
                )
            }
        }
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing)) }
    }
}

/** Landscape recommendation card with the title below and rating over the artwork. */
@Composable
private fun PlayerRecommendation(
    cover: String?, title: String, rating: Double?,
    playing: Boolean = false, onClick: (() -> Unit)? = null,
) {
    Column(Modifier.width(152.dp)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            Poster(cover, title, Modifier.fillMaxSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
            rating?.let {
                Text(it.toString(), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
            if (playing) Text("播放中", color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp)
                    .background(Accent, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Text(title, color = if (playing) Accent else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EpisodeGrid(
    episodes: List<Episode>,
    selectedPath: String?,
    enabled: Boolean,
    progressFor: (Episode) -> dev.pandavideo.data.user.EpisodeProgress?,
    onSelect: (Episode) -> Unit,
) {
    BoxWithConstraints {
        // Keep each cell at least ~80dp wide (8dp gaps included), so labels like “HD中字” stay on
        // one line; phones get 4–5 columns while wide XR displays keep up to six.
        val columns = ((maxWidth + 8.dp) / 88.dp).toInt().coerceIn(2, 6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            episodes.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { episode ->
                        val selected = episode.path == selectedPath
                        val progress = progressFor(episode)
                        Box(
                            Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = enabled && !selected, role = Role.Button) { onSelect(episode) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                episode.label,
                                fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                color = if (selected) Accent else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                            if (progress != null && progress.duration > 0) {
                                val fraction = if (progress.finished) 1f
                                    else progressFraction(progress.position, progress.duration)
                                Box(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                                        .background(Color.Black.copy(alpha = 0.10f)),
                                ) {
                                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Accent))
                                }
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DetailChip(label: String, selected: Boolean, click: () -> Unit) {
    Box(
        modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = click).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, lineHeight = 18.sp,
            color = if (selected) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
private fun VideoFrame(
    session: PlaybackSession, player: PlayerState, state: PlaybackState, fullscreen: Boolean,
    title: String, platform: PlatformServices, modifier: Modifier, model: AppState, cover: String?,
    back: () -> Unit, search: () -> Unit, toggleFullscreen: () -> Unit, openDetails: () -> Unit,
    home: () -> Unit,
    casting: DlnaCasting,
    pipMode: Boolean = false,
) {
    if (casting.device != null) {
        DlnaRemoteFrame(casting, title, modifier, back)
        return
    }
    if (platform.persistentPlayerControls) {
        DesktopVideoFrame(session, player, state, fullscreen, title, modifier,
            back, home, search, toggleFullscreen, openDetails)
        return
    }
    var controls by remember { mutableStateOf(true) }
    var locked by remember(fullscreen) { mutableStateOf(false) }
    var interaction by remember { mutableStateOf(0) }
    var scrubbing by remember(player.mediaId) { mutableStateOf<Float?>(null) }
    var gestureScrub by remember(player.mediaId) { mutableStateOf<Float?>(null) }
    var previewFrame by remember(player.mediaId) { mutableStateOf<PreviewFrame?>(null) }
    var previewTarget by remember(player.mediaId) { mutableStateOf<Long?>(null) }
    val previewCache = remember(session.controller, player.mediaId) {
        SeekPreview(session.controller, player.mediaId)
    }
    LaunchedEffect(previewCache, player.duration, pipMode, state.audioOnly) {
        // Extract only while the user scrubs. Rapid movements cancel obsolete requests;
        // the bounded memory cache never downloads or persists the video.
        snapshotFlow {
            if (pipMode || state.audioOnly) null else (gestureScrub ?: scrubbing)?.toLong()
                ?.let { previewPosition(it, player.duration) }
        }.distinctUntilChanged().sample(200).collectLatest { target ->
            previewFrame = null
            previewTarget = target
            if (target != null) previewFrame = previewCache.frame(target)
        }
    }
    var zoom by remember(player.mediaId) { mutableFloatStateOf(1f) }
    var zoomActive by remember { mutableStateOf(false) }
    var fineScrub by remember(player.mediaId) { mutableStateOf(false) }
    var dragBase by remember(player.mediaId) { mutableStateOf(0L) }
    var frameWidth by remember { mutableStateOf(1) }
    var rateMenu by remember { mutableStateOf(false) }
    var adjustment by remember { mutableStateOf<Adjustment?>(null) }
    var tapFeedback by remember { mutableStateOf<TapFeedback?>(null) }
    LaunchedEffect(adjustment?.active) {
        if (adjustment?.active == false) { delay(700); adjustment = null }
    }
    LaunchedEffect(tapFeedback?.token) {
        if (tapFeedback != null) { delay(650); tapFeedback = null }
    }
    val busy = state.loading || player.phase in listOf(PlayerPhase.Preparing, PlayerPhase.Buffering, PlayerPhase.Ready)
    val error = player.error ?: state.error?.takeIf { state.info == null || player.phase == PlayerPhase.Idle }
    val next = state.info?.let { nextEpisode(it) }
    val hasMultipleEpisodes = state.info?.episodes.orEmpty()
        .groupingBy { it.line }.eachCount().values.any { it > 1 }
    val dragSeekEnabled = state.info != null && error == null &&
        player.duration > 0 && session.controller.capabilities.seeking
    val seekPreview = gestureScrub ?: scrubbing
    LaunchedEffect(player.phase, controls, interaction, scrubbing != null, gestureScrub != null, rateMenu, state.loading, adjustment?.active, zoomActive, state.audioOnly) {
        if (!state.audioOnly && !platform.persistentPlayerControls && player.phase == PlayerPhase.Playing && controls && scrubbing == null && gestureScrub == null && !rateMenu && !state.loading && adjustment?.active != true && !zoomActive) {
            delay(3500); controls = false
        }
    }
    LaunchedEffect(player.phase, player.mediaId) {
        if (player.phase != PlayerPhase.Playing) controls = true
    }
    LaunchedEffect(state.ended) { if (state.ended) controls = true }
    LaunchedEffect(state.audioOnly) {
        controls = true
        locked = false
        zoom = 1f
    }
    fun togglePlay() {
        interaction++
        when {
            player.phase == PlayerPhase.Ended -> session.retry()
            player.wantsPlay -> session.controller.pause()
            else -> session.controller.play()
        }
    }
    val quickSeekEnabled = dragSeekEnabled
    fun quickSeek(forward: Boolean) {
        if (!quickSeekEnabled) return
        val target = quickSeekPosition(player.position, player.duration, forward)
        // A clamped result means the seek hit the start/end boundary: signal the rejection.
        if (target <= 0L || target >= player.duration) platform.haptics.warning()
        else platform.haptics.tick()
        session.controller.seekTo(target)
        tapFeedback = TapFeedback(if (forward) TapFeedbackKind.Forward else TapFeedbackKind.Rewind)
    }
    fun doubleTapPlayPause() {
        if (state.info == null || error != null) return
        platform.haptics.click()
        togglePlay()
        tapFeedback = null
    }
    // Pointer handlers survive recompositions. Read the current playback intent so repeated
    // double taps alternate pause/resume instead of using the state captured on first playback.
    val doubleTapAction by rememberUpdatedState<(Offset) -> Unit> { offset ->
        when (tapZone(offset.x, frameWidth, fullscreen)) {
            TapZone.Left -> quickSeek(false)
            TapZone.Right -> quickSeek(true)
            TapZone.Center -> doubleTapPlayPause()
        }
    }
    val zoomAction by rememberUpdatedState<(Float) -> Unit> { multiplier ->
        zoom = videoZoom(zoom, multiplier)
    }
    val zoomActivity by rememberUpdatedState<(Boolean) -> Unit> { active ->
        zoomActive = active
        interaction++
        if (active) {
            // A second finger cancels any pending seek before child detectors see its event.
            // In particular, lifting the first finger must not commit the scrub preview.
            gestureScrub = null
            scrubbing = null
            fineScrub = false
            tapFeedback = null
            adjustment = adjustment?.copy(active = false)
        }
    }
    Box(modifier.background(Color.Black).videoPinch(
        enabled = !state.audioOnly && !locked && !pipMode && state.info != null && error == null,
        onZoom = { zoomAction(it) },
        onActive = { zoomActivity(it) },
    )) {
        PersistentVideoContent(
            pipMode = pipMode,
            surface = {
                if (state.audioOnly) {
                    // Unmount the native surface instead of covering it: this also releases its
                    // screen-on request and detaches AVPlayerLayer for background audio playback.
                    AudioModeBackdrop(cover, title, showArtwork = !busy && error == null)
                } else PlayerSurface(session.controller, Modifier.fillMaxSize(), if (pipMode) 1f else zoom)
            },
            overlays = {
        if (!locked) {
        // Keep taps on the parent so the drag zones receive the same pointer stream.
        // An overlapping sibling would intercept hit testing before the zones can seek.
        Box(Modifier.fillMaxSize().onSizeChanged { frameWidth = it.width.coerceAtLeast(1) }
            .pointerInput(quickSeekEnabled, state.info != null, fullscreen) {
                detectTapGestures(
                    onTap = { controls = !controls; interaction++ },
                    onDoubleTap = { offset -> doubleTapAction(offset) },
                )
            }) {
            PlayerGestureZone(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.5f),
                adjust = if (state.audioOnly) AdjustKind.Volume else AdjustKind.Brightness, dragSeekEnabled = dragSeekEnabled,
                readLevel = if (state.audioOnly) platform::currentVolume else platform::currentBrightness,
                onSeekStart = {
                    dragBase = player.position.coerceIn(0, player.duration)
                    controls = true; interaction++
                },
                onSeekDelta = { delta ->
                    gestureScrub = dragSeekPosition(dragBase, gestureScrub, player.duration, delta, frameWidth)
                    interaction++
                },
                onSeekEnd = {
                    gestureScrub?.let { session.controller.seekTo(it.toLong()) }
                    gestureScrub = null; interaction++
                },
                onSeekCancel = { gestureScrub = null; interaction++ },
                onAdjust = { value ->
                    if (state.audioOnly) platform.setVolume(value) else platform.setBrightness(value)
                    adjustment = Adjustment(if (state.audioOnly) AdjustKind.Volume else AdjustKind.Brightness, value, active = true)
                },
                onAdjustFinish = { model.rememberPlayerLevels(); adjustment = adjustment?.copy(active = false) },
                onFineModeChange = { active -> fineScrub = active; if (active) platform.haptics.tick() },
            )
            PlayerGestureZone(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f),
                adjust = AdjustKind.Volume, dragSeekEnabled = dragSeekEnabled,
                readLevel = platform::currentVolume,
                onSeekStart = {
                    dragBase = player.position.coerceIn(0, player.duration)
                    controls = true; interaction++
                },
                onSeekDelta = { delta ->
                    gestureScrub = dragSeekPosition(dragBase, gestureScrub, player.duration, delta, frameWidth)
                    interaction++
                },
                onSeekEnd = {
                    gestureScrub?.let { session.controller.seekTo(it.toLong()) }
                    gestureScrub = null; interaction++
                },
                onSeekCancel = { gestureScrub = null; interaction++ },
                onAdjust = { value ->
                    platform.setVolume(value)
                    adjustment = Adjustment(AdjustKind.Volume, value, active = true)
                },
                onAdjustFinish = { model.rememberPlayerLevels(); adjustment = adjustment?.copy(active = false) },
                onFineModeChange = { active -> fineScrub = active; if (active) platform.haptics.tick() },
            )
        }
        if (controls || busy || error != null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier)
                    .padding(start = if (fullscreen) 16.dp else 4.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(back, Modifier.size(44.dp)) {
                    Icon(NavigationIcons.Back, if (fullscreen) "退出全屏" else "返回", tint = Color.White)
                }
                if (platform.persistentPlayerControls) {
                    TextButton(home) { Text("返回首页", color = Color.White) }
                }
                if (fullscreen || platform.persistentPlayerControls) {
                    Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp))
                }
                if (!fullscreen) Spacer(Modifier.weight(1f))
                if (session.controller.capabilities.audioOnly && state.info != null) IconToggleButton(
                    checked = state.audioOnly,
                    onCheckedChange = {
                        platform.haptics.tick()
                        session.setAudioOnly(it)
                        controls = true
                        interaction++
                    },
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        checkedContainerColor = Accent.copy(alpha = 0.16f),
                        checkedContentColor = Accent,
                    ),
                ) {
                    Icon(
                        NavigationIcons.Headphones,
                        contentDescription = if (state.audioOnly) "切回视频" else "音频模式",
                        modifier = Modifier.size(22.dp),
                    )
                }
                if (!state.audioOnly && fullscreen && session.controller.capabilities.pictureInPicture && platform.pipSupported) {
                    IconButton({ platform.haptics.tick(); platform.enterPictureInPicture() }, Modifier.size(44.dp)) {
                        Icon(NavigationIcons.Pip, "画中画", Modifier.size(22.dp), tint = Color.White)
                    }
                }
            }
        }
        if (zoomActive) Text(
            "${(zoom * 100).roundToInt()}%",
            color = Color.White, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f),
                RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (busy) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(30.dp), color = Accent, strokeWidth = 2.dp)
            Text(if (state.loading) "正在解析视频…" else if (player.phase == PlayerPhase.Buffering) "缓冲中…" else "准备播放…",
                color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        } else if (error != null) Column(
            Modifier.align(Alignment.Center).padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (state.info == null) "暂时无法播放，请重试或搜索其他资源" else "暂时无法播放，请重试或切换线路",
                color = Color.White, fontSize = 12.sp, maxLines = 3)
            Row {
                TextButton(session::retry) { Text("重试", color = Accent) }
                if (state.info == null) TextButton(search) { Text("搜索资源", color = Accent) }
            }
        }
        seekPreview?.let { preview ->
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
            ScrubFeedback(
                frame = previewFrame.takeIf {
                    previewTarget == previewPosition(preview.toLong(), player.duration)
                },
                position = preview.toLong(),
                duration = player.duration,
                fine = fineScrub,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        adjustment?.takeIf { !fullscreen }?.let { gesture ->
            val isVolume = gesture.kind == AdjustKind.Volume
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.66f)).padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (isVolume) NavigationIcons.Volume else NavigationIcons.Brightness,
                    contentDescription = if (isVolume) "音量" else "亮度",
                    tint = Color.White, modifier = Modifier.size(24.dp),
                )
                Box(
                    Modifier.padding(top = 12.dp).width(6.dp).height(92.dp)
                        .clip(CircleShape).background(Color.White.copy(alpha = 0.28f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(gesture.value).align(Alignment.BottomCenter)
                            .clip(CircleShape).background(Accent),
                    )
                }
                Text(
                    "${(gesture.value * 100).roundToInt()}%",
                    color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        adjustment?.takeIf { fullscreen }?.let { gesture ->
            PlayerLevelIndicator(
                gesture,
                Modifier.align(if (gesture.kind == AdjustKind.Volume) Alignment.CenterEnd else Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 24.dp),
            )
        }
        tapFeedback?.let { feedback ->
            when (feedback.kind) {
                TapFeedbackKind.Rewind -> SeekHint(false, Modifier.align(Alignment.CenterStart).padding(start = 28.dp))
                TapFeedbackKind.Forward -> SeekHint(true, Modifier.align(Alignment.CenterEnd).padding(end = 28.dp))
            }
        }
        if (next != null && !state.ended && player.phase == PlayerPhase.Playing && player.duration > 0 &&
            player.duration - player.position in 0..NEAR_END_WINDOW_MS) Surface(
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 96.dp)
                .clickable(role = Role.Button) { interaction++; session.select(next) },
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(NavigationIcons.Next, "下一集 ${next.label}", tint = Color.White, modifier = Modifier.size(16.dp))
                Text("下一集 ${next.label}", color = Color.White, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (state.ended && next != null) {
            Box(
                Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, role = null,
                    ) {},
            )
            Surface(
                Modifier.align(Alignment.Center).widthIn(min = 280.dp).padding(24.dp),
                shape = RoundedCornerShape(18.dp), color = Color(0xF2202020),
            ) {
                Column(
                    Modifier.padding(horizontal = 26.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("本集播放结束", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Text(
                        "下一集 ${next.label}",
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "${state.countdownSeconds} 秒后自动播放",
                        color = Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        Modifier.padding(top = 20.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextButton(
                            { session.dismissEnded() },
                            Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) { Text("取消", fontSize = 14.sp) }
                        TextButton(
                            { session.retry() },
                            Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) { Text("重播", fontSize = 14.sp) }
                        Button(
                            { session.advanceToNext() },
                            Modifier.weight(1.4f),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        ) { Text("立即播放", fontSize = 14.sp, maxLines = 1) }
                    }
                }
            }
        }
        if (fullscreen && controls && !busy && error == null && gestureScrub == null) {
            // Side buttons reveal the same feedback used by vertical dragging; the gesture
            // zones underneath remain responsible for brightness/volume changes.
            for (kind in AdjustKind.entries) {
                if (adjustment?.kind != kind) {
                    val volume = kind == AdjustKind.Volume
                    IconButton(
                        onClick = {
                            adjustment = Adjustment(kind,
                                if (volume) platform.currentVolume() else platform.currentBrightness(),
                                active = false)
                            interaction++
                        },
                        modifier = Modifier.align(if (volume) Alignment.CenterEnd else Alignment.CenterStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = 24.dp).size(44.dp)
                            .background(Color.Black.copy(alpha = 0.2f), CircleShape),
                    ) {
                        Icon(if (volume) NavigationIcons.Volume else NavigationIcons.Brightness,
                            if (volume) "音量" else "亮度", Modifier.size(22.dp), tint = Color.White)
                    }
                }
            }
        }
        // Keep the timeline mounted while seeking, even if playback starts buffering.
        // Both the center feedback and this slider display the same pending target.
        if ((controls || seekPreview != null) && (!busy || seekPreview != null) &&
            error == null && state.info != null) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) else Modifier)
                .padding(start = if (fullscreen) 24.dp else 12.dp,
                    end = if (fullscreen) 24.dp else 12.dp, bottom = 8.dp),
        ) {
            val previewPosition = gestureScrub ?: scrubbing
            if (fullscreen) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(playbackTime(previewPosition?.toLong() ?: player.position),
                    color = Color.White, fontSize = 13.sp)
                Text("/ ${playbackTime(player.duration)}",
                    color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!fullscreen) Text(playbackTime((previewPosition?.toLong() ?: player.position)), color = Color.White, fontSize = 11.sp)
                Slider(
                    value = previewPosition ?: player.position.coerceIn(0, player.duration.coerceAtLeast(1)).toFloat(),
                    onValueChange = { scrubbing = it; controls = true; interaction++ },
                    onValueChangeFinished = { scrubbing?.let { session.controller.seekTo(it.toLong()) }; scrubbing = null; interaction++ },
                    valueRange = 0f..player.duration.coerceAtLeast(1).toFloat(),
                    enabled = player.duration > 0 && session.controller.capabilities.seeking,
                    modifier = Modifier.weight(1f).height(32.dp),
                    thumb = {
                        Box(
                            Modifier.width(12.dp).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(if (fullscreen) 8.dp else 12.dp)
                                .background(if (fullscreen) Color.White else Accent, CircleShape))
                        }
                    },
                    track = { slider ->
                        val fraction = ((slider.value - slider.valueRange.start) /
                            (slider.valueRange.endInclusive - slider.valueRange.start)).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(if (fullscreen) 2.dp else 3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                                .background(if (fullscreen) Color.White else Accent))
                        }
                    },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                )
                if (!fullscreen) Text(playbackTime(player.duration), color = Color.White, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                IconButton(::togglePlay, Modifier.size(46.dp)) {
                    Icon(if (player.wantsPlay) PlayerTransportIcons.Pause else PlayerTransportIcons.Play,
                        if (player.wantsPlay) "暂停" else "播放", Modifier.size(28.dp), tint = Color.White)
                }
                IconButton(
                    { next?.let(session::select) },
                    Modifier.size(44.dp).padding(start = 2.dp),
                    enabled = next != null && !state.loading,
                ) {
                    Icon(PlayerTransportIcons.Next, "下一集", Modifier.size(24.dp),
                        tint = if (next != null) Color.White else Color.White.copy(alpha = 0.35f))
                }
                Spacer(Modifier.weight(1f))
                if (!state.audioOnly && platform.dlnaSupported) IconButton({
                    casting.picker = true
                    casting.search()
                    interaction++
                }, Modifier.size(44.dp)) {
                    Icon(NavigationIcons.Cast, "投屏到电视", Modifier.size(22.dp), tint = Color.White)
                }
                if (!state.audioOnly && !platform.dlnaSupported && platform.nativeCastingSupported)
                    platform.CastingButton(Modifier.size(44.dp))
                if (session.controller.capabilities.playbackRate) Box {
                    ControlTextPill(
                        if (fullscreen && player.rate == 1f) "倍速"
                        else if (fullscreen) "${player.rate.stripRate()}×"
                        else "倍速 ${player.rate.stripRate()}×",
                        plain = fullscreen,
                    ) { rateMenu = true; interaction++ }
                    DropdownMenu(rateMenu, { rateMenu = false }, containerColor = Color(0xF21F1F1F)) {
                        PlaybackRates.forEach { rate ->
                            DropdownMenuItem(text = {
                                Text("${rate.stripRate()}×", fontSize = 15.sp,
                                    color = if (rate == player.rate) Accent else Color.White,
                                    fontWeight = if (rate == player.rate) FontWeight.SemiBold else FontWeight.Normal)
                            }, onClick = {
                                session.setPlaybackRate(rate); rateMenu = false; interaction++
                            })
                        }
                    }
                }
                if (fullscreen && hasMultipleEpisodes) ControlTextPill("选集", plain = true) {
                    rateMenu = false
                    openDetails()
                }
                if (!state.audioOnly && !fullscreen && session.controller.capabilities.pictureInPicture && platform.pipSupported) {
                    IconButton({ platform.haptics.tick(); platform.enterPictureInPicture() }, Modifier.size(44.dp)) {
                        Icon(NavigationIcons.Pip, "画中画", Modifier.size(20.dp), tint = Color.White)
                    }
                }
                if (!state.audioOnly && !fullscreen && session.controller.capabilities.fullscreen) IconButton(toggleFullscreen, Modifier.size(44.dp)) {
                    Icon(NavigationIcons.Fullscreen, if (fullscreen) "退出全屏" else "全屏", Modifier.size(20.dp), tint = Color.White)
                }
            }
        }
        } else {
            // Locked playback still accepts a single tap to reveal/hide the unlock control.
            // Keep seeking, volume, brightness and double-tap playback gestures disabled.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { controls = !controls; interaction++ })
            })
        }
        if (fullscreen && controls && adjustment == null) IconButton(
            onClick = {
                locked = !locked
                controls = true
                interaction++
                platform.haptics.tick()
            },
            modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-64).dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(end = 24.dp).size(44.dp),
        ) {
            Icon(if (locked) NavigationIcons.Lock else NavigationIcons.Unlock,
                if (locked) "解锁" else "锁定", Modifier.size(20.dp), tint = Color.White)
        }
            },
        )
    }
}

/** Speeds exposed by the player rate menu, ascending and always including normal speed. */
internal val PlaybackRates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Player phases where the media has a known duration and accepts an initial seek. */
internal val ResumeSeekPhases = setOf(
    PlayerPhase.Ready, PlayerPhase.Playing, PlayerPhase.Paused, PlayerPhase.Buffering,
)

internal fun Float.stripRate(): String =
    if (this % 1f == 0f) toInt().toString() else toString().trimEnd('0').trimEnd('.')

internal enum class AdjustKind { Volume, Brightness }
private data class Adjustment(val kind: AdjustKind, val value: Float, val active: Boolean)

/** Side-aligned level feedback that leaves the center of fullscreen video unobstructed. */
@Composable
private fun PlayerLevelIndicator(adjustment: Adjustment, modifier: Modifier) {
    val volume = adjustment.kind == AdjustKind.Volume
    val level = adjustment.value.coerceIn(0f, 1f)
    Column(
        modifier.clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (volume) NavigationIcons.Volume else NavigationIcons.Brightness,
            if (volume) "音量" else "亮度",
            tint = Color.White, modifier = Modifier.size(20.dp),
        )
        Box(Modifier.width(4.dp).height(88.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(level)
                .background(Color.White))
        }
        Text("${(level * 100).roundToInt()}%", color = Color.White,
            fontSize = 11.sp, lineHeight = 14.sp)
    }
}

internal enum class TapZone { Left, Center, Right }
private enum class TapFeedbackKind { Rewind, Forward }
private data class TapFeedback(val kind: TapFeedbackKind, val token: Long = nextToken()) {
    companion object {
        private var counter = 0L
        fun nextToken(): Long = ++counter
    }
}

/** Maps a vertical drag (positive = finger moves down) onto a 0f..1f level. */
internal fun adjustedLevel(start: Float, dragRatio: Float): Float =
    (start - dragRatio).coerceIn(0f, 1f)

/** Fullscreen double taps toggle playback everywhere; inline taps retain edge seek shortcuts. */
internal fun tapZone(x: Float, width: Int, fullscreen: Boolean = false): TapZone {
    if (fullscreen) return TapZone.Center
    val safeWidth = width.coerceAtLeast(1).toFloat()
    return when {
        x < safeWidth / 3f -> TapZone.Left
        x >= safeWidth * 2f / 3f -> TapZone.Right
        else -> TapZone.Center
    }
}

/** Clamps the result of a ±10s double-tap seek to the playable range. */
internal fun quickSeekPosition(position: Long, duration: Long, forward: Boolean): Long =
    (position + if (forward) 10_000L else -10_000L).coerceIn(0L, duration.coerceAtLeast(0L))

/**
 * Preview position for a horizontal scrub drag. The pixel [delta] since the last event is
 * mapped onto the full duration through [frameWidth], so a full-width drag spans the whole
 * video; negative deltas rewind and the result always stays inside 0..[duration].
 */
internal fun dragSeekPosition(
    base: Long, previous: Float?, duration: Long, delta: Float, frameWidth: Int,
): Float {
    val safeWidth = frameWidth.coerceAtLeast(1).toFloat()
    val accumulated = (previous ?: base.toFloat()) - base + duration * (delta / safeWidth)
    return (base + accumulated).coerceIn(0f, duration.coerceAtLeast(0L).toFloat())
}

/** Horizontal delta scale after the finger slides up into fine-tune mode. */
private const val FINE_SEEK_SCALE = 0.25f

/** Small extracted frame and target/total time; the control bar owns progress. */
@Composable
private fun ScrubFeedback(
    frame: PreviewFrame?,
    position: Long,
    duration: Long,
    fine: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = 320.dp).fillMaxWidth(0.65f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (frame != null) {
            val context = LocalPlatformContext.current
            val request = remember(context, frame) {
                ImageRequest.Builder(context).data(frame.bytes)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED).build()
            }
            AsyncImage(
                model = request, contentDescription = "当前进度画面预览",
                modifier = Modifier.width(128.dp).aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(playbackTime(position), color = Accent, fontSize = 24.sp,
                lineHeight = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("/ ${playbackTime(duration)}", color = Color.White, fontSize = 20.sp,
                lineHeight = 28.sp, maxLines = 1)
        }
        if (fine) Text("精准", color = Color.White, fontSize = 10.sp,
            modifier = Modifier.background(Accent, RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

/** Double-tap seek hint shown at the left/right edge; the triangle is mirrored for rewind. */
@Composable
private fun SeekHint(forward: Boolean, modifier: Modifier) = Column(
    modifier.clip(RoundedCornerShape(14.dp))
        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 18.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    Icon(
        NavigationIcons.Play, if (forward) "快进 10 秒" else "快退 10 秒",
        tint = Color.White, modifier = Modifier.size(30.dp).graphicsLayer { scaleX = if (forward) 1f else -1f },
    )
    Text(if (forward) "+10秒" else "-10秒", color = Color.White,
        fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

/**
 * Text transport control with a stable touch height. Fullscreen uses a plain label;
 * the inline player keeps the compact pill treatment.
 */
@Composable
private fun ControlTextPill(text: String, plain: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = if (plain) 44.dp else 40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (plain) Color.Transparent else Color.White.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = if (plain) 16.sp else 13.sp,
            fontWeight = if (plain) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
    }
}

/**
 * One half of the video area. Horizontal drags seek, and vertical drags adjust volume on
 * the right or brightness on the left. The full-frame parent handles taps and double taps;
 * consuming a drag cancels the parent's tap detection.
 */
@Composable
internal fun PlayerGestureZone(
    modifier: Modifier,
    adjust: AdjustKind,
    dragSeekEnabled: Boolean,
    readLevel: () -> Float,
    onSeekStart: () -> Unit,
    onSeekDelta: (Float) -> Unit,
    onSeekEnd: () -> Unit,
    onSeekCancel: () -> Unit,
    onAdjust: (Float) -> Unit,
    onAdjustFinish: () -> Unit,
    onFineModeChange: (Boolean) -> Unit = {},
) {
    // The detector stays alive while playback ticks. Capture state holders, not the
    // callback instances from its first touch: those instances contain an immutable
    // PlayerState snapshot and would make later drags start at an earlier position.
    // Do not key pointerInput by position; restarting it on every tick cancels drags.
    val seekStart by rememberUpdatedState(onSeekStart)
    val seekDelta by rememberUpdatedState(onSeekDelta)
    val seekEnd by rememberUpdatedState(onSeekEnd)
    val seekCancel by rememberUpdatedState(onSeekCancel)
    val fineModeChange by rememberUpdatedState(onFineModeChange)
    var startLevel by remember { mutableFloatStateOf(0f) }
    var startY by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var fineMode by remember { mutableStateOf(false) }
    Box(
        modifier
            .pointerInput(dragSeekEnabled) {
                if (!dragSeekEnabled) return@pointerInput
                val fineThreshold = 64.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        fineMode = false
                        fineModeChange(false)
                        dragStartY = offset.y
                        seekStart()
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        // An upward drift while scrubbing switches to quarter-speed fine seeking.
                        if (!fineMode && dragStartY - change.position.y > fineThreshold) {
                            fineMode = true
                            fineModeChange(true)
                        }
                        seekDelta(if (fineMode) delta * FINE_SEEK_SCALE else delta)
                    },
                    onDragEnd = { fineMode = false; fineModeChange(false); seekEnd() },
                    onDragCancel = { fineMode = false; fineModeChange(false); seekCancel() },
                )
            }
            .pointerInput(adjust) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        startLevel = readLevel()
                        startY = offset.y
                        onAdjust(startLevel)
                    },
                    onVerticalDrag = { change, _ ->
                        val next = adjustedLevel(
                            startLevel,
                            (change.position.y - startY) / size.height.toFloat().coerceAtLeast(1f),
                        )
                        onAdjust(next)
                    },
                    onDragEnd = onAdjustFinish,
                    onDragCancel = onAdjustFinish,
                )
            },
    )
}
