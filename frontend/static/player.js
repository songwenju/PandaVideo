const params = new URLSearchParams(location.search);
const videoStage = document.querySelector("#videoStage");
const playerRoot = document.querySelector("#dplayer");
const message = document.querySelector("#playerMessage");
const statusText = document.querySelector("#playerStatus");
const hint = document.querySelector("#watchHint");
const retry = document.querySelector("#retryPlay");
const title = document.querySelector("#videoTitle");
const sourceName = document.querySelector("#sourceName");
const episodeList = document.querySelector("#episodeList");
const lineTabs = document.querySelector("#lineTabs");
const previous = document.querySelector("#previousEpisode");
const next = document.querySelector("#nextEpisode");
let hls = null;
let data = null;
let sequence = 0;
let controller = null;
let selectedLine = null;
let wantedEpisode = params.get("episode") || "";
let currentIndex = -1;
let currentLine = [];
let mediaReady = false;
let searchRequired = false;
let rotationMode = null;
let rotationPending = false;

// Match the shared web/native theme while retaining the existing playback engine.
const player = new DPlayer({
  container: playerRoot,
  theme: "#FF6740",
  lang: "zh-cn",
  autoplay: false,
  screenshot: false,
  contextmenu: [],
  video: {
    url: "",
    type: "normal",
    customType: {
      nasHls(media) {
        const ticket = sequence;
        const instance = new Hls({ enableWorker: false, maxBufferLength: 30 });
        hls = instance;
        instance.on(Hls.Events.ERROR, (_, event) => {
          if (ticket === sequence && event.fatal) {
            instance.destroy();
            failure("视频加载失败，资源可能暂时不可用");
          }
        });
        instance.on(Hls.Events.MANIFEST_PARSED, () => startPlayback(ticket));
        instance.loadSource(data.url);
        instance.attachMedia(media);
      },
    },
  },
});
const video = player.video;
// Keyboard navigation must activate shortcuts just like a pointer click does.
playerRoot.addEventListener("focusin", () => { player.focus = true; });
playerRoot.addEventListener("focusout", event => {
  if (!playerRoot.contains(event.relatedTarget)) player.focus = false;
});
// DPlayer's 100 ms clock polling mistakes coarse media timestamps for stalls.
// Keep its other timers, and drive buffering feedback from media events instead.
const enableTimer = player.timer.enable.bind(player.timer);
player.timer.enable = type => { if (type !== "loading") enableTimer(type); };
player.timer.disable("loading");
let bufferingTimer = null;
function clearBuffering() {
  clearTimeout(bufferingTimer);
  bufferingTimer = null;
  playerRoot.classList.remove("dplayer-loading");
}
function showBuffering() {
  if (video.paused || video.ended || bufferingTimer !== null) return;
  bufferingTimer = setTimeout(() => {
    bufferingTimer = null;
    if (!video.paused && !video.ended) playerRoot.classList.add("dplayer-loading");
  }, 500);
}
video.addEventListener("waiting", showBuffering);
video.addEventListener("stalled", () => {
  if (video.readyState < HTMLMediaElement.HAVE_FUTURE_DATA) showBuffering();
});
for (const event of ["playing", "canplay", "pause", "ended", "emptied", "error"]) {
  video.addEventListener(event, clearBuffering);
}
video.addEventListener("timeupdate", () => {
  if (!video.seeking && video.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) clearBuffering();
});
// Some tablets request a desktop user agent. Touch behavior follows the input
// device rather than DPlayer's user-agent-based mobile detection.
playerRoot.addEventListener("mousemove", event => {
  if (matchMedia("(hover: none), (pointer: coarse)").matches) {
    event.stopImmediatePropagation();
  }
}, true);
playerRoot.addEventListener("click", event => {
  if (!matchMedia("(hover: none), (pointer: coarse)").matches) return;
  if (event.target.closest(".dplayer-video-wrap, .dplayer-controller-mask")) {
    event.stopImmediatePropagation();
    if (player.controller.isShow()) player.controller.hide();
    else player.controller.setAutoHide();
  }
}, true);
video.id = "video";
video.setAttribute("playsinline", "");
video.setAttribute("aria-label", "视频播放");
video.tabIndex = 0;
const rotateButton = document.createElement("button");
rotateButton.id = "videoRotate";
rotateButton.type = "button";
rotateButton.className = "dplayer-icon video-rotate";
rotateButton.textContent = "↻";
rotateButton.hidden = true;
rotateButton.addEventListener("click", toggleRotation);
playerRoot.querySelector(".dplayer-icons-right").prepend(rotateButton);

