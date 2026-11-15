package dev.pandavideo.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.pandavideo.app.AppState
import dev.pandavideo.core.model.*
import dev.pandavideo.data.repository.ActorSource
import dev.pandavideo.ui.common.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Composable
private fun ActorAvatar(actor: ActorRef, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(context, actor.avatar) {
        ImageRequest.Builder(context).data(actor.avatar.takeIf(String::isNotBlank)).httpHeaders(
            NetworkHeaders.Builder().set("Referer", "https://www.iqiyi.com/")
                .set("User-Agent", "Mozilla/5.0").build(),
        ).build()
    }
    val fallbackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    // The placeholder has its own padded viewport: Crop scales painters to the whole avatar,
    // so reusing a navigation icon made its outline oversized and disproportionately heavy.
    val fallbackIcon = remember(fallbackColor) {
        ImageVector.Builder("ActorPlaceholder", 40.dp, 40.dp, 40f, 40f).apply {
            path(fill = null, stroke = SolidColor(fallbackColor), strokeLineWidth = 1.2f,
                strokeLineCap = StrokeCap.Round) {
                moveTo(23.7f, 16.2f)
                curveTo(23.7f, 18.24f, 22.04f, 19.9f, 20f, 19.9f)
                curveTo(17.96f, 19.9f, 16.3f, 18.24f, 16.3f, 16.2f)
                curveTo(16.3f, 14.16f, 17.96f, 12.5f, 20f, 12.5f)
                curveTo(22.04f, 12.5f, 23.7f, 14.16f, 23.7f, 16.2f)
                close()
                moveTo(13f, 27.6f)
                curveTo(13.7f, 23.8f, 16.5f, 22.2f, 20f, 22.2f)
                curveTo(23.5f, 22.2f, 26.3f, 23.8f, 27f, 27.6f)
            }
        }.build()
    }
    val fallback = rememberVectorPainter(fallbackIcon)
    AsyncImage(request, actor.name, modifier = modifier.clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
        imageLoader = LocalPosterImageLoader.current ?: coil3.SingletonImageLoader.get(context),
        placeholder = fallback, error = fallback, fallback = fallback, contentScale = ContentScale.Crop)
}

