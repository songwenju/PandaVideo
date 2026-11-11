(() => {
  const types = [...document.querySelectorAll("[data-browse-type]")];
  const genre = document.querySelector("#browseGenre");
  const year = document.querySelector("#browseYear");
  const sort = document.querySelector("#browseSort");
  const results = document.querySelector("#browseResults");
  const status = document.querySelector("#browseStatus");
  const previous = document.querySelector("#browsePrevious");
  const next = document.querySelector("#browseNext");
  const retry = document.querySelector("#browseRetry");
  const page = document.querySelector("#browsePage");
  const currentYear = new Date().getFullYear();
  for (let value = currentYear; value >= 1900; value--) {
    year.add(new Option(String(value), String(value)));
  }
  const params = new URLSearchParams(location.search);
  let type = types.some((tab) => tab.dataset.browseType === params.get("type"))
    ? params.get("type") : "movie";
  [genre, year, sort].forEach((select) => {
    const key = select === genre ? "genre" : select === year ? "year" : "sort";
    const value = params.get(key);
    if ([...select.options].some((option) => option.value === value)) select.value = value;
  });
  if (params.get("preset") === "annual_tv") {
    type = "tv";
    genre.value = "all";
    year.value = String(currentYear);
    sort.value = "hot";
  }
  let start = 0;
  let hasMore = false;
  let controller;
  let sequence = 0;
  let yearsExpanded = true;
  const yearMore = document.querySelector("#yearMore");
  const filterToggle = document.querySelector("#filterToggle");
  const filterPanel = document.querySelector("#filterPanel");
  const controls = [
    { select: genre, container: document.querySelector("#genreOptions"), key: "genre" },
    { select: year, container: document.querySelector("#yearOptions"), key: "year" },
    { select: sort, container: document.querySelector("#sortOptions"), key: "sort" },
  ];
  controls.forEach(({ select, container, key }) => {
    [...select.options].forEach((option) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "filter-option";
      button.dataset.filter = key;
      button.dataset.value = option.value;
      button.textContent = option.value === "all" ? "全部" : option.text;
      button.setAttribute("aria-pressed", String(option.value === select.value));
      container.append(button);
    });
    container.addEventListener("click", (event) => {
      const button = event.target.closest("button[data-value]");
      if (!button || select.value === button.dataset.value) return;
      select.value = button.dataset.value;
      resetPage();
    });
  });

  function syncFilters() {
    controls.forEach(({ select, container, key }) => {
      [...container.children].forEach((button) => {
        const active = button.dataset.value === select.value;
        button.setAttribute("aria-pressed", String(active));
        if (key === "year") {
          const recent = button.dataset.value === "all"
            || Number(button.dataset.value) >= currentYear - 5;
          button.hidden = !yearsExpanded && !recent && !active;
        }
      });
    });
    document.querySelector("#yearOptions").classList.toggle("is-expanded", yearsExpanded);
    yearMore.setAttribute("aria-expanded", String(yearsExpanded));
    yearMore.textContent = yearsExpanded ? "收起年份 ⌃" : "更多年份 ⌄";
  }

  yearMore.addEventListener("click", () => {
    yearsExpanded = !yearsExpanded;
    syncFilters();
  });
  filterToggle.addEventListener("click", () => {
    filterPanel.hidden = !filterPanel.hidden;
    filterToggle.setAttribute("aria-expanded", String(!filterPanel.hidden));
    filterToggle.textContent = filterPanel.hidden ? "展开筛选 ⌄" : "收起筛选 ⌃";
  });

  function renderItem(item) {
    return window.WebCatalog.card(item, true);
  }

  async function load() {
    controller?.abort();
    controller = new AbortController();
    const requestId = ++sequence;
    const filters = { type, genre: genre.value, year: year.value, sort: sort.value };
    const query = new URLSearchParams(filters);
    const url = new URL(location.href);
    Object.entries(filters).forEach(([key, value]) => url.searchParams.set(key, value));
    history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
    types.forEach((tab) => tab.setAttribute("aria-pressed", String(tab.dataset.browseType === type)));
    syncFilters();
    results.setAttribute("aria-busy", "true");
    previous.disabled = true;
    next.disabled = true;
    retry.disabled = true;
    status.textContent = "正在读取影视列表...";
    query.set("start", start);
    try {
      const response = await fetch(`/api/douban/browse?${query}`, { signal: controller.signal });
      const data = await response.json();
      if (requestId !== sequence) return;
      if (!response.ok) throw new Error(data.error || "影视列表读取失败");
      hasMore = data.has_more;
      results.replaceChildren(...data.items.map(renderItem));
      page.textContent = `第 ${start / 20 + 1} 页`;
      status.textContent = data.items.length
        ? `${data.label} · 本页 ${data.items.length} 部${data.stale ? " · 暂时展示缓存" : ""}`
        : data.has_more
          ? "当前页没有已评分的作品，可继续翻页或调整筛选。"
          : "没有符合条件的已评分作品，可以调整题材或年份。";
    } catch (error) {
      if (requestId !== sequence) return;
      hasMore = false;
      status.textContent = results.children.length ? "当前筛选加载失败，暂时显示上次结果，可重试。" : "影片加载失败，请点击刷新重试。";
    } finally {
      if (requestId === sequence) {
        results.setAttribute("aria-busy", "false");
        previous.disabled = start === 0;
        next.disabled = !hasMore || start >= 10000;
        retry.disabled = false;
      }
    }
  }

  function resetPage() { start = 0; load(); }
  types.forEach((tab) => tab.addEventListener("click", () => {
    type = tab.dataset.browseType;
    resetPage();
  }));
  [genre, year, sort].forEach((select) => select.addEventListener("change", resetPage));
  previous.addEventListener("click", () => { start -= 20; load(); });
  next.addEventListener("click", () => { start += 20; load(); });
  retry.addEventListener("click", load);
  load();
})();
