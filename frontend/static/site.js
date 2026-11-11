/* Shared shell and catalog presentation keep the web pages visually consistent. */
(() => {
  const paths = {
    discover: '<circle cx="12" cy="12" r="9"/><path d="m16 8-3 5-5 3 3-5z"/>',
    hot: '<path d="M13 3c1 5-4 6-2 10 2-1 3-3 3-5 4 3 5 6 4 9a7 7 0 0 1-13-1c0-4 3-7 8-13Z"/>',
    catalog: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
    download: '<path d="M12 3v12m-5-5 5 5 5-5M4 15v5h16v-5"/>',
    search: '<circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/>',
    back: '<path d="m12 5-7 7 7 7M5 12h15"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1 1m12 12 1 1M5 19l1-1M18 6l1-1"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    play: '<path d="m9 5 11 7-11 7Z"/>',
  };
  const icon = name => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.play}</svg>`;
  const page = document.body.dataset.page || "discover";
  const nav = [
    ["discover", "/", "发现"], ["hot", "/hot.html", "最近热播"],
    ["catalog", "/catalog.html", "分类"],
  ].map(([key, href, text]) => `<a href="${href}" ${page === key ? 'aria-current="page"' : ""}>${icon(key)}<span>${text}</span></a>`).join("");
  const header = document.querySelector("#siteHeader");
  if (header) header.innerHTML = `
    <a class="site-brand" href="/" aria-label="熊猫视频首页">
      <svg class="site-icon" viewBox="0 0 64 64" fill="none" aria-hidden="true">
        <defs><linearGradient id="brandGradient" x1="10" y1="3" x2="54" y2="62" gradientUnits="userSpaceOnUse"><stop stop-color="#FF7B3E"/><stop offset="1" stop-color="#4ECDC4"/></linearGradient></defs>
        <rect x="2" y="2" width="60" height="60" rx="15" fill="url(#brandGradient)"/>
        <g transform="translate(6.4 6.4) scale(0.8)">
        <path d="M23 18A8 8 0 1 1 7 18A8 8 0 1 1 23 18ZM57 18A8 8 0 1 1 41 18A8 8 0 1 1 57 18Z" fill="#242A33"/>
        <path d="M19 18A4 4 0 1 1 11 18A4 4 0 1 1 19 18ZM53 18A4 4 0 1 1 45 18A4 4 0 1 1 53 18Z" fill="#56606A"/>
        <path d="M32 15C18 15 10 25 10 37C10 50 20 55 32 55C44 55 54 50 54 37C54 25 46 15 32 15Z" fill="#FFFCF5"/>
        <path d="M17 29C20 25 25 26 27 30C29 34 26 41 22 42C17 43 13 35 17 29ZM47 29C44 25 39 26 37 30C35 34 38 41 42 42C47 43 51 35 47 29Z" fill="#242A33"/>
        <path d="M25 32A2.5 3 0 1 1 20 32A2.5 3 0 1 1 25 32ZM44 32A2.5 3 0 1 1 39 32A2.5 3 0 1 1 44 32Z" fill="#FFFFFF"/>
        <path d="M25 33A1.6 2 0 1 1 21.8 33A1.6 2 0 1 1 25 33ZM42.2 33A1.6 2 0 1 1 39 33A1.6 2 0 1 1 42.2 33Z" fill="#242A33"/>
        <path d="M20 43A4 2.4 0 1 1 12 43A4 2.4 0 1 1 20 43ZM52 43A4 2.4 0 1 1 44 43A4 2.4 0 1 1 52 43Z" fill="#FFB5A5"/>
        <path d="M28.5 39.5Q32 38 35.5 39.5Q36.5 41 32 44Q27.5 41 28.5 39.5Z" fill="#242A33"/>
        <path d="M26.5 46Q29 49 32 45Q35 49 37.5 46" stroke="#242A33" stroke-width="1.7" stroke-linecap="round"/>
        </g>
      </svg><span class="product">熊猫视频</span>
    </a>
    <nav class="site-nav" aria-label="主导航">${nav}</nav>
    <div class="topbar-actions">
      <a class="icon-button header-search" href="/search.html" aria-label="搜索影片">${icon("search")}</a>
      <button class="icon-button" id="themeButton" type="button" aria-label="切换外观">${icon("sun")}</button>
      <button class="mount-button" id="settingsButton" type="button" aria-haspopup="dialog" aria-controls="settingsDialog">${icon("download")}<span>NAS 下载</span></button>
    </div>`;
  const dialogRoot = document.querySelector("#nasDialogRoot");
  if (dialogRoot) dialogRoot.innerHTML = `
    <dialog class="settings-dialog" id="settingsDialog" aria-labelledby="settingsTitle">
      <div class="settings-header"><div><h2 id="settingsTitle">NAS 下载</h2><p>把想收藏的影片保存到 NAS</p></div>
        <button class="icon-button" id="closeSettings" type="button" aria-label="关闭 NAS 下载">${icon("close")}</button></div>
      <section class="nas-surface" aria-labelledby="nasTitle">
        <h3 id="nasTitle">下载环境</h3><p id="nasDestination">正在检查 NAS 目录…</p>
        <div class="status-row" id="statusRow" aria-label="服务状态"></div>
        <button class="mount-button" id="mountNasButton" type="button">挂载 NAS</button>
      </section>
      <section class="magnet-panel" aria-labelledby="queueTitle">
        <h3 id="queueTitle">添加下载链接</h3>
        <p id="magnetHelp">粘贴磁力或下载链接，交给迅雷处理。保存位置由迅雷设置决定，请选择已挂载的 NAS 目录。</p>
        <form id="magnetForm"><label for="magnetUrl">下载链接</label><div class="magnet-row">
          <input id="magnetUrl" name="download_uri" type="text" inputmode="url" autocomplete="off" spellcheck="false" aria-describedby="magnetHelp" placeholder="粘贴下载链接" required>
          <button class="primary-button" id="xunleiSubmit" type="submit">${icon("download")}<span>调用迅雷</span></button>
        </div></form><div class="notice hidden" id="magnetNotice" role="status"></div>
      </section>
    </dialog>`;
  const themeNames = { system: "跟随系统", light: "浅色模式", dark: "深色模式" };
  let theme = "system";
  try { theme = localStorage.getItem("suixinkan-theme") || "system"; } catch {}
  if (!(theme in themeNames)) theme = "system";
  function applyTheme() {
    document.documentElement.dataset.theme = theme;
    const button = document.querySelector("#themeButton");
    if (button) {
      button.title = `当前：${themeNames[theme]}`;
      button.setAttribute("aria-label", `当前${themeNames[theme]}，切换外观`);
    }
  }
  applyTheme();
  document.querySelector("#themeButton")?.addEventListener("click", () => {
    theme = theme === "system" ? "light" : theme === "light" ? "dark" : "system";
    try { localStorage.setItem("suixinkan-theme", theme); } catch {}
    applyTheme();
  });
  window.WebCatalog = {
    icon,
    playbackHref(item) {
      return `/player.html?${new URLSearchParams({
        title: item.title, subject: item.url, year: item.year || "",
      })}`;
    },
    card(item, showCasts = false) {
      const card = document.createElement("a");
      card.className = "browse-card";
      card.href = this.playbackHref(item);
      card.setAttribute("aria-label", `观看《${item.title}》`);
      const title = document.createElement("h3");
      title.textContent = item.title;
      const metadata = document.createElement("p");
      metadata.className = "film-metadata";
      const year = document.createElement("span");
      year.textContent = item.year || "";
      metadata.append(year);
      if (item.rating) {
        const score = document.createElement("strong");
        score.textContent = `${Number(item.rating).toFixed(1)} 分`;
        if (item.year) metadata.append(document.createTextNode(" · "));
        metadata.append(score);
      }
      card.append(createMediaPoster({ title: item.title, url: item.cover, className: "browse-poster" }), title, metadata);
      if (showCasts && item.casts?.length) {
        const casts = document.createElement("p");
        casts.className = "browse-casts";
        casts.textContent = item.casts.join(" / ");
        card.append(casts);
      }
      return card;
    },
  };
})();
