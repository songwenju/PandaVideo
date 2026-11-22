package dev.pandavideo.tvui

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import coil3.compose.LocalPlatformContext
import dev.pandavideo.app.AppState
import dev.pandavideo.core.model.*
import dev.pandavideo.core.platform.AndroidPlatform
import dev.pandavideo.core.platform.PlayerSurface
import dev.pandavideo.core.player.PlaybackSession
import dev.pandavideo.core.player.TvConfirmPress
import dev.pandavideo.core.player.TvSeekGesture
import dev.pandavideo.core.platform.AndroidPlayer
import dev.pandavideo.ui.common.LocalPosterImageLoader
import dev.pandavideo.ui.common.Poster
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.player.VideoFrameVisibility
import dev.pandavideo.ui.player.collectPlaybackState
import dev.pandavideo.resources.Res
import dev.pandavideo.resources.panda_logo
import org.jetbrains.compose.resources.painterResource
import dev.pandavideo.ui.common.posterImageLoader
import dev.pandavideo.ui.common.prefetchPosters
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val Orange = Color(0xFFFF6740)
internal val Background = Color(0xFF10131A)
internal val TvBackdrop = Brush.verticalGradient(listOf(Color(0xFF202840), Color(0xFF151726), Background))
internal val Muted = Color(0xFFA9B0BF)
internal val Types = linkedMapOf("movie" to "电影", "tv" to "电视剧", "animation" to "动漫",
    "documentary" to "纪录片", "variety" to "综艺")

/**
 * Renders the television catalog and player using the mobile application's source policies.
 * Android TV components belong here because their focus treatment is platform-specific;
 * catalog matching, history, retries and playback lifetime remain owned by [AppState].
 */
