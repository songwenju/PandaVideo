const searchForm = document.querySelector("#searchForm");
const searchButton = document.querySelector("#searchButton");
const queryInput = document.querySelector("#query");
const searchTypes = [...document.querySelectorAll("[data-search-type]")];
const mediaTypeLabels = { all: "全部", movie: "电影", tv: "电视剧" };
const initialMediaType = new URLSearchParams(location.search).get("media_type");
let mediaType = ["all", "movie", "tv"].includes(initialMediaType) ? initialMediaType : "all";
let lastSearchQuery = "";

function syncSearchType() {
  searchTypes.forEach(button => {
    button.setAttribute("aria-pressed", String(button.dataset.searchType === mediaType));
  });
  if (!queryInput) return;
  queryInput.placeholder = mediaType === "tv"
    ? "例如：庆余、三体，输入部分剧名即可搜索"
    : mediaType === "movie"
      ? "例如：星际、流浪地球，输入部分片名即可搜索"
      : "例如：星际、庆余、三体，支持输入部分片名";
}
syncSearchType();
searchTypes.forEach(button => button.addEventListener("click", () => {
  if (mediaType === button.dataset.searchType) return;
  mediaType = button.dataset.searchType;
  syncSearchType();
  if (lastSearchQuery && queryInput.value.trim() === lastSearchQuery) searchForm.requestSubmit();
}));
const resultSection = document.querySelector(".result-section");
const resultList = document.querySelector("#resultList");
const emptyState = document.querySelector("#emptyState");
const notice = document.querySelector("#notice");
const intentText = document.querySelector("#intentText");
const statusRow = document.querySelector("#statusRow");
const mountNasButton = document.querySelector("#mountNasButton");
const magnetForm = document.querySelector("#magnetForm");
const magnetUrlInput = document.querySelector("#magnetUrl");
const xunleiSubmitButton = document.querySelector("#xunleiSubmit");
const magnetNotice = document.querySelector("#magnetNotice");
const settingsDialog = document.querySelector("#settingsDialog");
document.querySelector("#settingsButton")?.addEventListener("click", () => {
  settingsDialog.showModal();
  loadStatus();
});
document.querySelector("#closeSettings")?.addEventListener("click", () => settingsDialog.close());
settingsDialog?.addEventListener("click", (event) => {
  if (event.target !== settingsDialog) return;
  const bounds = settingsDialog.getBoundingClientRect();
  if (event.clientX < bounds.left || event.clientX > bounds.right
      || event.clientY < bounds.top || event.clientY > bounds.bottom) settingsDialog.close();
});

function refreshIcons() {
  if (window.lucide) {
    window.lucide.createIcons();
  }
}

function statusChip(label, ready) {
  const chip = document.createElement("span");
  chip.className = `status ${ready ? "ready" : "pending"}`;
  chip.textContent = label;
  return chip;
}

function setMountButtonState(ready, configured = true) {
  mountNasButton.disabled = ready || !configured;
  mountNasButton.innerHTML = ready
    ? '<i data-lucide="check" aria-hidden="true"></i><span>NAS 已挂载</span>'
    : configured
      ? '<i data-lucide="hard-drive-download" aria-hidden="true"></i><span>挂载 NAS</span>'
      : '<i data-lucide="hard-drive" aria-hidden="true"></i><span>NAS 未配置</span>';
}

async function loadStatus() {
  if (!statusRow || !mountNasButton) return;
  try {
    const response = await fetch("/api/status");
    const data = await response.json();
    // Keep status readable while an existing server finishes upgrading.
    const nas = data.nas || data.aria2;
    const destination = document.querySelector("#nasDestination");
    if (destination) destination.textContent = `NAS 目录：${nas.download_dir || "尚未配置"}`;
    setMountButtonState(
      nas.directory_ready,
      nas.mount_configured,
    );
    mountNasButton.hidden = nas.mount_supported === false;
    statusRow.replaceChildren(
      statusChip(
        nas.directory_ready ? "NAS 目录可用" : "NAS 目录不可用",
        nas.directory_ready,
      ),
      statusChip(
        data.xunlei?.browser_launch ? "支持当前设备迅雷" : data.xunlei?.installed ? "迅雷已安装" : "迅雷未安装",
        Boolean(data.xunlei?.installed || data.xunlei?.browser_launch),
      ),
      statusChip(
        data.xunlei?.mcp_configured ? "迅雷 MCP 已配置" : "迅雷 MCP 未配置",
        Boolean(data.xunlei?.mcp_configured),
      ),
    );
    refreshIcons();
  } catch {
    statusRow.replaceChildren(statusChip("服务异常", false));
    setMountButtonState(false, false);
    refreshIcons();
  }
}