title.textContent = params.get("title") || "视频播放";
const searchPlayback = document.querySelector("#searchPlayback");
const downloadSearch = document.querySelector("#downloadSearch");
function syncSearchLinks() {
  const href = `/search.html?${new URLSearchParams({ query: title.textContent })}`;
  searchPlayback.href = href;
  downloadSearch.href = href;
}
syncSearchLinks();
function updateSourceLink(source, page) {
  sourceName.textContent = source || "";
  sourceName.removeAttribute("href");
  sourceName.removeAttribute("title");
  sourceName.removeAttribute("aria-label");
  if (source !== "阳光电影") return;
  try {
    const url = new URL(page);
    if (url.origin !== "https://huolot.com"
        || !/^\/vod(?:detail\/\d+|play\/\d+-\d+-\d+)\.html$/.test(url.pathname)
        || url.username || url.password || url.search || url.hash) return;
    sourceName.href = url.href;
    sourceName.title = url.href;
    sourceName.setAttribute("aria-label", `在阳光电影打开《${title.textContent}》`);
  } catch (error) { /* Leave other sources as plain text. */ }
}
if (params.get("page")) updateSourceLink("阳光电影", params.get("page"));
function showMessage(text, failed = false) {
  statusText.textContent = text;
  message.hidden = false;
  retry.hidden = !failed;
}
function failure(text, expired = false) {
  searchRequired = expired;
  mediaReady = false;
  player.pause();
  retry.textContent = expired ? "重新搜索" : "重新加载";
  showMessage(text, true);
  searchPlayback.hidden = false;
  hint.textContent = expired
    ? "点击“重新搜索”获取这部影片的新播放入口。"
    : "可以重新加载，或在选集区域尝试其他播放线路。";
  hint.classList.add("error");
}
function destroyMedia() {
  clearBuffering();
  mediaReady = false;
  player.pause();
  if (hls) hls.destroy();
  hls = null;
  video.removeAttribute("src");
  video.load();
}
function renderEpisodes() {
  const episodes = data.episodes || [];
  const active = episodes.find(episode => episode.path === data.selected);
  const lines = [...new Set(episodes.map(episode => episode.line))];
  if (!lines.includes(selectedLine)) selectedLine = active?.line || lines[0];
  currentLine = episodes.filter(episode => episode.line === active?.line);
  currentIndex = currentLine.findIndex(episode => episode.path === data.selected);
  previous.disabled = currentIndex <= 0;
  next.disabled = currentIndex < 0 || currentIndex >= currentLine.length - 1;
  document.querySelector("#currentEpisode").textContent = active?.label || "视频播放";
  document.querySelector("#episodeCount").textContent = episodes.length
    ? `${episodes.length} 集 / ${lines.length} 条线路` : "单个视频";
  lineTabs.replaceChildren(...lines.map((line, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = lines.length > 1 ? `线路 ${index + 1}` : "云播资源";
    button.setAttribute("aria-pressed", String(line === selectedLine));
    button.addEventListener("click", () => { selectedLine = line; renderEpisodes(); });
    return button;
  }));
  episodeList.replaceChildren(...episodes.filter(episode => episode.line === selectedLine).map(episode => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = episode.label;
    button.setAttribute("aria-pressed", String(episode.path === data.selected));
    button.addEventListener("click", () => loadEpisode(episode.path));
    return button;
  }));
  if (!episodes.length) {
    const text = document.createElement("p");
    text.textContent = "当前资源为单个视频。";
    episodeList.append(text);
  }
}
async function startPlayback(ticket) {
  if (ticket !== sequence) return;
  mediaReady = true;
  try {
    await video.play();
    if (ticket === sequence) hint.textContent = "支持暂停、拖动进度、音量调节和全屏观看。";
  } catch (error) {
    if (ticket !== sequence || error.name === "AbortError") return;
    if (error.name !== "NotAllowedError") {
      failure("视频无法播放，请重新加载或切换播放线路");
      return;
    }
    // Browsers can block audible autoplay after navigation; muted autoplay is allowed.
    video.muted = true;
    try {
      await video.play();
      if (ticket === sequence) {
        player.notice("已静音自动播放，点击音量开启声音");
        hint.textContent = "已静音自动播放，可通过音量按钮开启声音。";
      }
    } catch (mutedError) {
      if (ticket !== sequence || mutedError.name === "AbortError") return;
      message.hidden = true;
      player.controller.show();
      hint.textContent = "视频已就绪，点击播放按钮开始观看。";
    }
  }
}
async function loadEpisode(episode = wantedEpisode) {
  wantedEpisode = episode;
  searchRequired = false;
  retry.textContent = "重新加载";
  const ticket = ++sequence;
  controller?.abort();
  controller = new AbortController();
  destroyMedia();
  hint.classList.remove("error");
  hint.textContent = "正在连接视频资源…";
  showMessage("正在读取播放信息…");
  searchPlayback.hidden = true;
  previous.disabled = next.disabled = true;
  const query = new URLSearchParams(params);
  if (query.get("page")) {
    query.delete("token");
    params.delete("token");
  }
  if (episode) query.set("episode", episode);
  else query.delete("episode");
  try {
    // Resolve only after entering details, so the grid stays immediately interactive.
    if (query.has("subject") && !query.has("page") && !query.has("token")) {
      showMessage("正在查找播放资源…");
      const resolution = await fetch("/api/playback/resolve", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: query.get("title"), url: query.get("subject"), year: query.get("year") || "" }),
        signal: controller.signal,
      });
      const match = await resolution.json();
      if (ticket !== sequence) return;
      if (!resolution.ok) throw new Error(match.error || "播放资源暂时无法读取");
      if (!match.playable || !match.page_url) throw new Error("暂未匹配到播放资源，可以重试或搜索其他资源");
      query.set("page", match.page_url);
      params.set("page", match.page_url);
    }
    const response = await fetch(`/api/player?${query}`, { signal: controller.signal });
    const payload = await response.json();
    if (ticket !== sequence) return;
    if (!response.ok) {
      const error = new Error(payload.error || "读取播放信息失败");
      error.status = response.status;
      throw error;
    }
    data = payload;
    data.episodes ||= [];
    title.textContent = data.title;
    document.title = `${data.title} · 熊猫视频`;
    syncSearchLinks();
    updateSourceLink(data.source, data.page_url);
    if (data.episodes.length) {
      params.delete("token");
      params.set("page", data.page_url);
      params.set("title", data.title);
      params.set("episode", data.selected);
      wantedEpisode = data.selected;
      history.replaceState(null, "", `/player.html?${params}`);
    }
    renderEpisodes();
    showMessage("正在加载视频…");
    let type = "normal";
    if (data.kind === "hls" && !video.canPlayType("application/vnd.apple.mpegurl")) {
      if (!window.Hls?.isSupported()) throw new Error("当前浏览器不支持该视频，请尝试 Safari 或新版 Chrome");
      type = "nasHls";
    }
    video.addEventListener("loadedmetadata", () => startPlayback(ticket), { once: true });
    player.switchVideo({ url: data.url, type });
    video.load();
  } catch (error) {
    if (error.name !== "AbortError" && ticket === sequence) failure(error.message, error.status === 410);
  }
}
video.addEventListener("playing", () => {
  if (mediaReady) {
    message.hidden = true;
    retry.hidden = true;
    player.controller.setAutoHide();
  }
});
video.addEventListener("error", () => {
  if (video.getAttribute("src") || mediaReady) failure("视频无法播放，可能是链接失效或格式不受支持");
});
video.addEventListener("ended", () => {
  if (document.querySelector("#autoNext").checked && !next.disabled) next.click();
});
previous.addEventListener("click", () => {
  selectedLine = currentLine[currentIndex - 1].line;
  loadEpisode(currentLine[currentIndex - 1].path);
});
next.addEventListener("click", () => {
  selectedLine = currentLine[currentIndex + 1].line;
  loadEpisode(currentLine[currentIndex + 1].path);
});
retry.addEventListener("click", () => {
  if (searchRequired) {
    const query = params.get("title")?.trim();
    location.assign(query ? `/search.html?${new URLSearchParams({ query })}` : "/search.html");
  } else loadEpisode();
});
document.querySelector("#playbackRate").addEventListener("change", event => {
  player.speed(Number(event.target.value));
});
function isPlayerFullscreen() {
  const native = document.fullscreenElement || document.webkitFullscreenElement;
  return native === videoStage || native === playerRoot
    || player.fullScreen.isFullScreen("web");
}
async function toggleFullscreen() {
  const native = document.fullscreenElement || document.webkitFullscreenElement;
  if (player.fullScreen.isFullScreen("web")) {
    cancelFullscreen("web");
    return;
  }
  try {
    if (native) {
      const exit = document.exitFullscreen || document.webkitExitFullscreen || document.webkitCancelFullScreen;
      if (exit) await exit.call(document);
    } else {
      const request = videoStage.requestFullscreen || videoStage.webkitRequestFullscreen;
      if (request) await request.call(videoStage);
      else requestFullscreen("web");
    }
  } catch (error) {
    // iOS and embedded browsers may reject DOM fullscreen. Web fullscreen keeps
    // the custom controls available, unlike the system's native video player.
    if (!native) requestFullscreen("web");
  }
}
function syncRotation() {
  const fullscreen = isPlayerFullscreen();
  rotateButton.hidden = !fullscreen;
  videoStage.classList.toggle("is-page-fullscreen", Boolean(player.fullScreen.isFullScreen("web")));
  playerRoot.classList.toggle("player-screen-full", Boolean(fullscreen));
  rotateButton.disabled = rotationPending;
  rotateButton.setAttribute("aria-pressed", String(Boolean(rotationMode)));
  rotateButton.setAttribute("aria-label", rotationMode ? "恢复屏幕" : "旋转屏幕");
  rotateButton.title = rotationMode ? "恢复屏幕" : "旋转屏幕";
  rotateButton.textContent = rotationMode ? "恢复屏幕" : "旋转屏幕";
  playerRoot.classList.toggle("is-rotated", rotationMode === "page");
}
function exitRotation() {
  if (rotationMode === "native") {
    try { screen.orientation?.unlock?.(); } catch (error) { /* Browser may have unlocked. */ }
  }
  rotationMode = null;
  syncRotation();
}
async function toggleRotation() {
  if (rotationPending || !isPlayerFullscreen()) return;
  if (rotationMode) { exitRotation(); return; }
  rotationPending = true;
  syncRotation();
  try {
    if (screen.orientation?.lock) {
      try {
        await screen.orientation.lock("landscape");
        if (isPlayerFullscreen()) rotationMode = "native";
        else screen.orientation.unlock();
      } catch (error) { /* Desktop browsers use a visual rotation inside fullscreen. */ }
    }
    if (!rotationMode && isPlayerFullscreen()) rotationMode = "page";
  } finally {
    rotationPending = false;
    syncRotation();
  }
}
function fullscreenChanged() {
  if (!isPlayerFullscreen()) exitRotation();
  else syncRotation();
  player.controller.setAutoHide();
}
document.addEventListener("fullscreenchange", fullscreenChanged);
document.addEventListener("webkitfullscreenchange", fullscreenChanged);
player.on("webfullscreen", fullscreenChanged);
player.on("webfullscreen_cancel", fullscreenChanged);
// Keep DPlayer's browser-fullscreen button and the external shortcut in sync.
const requestFullscreen = player.fullScreen.request.bind(player.fullScreen);
const cancelFullscreen = player.fullScreen.cancel.bind(player.fullScreen);
player.fullScreen.request = type => type === "browser" ? toggleFullscreen() : requestFullscreen(type);
player.fullScreen.cancel = type => type === "browser" ? toggleFullscreen() : cancelFullscreen(type);
document.querySelector("#fullscreen").addEventListener("click", toggleFullscreen);
syncRotation();
window.addEventListener("pagehide", () => {
  if (player.fullScreen.isFullScreen("web")) cancelFullscreen("web");
  exitRotation();
  controller?.abort();
  destroyMedia();
  player.destroy();
});
loadEpisode();
