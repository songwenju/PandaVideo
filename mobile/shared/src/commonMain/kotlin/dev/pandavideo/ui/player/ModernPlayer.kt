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
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import dev.pandavideo.core.model.visiblePlaybackLines
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.core.platform.PlayerSurface
import dev.pandavideo.core.platform.PlayerOverlayBackHandler
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
import dev.pandavideo.ui.search.ActorHalfSheet
import dev.pandavideo.ui.search.ActorScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlin.math.roundToInt

/** Window before the end in which the quick "next episode" pill appears. */
internal const val NEAR_END_WINDOW_MS = 20_000L

private data class RemoteEpisodeHandoff(
    val device: dev.pandavideo.core.player.DlnaDevice,
    val mediaId: Long, val playing: Boolean, val position: Long? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModernPlayerScreen(
    session: PlaybackSession, model: AppState, platform: PlatformServices,
    search: (String) -> Unit, back: () -> Unit,
    home: () -> Unit = back,
) {
    val state by session.state.collectPlaybackState()
    val adMarks by model.lineAds.marks.collectAsState()
    val adMarkedLines = remember(state.info?.pageUrl, state.info?.episodes, adMarks) {
        state.info?.let { info ->
            info.episodes.filter { model.lineAds.hasAds(info.pageUrl, it) }.map { it.line }.toSet()
        }.orEmpty()
    }
    var adMarkTarget by remember(state.info?.pageUrl) { mutableStateOf<Episode?>(null) }
    adMarkTarget?.let { episode ->
        state.info?.let { info ->
            LineAdMarkDialog(model.lineAds, info.pageUrl, episode,
                onDismiss = { adMarkTarget = null },
                onSaved = session::invalidateLinePreload,
                onFailure = { model.emitMessage("广告标记保存失败，请重试") })
        }
    }
    // Keep measurements alive across inline/fullscreen/casting layouts, and discard stale
    // results at the session boundary when a newer episode has superseded this request.
    LaunchedEffect(session, state.info?.pageUrl, state.info?.selected, state.info?.episodes, state.loading) {
        val info = state.info
        if (info != null && !state.loading) {
            try {
                session.updateLineProbes(info, model.probeLines(info) { measured ->
                    session.updateLineProbes(info, measured)
                })
            }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* Optional line measurements never interrupt playback. */ }
        }
    }
    val casting = remember(session, platform) {
        // The resolved session identifies the source even before Android's async
        // MediaController connects. Casting must not wait for the phone decoder.
        DlnaCasting(platform, session.controller, currentMediaId = { session.state.value.mediaId })
    }
    DisposableEffect(casting) { onDispose { casting.close() } }
    LaunchedEffect(state.mediaId) { casting.mediaChanged(state.mediaId) }
    DisposableEffect(session) {
        model.restorePlayerLevels()
        onDispose { model.minimizePlayer(session) }
    }
    val player by session.controller.state.collectPlaybackState()
    val discover by model.discover.collectAsState()
    val browse by model.browse.collectAsState()
    val hot by model.hot.collectAsState()
    val results by model.search.collectAsState()
    val fullscreenLayout = remember(session) { FullscreenLayoutState() }
    val fullscreen = fullscreenLayout.requested
    var episodePickerRequested by remember { mutableStateOf(false) }
    var selectedActor by remember(session) { mutableStateOf<dev.pandavideo.core.model.ActorRef?>(null) }
    var actorSheetVisible by remember(session) { mutableStateOf(false) }
    fun openActor(actor: dev.pandavideo.core.model.ActorRef) {
        selectedActor = actor
        actorSheetVisible = true
    }
    val pipActive by platform.pictureInPictureActive.collectAsState()
    val pipWindow = pipActive && platform.pipResizesAppWindow
    val catalog = (discover.items + browse.items + hot.items).distinctBy { it.url }
    val artwork = model.playingCatalog ?: catalog.find { it.title == state.info?.title }
    val cover = artwork?.let(model::poster) ?: model.playingSearch?.let(model::poster) ?: results.result?.results
        ?.find { it.detailUrl == state.info?.pageUrl }?.let(model::poster) ?: model.playingCover
    val recommendationScope = dev.pandavideo.data.repository.playerRecommendationScope(
        artwork, model.playingSearch, state.info)
    val originating = model.playingRecommendations
    val candidates = originating ?: catalog
    var fetchedRelated by remember(session, recommendationScope, originating) {
        mutableStateOf<List<CatalogItem>>(emptyList())
    }
    // Region/type changes invalidate the old rail immediately; late responses from a previous
    // title cannot repaint it. Metadata arrives independently and never delays playback.
    LaunchedEffect(session, recommendationScope, originating, state.loading, state.info?.pageUrl) {
        if (state.loading || state.info == null) return@LaunchedEffect
        fetchedRelated = model.playerRecommendations(recommendationScope, candidates, originating) {
            fetchedRelated = it
        }
    }
    val related = recommendationScope.select(fetchedRelated + candidates, originating)
    val title = state.info?.title ?: artwork?.title ?: model.playingSearch?.title ?: "正在加载影片"
    val detailsBackgroundColor = MaterialTheme.colorScheme.surface
    LaunchedEffect(fullscreen) {
        platform.fullscreen(fullscreen)
        if (!fullscreen) episodePickerRequested = false
    }
    // Audio mode changes the output, not the presentation. Keep the inline/fullscreen choice
    // until the user explicitly toggles it; PiP exit must not expand an inline audio session.
    // Entering picture-in-picture must leave immersive fullscreen first, otherwise the app
    // keeps the fullscreen state and UI flags while the video shrinks into a floating window.
    LaunchedEffect(pipActive) {
        if (pipActive) fullscreenLayout.enterPictureInPicture()
    }
    LaunchedEffect(state.info?.pageUrl, state.info?.selected) {
        val info = state.info
        if (info != null && info.pageUrl.isNotBlank()) {
            val episode = info.episodes.find { it.path == info.selected }?.label.orEmpty()
            model.rememberPlayed(info.title, info.pageUrl, cover, artwork?.year.orEmpty(), episode, info.selected)
        }
    }
    DisposableEffect(Unit) { onDispose { platform.fullscreen(false) } }
    var remoteEpisodeTarget by remember(session) {
        mutableStateOf<RemoteEpisodeHandoff?>(null)
    }
    // Selecting an episode replaces the local media ID and closes its old remote session.
    // Wait for the new source to finish resolving before handing it back to the same receiver.
    LaunchedEffect(state.mediaId, state.loading, player.mediaId, remoteEpisodeTarget) {
        val pending = remoteEpisodeTarget ?: return@LaunchedEffect
        if (state.mediaId != pending.mediaId && !state.loading && state.error != null) {
            remoteEpisodeTarget = null
        } else if (state.mediaId != pending.mediaId && !state.loading && player.mediaId == state.mediaId) {
            remoteEpisodeTarget = null
            session.controller.pause()
            state.info?.let { info ->
                casting.connect(pending.device, MediaSource(state.mediaId, info.url, info.kind, info.title,
                    episodeLabel = info.episodes.find { it.path == info.selected }?.label.orEmpty()),
                    wantsPlay = pending.playing, startPosition = pending.position ?: player.position)
                casting.picker = true
            }
        }
    }
    if (casting.connectingDevice != null && !pipActive) {
        PlayerOverlayBackHandler(enabled = true, onBack = casting::cancelConnection)
        DlnaCastStatusScreen(casting.connectingDevice?.name.orEmpty(), connecting = true,
            onAction = casting::cancelConnection, back = casting::cancelConnection)
        return
    }
    if (casting.endedDevice != null && !pipActive) {
        PlayerOverlayBackHandler(enabled = true, onBack = casting::dismissEnded)
        DlnaCastStatusScreen(casting.endedDevice?.name.orEmpty(), connecting = false,
            onAction = { casting.dismissEnded(); casting.picker = true; casting.search() },
            back = casting::dismissEnded)
        return
    }
    if (casting.device != null && !pipActive) {
        PlayerOverlayBackHandler(enabled = episodePickerRequested) { episodePickerRequested = false }
        Box(Modifier.fillMaxSize()) {
            DlnaRemoteScreen(casting, title,
                state.info?.let { info -> info.episodes.find { it.path == info.selected }?.label }.orEmpty(),
                cover = cover,
                canSelectEpisode = (state.info?.episodes?.size ?: 0) > 1,
                onEpisodes = { episodePickerRequested = true }, back = back)
            FullscreenEpisodeDrawer(episodePickerRequested, state,
                adMarkedLines = adMarkedLines,
                onLineLongPress = { target ->
                    adMarkTarget = state.info?.episodes?.firstOrNull { it.line == target }
                },
                onDismiss = { episodePickerRequested = false },
                onLineSelected = {
                    val info = state.info
                    val currentLine = info?.episodes?.find { episode -> episode.path == info.selected }?.line
                    if (it != currentLine) {
                        remoteEpisodeTarget = casting.device?.let { device ->
                            RemoteEpisodeHandoff(device, state.mediaId, casting.playing, casting.position)
                        }
                        session.selectLine(it)
                    }
                },
                onEpisodeSelected = {
                    if (it.path != state.info?.selected) {
                        remoteEpisodeTarget = casting.device?.let { device ->
                            RemoteEpisodeHandoff(device, state.mediaId, casting.playing)
                        }
                        session.select(it)
                    }
                    episodePickerRequested = false
                })
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
    val landscape = maxWidth > maxHeight
    LaunchedEffect(landscape) { fullscreenLayout.windowChanged(landscape) }
    LaunchedEffect(fullscreenLayout.restoring) {
        if (fullscreenLayout.restoring) {
            // Sensor-based Android policies can keep landscape after restoring the original
            // policy. No resize callback arrives in that case; do not strand the inline UI.
            // Normal rotations finish on their first matching constraints and cancel this job.
            delay(1_000)
            fullscreenLayout.finishUnchangedWindow()
        }
    }
    // Native rotation is asynchronous. Switching to inline at the button press briefly
    // mounts the landscape details/episode panel before the portrait constraints arrive.
    // Keep the video-only branch until the restored constraints arrive, without reloading.
    val fullscreen = fullscreenLayout.usesFullscreenLayout(landscape) && !pipActive
    fun changeFullscreen(enabled: Boolean) {
        if (!enabled) episodePickerRequested = false
        fullscreenLayout.request(enabled, landscape)
    }
    // System back follows the same window restoration path as the fullscreen button.
    // Register the episode overlay afterwards so it dismisses before fullscreen exits.
    PlayerOverlayBackHandler(enabled = fullscreenLayout.requested) { changeFullscreen(false) }
    PlayerOverlayBackHandler(enabled = fullscreen && episodePickerRequested) {
        episodePickerRequested = false
    }
    val density = LocalDensity.current
    var inlineVideoHeight by remember(session) { mutableStateOf(0.dp) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
        .then(if (!fullscreen && !pipWindow) Modifier.statusBarsPadding() else Modifier)) {
        val dualPane = maxWidth >= 840.dp && maxHeight >= 360.dp && !fullscreen && !pipWindow
        // Measure the complete video host, including platform controls. A fixed half-screen
        // sheet leaves an unnecessary gap on tall phones and can cover controls in audio mode.
        val actorViewport = when {
            dualPane -> Modifier.align(Alignment.CenterEnd).width(maxWidth / 2.65f).fillMaxHeight()
            fullscreen || landscape -> Modifier.align(Alignment.CenterEnd)
                .width(minOf(360.dp, maxWidth * 0.45f)).fillMaxHeight()
            else -> Modifier.fillMaxSize().padding(top = inlineVideoHeight.coerceAtMost(maxHeight))
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
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
                            toggleFullscreen = { changeFullscreen(true) },
                            openDetails = {}, pipMode = pipActive)
                    }
                    PullToRefreshBox(state.refreshing, session::refresh,
                        Modifier.weight(1f).fillMaxHeight().background(detailsBackgroundColor)) {
                        PlayerDetails(session, state, model, cover, artwork, related, search,
                            Modifier.fillMaxSize(), panel = true,
                            scrollToEpisodes = episodePickerRequested,
                            onEpisodesShown = { episodePickerRequested = false }, openActor = ::openActor)
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
            val videoModifier = when {
                pipWindow -> Modifier.fillMaxSize()
                fullscreen -> Modifier.fillMaxSize()
                // Audio actions need room above the timeline even on a narrow portrait phone.
                state.audioOnly -> Modifier.fillMaxWidth().height(240.dp)
                else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            }
            VideoFrame(session, player, state, fullscreen, title, platform,
                videoModifier.onSizeChanged { inlineVideoHeight = with(density) { it.height.toDp() } }, model, cover,
                back = { if (fullscreen) changeFullscreen(false) else back() },
                home = home,
                casting = casting,
                search = { search(artwork?.title ?: model.playingSearch?.title ?: state.info?.title.orEmpty()) },
                toggleFullscreen = { changeFullscreen(!fullscreenLayout.requested) },
                openDetails = {
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
                    onEpisodesShown = { episodePickerRequested = false }, openActor = ::openActor)
            }
            }
        }
        // Keep this overlay outside VideoFrame's gesture detectors and leave the native
        // playback surface mounted while the episode drawer opens, closes or changes source.
        FullscreenEpisodeDrawer(
            visible = fullscreenLayout.requested && !pipActive && episodePickerRequested,
            state = state,
            adMarkedLines = adMarkedLines,
            onLineLongPress = { target ->
                adMarkTarget = state.info?.episodes?.firstOrNull { it.line == target }
            },
            onDismiss = { episodePickerRequested = false },
            onLineSelected = session::selectLine,
            onEpisodeSelected = {
                model.platform.haptics.tick()
                session.select(it)
                episodePickerRequested = false
            },
        )
        // This is an in-place sibling overlay, not a navigation destination or a native dialog.
        // Keeping the player route and surface mounted preserves playback visibility, timeline,
        // paused intent and fullscreen state while actor data loads or the panel is dismissed.
        ActorHalfSheet(visible = actorSheetVisible && !pipActive,
            onDismiss = { actorSheetVisible = false }, modifier = actorViewport) {
            selectedActor?.let { actor ->
                key(actor.id) {
                    ActorScreen(actor, model, back = { actorSheetVisible = false },
                        play = { item -> model.match(item) { actorSheetVisible = false } },
                        compact = true)
                }
            }
        }
        if (!pipActive) DlnaDevicePicker(casting, state.info?.takeUnless { state.loading }?.let {
            MediaSource(state.mediaId, it.url, it.kind, it.title,
                episodeLabel = it.episodes.find { episode -> episode.path == it.selected }?.label.orEmpty())
        }, platform, modifier = actorViewport)
    }
    }
}