function renderSourceResults(sources) {
  const list = document.querySelector("#sourceResults");
  list.replaceChildren();
  for (const source of sources || []) {
    if (["Pexels", "Pixabay"].includes(source.name)) continue;
    const chip = document.createElement("span");
    chip.className = `status ${source.status === "ok" ? "ready" : "pending"}`;
    chip.textContent = `${source.name} · ${source.status === "error" ? "暂时无法读取" : `${source.count} 条${source.status === "partial" ? "（部分读取）" : ""}`}`;
    list.append(chip);
  }
}

mountNasButton?.addEventListener("click", async () => {
  const original = mountNasButton.innerHTML;
  mountNasButton.disabled = true;
  mountNasButton.innerHTML =
    '<i data-lucide="loader-circle" aria-hidden="true"></i><span>挂载中...</span>';
  refreshIcons();
  try {
    const response = await fetch("/api/nas/mount", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "NAS 挂载失败");
    await loadStatus();
  } catch (error) {
    showMagnetNotice(error.message, true);
    mountNasButton.innerHTML = original;
    mountNasButton.disabled = false;
    refreshIcons();
  }
});

function showNotice(message, isError = false) {
  notice.textContent = message;
  notice.classList.toggle("error", isError);
  notice.classList.remove("hidden");
}

function hideNotice() {
  notice.classList.add("hidden");
  notice.textContent = "";
}

function showMagnetNotice(message, isError = false) {
  magnetNotice.textContent = message;
  magnetNotice.classList.toggle("error", isError);
  magnetNotice.classList.remove("hidden");
}

function hideMagnetNotice() {
  magnetNotice.classList.add("hidden");
  magnetNotice.textContent = "";
}

function setLoading(loading) {
  resultSection.setAttribute("aria-busy", String(loading));
  searchButton.disabled = loading;
  queryInput.disabled = loading;
  searchTypes.forEach(button => { button.disabled = loading; });
  if (loading) {
    emptyState.classList.add("hidden");
    resultList.replaceChildren(
      ...Array.from({ length: 3 }, () => {
        const item = document.createElement("div");
        item.className = "skeleton";
        return item;
      }),
    );
  }
}

function formatSize(bytes) {
  const value = Number(bytes || 0);
  if (!value) return "";
  const units = ["B", "KB", "MB", "GB"];
  let size = value;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  return `${size.toFixed(index > 1 ? 1 : 0)} ${units[index]}`;
}

function metadataItem(text, className = "") {
  const span = document.createElement("span");
  span.className = className;
  span.textContent = text;
  return span;
}

function hasPlayback(item) {
  return Boolean(item.playable || String(item.source_id || "").split(":")[0] === "ygdy");
}