@Composable
internal fun ActorRail(actors: List<ActorRef>, open: (ActorRef) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(actors, key = { it.id }) { actor ->
            Column(Modifier.width(76.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { open(actor) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ActorAvatar(actor, Modifier.size(60.dp))
                Text(actor.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (actors.count { it.name == actor.name } > 1) Text(
                    actor.alternateName.ifBlank { "人物 ${actor.id}" }, fontSize = 10.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Resolves unambiguous source identities before enabling actor navigation. */
@Composable
internal fun CastRail(model: AppState, names: List<String>, subjectUrl: String, open: (ActorRef) -> Unit) {
    val cast = remember(names) { names.map(String::trim).filter(String::isNotBlank).distinct() }
    if (cast.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("演员", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cast, key = { it }) { name ->
                // Resolve identities as LazyRow composes visible/prefetched cards. Complete
                // casts can be large; offscreen names must not create a burst of source requests.
                val actor by produceState<ActorRef?>(null, name, subjectUrl) {
                    value = null
                    try { value = model.findCastActor(name) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* Keep the named card visible when lookup fails. */ }
                }
                Column(Modifier.width(76.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = actor != null, role = Role.Button) {
                    actor?.let(open)
                }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ActorAvatar(actor ?: ActorRef("", name), Modifier.size(60.dp))
                    Text(name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun ActorSearchResults(model: AppState, open: (ActorRef) -> Unit) {
    val state by model.actors.state.collectAsState()
    if (state.loading || state.error || state.result?.actors?.isNotEmpty() == true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("演员", fontWeight = FontWeight.Bold)
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) { SkeletonBox(Modifier.size(60.dp), rememberSkeletonAlpha(), 50) }
                }
                state.error -> Text("演员搜索暂时不可用，可重新搜索", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    ActorRail(state.result!!.actors, open)
                    if (state.result!!.stale) Text("显示缓存演员资料", fontSize = 12.sp)
                }
            }
        }
    }
}

private data class ActorPageState(
    val snapshot: ActorFilmography? = null, val loading: Boolean = true, val error: Boolean = false,
)

/** Profile views on the actor page; both render the same cached filmography snapshot. */
private const val ACTOR_ABOUT = "about"
private const val ACTOR_WORKS = "works"

@Composable
internal fun ActorScreen(
    actor: ActorRef, model: AppState, back: () -> Unit,
    play: (CatalogItem) -> Unit,
    compact: Boolean = false,
) {
    var attempt by remember { mutableStateOf(0) }
    var tab by rememberSaveable(actor.id) { mutableStateOf(ACTOR_ABOUT) }
    var kind by rememberSaveable(actor.id) { mutableStateOf("all") }
    var count by rememberSaveable(actor.id, kind) { mutableStateOf(20) }
    // Navigation carries source properties for immediate/offline rendering. Refresh by person ID
    // through an independent cache, including for routes restored from older app versions.
    val profile by produceState(actor to true, actor.id, attempt) {
        value = value.first to true
        try {
            val result = model.actorProfile(actor)
            ensureActive()
            value = result to false
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { value = value.first to false }
    }
    val state by produceState(ActorPageState(), actor.id, attempt) {
        value = value.copy(loading = true, error = false)
        try {
            val result = model.actorWorks(actor)
            ensureActive()
            value = ActorPageState(result, loading = false)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { value = value.copy(loading = false, error = true) }
    }
    val list = key(tab, kind) { rememberLazyListState() }
    val filtered = remember(state.snapshot, kind) {
        state.snapshot?.works.orEmpty().filter { kind == "all" || it.kind == kind }
    }
    val page = remember(filtered, count) { filtered.take(count) }
    var enriched by remember(actor.id, state.snapshot, attempt) { mutableStateOf(emptyMap<String, ActorWork>()) }
    var hidden by remember(actor.id, kind, state.snapshot, attempt) { mutableStateOf(emptySet<String>()) }
    var checking by remember(actor.id, kind, state.snapshot) { mutableStateOf(false) }
    var checkFailed by remember(actor.id, kind, state.snapshot, attempt) { mutableStateOf(false) }
    val visibleWorks = remember(page, enriched, hidden) {
        actorPlaybackWorks(page.map { enriched[it.id] ?: it }, hidden)
    }
    LaunchedEffect(page, kind, state.snapshot, attempt) {
        checking = true
        try {
            page.chunked(4).forEach { wave ->
                val results = coroutineScope {
                    wave.map { work -> async {
                        enriched[work.id] ?: try { model.actorCatalogWork(work) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { checkFailed = true; work }
                    } }.awaitAll()
                }
                val mapped = actorPlaybackWorks(results, emptySet()).map { it.catalog() }
                val visible = model.visibleActorWorks(mapped).map { it.url }.toSet()
                hidden = hidden + mapped.filter { it.url !in visible }.map { it.url }
                // Check each small wave before publishing it. Both persisted misses and fresh
                // misses use the translated catalog identity that the playback button opens.
                // A timeout remains Unknown; it never becomes a permanent "missing" record.
                model.inspectActorWorks(mapped) { missing -> hidden = hidden + missing.url }
                ensureActive()
                enriched = enriched + results.associateBy { it.id }
            }
        } finally { checking = false }
    }
    val works = state.snapshot?.works.orEmpty()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(bottom =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp)) {
            if (!compact) item { ActorHero(profile.first) }
            item { ActorProfileHeader(profile.first, works.size) }
            item { ActorTabs(tab, works.size) { tab = it } }
            if (tab == ACTOR_ABOUT) {
                item { ActorAbout(profile.first, profile.second) }
            } else {
                item {
                    Column(Modifier.padding(top = 12.dp)) {
                        ChoiceRow(listOf("all" to "全部", "tv" to "电视剧", "movie" to "电影"), kind) {
                            kind = it
                        }
                    }
                }
                if (state.loading || checking && visibleWorks.isEmpty()) {
                    items(2) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(2) {
                                Column(Modifier.weight(1f)) {
                                    SkeletonBox(Modifier.fillMaxWidth().aspectRatio(0.7f),
                                        rememberSkeletonAlpha(), 8)
                                    SkeletonBox(Modifier.padding(top = 8.dp)
                                        .fillMaxWidth(0.7f).height(14.dp), rememberSkeletonAlpha())
                                }
                            }
                        }
                    }
                }
                if (state.error) item {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("演员信息暂时无法加载")
                        TextButton({ attempt++ }) { Text("重试") }
                    }
                }
                if (!state.loading && !state.error && filtered.isEmpty()) item {
                    Text(when (kind) {
                        "movie" -> "暂无已收录的电影"; "tv" -> "暂无已收录的电视剧"
                        else -> "暂无已收录的参演作品"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                }
                if (!checking && page.isNotEmpty() && page.all { it.id in enriched } &&
                    visibleWorks.isEmpty()) item {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (checkFailed) "播放资源暂时无法检查，请重试"
                            else "暂未匹配到可播放的参演作品",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ attempt++ }) { Text("重试") }
                    }
                }
                items(visibleWorks.chunked(2), key = { it.first().id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { work ->
                            ActorWorkCard(work, Modifier.weight(1f)) { play(work.catalog()) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (count < filtered.size) item {
                    LaunchedEffect(filtered, count) { count += 20 }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        TextButton({ count += 20 }) { Text("加载更多") }
                    }
                }
            }
        }
        if (!compact) IconButton(back, Modifier.align(Alignment.TopStart).padding(8.dp).size(40.dp)
            .clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))) {
            Icon(NavigationIcons.Back, "返回", tint = Color.White)
        }
    }
}

/** Blurred portrait backdrop over a rounded sheet edge; no source branding is rendered. */
@Composable
private fun ActorHero(actor: ActorRef) {
    val context = LocalPlatformContext.current
    val request = remember(context, actor.avatar) {
        ImageRequest.Builder(context).data(actor.avatar.takeIf(String::isNotBlank)).httpHeaders(
            NetworkHeaders.Builder().set("Referer", "https://www.iqiyi.com/")
                .set("User-Agent", "Mozilla/5.0").build(),
        ).build()
    }
    Box(Modifier.fillMaxWidth().height(200.dp).clipToBounds()) {
        if (actor.avatar.isNotBlank()) {
            AsyncImage(request, null, modifier = Modifier.fillMaxSize().blur(32.dp),
                imageLoader = LocalPosterImageLoader.current
                    ?: coil3.SingletonImageLoader.get(context),
                contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                listOf(Accent.copy(alpha = 0.55f), Accent.copy(alpha = 0.18f)))))
        }
        // A soft top shade keeps the floating back button legible over bright stills.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.04f)))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(20.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.background))
    }
}

@Composable
private fun ActorProfileHeader(actor: ActorRef, works: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ActorAvatar(actor, Modifier.size(84.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(actor.name, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val occupation = actor.profileFields.firstOrNull { it.id == "occupation" }?.value
                if (!occupation.isNullOrBlank()) Text(occupation,
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(4.dp))
                        .background(Accent.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                if (works > 0) Text("参演 $works 部", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActorTabs(value: String, works: Int, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            ActorTab("关于TA", value == ACTOR_ABOUT, Modifier.weight(1f)) { onSelect(ACTOR_ABOUT) }
            ActorTab(if (works > 0) "影视作品 $works" else "影视作品",
                value == ACTOR_WORKS, Modifier.weight(1f)) { onSelect(ACTOR_WORKS) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun ActorTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp))
        Box(Modifier.fillMaxWidth().height(2.dp)
            .background(if (selected) Accent else Color.Transparent))
    }
}

/** Displays every nonempty source property with its original label and ordering. */
@Composable
private fun ActorAbout(actor: ActorRef, loading: Boolean) {
    val rows = actor.visibleProfileFields()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        when {
            loading && rows.isEmpty() ->
                SkeletonBox(Modifier.fillMaxWidth().height(112.dp), rememberSkeletonAlpha(), 12)
            rows.isEmpty() -> Text("暂无更多介绍", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { field ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(field.label, Modifier.width(76.dp), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(field.displayValue(), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 21.sp,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Opens the verified work by tapping its poster, with feedback clipped to the artwork. */
@Composable
private fun ActorWorkCard(work: ActorWork, modifier: Modifier, play: () -> Unit) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "播放${work.title}", onClick = play)) {
            Poster(ActorSource.externalPoster(work.cover), work.title, Modifier.fillMaxSize())
        }
        Text(work.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        Text(listOf(if (work.kind == "tv") "电视剧" else "电影", work.year,
            if (work.voice) "配音" else "").filter(String::isNotBlank).joinToString(" · "),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
    }
}
