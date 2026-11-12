package dev.pandavideo.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.BrowseCountries
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.data.user.ThemeMode
import dev.pandavideo.ui.catalog.CatalogContent
import dev.pandavideo.ui.catalog.HomeCategories
import dev.pandavideo.ui.catalog.BrowseFilterPanel
import io.ktor.util.date.GMTDate
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.ChoiceRow
import dev.pandavideo.ui.common.DarkColors
import dev.pandavideo.ui.common.LightColors
import dev.pandavideo.ui.common.LocalDarkTheme
import dev.pandavideo.ui.common.LocalNavAnimatedVisibilityScope
import dev.pandavideo.ui.common.LocalSharedTransitionScope
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.BottomNavigationTabs
import dev.pandavideo.ui.common.topTabSwipe
import dev.pandavideo.ui.common.RetryDialog
import dev.pandavideo.ui.player.MiniPlayerBar
import dev.pandavideo.ui.player.ModernPlayerScreen
import dev.pandavideo.ui.profile.ProfileScreen
import dev.pandavideo.ui.profile.SettingsScreen
import dev.pandavideo.ui.search.SearchPage

/** Bottom navigation destinations in display order; ids match the NavHost routes. */
internal val BottomTabs: List<Pair<String, String>> = listOf(
    "hot" to "最近热播",
    "discover" to "发现",
    "browse" to "分类",
    "profile" to "我的",
)
internal val HotTypeOptions = listOf(
    "tv" to "热播剧集", "movie" to "热门电影", "variety" to "热门综艺",
    "animation" to "热门动漫", "documentary" to "纪录片",
)
internal val BrowseTypeOptions = listOf(
    "movie" to "电影", "tv" to "电视剧", "documentary" to "纪录片", "animation" to "动画",
)
internal val BrowseGenreOptions = listOf(
    "all" to "全部题材", "drama" to "剧情", "comedy" to "喜剧", "action" to "动作",
    "romance" to "爱情", "scifi" to "科幻", "mystery" to "悬疑", "finance" to "金融",
    "nature" to "自然", "biography" to "人物传记", "war" to "战争", "hollywood" to "好莱坞大片",
)
internal val BrowseSortOptions = listOf("hot" to "近期热门", "rating" to "评分最高", "latest" to "最新上映")
internal val BrowseCountryOptions = BrowseCountries.toList()
internal val HomeCategoryOptions = listOf(
    "top250" to "精选", "tv" to "高分剧集", "comedy" to "喜剧", "anime" to "动画", "nature" to "自然",
    "movie" to "高分电影", "documentary" to "纪录片", "drama" to "剧情", "action" to "动作",
    "romance" to "爱情", "scifi" to "科幻", "mystery" to "悬疑", "finance" to "金融",
    "biography" to "传记", "war" to "战争", "hollywood" to "好莱坞",
)

/** “全部年份” followed by every selectable year, newest first. */
internal fun browseYearOptions(maxYear: Int = GMTDate().year): List<Pair<String, String>> =
    listOf("all" to "全部年份") + (maxYear downTo 1900).map { "$it" to "$it" }

private sealed interface StartupState {
    data object Failed : StartupState
    data class Ready(val model: AppState, val imageLoader: coil3.ImageLoader) : StartupState
}

/**
 * Renders the app after the system launch screen.
 *
 * @param platform Native services used by the app.
 * @param onReady Called after the first Compose frame is scheduled.
 */
@Composable
fun PandaVideoApp(platform: PlatformServices, onReady: () -> Unit = {}) {
    val context = coil3.compose.LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val readyCallback by rememberUpdatedState(onReady)
    LaunchedEffect(platform) {
        withFrameNanos { }
        readyCallback()
    }
    val startup = remember(platform, context, scope) {
        runCatching {
            val loader = dev.pandavideo.ui.common.posterImageLoader(context, platform.posterCacheDirectory())
            try {
                StartupState.Ready(AppState(platform, scope, posterImageLoader = loader) { urls ->
                    dev.pandavideo.ui.common.prefetchPosters(loader, context, urls)
                }, loader)
            } catch (error: Throwable) {
                loader.shutdown()
                throw error
            }
        }.getOrElse { StartupState.Failed }
    }
    DisposableEffect(startup) {
        onDispose {
            if (startup is StartupState.Ready) {
                try { startup.model.close() } finally { startup.imageLoader.shutdown() }
            }
        }
    }
    when (val state = startup) {
        is StartupState.Ready -> {
            CompositionLocalProvider(dev.pandavideo.ui.common.LocalPosterImageLoader provides state.imageLoader) {
                PandaVideoContent(platform, state.model)
            }
        }
        StartupState.Failed -> StartupFailureScreen()
    }
}