function renderResult(item) {
  const card = document.createElement("article");
  card.className = "result-card";

  const content = document.createElement("div");
  const title = document.createElement("h3");
  title.className = "result-title";
  const detailLink = document.createElement(hasPlayback(item) ? "a" : "span");
  detailLink.textContent = item.title;
  title.append(detailLink);
  content.append(title);

  if (item.description) {
    const description = document.createElement("p");
    description.className = "result-description";
    description.textContent = item.description;
    content.append(description);
  }

  const metadata = document.createElement("div");
  metadata.className = "metadata";
  if (item.media_type && mediaTypeLabels[item.media_type]) {
    metadata.append(metadataItem(mediaTypeLabels[item.media_type]));
  }
  if (["80s电影网", "电影天堂", "飘花电影网", "电影港", "66影视"].includes(item.source)) {
    const rating = document.createElement("a");
    rating.className = "douban-rating";
    rating.href = item.douban_url || "https://movie.douban.com/";
    rating.target = "_blank";
    rating.rel = "noopener noreferrer";
    rating.textContent = item.douban_rating ? `${Number(item.douban_rating).toFixed(1)} 分` : "";
    if (item.douban_rating) metadata.append(rating);
  }
  if (item.creator) metadata.append(metadataItem(`作者：${item.creator}`));
  if (item.width && item.height) {
    metadata.append(metadataItem(`${item.width} × ${item.height}`));
  }
  if (item.duration) metadata.append(metadataItem(`${item.duration} 秒`));
  if (item.size) metadata.append(metadataItem(formatSize(item.size)));
  if (item.license) metadata.append(metadataItem(item.license, "license"));
  if (item.resource_type === "online") metadata.append(metadataItem("可在线观看"));
  content.append(metadata);

  const button = document.createElement("button");
  button.className = "download-button";
  button.type = "button";
  button.innerHTML =
    '<i data-lucide="download" aria-hidden="true"></i><span>迅雷下载</span>';
  button.addEventListener("click", () => submitDownload(item, button));

  const actions = document.createElement("div");
  actions.className = "download-actions";
  actions.append(button);
  const copyButton = document.createElement("button");
  copyButton.className = "secondary-button";
  copyButton.type = "button";
  copyButton.innerHTML =
    '<i data-lucide="copy" aria-hidden="true"></i><span>复制下载链接</span>';
  copyButton.addEventListener("click", () => copyDownloadLink(item, copyButton));
  actions.append(copyButton);
  if (item.resource_type === "online") {
    actions.replaceChildren();
  }
  if (hasPlayback(item)) {
    const play = document.createElement("a");
    play.className = "download-button watch-button";
    const playbackParams = new URLSearchParams({ title: item.title });
    if (String(item.source_id || "").split(":")[0] === "ygdy") {
      playbackParams.set("page", item.detail_url);
    } else {
      playbackParams.set("token", item.token);
    }
    play.href = `/player.html?${playbackParams}`;
    play.innerHTML = `${window.WebCatalog.icon("play")}<span>立即观看</span>`;
    actions.prepend(play);
    detailLink.href = play.href;
    detailLink.removeAttribute("target");
  }
  const summary = document.createElement("div");
  summary.className = "result-summary";
  {
    const posterLink = document.createElement(hasPlayback(item) ? "a" : "div");
    posterLink.className = "result-thumbnail-link";
    if (hasPlayback(item)) posterLink.href = detailLink.href;
    const poster = createMediaPoster({
      title: item.title, url: item.thumbnail_url, className: "result-thumbnail",
    });
    posterLink.append(poster);
    summary.classList.add("has-thumbnail");
    summary.append(posterLink);
  }
  summary.append(content);
  card.append(summary, actions);
  return card;
}

function renderResults(data) {
  intentText.textContent = `共找到 ${(data.results || []).length} 个结果`;
  const orderedResults = [...(data.results || [])].sort(
    (left, right) => Number(hasPlayback(right)) - Number(hasPlayback(left)),
  );
  const cards = orderedResults.map(renderResult);
  resultList.replaceChildren(...cards);
  emptyState.classList.toggle("hidden", cards.length > 0);
  if (!cards.length) {
    emptyState.querySelector("span").textContent = "没有找到匹配的视频资源";
  }
  if (data.errors && data.errors.length) {
    showNotice("部分资源暂时无法读取，已保留可用结果，请稍后重试。");
  }
  refreshIcons();
}

async function copyDownloadLink(item, button) {
  hideNotice();
  const original =
    '<i data-lucide="copy" aria-hidden="true"></i><span>复制下载链接</span>';
  button.disabled = true;
  button.textContent = "正在复制...";
  try {
    const response = await fetch("/api/download/copy", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: item.token }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "获取下载链接失败");
    try {
      await navigator.clipboard.writeText(data.download_url);
      button.textContent = "已复制";
      showNotice("下载链接已复制，可粘贴到其他下载工具");
    } catch {
      showNotice("自动复制失败，请选中下方链接手动复制");
      const input = document.createElement("textarea");
      input.value = data.download_url;
      input.readOnly = true;
      input.setAttribute("aria-label", "下载链接");
      input.style.width = "100%";
      notice.append(input);
      input.focus();
      input.select();
      button.innerHTML = original;
      refreshIcons();
    }
  } catch (error) {
    showNotice(error.message, true);
    button.innerHTML = original;
    refreshIcons();
  } finally {
    button.disabled = false;
  }
}

