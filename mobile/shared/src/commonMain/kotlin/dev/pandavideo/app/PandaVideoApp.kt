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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import io.ktor.http.encodeURLParameter
import dev.pandavideo.core.model.ActorRef
import dev.pandavideo.core.model.ActorProfileField
import kotlinx.serialization.json.Json
import dev.pandavideo.ui.search.ActorScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.core.model.BrowseCountries
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.data.user.ThemeMode
import dev.pandavideo.ui.catalog.CatalogContent
import dev.pandavideo.ui.catalog.collectCatalogPageState
import dev.pandavideo.ui.catalog.HomeCategories
import dev.pandavideo.ui.catalog.BrowseFilterPanel
import dev.pandavideo.ui.catalog.homeHistoryFor
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
import dev.pandavideo.ui.common.TopTabPager
import dev.pandavideo.ui.common.RetryDialog
import dev.pandavideo.ui.player.MiniPlayerBar
import dev.pandavideo.ui.player.ModernPlayerScreen
import dev.pandavideo.ui.profile.ProfileScreen
import dev.pandavideo.ui.profile.SettingsScreen
import dev.pandavideo.ui.search.SearchPage

/** Bottom navigation destinations in display order; ids match the NavHost routes. */
internal val BottomTabs: List<Pair<String, String>> = listOf(
    "hot" to "最近热播",
    "discover" to "精选",
    "browse" to "分类",
    "profile" to "我的",
)
internal val HotTypeOptions = listOf(
    "tv" to "热播剧集", "movie" to "热门电影", "variety" to "热门综艺",
    "animation" to "热门动漫", "documentary" to "纪录片",
    "xiaopin" to "小品",
)
internal val BrowseTypeOptions = listOf(
    "movie" to "电影", "tv" to "电视剧", "documentary" to "纪录片", "animation" to "动画",
)
internal val BrowseGenreOptions = listOf(
    "all" to "全部题材", "drama" to "剧情", "comedy" to "喜剧", "action" to "动作",
    "romance" to "爱情", "scifi" to "科幻", "mystery" to "悬疑",
    "finance" to "金融", "nature" to "自然", "biography" to "传记", "war" to "战争",
    "hollywood" to "好莱坞",
)
internal val BrowseSortOptions = listOf("hot" to "热门", "rating" to "豆瓣评分", "latest" to "最新上映")
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
            "搜索影视或演员",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text("搜索", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
    }
}

@Composable
internal fun NavigationDestinationContent(
    topInset: Dp,
    content: @Composable () -> Unit,
) {
    // Navigation keeps both destinations composed during player transitions. Insets must belong
    // to each destination: changing the NavHost's padding on pop moves the outgoing actor page
    // upward before it disappears. Player and profile already own their system insets.
    Box(Modifier.fillMaxSize().padding(top = topInset)) {
        content()
    }
}

