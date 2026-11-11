(() => {
  const tabs = [...document.querySelectorAll("[data-hot-type]")];
  const results = document.querySelector("#hotResults");
  const status = document.querySelector("#hotStatus");
  const previous = document.querySelector("#hotPrevious");
  const next = document.querySelector("#hotNext");
  const retry = document.querySelector("#hotRetry");
  const page = document.querySelector("#hotPage");
  let type = new URLSearchParams(location.search).get("type") === "movie" ? "movie" : "tv";
  let start = 0;
  let controller;
  let sequence = 0;
  async function load() {
    controller?.abort();
    controller = new AbortController();
    const ticket = ++sequence;
    tabs.forEach(tab => tab.setAttribute("aria-pressed", String(tab.dataset.hotType === type)));
    history.replaceState(null, "", `/hot.html?${new URLSearchParams({ type })}`);
    previous.disabled = next.disabled = retry.disabled = true;
    results.setAttribute("aria-busy", "true");
    status.textContent = "正在加载影片…";
    try {
      const response = await fetch(`/api/douban/browse?${new URLSearchParams({
        type, genre: "all", year: "all", sort: "hot", start,
      })}`, { signal: controller.signal });
      const data = await response.json();
      if (ticket !== sequence) return;
      if (!response.ok) throw new Error("影片加载失败");
      results.replaceChildren(...data.items.map(item => window.WebCatalog.card(item)));
      status.textContent = data.items.length ? `本页 ${data.items.length} 部${data.stale ? " · 正在展示缓存" : ""}` : "暂无热播影片";
      page.textContent = `第 ${start / 20 + 1} 页`;
      previous.disabled = start === 0;
      next.disabled = !data.has_more || start >= 10000;
    } catch (error) {
      if (ticket !== sequence) return;
      status.textContent = results.children.length ? "当前列表加载失败，暂时显示上次结果，可重试。" : "影片加载失败，请点击刷新重试。";
      previous.disabled = start === 0;
    } finally {
      if (ticket === sequence) {
        results.setAttribute("aria-busy", "false");
        retry.disabled = false;
      }
    }
  }
  tabs.forEach(tab => tab.addEventListener("click", () => {
    if (type === tab.dataset.hotType) return;
    type = tab.dataset.hotType;
    start = 0;
    results.replaceChildren();
    load();
  }));
  previous.addEventListener("click", () => { start -= 20; load(); });
  next.addEventListener("click", () => { start += 20; load(); });
  retry.addEventListener("click", load);
  load();
})();
