const { chromium } = require("playwright");
const { launchOptions } = require("./test-support.cjs");

(async () => {
  const browser = await chromium.launch({
    ...launchOptions,
    headless: true,
  });
  const base = process.env.SITE_URL || "http://127.0.0.1:8766";
  const failures = [];
  let checks = 0;
  const check = (ok, name) => { checks++; if (!ok) failures.push(name); };
  for (const width of [320, 390, 720, 840, 1440]) {
    const page = await browser.newPage({ viewport: { width, height: 916 } });
    await page.goto(base + "/", { waitUntil: "domcontentloaded" });
    await page.locator(".douban-list li").first().waitFor();
    const home = await page.evaluate(() => {
      const rect = s => document.querySelector(s).getBoundingClientRect().toJSON();
      return {
        overflow: document.documentElement.scrollWidth - innerWidth,
        logo: rect(".site-icon"), main: rect("main"),
        cards: Array.from(document.querySelectorAll(".douban-list .browse-card")).map(e => e.getBoundingClientRect().toJSON()),
        pagination: Array.from(document.querySelectorAll(".douban-pagination button,.douban-page-size")).map(e => e.getBoundingClientRect().toJSON()),
      };
    });
    check(home.overflow === 0, `home ${width}: horizontal overflow`);
    check(Math.abs(home.logo.x - home.main.x) < 1, `home ${width}: header/main alignment`);
    check(home.cards.every(r => r.height >= 44 && r.right <= width), `home ${width}: poster card bounds`);
    if (width >= 840) check(home.pagination.every(r => Math.abs(r.y + r.height / 2 - home.pagination[0].y - home.pagination[0].height / 2) < 1), `home ${width}: pagination alignment`);
    await page.goto(base + "/catalog.html", { waitUntil: "domcontentloaded" });
    await page.locator(".browse-card").first().waitFor();
    const catalog = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth - innerWidth,
      actions: Array.from(document.querySelectorAll(".browse-card-actions a")).map(e => e.getBoundingClientRect().height),
      poster: document.querySelector(".browse-poster").getBoundingClientRect().width,
    }));
    check(catalog.overflow === 0, `catalog ${width}: horizontal overflow`);
    check(catalog.actions.every(h => h >= 44), `catalog ${width}: action targets under 44px`);
    if (width === 720) check(catalog.poster < 220, "catalog 720: oversized two-column posters");

    // Isolated browser fixtures cover a long episode label and a long error.
    await page.route("**/api/player?**", route => route.fulfill({
      json: { title: "长剧集名称布局验证", source: "阳光电影",
        page_url: "https://huolot.com/voddetail/12.html", selected: "/vodplay/12-1-1.html",
        episodes: [{ path: "/vodplay/12-1-1.html", line: "1", label: "第01集完整版高清中文字幕特别加长版".repeat(3) }],
        kind: "video", url: base + "/layout-missing-video.mp4" },
    }));
    await page.goto(base + "/player.html?token=layout", { waitUntil: "domcontentloaded" });
    await page.waitForFunction(() => document.querySelector("#currentEpisode").textContent.startsWith("第01集"));
    const player = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth - innerWidth,
      tools: Array.from(document.querySelectorAll(".watch-tools select,.watch-tools button")).map(e => e.getBoundingClientRect().toJSON()),
      toolbar: document.querySelector(".watch-toolbar").getBoundingClientRect().toJSON(),
    }));
    check(player.overflow === 0, `player ${width}: long episode causes overflow`);
    check(player.tools.every(r => r.height >= 44), `player ${width}: toolbar controls under 44px`);
    check(player.tools.every(r => r.right <= player.toolbar.right + 1), `player ${width}: tools outside toolbar`);
    await page.unroute("**/api/player?**");
    await page.route("**/api/player?**", route => route.fulfill({
      status: 410, json: { error: "搜索结果已过期，请重新搜索以获取有效播放入口。".repeat(3) },
    }));
    await page.goto(base + "/player.html?token=expired", { waitUntil: "domcontentloaded" });
    await page.locator("#retryPlay").waitFor({ state: "visible" });
    const message = await page.evaluate(() => {
      const panel = document.querySelector("#playerMessage");
      const button = document.querySelector("#retryPlay").getBoundingClientRect();
      const r = panel.getBoundingClientRect();
      return { canScroll: getComputedStyle(panel).overflowY === "auto",
        visible: button.top >= r.top && button.bottom <= r.bottom, height: button.height };
    });
    check(message.visible || message.canScroll, `player ${width}: error/retry clipped`);
    check(message.height >= 44, `player ${width}: retry target under 44px`);
    await page.close();
  }
  await browser.close();
  console.log(JSON.stringify({ base, checks, failures }, null, 2));
  if (failures.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exitCode = 1; });