@Composable
private fun PandaVideoContent(platform: PlatformServices, model: AppState) {
    // Settings are loaded synchronously. Capture the saved first tab once so reordering
    // tabs during this session cannot rebuild the navigation graph or reset its back stack.
    val initialTab = remember(model) { model.settings.settings.value.tabOrder.first() }
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
    var searchType by rememberSaveable { mutableStateOf("all") }
    var showingSearch by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun submitSearch() {
        if (query.isBlank()) return
        query = query.trim()
        focus.clearFocus()
        showingSearch = true
        if (dev.pandavideo.data.repository.BilibiliSource.reference(query) != null) searchType = "bilibili"
        model.search(query, searchType)
    }
    fun openSearch() {
        query = ""; searchType = "all"; showingSearch = false; model.clearSearch()
        navigation.navigate("search") { launchSingleTop = true }
    }
    fun openActor(actor: ActorRef) {
        focus.clearFocus()
        navigation.navigate("actor/${actor.id}?name=${actor.name.encodeURLParameter()}" +
            "&avatar=${actor.avatar.encodeURLParameter()}&alternate=${actor.alternateName.encodeURLParameter()}" +
            "&fields=${Json.encodeToString(actor.profileFields).encodeURLParameter()}")
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
    val history by model.user.history.collectAsState()
    fun resumeHome(record: dev.pandavideo.data.user.HistoryTitle) {
        model.resumeHomeHistory(record)
        navigation.navigate("player") { launchSingleTop = true }
    }
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
                dismiss = { model.retryPrompts.dismiss(prompt.id) }, confirmLabel = prompt.confirmLabel)
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (route != "player" && route != "search" && route?.startsWith("actor/") != true) Column(
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
                        onReorder = model.settings::setTabOrder,
                    )
                }
            },
        ) { padding ->
            // Lists extend beneath the translucent bar; each scroll container reserves its last row.
            Column(Modifier.fillMaxSize()) {
                SharedTransitionLayout(
                    Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background),
                ) {
                  CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    NavHost(
                        navigation, startDestination = initialTab,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                      composable("discover") {
                        NavigationDestinationContent(padding.calculateTopPadding()) {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        run {
                            var selected by rememberSaveable { mutableStateOf(model.kind) }
                            var country by rememberSaveable { mutableStateOf(model.discoverCountry) }
                            LaunchedEffect(selected, country) {
                                if (model.kind != selected || model.discoverCountry != country)
                                    model.loadDiscover(selected, country = country)
                            }
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                SearchEntry(::openSearch)
                                HomeCategories(selected) {
                                    selected = it; model.loadDiscover(it, country = country)
                                }
                                ChoiceRow(BrowseCountryOptions, country, tab = true) {
                                    country = it; model.loadDiscover(selected, country = it)
                                }
                                TopTabPager(HomeCategoryOptions.indexOfFirst { it.first == selected }, HomeCategoryOptions.size, onSelect = { index ->
                                        selected = HomeCategoryOptions[index].first
                                        model.loadDiscover(selected, country = country)
                                    }) { index ->
                                    val type = HomeCategoryOptions[index].first
                                    val page by (if (type == selected) model.discover
                                        else remember(model, type, selected, country) {
                                            model.discoverPage(type, country)
                                        }).collectCatalogPageState()
                                    CatalogContent(page, model, { model.loadDiscover(type, country = country, refresh = true) },
                                        { model.loadDiscover(type, true, country) },
                                        { item -> model.match(item, page.items) { navigation.navigate("player") } },
                                        home = true, active = type == selected,
                                        recent = homeHistoryFor(themeMode.tabOrder, "discover", history, index),
                                        onResume = ::resumeHome)
                                }
                            }
                        }
                        }
                        }
                      }
                      composable("search") {
                        NavigationDestinationContent(padding.calculateTopPadding()) {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            SearchPage(
                                model = model, query = query, showingResults = showingSearch,
                                sourceType = searchType, onSourceChange = {
                                    searchType = it
                                    model.clearSearch()
                                    showingSearch = false
                                    if (query.isNotBlank()) submitSearch()
                                },
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
                                openActor = ::openActor,
                            )
                        }
                        }
                      }
                      composable("actor/{id}?name={name}&avatar={avatar}&alternate={alternate}&fields={fields}",
                          arguments = listOf("name", "avatar", "alternate", "fields").map {
                              navArgument(it) { defaultValue = "" }
                          }) { actorEntry ->
                          val args = actorEntry.arguments
                          val actor = args?.read {
                              ActorRef(getString("id"), getString("name"), getString("avatar"), getString("alternate"),
                                  profileFields = runCatching {
                                      Json.decodeFromString<List<ActorProfileField>>(getString("fields"))
                                  }.getOrDefault(emptyList()))
                          } ?: ActorRef("", "")
                          NavigationDestinationContent(padding.calculateTopPadding()) {
                          ActorScreen(actor, model, back = { navigation.popBackStack() },
                              play = { item -> model.match(item) { navigation.navigate("player") } })
                          }
                      }
                      composable("browse") {
                        NavigationDestinationContent(padding.calculateTopPadding()) {
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
                            // Navigation restores its filters independently of AppState. Align
                            // them even when another filter already has a loaded page; the
                            // same-selection guard preserves an unchanged list without reloads.
                            LaunchedEffect(current) { model.loadBrowse(current) }
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                SearchEntry { openSearch() }
                                BrowseFilterPanel(current, ::update)
                                TopTabPager(BrowseTypeOptions.indexOfFirst { it.first == current.type }, BrowseTypeOptions.size, onSelect = { index ->
                                        update(current.copy(type = BrowseTypeOptions[index].first))
                                    }) { index ->
                                    val pageFilters = current.copy(type = BrowseTypeOptions[index].first)
                                    // The selected page always observes the mutable browse channel.
                                    // A remembered neighbor flow can otherwise survive promotion
                                    // to active and miss pagination/resource-check updates.
                                    val page by (if (pageFilters == current) model.browse
                                        else remember(model, pageFilters, current) {
                                            model.browsePage(pageFilters)
                                        }).collectCatalogPageState()
                                    CatalogContent(page, model, { model.loadBrowse(pageFilters, refresh = true) }, { model.loadBrowse(pageFilters, true) },
                                        { item -> model.match(item, page.items) { navigation.navigate("player") } },
                                        active = pageFilters == current,
                                        recent = homeHistoryFor(themeMode.tabOrder, "browse", history, index),
                                        onResume = ::resumeHome)
                                }
                            }
                        }
                        }
                        }
                      }
                      composable("hot") {
                        NavigationDestinationContent(padding.calculateTopPadding()) {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        val state by model.hot.collectAsState()
                        var selected by rememberSaveable { mutableStateOf(model.hotType) }
                        LaunchedEffect(Unit) { if (state.page == null && !state.loading) model.loadHot(selected) }
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            SearchEntry { openSearch() }
                            HomeCategories(selected, HotTypeOptions) { selected = it; model.loadHot(it) }
                            TopTabPager(HotTypeOptions.indexOfFirst { it.first == selected }, HotTypeOptions.size, onSelect = { index ->
                                    selected = HotTypeOptions[index].first
                                    model.loadHot(selected)
                                }) { index ->
                                val type = HotTypeOptions[index].first
                                val page by remember(model, type, selected) { model.hotPage(type) }.collectCatalogPageState()
                                CatalogContent(page, model, { model.loadHot(type, refresh = true) }, { model.loadHot(type, more = true) },
                                    { item -> model.match(item, page.items) { navigation.navigate("player") } },
                                    showHeading = false, active = type == selected, showLoadedCount = false,
                                    recent = homeHistoryFor(themeMode.tabOrder, "hot", history, index),
                                    onResume = ::resumeHome)
                            }
                        }
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
                          NavigationDestinationContent(padding.calculateTopPadding()) {
                          dev.pandavideo.ui.profile.DownloadsScreen(model,
                              back = { navigation.popBackStack() },
                              openPlayer = { navigation.navigate("player") })
                          }
                      }
                      composable("settings") {
                          NavigationDestinationContent(padding.calculateTopPadding()) {
                          SettingsScreen(model) { navigation.popBackStack() }
                          }
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