async function submitDownload(item, button) {
  hideNotice();
  const original = button.innerHTML;
  button.disabled = true;
  button.textContent = "正在提交...";
  try {
    const response = await fetch("/api/download", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: item.token }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "提交失败");
    showNotice(`${data.message}：${data.title} → ${data.destination}`);
    if (data.engine === "xunlei_browser") {
      appendBrowserDownload(notice, data.launch_uri);
      button.innerHTML = original;
      button.disabled = false;
      refreshIcons();
    }
    if (data.engine === "xunlei_local") {
      button.textContent = "已发送迅雷";
    } else if (data.engine === "xunlei_mcp") {
      button.textContent = "已提交迅雷";
    }
  } catch (error) {
    showNotice(error.message, true);
    button.innerHTML = original;
    button.disabled = false;
    refreshIcons();
  }
}

function appendBrowserDownload(container, uri) {
  if (!/^(thunder|magnet|ed2k|jianpian):/i.test(uri || "")) return;
  const link = document.createElement("a");
  link.href = uri;
  link.textContent = "打开当前设备迅雷";
  link.style.display = "block";
  container.append(link);
  const hint = document.createElement("span");
  hint.textContent = "下载位置由该设备的迅雷设置决定。";
  container.append(hint);
}

async function submitManualDownload() {
  hideMagnetNotice();
  const submitButton = xunleiSubmitButton;
  const original = submitButton.innerHTML;
  xunleiSubmitButton.disabled = true;
  magnetUrlInput.disabled = true;
  submitButton.textContent = "正在提交...";
  try {
    const response = await fetch("/api/download/xunlei", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ download_uri: magnetUrlInput.value.trim() }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "提交失败");
    showMagnetNotice(`${data.message}：${data.title} → ${data.destination}`);
    if (data.engine === "xunlei_browser") appendBrowserDownload(magnetNotice, data.launch_uri);
    magnetUrlInput.value = "";
  } catch (error) {
    showMagnetNotice(error.message, true);
  } finally {
    submitButton.innerHTML = original;
    xunleiSubmitButton.disabled = false;
    magnetUrlInput.disabled = false;
    refreshIcons();
  }
}

magnetForm?.addEventListener("submit", (event) => {
  event.preventDefault();
  submitManualDownload();
});

searchForm?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const submittedQuery = queryInput.value.trim();
  if (!submittedQuery) return;
  if (document.body.dataset.page !== "search") {
    location.assign(`/search.html?${new URLSearchParams({ query: submittedQuery, media_type: mediaType })}`);
    return;
  }
  const url = new URL(location.href);
  url.searchParams.set("query", submittedQuery);
  url.searchParams.set("media_type", mediaType);
  history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
  resultSection.hidden = false;
  hideNotice();
  renderSourceResults([]);
  setLoading(true);
  try {
    const response = await fetch("/api/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query: submittedQuery, media_type: mediaType }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "搜索失败");
    renderResults(data);
    lastSearchQuery = submittedQuery;
  } catch (error) {
    resultList.replaceChildren();
    emptyState.classList.remove("hidden");
    emptyState.querySelector("span").textContent = "搜索失败";
    showNotice(error.message, true);
  } finally {
    setLoading(false);
  }
});

document.addEventListener("resource-search", (event) => {
  if (document.body.dataset.page !== "search") {
    location.assign(`/search.html?${new URLSearchParams({
      query: event.detail.query, media_type: event.detail.mediaType || "all",
    })}`);
    return;
  }
  if (searchButton.disabled) return;
  queryInput.value = event.detail.query;
  mediaType = event.detail.mediaType || "all";
  syncSearchType();
  searchForm.requestSubmit();
  searchForm.scrollIntoView({ behavior: "smooth", block: "start" });
});

document.querySelectorAll("[data-catalog-query]").forEach((button) => {
  button.addEventListener("click", (event) => {
    event.preventDefault();
    queryInput.value = button.dataset.catalogQuery;
    searchForm.requestSubmit();
    resultSection.scrollIntoView({ behavior: "smooth", block: "start" });
  });
});

document.addEventListener("visibilitychange", () => {
  if (!document.hidden) loadStatus();
});

const initialQuery = new URLSearchParams(window.location.search)
  .get("query")
  ?.trim();
if (initialQuery && queryInput) {
  queryInput.value = initialQuery;
  searchForm.requestSubmit();
}

loadStatus();
refreshIcons();