@Composable
fun AndroidTvApp(platform: AndroidPlatform, receiveCast: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current
    val posterClient = remember { platform.httpClient() }
    val images = remember(context) { posterImageLoader(context, platform.posterCacheDirectory(), posterClient) }
    val model = remember { AppState(platform, scope, images,
        cachePosters = { prefetchPosters(images, context, it) }) }
    val library = remember { TvLibraryState() }
    var detail by remember { mutableStateOf<TvDetailSelection?>(null) }
    var detailInfo by remember { mutableStateOf<PlaybackInfo?>(null) }
    var playerVisible by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val prompts by model.retryPrompts.pending.collectAsState()
    LaunchedEffect(Unit) { model.userMessages.collect { message = it } }
    LaunchedEffect(message) { if (message != null) { delay(4_000); message = null } }
    DisposableEffect(model) { onDispose { model.close(); images.shutdown(); posterClient.close() } }
    CompositionLocalProvider(LocalPosterImageLoader provides images) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val hostDensity = LocalDensity.current
    // TV content uses a 1280dp canvas so a phone's high display density does not
    // magnify remote controls or push episodes off screen. Window width, rather
    // than physical display size, also keeps split-window bounds correct.
    CompositionLocalProvider(LocalDensity provides Density(
        hostDensity.density * maxWidth.value / 1280f, hostDensity.fontScale)) {
    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
        primary = Orange, background = Background, surface = Color(0xFF232833))) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Orange, background = Background,
        surface = Color(0xFF232833), onSurface = Color.White)) {
        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Color.White) {
        BoxWithConstraints(Modifier.fillMaxSize().background(TvBackdrop)) {
            detail?.let { selected ->
                val active = model.session
                val current by (active?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(
                    dev.pandavideo.core.player.PlaybackState()) }).collectAsState()
                // Preview and fullscreen share one probe job. Match the phone's lifecycle:
                // publish each route immediately, cancel on episode changes, and reject
                // superseded media at the session boundary before updating either list.
                LaunchedEffect(active, current.info?.pageUrl, current.info?.selected, current.info?.episodes, current.loading) {
                    val info = current.info
                    if (active != null && info != null && !current.loading) {
                        try {
                            active.updateLineProbes(info, model.probeLines(info) { measured ->
                                active.updateLineProbes(info, measured)
                            })
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Optional measurements never interrupt playback. */ }
                    }
                }
                TvDetail(model, selected, current.info ?: detailInfo, fullscreen = playerVisible,
                    loaded = {
                        detailInfo = it
                        model.playTelevisionDetail(selected.request.copy(page = it.pageUrl),
                            selected.catalog, selected.search, selected.cover, selected.recommendations)
                    }, onBack = {
                    model.closePlayer()
                    detail = null
                    library.restoreFocus = true
                }, onFullscreen = { playerVisible = true }, open = {
                    model.closePlayer()
                    detailInfo = null
                    detail = it
                })
                active?.let { session ->
                    // Keep the surface at the same composition site across preview/fullscreen.
                    // Only its bounds change; disposing it here would pause the session and
                    // discard the decoder's visible frame during the transition.
                    TvPlayer(session, selected.title, playerVisible,
                        if (playerVisible) Modifier.fillMaxSize() else Modifier
                            .padding(start = 36.dp, top = 84.dp)
                            .width(tvDetailPreviewWidth(maxWidth, maxHeight)).aspectRatio(16f / 9f),
                        fullscreen = { playerVisible = true }, exit = { playerVisible = false })
                }
                Unit
            } ?: TvLibrary(model, library, receiveCast = {
                // Relinquish the catalog player's audio and decoder before the receiver
                // Activity takes ownership. Returning keeps library focus and filters.
                model.session?.controller?.stop()
                model.closePlayer()
                receiveCast()
            }) { selected ->
                model.closePlayer()
                detailInfo = null
                detail = selected
            }
            message?.let { Text(it, Modifier.align(Alignment.BottomCenter)
                .background(Color(0xEE232833), RoundedCornerShape(10.dp)).padding(18.dp), color = Color.White) }
            prompts.firstOrNull { it.available() }?.let { prompt ->
                TvDialog(onDismissRequest = { model.retryPrompts.dismiss(prompt.id) }) {
                    Column(Modifier.width(580.dp).background(Background, RoundedCornerShape(16.dp)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(prompt.title, fontSize = 22.sp, color = Color.White)
                        Text(prompt.message, fontSize = 16.sp, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvAction(prompt.confirmLabel) { model.retryPrompts.retry(prompt.id) }
                            TvAction("取消") { model.retryPrompts.dismiss(prompt.id) }
                        }
                    }
                }
            }
        }
        }
    }
    }
    }
    }
    }
}

/** Preserves the TV canvas density across Android's separate dialog window. */
@Composable
internal fun TvDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Dialog creates a new Compose root with the device density. Reprovide the
        // captured canvas density so text and padding keep their television dimensions.
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

@Composable
private fun Modifier.tvTouchClick(action: () -> Unit): Modifier {
    val latestAction by rememberUpdatedState(action)
    // TV Material 1.1 handles D-pad Enter and accessibility clicks, but installs no
    // pointer handler. Add only touch detection: foundation.clickable would introduce
    // a second focus/key handler and could execute a remote action twice. Keep the
    // pointer coroutine stable while callbacks change during catalog recomposition;
    // detectTapGestures cancels taps consumed by scrolling instead of opening a card.
    return pointerInput(Unit) { detectTapGestures { latestAction() } }
}

@Composable
internal fun TvAction(label: String, selected: Boolean = false, modifier: Modifier = Modifier,
    compact: Boolean = false, icon: ImageVector? = null, showLabel: Boolean = true,
    dropdown: Boolean = false, horizontalPadding: Dp = 16.dp, action: () -> Unit) {
    // Lazy lists clip visual overflow using unscaled item bounds.
    Button(onClick = action, modifier = modifier.tvTouchClick(action),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = if (selected) Orange else Color(0xFF232833),
            contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Background),
        contentPadding = PaddingValues(horizontal = if (!showLabel) 8.dp else horizontalPadding,
            vertical = if (compact) 4.dp else 10.dp)) {
        Row(modifier = if (showLabel) Modifier else Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            icon?.let { Icon(it, contentDescription = if (showLabel) null else label, modifier = Modifier.size(22.dp)) }
            if (showLabel) Text(label, fontSize = 16.sp, maxLines = 1)
            // Old TV fonts can render the down-triangle character as a missing glyph.
            if (dropdown) Icon(NavigationIcons.Chevron, contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(90f))
        }
    }
}

