(() => {
  const section = document.querySelector("[data-douban-list]");
  if (!section) return;
  const list = section.querySelector(".douban-list");
  const status = section.querySelector(".douban-list-status");
  const tabs = [...section.querySelectorAll("[data-douban-kind]")];
  const previous = section.querySelector("[data-douban-previous]");
  const next = section.querySelector("[data-douban-next]");
  const retry = section.querySelector("[data-douban-retry]");
  const page = section.querySelector(".douban-page-number");
  const sizeSelect = section.querySelector("[data-douban-page-size]");
  const pagination = new DoubanPagination.ListPagination();
  const preview = section.dataset.doubanList === "preview";
  let kind = tabs.find((tab) => tab.getAttribute("aria-pressed") === "true")
    ?.dataset.doubanKind || "top250";
  let start = 0;
  let hasMore = false;
  let pageSize = 25;
  try {
    const saved = Number(localStorage.getItem("douban-page-size"));
    if (DoubanPagination.PAGE_SIZES.includes(saved)) pageSize = saved;
  } catch (error) { /* Storage can be unavailable in private browsers. */ }
  if (sizeSelect) sizeSelect.value = String(pageSize);
  let playbackController;

  function renderItem(item, index) {
    const row = document.createElement("li");
    row.append(window.WebCatalog.card(item));
    return row;
  }

  async function load() {
    playbackController?.abort();
    playbackController = new AbortController();
    const signal = playbackController.signal;
    section.setAttribute("aria-busy", "true");
    tabs.forEach((tab) => {
      tab.disabled = true;
      tab.setAttribute("aria-pressed", String(tab.dataset.doubanKind === kind));
    });
    if (previous) previous.disabled = true;
    if (next) next.disabled = true;
    if (sizeSelect) sizeSelect.disabled = true;
    retry.disabled = true;
    status.textContent = "正在读取豆瓣列表...";
    try {
      const activeTab = tabs.find((tab) => tab.dataset.doubanKind === kind);
      const genre = activeTab?.dataset.doubanGenre;
      const data = await pagination.read({
        key: kind, start, limit: pageSize, nativeSize: kind === "top250" ? 25 : 20,
        fixedTotal: kind === "top250" ? 250 : null, signal,
      }, async (offset, requestSignal) => {
        const endpoint = genre
          ? `/api/douban/browse?${new URLSearchParams({
              type: "movie", genre, year: "all", sort: "rating", start: offset,
            })}`
          : `/api/douban/list?${new URLSearchParams({ kind, start: offset })}`;
        const response = await fetch(endpoint, { signal: requestSignal });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || "豆瓣列表读取失败");
        return result;
      });
      const items = preview ? data.items.slice(0, 8) : data.items;
      const rows = items.map(renderItem);
      list.replaceChildren(...rows);
      hasMore = data.has_more;
      document.querySelector("#doubanTitle").textContent = activeTab.textContent.trim();
      status.textContent = items.length
        ? `本页 ${items.length} 部${data.stale ? " · 正在展示缓存" : ""}`
        : "暂无影片，可以切换分类";
      if (page) page.textContent = data.total_pages !== null
        ? `第 ${start / pageSize + 1} / ${Math.max(1, data.total_pages)} 页${kind === "top250" ? " · 共 250 条" : ""}`
        : `第 ${start / pageSize + 1} 页 · 总页数待确认`;
      if (page) page.title = data.total_pages === null
        ? "来源暂未提供总量，到达末页后可确认总页数" : "";
    } catch (error) {
      hasMore = false;
      status.textContent = list.children.length ? "当前列表加载失败，暂时显示上次结果，可重试。" : "影片加载失败，请点击刷新重试。";
    } finally {
      section.setAttribute("aria-busy", "false");
      tabs.forEach((tab) => { tab.disabled = false; });
      if (previous) previous.disabled = start === 0;
      if (next) next.disabled = !hasMore;
      if (sizeSelect) sizeSelect.disabled = false;
      retry.disabled = false;
    }
  }

  tabs.forEach((tab) => tab.addEventListener("click", () => {
    kind = tab.dataset.doubanKind;
    start = 0;
    load();
  }));
  retry.addEventListener("click", load);
  sizeSelect?.addEventListener("change", () => {
    pageSize = Number(sizeSelect.value);
    try { localStorage.setItem("douban-page-size", String(pageSize)); } catch (error) {}
    start = 0;
    load();
  });
  if (previous) previous.addEventListener("click", () => { start -= pageSize; load(); });
  if (next) next.addEventListener("click", () => { start += pageSize; load(); });
  load();
})();