@Composable
private fun StartupFailureScreen() {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("启动失败，请重新打开应用", color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun SearchEntry(openSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = openSearch)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            NavigationIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "搜索影视名称",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text("搜索", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
    }
}

@Composable
private fun PandaVideoContent(platform: PlatformServices, model: AppState) {
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val pipActive by platform.pictureInPictureActive.collectAsState()
    val session = model.session
    LaunchedEffect(session, route, lifecycleState, pipActive) {
        session?.setPlaybackVisible(
            pipActive || (route == "player" && lifecycleState.isAtLeast(Lifecycle.State.STARTED)),
        )
    }
    var query by rememberSaveable { mutableStateOf("") }
    var showingSearch by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun submitSearch() {
        if (query.isBlank()) return
        query = query.trim()
        focus.clearFocus()
        showingSearch = true
        model.search(query, "all")
    }
    fun openSearch() {
        query = ""; showingSearch = false; model.clearSearch()
        navigation.navigate("search") { launchSingleTop = true }
    }
    fun navigateTab(target: String) {
        if (target == "discover") {
            query = ""; showingSearch = false; model.clearSearch(); focus.clearFocus()
        }
        navigation.navigate(target) {
            popUpTo(navigation.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun searchFromCatalog(title: String) {
        query = title; submitSearch()
        // Discard the player instead of saving it into the discover tab's back stack.
        if (!navigation.popBackStack("search", inclusive = false)) {
            navigation.popBackStack("discover", inclusive = false)
            navigation.navigate("search") { launchSingleTop = true }
        }
    }
    val themeMode by model.settings.settings.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(platform, darkTheme, route) {
        platform.setSystemBarAppearance(lightStatusBarIcons = darkTheme || route == "player", darkTheme = darkTheme)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(model) {
        model.userMessages.collect { snackbarHostState.showSnackbar(it) }
    }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
        val retryPrompts by model.retryPrompts.pending.collectAsState()
        val downloadTasks by model.downloads.tasks.collectAsState()
        val maintenance by model.cacheMaintenance.state.collectAsState()
        LaunchedEffect(downloadTasks, maintenance) { model.retryPrompts.discardUnavailable() }
        retryPrompts.firstOrNull {
            (it.destination == null || it.destination == route) && it.available()
        }?.let { prompt ->
            RetryDialog(prompt.title, prompt.message,
                retry = { model.retryPrompts.retry(prompt.id) },
                dismiss = { model.retryPrompts.dismiss(prompt.id) })
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (route != "player" && route != "search") Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .navigationBarsPadding(),
                ) {
                    if (model.session != null) MiniPlayerBar(model) {
                        navigation.navigate("player") { launchSingleTop = true }
                    }
                    BottomNavigationTabs(
                        tabs = BottomTabs,
                        savedOrder = themeMode.tabOrder,
                        selectedRoute = route,
                        onSelect = ::navigateTab,
                    )
                }
            },
        ) { padding ->
            // Lists extend beneath the translucent bar; each scroll container reserves its last row.
            Column(Modifier.fillMaxSize().padding(top =
                if (route == "player" || route == "profile") 0.dp else padding.calculateTopPadding())) {
                SharedTransitionLayout(
                    Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background),
                ) {
                  CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    NavHost(
                        navigation, startDestination = BottomTabs.first().first,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                      composable("discover") {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        run {
                            val state by model.discover.collectAsState()
                            var selected by rememberSaveable { mutableStateOf(model.kind) }
                            LaunchedEffect(selected) {
                                if (model.kind != selected || model.discoverCountry != "all")
                                    model.loadDiscover(selected, country = "all")
                            }
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                SearchEntry(::openSearch)
                                HomeCategories(selected) {
                                    selected = it; model.loadDiscover(it, country = "all")
                                }
                                CatalogContent(state, model, { model.loadDiscover(selected, country = "all", refresh = true) },
                                    { model.loadDiscover(selected, true, "all") },
                                    { item -> model.match(item, state.items) { navigation.navigate("player") } }, home = true,
                                    modifier = Modifier.topTabSwipe(HomeCategoryOptions.indexOfFirst { it.first == selected }, HomeCategoryOptions.size) { index ->
                                        selected = HomeCategoryOptions[index].first
                                        model.loadDiscover(selected, country = "all")
                                    })
                            }
                        }
                        }
                      }
                      composable("search") {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            SearchPage(
                                model = model, query = query, showingResults = showingSearch,
                                onQueryChange = {
                                    query = it
                                    showingSearch = false
                                    model.clearSearch()
                                },
                                submit = ::submitSearch,
                                searchTitle = { query = it; submitSearch() },
                                reset = { query = ""; showingSearch = false; model.clearSearch() },
                                back = { focus.clearFocus(); navigation.popBackStack() },
                                play = {
                                    model.startPlayer(PlayerRequest(page = it.detailUrl, title = it.title), search = it)
                                    navigation.navigate("player")
                                },
                            )
                        }
                      }
                      composable("browse") {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        run {
                            val state by model.browse.collectAsState()
                            var filters by rememberSaveable { mutableStateOf("movie|all|all|hot|all") }
                            val pieces = filters.split('|')
                            // Saved state from older builds has four fields and means all regions.
                            val current = BrowseFilters(pieces[0], pieces[1], pieces[2], pieces[3], pieces.getOrElse(4) { "all" })
                            fun update(next: BrowseFilters) {
                                filters = "${next.type}|${next.genre}|${next.year}|${next.sort}|${next.country}"
                                model.loadBrowse(next)
                            }
                            LaunchedEffect(Unit) { if (state.page == null && !state.loading) model.loadBrowse(current) }
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                SearchEntry { openSearch() }
                                BrowseFilterPanel(current, ::update)
                                CatalogContent(state, model, { model.loadBrowse(current, refresh = true) }, { model.loadBrowse(current, true) },
                                    { item -> model.match(item, state.items) { navigation.navigate("player") } },
                                    modifier = Modifier.topTabSwipe(BrowseTypeOptions.indexOfFirst { it.first == current.type }, BrowseTypeOptions.size) { index ->
                                        update(current.copy(type = BrowseTypeOptions[index].first))
                                    })
                            }
                        }
                        }
                      }
                      composable("hot") {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        val state by model.hot.collectAsState()
                        var selected by rememberSaveable { mutableStateOf(model.hotType) }
                        LaunchedEffect(Unit) { if (state.page == null && !state.loading) model.loadHot(selected) }
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            SearchEntry { openSearch() }
                            ChoiceRow(HotTypeOptions, selected, tab = true) { selected = it; model.loadHot(it) }
                            CatalogContent(state, model, { model.loadHot(selected, refresh = true) }, { model.loadHot(selected, more = true) },
                                { item -> model.match(item, state.items) { navigation.navigate("player") } },
                                modifier = Modifier.topTabSwipe(HotTypeOptions.indexOfFirst { it.first == selected }, HotTypeOptions.size) { index ->
                                    selected = HotTypeOptions[index].first
                                    model.loadHot(selected)
                                })
                        }
                        }
                      }
                      composable("profile") {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            ProfileScreen(model, { navigation.navigate("settings") },
                                topInset = padding.calculateTopPadding(),
                                openPlayer = { navigation.navigate("player") })
                        }
                      }
                      composable("downloads") {
                          dev.pandavideo.ui.profile.DownloadsScreen(model,
                              back = { navigation.popBackStack() },
                              openPlayer = { navigation.navigate("player") })
                      }
                      composable("settings") {
                          SettingsScreen(model) { navigation.popBackStack() }
                      }
                      composable(
                        "player",
                        enterTransition = { slideInHorizontally(tween(300)) { it } },
                        exitTransition = { slideOutHorizontally(tween(300)) { it } },
                        popEnterTransition = { slideInHorizontally(tween(300)) { it } },
                        popExitTransition = { slideOutHorizontally(tween(300)) { it } },
                    ) {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            val session = model.session
                            if (session != null) ModernPlayerScreen(session, model, platform,
                                search = ::searchFromCatalog, back = { navigation.popBackStack() },
                                home = {
                                    query = ""; showingSearch = false; model.clearSearch(); focus.clearFocus()
                                    navigation.navigate(BottomTabs.first().first) {
                                        popUpTo(navigation.graph.startDestinationId)
                                        launchSingleTop = true
                                    }
                                })
                            else LaunchedEffect(Unit) { navigation.popBackStack() }
                            // Keep the session alive for the mini bar; explicit close happens
                            // from the mini bar action or when another title starts playing.
                            DisposableEffect(Unit) { onDispose { model.minimizePlayer() } }
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