@Composable
internal fun TvBrand(modifier: Modifier = Modifier, title: String = "熊猫视频") {
    Row(modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(Res.drawable.panda_logo), contentDescription = "熊猫视频 Logo",
            modifier = Modifier.size(32.dp), contentScale = ContentScale.Fit)
        Text(title, fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Shows loading until the first frame; rebuffering retains the painted video. */
@Composable
internal fun TvVideoLoading(coverVideo: Boolean = true) {
    Box(Modifier.fillMaxSize().then(if (coverVideo) Modifier.background(Color.Black) else Modifier),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(40.dp), color = Orange, strokeWidth = 3.dp)
            Text("正在加载", fontSize = 16.sp, color = Muted)
        }
    }
}

@Composable
internal fun TvPoster(title: String, cover: String?, subtitle: String, modifier: Modifier = Modifier,
    imageRatio: Float = 0.72f,
    play: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val revealTitle = focused || hovered
    // Keep focus feedback inside the item, including at a lazy viewport's first
    // or last edge. Negative inset contains the entire 3dp stroke within the card.
    Card(onClick = play, interactionSource = interactions,
        modifier = modifier.fillMaxWidth().hoverable(interactions).tvTouchClick(play),
        scale = CardDefaults.scale(focusedScale = 1f),
        border = CardDefaults.border(focusedBorder = Border(
            border = BorderStroke(3.dp, Color.White), inset = (-2).dp))) {
        Column(Modifier.background(Color(0xFF232833))) {
            Box(Modifier.fillMaxWidth().aspectRatio(imageRatio).background(Color(0xFF303745)),
                contentAlignment = Alignment.Center) {
                Text(title.take(12), Modifier.padding(12.dp), fontSize = 22.sp, color = Muted)
                Poster(cover, title, Modifier.fillMaxSize())
            }
            Column(Modifier.padding(10.dp)) {
                // The card owns focus/hover; the text itself must not add a remote stop.
                // Clip the active title instead of ellipsizing, so marquee can reveal it all.
                Text(title, modifier = Modifier.fillMaxWidth().then(
                    if (revealTitle) Modifier.basicMarquee(iterations = Int.MAX_VALUE,
                        initialDelayMillis = 800, repeatDelayMillis = 1_200, velocity = 32.dp)
                    else Modifier),
                    maxLines = 1, softWrap = false,
                    overflow = if (revealTitle) TextOverflow.Clip else TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp, color = Color.White)
                if (subtitle.isNotBlank())
                    Text(subtitle, maxLines = 1, color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TvPlayer(session: PlaybackSession, fallbackTitle: String, expanded: Boolean,
    modifier: Modifier, fullscreen: () -> Unit, exit: () -> Unit) {
    val playback by session.state.collectPlaybackState()
    val player by session.controller.state.collectPlaybackState()
    val frameVisibility = remember(session) { VideoFrameVisibility() }
    val coverVideo = frameVisibility.cover(player, playback.loading, playback.audioOnly,
        playback.error ?: player.error, requestedMediaId = playback.mediaId)
    var controls by remember { mutableStateOf(false) }
    var previewFocused by remember { mutableStateOf(false) }
    var playFocused by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf(false) }
    var interaction by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var seekForward by remember { mutableStateOf(true) }
    var seekExpanded by remember { mutableStateOf(false) }
    var seekSuppressed by remember { mutableStateOf(false) }
    var seekKey by remember { mutableStateOf<Int?>(null) }
    var seekPending by remember { mutableStateOf(false) }
    var seekSerial by remember { mutableLongStateOf(0L) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    val gesture = remember(session) { TvSeekGesture() }
    val seekScope = rememberCoroutineScope()
    var seekJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val screenFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val confirmPress = remember(session) { TvConfirmPress() }
    var episodeGroup by remember { mutableIntStateOf(0) }
    fun toggle() {
        if (player.wantsPlay) session.controller.pause() else session.controller.play()
    }
    fun syncPreview() {
        seekTarget = gesture.target
        seekExpanded = gesture.longPress
        seekSuppressed = gesture.suppressed
        seekForward = gesture.forward
        if (seekExpanded) seekHint = null
    }
    fun cancelSeek() {
        seekSerial++
        gesture.cancel()
        syncPreview()
        seekKey = null
        seekHint = null
        seekJob?.cancel()
        seekPending = false
    }
    fun commitSeek() {
        val target = gesture.release(android.os.SystemClock.uptimeMillis()) ?: return
        syncPreview()
        seekKey = null
        val serial = ++seekSerial
        val previous = seekJob
        previous?.cancel()
        seekPending = true
        seekJob = seekScope.launch {
            try {
                // Remove the previous native observer before registering a new one.
                previous?.join()
                val android = session.controller as? AndroidPlayer
                if (android != null) android.seekAndAwaitReady { session.seekTo(target) }
                else session.seekTo(target)
            } finally {
                if (serial == seekSerial) {
                    seekPending = false
                    interaction++
                }
            }
        }
    }
    LaunchedEffect(seekKey) {
        while (seekKey != null) {
            delay(16)
            gesture.tick(android.os.SystemClock.uptimeMillis())
            syncPreview()
        }
    }
    LaunchedEffect(seekHint, seekKey) {
        if (seekHint != null && seekKey == null) { delay(1_000); seekHint = null }
    }
    LaunchedEffect(player.mediaId, expanded) { cancelSeek() }
    BackHandler(enabled = expanded) { when {
        seekTarget != null && !seekSuppressed -> { gesture.dismiss(); syncPreview(); seekHint = null }
        seekTarget != null -> Unit
        episodes -> episodes = false
        controls -> controls = false
        else -> exit()
    } }
    LaunchedEffect(controls, episodes, expanded) {
        if (expanded) {
            if (episodes) episodeFocus.requestFocus()
            else if (controls) playFocus.requestFocus() else screenFocus.requestFocus()
        }
    }
    LaunchedEffect(interaction, controls, episodes, seekKey, seekPending) {
        if (controls && !episodes && seekKey == null && !seekPending) { delay(5_000); controls = false }
    }
    // Hidden playback must not continue when the activity is backgrounded by Home or another app.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(session, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                cancelSeek()
                session.setPlaybackVisible(false)
            }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) session.setPlaybackVisible(true)
        }
        owner.lifecycle.addObserver(observer)
        session.setPlaybackVisible(true)
        onDispose { cancelSeek(); owner.lifecycle.removeObserver(observer); session.setPlaybackVisible(false) }
    }
    LaunchedEffect(expanded) { controls = false; episodes = false }
    Box(modifier.background(Color.Black).onPreviewKeyEvent { event ->
        if (!expanded) return@onPreviewKeyEvent false
        val key = event.nativeKeyEvent.keyCode
        if (key == AndroidKey.KEYCODE_DPAD_CENTER || key == AndroidKey.KEYCODE_ENTER ||
            key == AndroidKey.KEYCODE_NUMPAD_ENTER) {
            // Revealing controls moves focus to the play button. TV Material calls
            // onClick on key-up without requiring a key-down on that button, so
            // consume this entire press (including repeats) across the focus move.
            // Revealing the UI must preserve both playing and paused sessions.
            if (confirmPress.handle(key, event.nativeKeyEvent.action == AndroidKey.ACTION_DOWN,
                    event.nativeKeyEvent.repeatCount, controls || episodes) {
                    controls = true
                    interaction++
                }) return@onPreviewKeyEvent true
        }
        // Left/right seek from the playback row whether its toolbar is visible or hidden.
        // The lower action row and episode picker still need horizontal focus navigation.
        if ((seekKey != null || (!controls || playFocused) && !episodes) &&
            key in setOf(AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT)) {
            interaction++
            if (event.nativeKeyEvent.action == AndroidKey.ACTION_UP) {
                if (key == seekKey) commitSeek()
            } else if (event.nativeKeyEvent.action == AndroidKey.ACTION_DOWN && player.duration > 0) {
                // Orphaned repeats after a media change must wait for a fresh press.
                if (seekKey == null && event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
                gesture.press(android.os.SystemClock.uptimeMillis(), player.position,
                    player.duration, key == AndroidKey.KEYCODE_DPAD_RIGHT)
                seekKey = key
                syncPreview()
                if (!seekExpanded && !seekSuppressed) seekHint = if (seekForward) "快进 10s" else "快退 10s"
            }
            return@onPreviewKeyEvent true
        }
        if (event.nativeKeyEvent.action != AndroidKey.ACTION_DOWN || episodes) return@onPreviewKeyEvent false
        interaction++
        when (event.nativeKeyEvent.keyCode) {
            AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE -> { toggle(); true }
            AndroidKey.KEYCODE_MEDIA_PLAY -> { session.controller.play(); true }
            AndroidKey.KEYCODE_MEDIA_PAUSE -> { session.controller.pause(); true }
            AndroidKey.KEYCODE_MENU -> { controls = !controls; true }
            AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN -> if (!controls) {
                controls = true; true
            } else false
            else -> false
        }
    }) {
        PlayerSurface(session.controller, Modifier.fillMaxSize())
        if (expanded && !episodes) Box(Modifier.fillMaxSize().focusRequester(screenFocus).focusable()
            .tvTouchClick { controls = !controls; interaction++ })
        else if (!expanded) Box(Modifier.fillMaxSize()
            .onFocusChanged { previewFocused = it.isFocused }
            .clickable(onClickLabel = "全屏", onClick = fullscreen))
        seekHint?.let { hint ->
            Text(hint,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .background(Color(0xEE10131A), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                color = Color.White, fontSize = 20.sp)
        }
        // The loading indicator belongs to the first-frame cover. Seeking can enter
        // Buffering after video is visible; covering it then obscures both
        // the retained frame and the remote's seek preview.
        if (coverVideo) {
            TvVideoLoading()
        }
        // Draw above the first-frame cover so remote focus remains visible while loading.
        if (!expanded && previewFocused) {
            Box(Modifier.fillMaxSize().border(3.dp, Orange))
        }
        if (expanded && seekExpanded && !seekSuppressed && !controls && !episodes) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).width(380.dp)
                .background(Color(0xEE10131A), RoundedCornerShape(12.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${clock(seekTarget ?: player.position)} / ${clock(player.duration)}",
                    color = Color.White, fontSize = 22.sp)
                TvTimeline(seekTarget ?: player.position, player.duration)
            }
        }
        if (expanded && controls && !episodes) {
            TvBrand(Modifier.fillMaxWidth().background(Brush.verticalGradient(
                listOf(Color(0xBB000000), Color.Transparent))).padding(36.dp),
                title = listOfNotNull(playback.info?.title ?: fallbackTitle,
                playback.info?.episodes?.firstOrNull { it.path == playback.info?.selected }?.label)
                .joinToString("  ·  "))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000)))).padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // Text Button's nested Row shifts this icon upward within its padding.
                // TV IconButton centers content in the full circular surface in both states.
                IconButton(onClick = ::toggle,
                    modifier = Modifier.size(48.dp).focusRequester(playFocus)
                        .onFocusChanged { playFocused = it.isFocused }.tvTouchClick(::toggle),
                    scale = IconButtonDefaults.scale(focusedScale = 1f),
                    colors = IconButtonDefaults.colors(containerColor = Color(0xFF232833),
                        contentColor = Color.White, focusedContainerColor = Color.White,
                        focusedContentColor = Background)) {
                    Icon(if (player.wantsPlay) NavigationIcons.Pause else NavigationIcons.Play,
                        contentDescription = if (player.wantsPlay) "暂停" else "播放",
                        modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    val preview = seekExpanded && !seekSuppressed
                    val controlPosition = if (preview) seekTarget ?: player.position else player.position
                    if (!preview) Text(clock(controlPosition), color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    TvTimeline(controlPosition, player.duration, preview)
                }
                Text(clock(player.duration), color = Color.White, fontSize = 16.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TvAction("选集 / 线路", icon = NavigationIcons.Browse) {
                    val info = playback.info
                    val selected = info?.episodes?.firstOrNull { it.path == info.selected }
                    episodeGroup = (info?.episodes.orEmpty().filter { it.line == selected?.line }
                        .indexOfFirst { it.path == selected?.path }.coerceAtLeast(0) / 24)
                    episodes = true
                }
                TvAction("倍速 ${player.rate}×") {
                    val rates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                    session.setPlaybackRate(rates[(rates.indexOf(player.rate) + 1) % rates.size])
                }
                TvAction("返回详情", icon = NavigationIcons.FullscreenExit, action = exit)
            }
            }
        }
        if (expanded && episodes) {
            val info = playback.info
            val currentLine = info?.episodes?.find { it.path == info.selected }?.line
            val lines = info?.let { visiblePlaybackLines(it.episodes.map { ep -> ep.line }.distinct(), it.lineProbes) }.orEmpty()
            val available = info?.episodes.orEmpty().filter { it.line == currentLine }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 330.dp)
                .background(Color(0xF510131A)).padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("选集 · 共 ${available.size} 集", fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    TvAction("关闭", modifier = Modifier.height(30.dp).focusRequester(episodeFocus),
                        compact = true, icon = NavigationIcons.Close) { episodes = false }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
                    items(lines, key = { it }) { line ->
                        TvAction(tvPlaybackLineLabel(info!!, line), line == currentLine,
                            Modifier.height(30.dp), compact = true) {
                            session.selectLine(line)
                            episodeGroup = 0
                        }
                    }
                }
                if (available.size > 24) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(6.dp)) {
                    items(available.chunked(24).indices.toList()) { index ->
                        TvAction("${index * 24 + 1}-${minOf((index + 1) * 24, available.size)}",
                            episodeGroup == index, Modifier.height(30.dp), compact = true) { episodeGroup = index }
                    }
                }
                LazyVerticalGrid(GridCells.Fixed(8), Modifier.heightIn(max = 150.dp),
                    contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(available.drop(episodeGroup * 24).take(24), key = { it.path }) { ep ->
                        TvAction(ep.label, ep.path == info?.selected, Modifier.height(36.dp),
                            compact = true) { session.select(ep); episodes = false }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvTimeline(position: Long, duration: Long, preview: Boolean = false) {
    val fraction = (position.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
    // Reserve real layout space for the popup. Offsetting text outside a 5dp bar
    // still measures it against that height and clips both glyphs and accessibility.
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (preview) 50.dp else 5.dp)) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp)
            .background(Color(0xFF586170), RoundedCornerShape(3.dp)))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction)
            .height(5.dp).background(Orange, RoundedCornerShape(3.dp)))
        if (preview) {
            val thumb = (maxWidth * fraction - 6.dp).coerceIn(0.dp, (maxWidth - 12.dp).coerceAtLeast(0.dp))
            Box(Modifier.align(Alignment.BottomStart).offset(x = thumb, y = 3.dp).size(12.dp)
                .background(Color.White, RoundedCornerShape(6.dp)))
            Text(clock(position), Modifier.offset(
                x = (maxWidth * fraction - 38.dp).coerceIn(0.dp, (maxWidth - 76.dp).coerceAtLeast(0.dp))).width(76.dp)
                .background(Orange, RoundedCornerShape(6.dp)).padding(6.dp),
                color = Color.White, fontSize = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

internal fun clock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