@Composable
private fun PlayerDetails(
    session: PlaybackSession, state: PlaybackState, model: AppState,
    cover: String?, artwork: CatalogItem?, related: List<CatalogItem>,
    search: (String) -> Unit, modifier: Modifier, panel: Boolean,
    scrollToEpisodes: Boolean = false,
    onEpisodesShown: () -> Unit = {},
    openActor: (dev.pandavideo.core.model.ActorRef) -> Unit = {},
) {
    val info = state.info
    val favorites by model.user.favorites.collectAsState()
    val adMarks by model.lineAds.marks.collectAsState()
    var adMarkTarget by remember(info?.pageUrl) { mutableStateOf<Episode?>(null) }
    var cachePickerVisible by remember(info?.pageUrl) { mutableStateOf(false) }
    val canCache = info != null && model.downloads.supports(info.kind)
    val favoriteUrl = info?.pageUrl ?: model.playingSearch?.detailUrl.orEmpty()
    val saved = favoriteUrl.isNotBlank() && favorites.any { it.pageUrl == favoriteUrl }
    var synopsisExpanded by remember(info?.title) { mutableStateOf(false) }
    // Only changes to the episode list invalidate grouping; progress and download updates
    // must not allocate a new map and line lists on every recomposition.
    val lines = remember(info?.episodes) { info?.episodes?.groupBy { it.line }.orEmpty() }
    val lineProbes = info?.lineProbes.orEmpty()
    val visibleLines = remember(lines, lineProbes) { visiblePlaybackLines(lines.keys, lineProbes) }
    var selectedLine by remember(info?.pageUrl) { mutableStateOf<Int?>(null) }
    var episodeRange by remember(info?.pageUrl) { mutableStateOf(0) }
    LaunchedEffect(info?.selected) {
        val episode = info?.episodes?.find { it.path == info.selected }
        selectedLine = episode?.line
        episodeRange = (lines[episode?.line]?.indexOf(episode)?.coerceAtLeast(0) ?: 0) / 30
    }
    val line = selectedLine?.takeIf { it in lines } ?: lines.keys.firstOrNull()
    val episodes = lines[line].orEmpty()
    val ranges = remember(episodes) { episodes.chunked(30) }
    val titleSize = 18.sp
    val sectionTitleSize = if (panel) 17.sp else 16.sp
    val bodySize = 12.5.sp
    val supportingSize = 11.sp
    val listState = rememberLazyListState()
    val resourceListState = rememberLazyListState()
    LaunchedEffect(visibleLines) {
        resourceListState.scrollToItem(0)
    }
    val actorNames = artwork?.casts?.takeIf { it.isNotEmpty() }
        ?: model.playingSearch?.cast.orEmpty().split(Regex("[/、,，]"))
    // Playback sources such as Pomo often omit the plot, so prefer their text when present
    // and fall back to the catalog subject synopsis resolved after the episode list loads.
    val synopsis = info?.synopsis?.takeIf(String::isNotBlank) ?: artwork?.synopsis.orEmpty()
    LaunchedEffect(scrollToEpisodes, info, panel) {
        if (scrollToEpisodes && lines.isNotEmpty()) {
            // Resource cards follow the compact header. Cast/recommendations are below
            // episodes, so fullscreen's picker request must not scroll past the controls.
            val headingIndex = if (!panel) 1 else 0
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
                }
                if (synopsis.isNotBlank()) Text(synopsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = bodySize, lineHeight = 20.sp,
                    maxLines = if (synopsisExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val savedNow = model.toggleFavorite(
                                headerTitle, favoriteUrl, cover, artwork?.year.orEmpty(),
                            )
                            if (savedNow) model.platform.haptics.success() else model.platform.haptics.tick()
                        },
                        enabled = favoriteUrl.isNotBlank(),
                        contentPadding = CompactActionButtonPadding,
                    ) {
                        Icon(
                            if (saved) NavigationIcons.HeartFilled else NavigationIcons.Heart,
                            contentDescription = if (saved) "取消收藏" else "收藏",
                            tint = if (saved) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (saved) "已收藏" else "收藏", fontSize = 12.sp,
                            color = if (saved) Accent else MaterialTheme.colorScheme.onSurface)
                    }
                    if (canCache) PlayerCacheButton(enabled = !state.loading) { cachePickerVisible = true }
                }
                // Use only available metadata. Search results may supply region/genre while
                // catalog results supply rating and cast; never invent awards or series.
                val metadata = buildList {
                    artwork?.rating?.let { add("豆瓣 $it 分") }
                    (artwork?.year?.takeIf(String::isNotBlank)
                        ?: model.playingSearch?.year?.takeIf(String::isNotBlank))?.let(::add)
                }.distinct()
                val extraMetadata = listOfNotNull(
                    info?.region?.takeIf(String::isNotBlank) ?: model.playingSearch?.region?.takeIf(String::isNotBlank),
                    model.playingSearch?.genre?.takeIf(String::isNotBlank),
                ).distinct().filterNot { it in metadata }
                if (metadata.isNotEmpty() || extraMetadata.isNotEmpty()) LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(metadata) { text ->
                        PlayerMetadataChip(text)
                    }
                    items(extraMetadata) { text -> PlayerMetadataChip(text) }
                }
            }
        }
        if (lines.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("播放资源", fontSize = sectionTitleSize, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Text("长按标记广告", fontSize = supportingSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyRow(state = resourceListState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(visibleLines, key = { it }) { target ->
                            val current = target == line
                            val probe = lineProbes[target]
                            val episode = lines[target]?.firstOrNull()
                            val marked = adMarks.let {
                                info != null && episode != null && model.lineAds.hasAds(info.pageUrl, episode)
                            }
                            PlaybackLineButton(
                                name = playbackLineName(lines.getValue(target).first(), info?.source.orEmpty()),
                                probe = probe, selected = current, marked = marked, enabled = !state.loading,
                                onLongClick = {
                                    model.platform.haptics.tick()
                                    adMarkTarget = episode
                                },
                                onClick = {
                                    selectedLine = target
                                    episodeRange = 0
                                    model.platform.haptics.tick()
                                    session.selectLine(target)
                                },
                            )
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", fontSize = sectionTitleSize, lineHeight = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (panel && canCache) {
                        PlayerCacheButton(enabled = !state.loading) { cachePickerVisible = true }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("共 ${episodes.size} 集", fontSize = supportingSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (actorNames.any(String::isNotBlank)) item {
            dev.pandavideo.ui.search.CastRail(model, actorNames, artwork?.url.orEmpty(), openActor)
        }
        if (!panel && related.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("相关推荐", fontSize = sectionTitleSize, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(related, key = { it.url }) { item ->
                        PlayerRecommendation(model.poster(item), item.title, item.rating,
                            onClick = { model.match(item) {} })
                    }
                }
            }
        }
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing)) }
    }
    if (cachePickerVisible && info != null) {
        EpisodeCacheSheet(info, model, cover) { cachePickerVisible = false }
    }
    adMarkTarget?.let { episode ->
        info?.let {
            LineAdMarkDialog(model.lineAds, it.pageUrl, episode,
                onDismiss = { adMarkTarget = null },
                onSaved = session::invalidateLinePreload,
                onFailure = { model.emitMessage("广告标记保存失败，请重试") })
        }
    }
}

