package dev.pandavideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.core.platform.PlatformServices
import dev.pandavideo.core.player.*
import dev.pandavideo.ui.common.NavigationIcons
import dev.pandavideo.ui.common.CompactActionButtonPadding
import dev.pandavideo.ui.common.playbackTime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one page-scoped casting session. Remote commands are serialized so a late poll cannot
 * overtake a seek or disconnect. Cleanup has its own bounded lifetime after the page disappears.
 */
internal class DlnaCasting(
    private val platform: PlatformServices, private val player: PlayerController,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val baseClient = platform.httpClient()
    private val client = DlnaClient(baseClient)
    private val commands = Mutex()
    private var discovery: Job? = null
    private var action: Job? = null
    private var poll: Job? = null
    private var generation = 0L
    private var closed = false
    private var castMediaId = 0L
    var picker by mutableStateOf(false)
    var searching by mutableStateOf(false)
        private set
    var devices by mutableStateOf<List<DlnaDevice>>(emptyList())
        private set
    var device by mutableStateOf<DlnaDevice?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun search(host: String? = null) {
        if (closed || busy) return
        if (host != null && !dlnaIpv4(host)) {
            error = "请输入电视的 IPv4 地址，例如 192.168.1.100"
            return
        }
        discovery?.cancel()
        searching = true
        error = null
        discovery = scope.launch {
            try {
                val locations = platform.discoverDlnaLocations(host)
                val semaphore = kotlinx.coroutines.sync.Semaphore(4)
                val found = linkedMapOf<String, DlnaDevice>()
                var failures = 0
                // Publish each usable renderer immediately. A dead descriptor used to hold
                // back every result until awaitAll completed (up to 16 batches of timeouts).
                // Keep previous results usable during a rescan, then replace them with this
                // scan's verified devices. Cancellation never clears a newer scan's results.
                withTimeoutOrNull(8_000) {
                    coroutineScope {
                        locations.distinct().take(64).map { location ->
                            launch {
                                semaphore.acquire()
                                try {
                                    try {
                                        client.device(location)?.let { renderer ->
                                            ensureActive()
                                            found[renderer.id] = renderer
                                            devices = (devices + renderer).distinctBy { it.id }
                                        }
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { failures++ }
                                } finally { semaphore.release() }
                            }
                        }.joinAll()
                    }
                }
                ensureActive()
                devices = found.values.toList()
                if (devices.isEmpty() && failures > 0)
                    error = "已收到设备响应，但无法读取投屏信息，请检查本地网络权限和电视投屏设置"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = (e as? DlnaException)?.message
                    ?: "搜索失败，请检查局域网连接，或填写电视 IP 搜索"
            }
            finally { if (currentCoroutineContext().isActive) searching = false }
        }
    }

    fun dismissPicker() {
        if (busy) return
        picker = false
        discovery?.cancel()
        searching = false
    }

    fun connect(target: DlnaDevice, source: MediaSource) {
        if (busy || closed) return
        val token = ++generation
        castMediaId = source.id
        busy = true
        error = null
        action = scope.launch {
            try {
                commands.withLock {
                    var attempted = false
                    try {
                        val start = player.state.value.position
                        attempted = true
                        client.setMedia(target, source)
                        client.play(target)
                        // Seek is optional: many domestic renderers accept URI/Play but not REL_TIME.
                        // A rejected seek must not falsely report that an otherwise valid cast failed.
                        var initialPosition = start
                        if (start >= 1_000) {
                            try { client.seek(target, start) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { initialPosition = 0; error = "电视不支持同步进度，将从头播放" }
                        }
                        ensureActive()
                        if (token != generation || player.state.value.mediaId != source.id)
                            throw CancellationException("Media changed")
                        player.pause()
                        device = target
                        playing = true
                        position = initialPosition
                        duration = player.state.value.duration
                        picker = false
                        discovery?.cancel()
                        searching = false
                    } catch (e: Exception) {
                        // Complete Stop before releasing the command lock. Otherwise a cancelled
                        // handoff could stop a newer cast to the same renderer after it starts.
                        if (attempted) cleanup(target)
                        throw e
                    }
                }
                beginPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? DlnaException)?.message ?: "投屏失败，请检查电视连接或更换播放线路"
            } finally { if (token == generation) busy = false }
        }
    }

    private fun beginPolling() {
        poll?.cancel()
        poll = scope.launch {
            var failures = 0
            while (isActive && device != null) {
                delay(2_000)
                try {
                    commands.withLock {
                        val target = device ?: return@withLock
                        val time = client.position(target)
                        position = time.position
                        duration = time.duration
                        playing = client.playing(target)
                    }
                    if (failures >= 3) error = null
                    failures = 0
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (++failures >= 3) error = "暂时无法读取电视状态，可重试操作或结束投屏"
                }
            }
        }
    }

    fun togglePlay() = control { target ->
        if (playing) client.pause(target) else client.play(target)
        playing = !playing
    }

    fun seek(value: Long) = control { target ->
        client.seek(target, value)
        position = value
    }

    private fun control(block: suspend (DlnaDevice) -> Unit) {
        if (busy || closed || device == null) return
        busy = true
        error = null
        val token = generation
        action = scope.launch {
            try { commands.withLock { device?.let { block(it) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e as? DlnaException)?.message ?: "电视连接异常，请重试" }
            finally { if (token == generation) busy = false }
        }
    }

    fun disconnect() = control { target ->
        client.stop(target)
        poll?.cancel()
        device = null
        playing = false
        // Local playback remains paused, allowing an explicit decision to resume on the phone.
        if (player.state.value.mediaId == castMediaId) player.seekTo(position)
    }

    fun mediaChanged(id: Long) {
        if (castMediaId == 0L || castMediaId == id) return
        ++generation
        action?.cancel()
        poll?.cancel()
        val target = device
        device = null
        busy = false
        playing = false
        castMediaId = 0
        if (target != null) scope.launch { commands.withLock { cleanup(target) } }
    }

    private suspend fun cleanup(target: DlnaDevice) {
        withContext(NonCancellable) {
            withTimeoutOrNull(5_500) {
                try { client.stop(target) } catch (_: Exception) { /* Teardown is best effort. */ }
            }
        }
    }

    fun close() {
        closed = true
        ++generation
        discovery?.cancel()
        action?.cancel()
        poll?.cancel()
        val target = device
        device = null
        scope.launch {
            try { commands.withLock { if (target != null) cleanup(target) } }
            finally { client.close(); baseClient.close(); scope.cancel() }
        }
    }
}

@Composable
internal fun DlnaDevicePicker(casting: DlnaCasting, source: MediaSource?, platform: PlatformServices) {
    if (!casting.picker) return
    var ip by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = casting::dismissPicker,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(casting::dismissPicker, enabled = !casting.busy) {
                    Icon(NavigationIcons.Back, "返回")
                }
                Text(if (casting.searching) "正在搜索投屏设备…" else "选择投屏设备",
                    Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton({ casting.search() }, enabled = !casting.busy && !casting.searching) {
                    Icon(NavigationIcons.Refresh, "重新搜索", tint = colors.primary)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("没有找到想投的设备？", Modifier.align(Alignment.CenterHorizontally),
                    color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("1. 检查电视 / 盒子是否已开机", fontSize = 13.sp)
                Text("2. 请确认电视或盒子与手机连接同一 Wi-Fi", fontSize = 13.sp)
                if (platform.nativeCastingSupported) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("3. iOS 用户请开启", fontSize = 13.sp)
                        TextButton(platform::openAppSettings, enabled = !casting.busy,
                            contentPadding = CompactActionButtonPadding) {
                            Text("本地网络权限", fontSize = 13.sp, color = colors.primary)
                            Spacer(Modifier.width(6.dp))
                            Icon(NavigationIcons.Chevron, null, Modifier.size(14.dp), tint = colors.primary)
                        }
                    }
                } else {
                    Text("3. 请开启电视或盒子的投屏功能", fontSize = 13.sp)
                }
                if (casting.searching) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
                    Text("正在搜索投屏设备…", Modifier.padding(start = 8.dp), fontSize = 13.sp)
                }
                if (platform.nativeCastingSupported && !casting.busy) {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("AirPlay 投屏", color = colors.onSurface, fontSize = 14.sp)
                                Text("点击右侧图标搜索 AirPlay 设备", fontSize = 11.sp)
                            }
                            platform.CastingButton(Modifier.size(44.dp), colors.primary)
                        }
                    }
                }
                Text("DLNA 投屏", color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("请在电视或盒子上开启 DLNA 投屏；AirPlay 和 Chromecast 设备不显示在此列表中。",
                    fontSize = 11.sp)
                Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant) {
                    Column(Modifier.fillMaxWidth()) {
                        LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(casting.devices, key = { it.id }) { device ->
                                Row(Modifier.fillMaxWidth()
                                    .clickable(enabled = !casting.busy && source != null) {
                                        source?.let { casting.connect(device, it) }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(NavigationIcons.Cast, null, Modifier.size(22.dp), tint = colors.primary)
                                    Text(device.name, Modifier.padding(start = 10.dp).weight(1f),
                                        color = colors.onSurface, maxLines = 2, fontSize = 14.sp)
                                    Icon(NavigationIcons.Chevron, null, Modifier.size(16.dp),
                                        tint = colors.onSurfaceVariant)
                                }
                            }
                        }
                        if (!casting.searching && casting.devices.isEmpty()) {
                            Text("暂未发现支持 DLNA 的设备，可开启电视投屏后重试，或按电视 IP 搜索。",
                                Modifier.padding(12.dp), fontSize = 13.sp)
                        }
                    }
                }
                if (casting.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary)
                casting.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                TextButton({ manual = !manual }, enabled = !casting.busy) { Text("按电视 IP 搜索") }
                if (manual) {
                    OutlinedTextField(ip, { ip = it }, singleLine = true, label = { Text("电视 IP 地址") },
                        supportingText = { Text("可在电视「网络设置」中查看") }, modifier = Modifier.fillMaxWidth())
                    TextButton({ casting.search(ip.trim()) }, enabled = !casting.busy && !casting.searching) { Text("搜索此电视") }
                }
                Text("电视需支持当前视频格式；部分线路可能因电视解码或站点访问限制无法播放。", fontSize = 11.sp)
            }
        },
        confirmButton = {},
    )
}