@Composable
private fun PlayerMetadataChip(text: String) {
    Text(
        text, fontSize = 12.sp, lineHeight = 18.sp,
        fontWeight = if (text.startsWith("豆瓣")) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun PlayerCacheButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
        Icon(NavigationIcons.Download, null, Modifier.size(16.dp), tint = Accent)
        Spacer(Modifier.width(4.dp))
        Text("缓存", color = Accent, fontSize = 12.sp)
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
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp))) {
            // Use the same portrait proportions as catalog cards. A landscape slot shrinks
            // portrait posters into narrow thumbnails; preserve the complete artwork here.
            Poster(cover, title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
    var skipSettingsRequested by remember(session) { mutableStateOf(false) }
    if (skipSettingsRequested) state.info?.let { info ->
        // Pomo details carry no area label, so a result tapped from search supplies it.
        val region = info.region.ifBlank { model.playingSearch?.region.orEmpty() }
        SkipDurationsDialog(info.pageUrl, region, model.seriesSkipping) {
            skipSettingsRequested = false
        }
    }
    if (platform.persistentPlayerControls) {
        DesktopVideoFrame(session, player, state, fullscreen, title, modifier,
            back, home, search, toggleFullscreen, openDetails,
            openSkipSettings = { skipSettingsRequested = true })
        return
    }
    var controls by remember { mutableStateOf(true) }
    var sleepTimerRequested by remember(session) { mutableStateOf(false) }
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
    // Start each presentation at aspect fit. Carrying a pinch scale into fullscreen,
    // or scaling to cover an assumed 16:9 frame, can crop subtitles burned into the video.
    var zoom by remember(player.mediaId, fullscreen) { mutableFloatStateOf(1f) }
    var fillViewport by remember(player.mediaId, fullscreen) { mutableStateOf(false) }
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
    // Keep the startup overlay until the first frame is actually painted: the resolver can
    // finish while the player is still Idle or preparing. Once a frame is visible, hide it
    // immediately even if the initial buffer is still filling; positional buffering later in
    // playback still shows the overlay.
    val busy = state.loading ||
        (!player.firstFrameRendered && player.error == null && state.error == null &&
            player.phase in listOf(PlayerPhase.Idle, PlayerPhase.Preparing, PlayerPhase.Buffering)) ||
        (player.phase in listOf(PlayerPhase.Preparing, PlayerPhase.Buffering, PlayerPhase.Ready) &&
            player.position > 0)
    val error = player.error ?: state.error?.takeIf { state.info == null || player.phase == PlayerPhase.Idle }
    // Android keeps the previous SurfaceView frame while the next item is resolved and buffered.
    // Cover the video until the new item paints its first frame so a switch never shows the
    // previous episode; mid-playback rebuffering keeps its last frame because it is already rendered.
    val frameVisibility = remember(session) { VideoFrameVisibility() }
    val coverStaleFrame = frameVisibility.cover(player, state.loading, state.audioOnly, error,
        requestedMediaId = state.mediaId)
    val next = state.info?.let { nextEpisode(it) }
    val hasMultipleEpisodes = state.info?.episodes.orEmpty()
        .groupingBy { it.line }.eachCount().values.any { it > 1 }
    val dragSeekEnabled = state.info != null && error == null &&
        player.duration > 0 && session.controller.capabilities.seeking
    val seekPreview = gestureScrub ?: scrubbing
    LaunchedEffect(player.phase, controls, interaction, scrubbing != null, gestureScrub != null, rateMenu, sleepTimerRequested, skipSettingsRequested, state.loading, adjustment?.active, zoomActive, state.audioOnly) {
        if (!state.audioOnly && !platform.persistentPlayerControls && player.phase == PlayerPhase.Playing && controls && scrubbing == null && gestureScrub == null && !rateMenu && !sleepTimerRequested && !skipSettingsRequested && !state.loading && adjustment?.active != true && !zoomActive) {
            delay(3500); controls = false
        }
    }
    LaunchedEffect(player.phase, player.mediaId) {
        if (player.phase != PlayerPhase.Playing) controls = true
    }
    LaunchedEffect(state.audioOnly) {
        controls = true
        locked = false
        zoom = 1f
    }
    fun togglePlay() {
        interaction++
        when {
            player.phase == PlayerPhase.Ended || state.finished -> session.retry()
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
        session.seekTo(target)
        tapFeedback = TapFeedback(if (forward) TapFeedbackKind.Forward else TapFeedbackKind.Rewind)
    }
    fun seekBySeconds(forward: Boolean) {
        if (!quickSeekEnabled) return
        session.seekTo(quickSeekPosition(
            player.position, player.duration, forward, distanceMs = 10_000L,
        ))
        platform.haptics.tick()
        interaction++
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
        when (tapZone(offset.x, frameWidth)) {
            TapZone.Left -> quickSeek(false)
            TapZone.Right -> quickSeek(true)
            TapZone.Center -> doubleTapPlayPause()
        }
    }
    val zoomAction by rememberUpdatedState<(Float) -> Unit> { multiplier ->
        fillViewport = false
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
                PlayerSurface(session.controller, Modifier.fillMaxSize(),
                    if (pipMode) 1f else zoom,
                    audioOnly = state.audioOnly, fillViewport = fillViewport && !pipMode && !state.audioOnly)
            },
            audioOnly = state.audioOnly,
            audioBackdrop = { AudioModeBackdrop(cover, title) },
            overlays = {
        if (coverStaleFrame) Box(Modifier.fillMaxSize().background(Color.Black))
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
                    gestureScrub?.let { session.seekTo(it.toLong()) }
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
                    gestureScrub?.let { session.seekTo(it.toLong()) }
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
                if (fullscreen && !state.audioOnly && state.info != null) TextButton(
                    onClick = {
                        fillViewport = !fillViewport
                        zoom = 1f
                        controls = true
                        interaction++
                    },
                ) {
                    Text(if (fillViewport) "原始比例" else "铺满屏幕", color = Color.White, fontSize = 13.sp)
                }
                if (hasMultipleEpisodes && state.info != null && !state.loading) {
                    IconButton(onClick = {
                        skipSettingsRequested = true
                        controls = true
                        interaction++
                    }, modifier = Modifier.size(44.dp)) {
                        Icon(NavigationIcons.More, "播放设置", Modifier.size(22.dp), tint = Color.White)
                    }
                }
                if (!state.audioOnly && session.controller.capabilities.audioOnly && state.info != null) PlayerAudioButton(
                    session = session,
                    tint = Color.White,
                    onOpen = { platform.haptics.tick(); controls = true; interaction++ },
                )
                if (!state.audioOnly && fullscreen && session.controller.capabilities.pictureInPicture && platform.pipSupported) {
                    IconButton({ platform.haptics.tick(); platform.enterPictureInPicture() }, Modifier.size(44.dp)) {
                        Icon(NavigationIcons.Pip, "画中画", Modifier.size(22.dp), tint = Color.White)
                    }
                }
            }
        }
        if (state.audioOnly && error == null && !locked) {
            AudioModeContent(cover, title) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioReturnButton {
                        platform.haptics.tick()
                        returnToVideo(session)
                        controls = true
                        interaction++
                    }
                    PlayerSleepTimerButton(model, onClick = {
                        platform.haptics.tick()
                        sleepTimerRequested = true
                        controls = true
                        interaction++
                    })
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
            val sourcePage = model.playingCatalog?.playbackPageUrl?.ifBlank { model.playingCatalog?.url.orEmpty() }
                ?: model.playingSearch?.detailUrl ?: state.info?.pageUrl.orEmpty()
            val directSource = dev.pandavideo.data.repository.JianpianSource.owns(sourcePage)
            Text(if (state.loading && directSource) "正在读取线路…" else if (state.loading) "正在解析视频…"
                else if (player.phase == PlayerPhase.Buffering) "缓冲中…" else "准备播放…",
                color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        // AppState owns the single retry dialog after automatic recovery finishes.
        // Native errors still mask stale frames, but must not create a second error prompt.
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
                    onValueChangeFinished = { scrubbing?.let { session.seekTo(it.toLong()) }; scrubbing = null; interaction++ },
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
                if (fullscreen) SeekButton(forward = false, enabled = quickSeekEnabled) { seekBySeconds(false) }
                IconButton(::togglePlay, Modifier.size(46.dp)) {
                    Icon(if (player.wantsPlay) PlayerTransportIcons.Pause else PlayerTransportIcons.Play,
                        if (player.wantsPlay) "暂停" else "播放", Modifier.size(28.dp), tint = Color.White)
                }
                if (fullscreen) SeekButton(forward = true, enabled = quickSeekEnabled) { seekBySeconds(true) }
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
                if (session.controller.capabilities.playbackRate) PlayerRateControl(
                    rate = player.rate,
                    expanded = rateMenu,
                    onOpen = { rateMenu = true; interaction++ },
                    onDismiss = { rateMenu = false },
                    onSelect = { session.setPlaybackRate(it); interaction++ },
                )
                if (fullscreen && hasMultipleEpisodes) ControlTextPill("选集", plain = true) {
                    rateMenu = false
                    openDetails()
                }
                if (!state.audioOnly && !fullscreen && session.controller.capabilities.pictureInPicture && platform.pipSupported) {
                    IconButton({ platform.haptics.tick(); platform.enterPictureInPicture() }, Modifier.size(44.dp)) {
                        Icon(NavigationIcons.Pip, "画中画", Modifier.size(20.dp), tint = Color.White)
                    }
                }
                // Reserve the transport slot for the independently mounted fullscreen button.
                if (session.controller.capabilities.fullscreen) Spacer(Modifier.size(44.dp))
            }
        }
        // Keep the inline fullscreen entry discoverable when transport controls auto-hide,
        // including during resolution, buffering and errors. In fullscreen it follows the
        // overlay as usual. This only changes presentation; video sizing stays aspect fit.
        if ((!fullscreen || controls || seekPreview != null) && session.controller.capabilities.fullscreen) IconButton(
            onClick = toggleFullscreen,
            modifier = Modifier.align(Alignment.BottomEnd)
                .then(if (fullscreen) Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ) else Modifier)
                .padding(end = if (fullscreen) 24.dp else 12.dp, bottom = 9.dp)
                .size(44.dp)
                .then(if (!fullscreen && !controls)
                    Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape) else Modifier),
        ) {
            Icon(if (fullscreen) NavigationIcons.FullscreenExit else NavigationIcons.Fullscreen,
                if (fullscreen) "退出全屏" else "全屏", Modifier.size(20.dp), tint = Color.White)
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
    if (sleepTimerRequested) PlayerSleepTimerSheet(model, fullscreen) { sleepTimerRequested = false }
}

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

/** Both inline and fullscreen video use edge seek shortcuts and center playback toggling. */
internal fun tapZone(x: Float, width: Int): TapZone {
    val safeWidth = width.coerceAtLeast(1).toFloat()
    return when {
        x < safeWidth / 3f -> TapZone.Left
        x >= safeWidth * 2f / 3f -> TapZone.Right
        else -> TapZone.Center
    }
}

/** Clamps a step seek to the playable range. */
internal fun quickSeekPosition(
    position: Long, duration: Long, forward: Boolean, distanceMs: Long = 10_000L,
): Long = (position + if (forward) distanceMs else -distanceMs)
    .coerceIn(0L, duration.coerceAtLeast(0L))

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

/** Rewind/forward 10-second control shown on both sides of the fullscreen play button. */
@Composable
private fun SeekButton(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    IconButton(onClick, Modifier.size(44.dp), enabled = enabled) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                PlayerTransportIcons.Replay,
                contentDescription = if (forward) "快进 10 秒" else "快退 10 秒",
                modifier = Modifier.size(28.dp).graphicsLayer { scaleX = if (forward) -1f else 1f },
                tint = tint,
            )
            Text(
                "10",
                color = tint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(y = 1.dp).clearAndSetSemantics {},
            )
        }
    }
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