@Composable
internal fun DlnaRemoteFrame(casting: DlnaCasting, title: String, modifier: Modifier, back: () -> Unit) {
    var scrub by remember(casting.device) { mutableStateOf<Float?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.background(Color.Black)) {
        Row(Modifier.align(Alignment.Center).padding(start = 48.dp, end = 16.dp, bottom = 52.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(NavigationIcons.Cast, null, Modifier.size(28.dp), tint = accent)
            Column(Modifier.padding(start = 12.dp)) {
                Text(casting.device?.name.orEmpty(), color = Color.White, fontSize = 15.sp, maxLines = 1)
                Text(title, color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                casting.error?.let { Text(it, color = accent, fontSize = 11.sp, maxLines = 2) }
            }
        }
        IconButton(back, Modifier.align(Alignment.TopStart)) {
            Icon(NavigationIcons.Back, "返回", tint = Color.White)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(playbackTime(scrub?.toLong() ?: casting.position), color = Color.White, fontSize = 11.sp)
                Slider(
                    value = scrub ?: casting.position.coerceIn(0, casting.duration.coerceAtLeast(1)).toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { casting.seek(it.toLong()) }; scrub = null },
                    valueRange = 0f..casting.duration.coerceAtLeast(1).toFloat(),
                    enabled = casting.duration > 0 && !casting.busy,
                    modifier = Modifier.weight(1f).height(32.dp),
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                )
                Text(playbackTime(casting.duration), color = Color.White, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(casting::togglePlay, enabled = !casting.busy) {
                    Icon(if (casting.playing) NavigationIcons.Pause else NavigationIcons.Play,
                        if (casting.playing) "暂停电视" else "播放电视", tint = Color.White)
                }
                Text(if (casting.playing) "电视播放中" else "电视已暂停", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(casting::disconnect, enabled = !casting.busy) { Text("结束投屏", color = accent) }
            }
        }
    }
}
