const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const { fixturePath, launchOptions, outputPath } = require("./test-support.cjs");
const base = process.env.SITE_URL || "http://127.0.0.1:8766";

(async () => {
  const browser = await chromium.launch(launchOptions);
  try {
  let checks = 0;
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on("pageerror", error => errors.push(error.message));
  // Keep real catalog titles and posters in visual evidence. Isolate all download
  // requests so this suite can never start an actual NAS/Thunder transfer.
  const catalogResponse = await page.request.get(base + "/api/douban/list?kind=top250&start=0");
  assert.equal(catalogResponse.ok(), true);
  const catalog = await catalogResponse.json();
  assert.ok(catalog.items.length);
  await page.route("**/api/douban/list?**", route => route.fulfill({ json: catalog }));
  let browseQuery;
  await page.route("**/api/douban/browse?**", route => {
    browseQuery = new URL(route.request().url()).searchParams;
    return route.fulfill({ json: { ...catalog, items: catalog.items.slice(0, 20), has_more: false } });
  });
  await page.route("**/api/status", route => route.fulfill({ json: {
    providers: {}, nas: { download_dir: "/Volumes/video/downloads", directory_ready: false,
      mount_configured: true, mount_supported: true },
    xunlei: { installed: true, mcp_configured: true },
  } }));
  for (const width of [320, 390, 720, 840, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    for (const path of ["/", "/hot.html", "/catalog.html"]) {
      await page.goto(base + path);
      await page.locator(".browse-card").first().waitFor();
      const geometry = await page.evaluate(() => ({
        overflow: document.documentElement.scrollWidth - innerWidth,
        brand: document.querySelector(".product").textContent,
        poster: document.querySelector(".browse-poster").getBoundingClientRect().toJSON(),
        titleWeight: getComputedStyle(document.querySelector(".browse-card h3")).fontWeight,
        metadata: getComputedStyle(document.querySelector(".film-metadata")).color,
        nav: getComputedStyle(document.querySelector(".site-nav")).position,
      }));
      assert.equal(geometry.overflow, 0, `${path} ${width}: overflow`);
      assert.equal(geometry.brand, "熊猫视频");
      assert.ok(Number(geometry.titleWeight) >= 700);
      assert.ok(geometry.poster.height > geometry.poster.width);
      assert.equal(geometry.nav, width < 600 ? "fixed" : "static");
      checks += 5;
    }
    await page.locator("#settingsButton").click();
    await page.locator("#nasDestination").filter({ hasText: "/Volumes/video/downloads" }).waitFor();
    assert.equal(await page.locator("#mountNasButton").isVisible(), true);
    assert.equal(await page.locator("#magnetForm").isVisible(), true);
    assert.equal(await page.locator("#settingsDialog").evaluate(dialog => dialog.scrollWidth <= dialog.clientWidth), true);
    checks += 3;
    await page.locator("#closeSettings").click();
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(base + "/");
  await page.locator(".browse-card").first().waitFor();
  await page.waitForFunction(() => {
    const image = document.querySelector(".browse-poster img");
    return image?.complete && image.naturalWidth > 0;
  });
  await page.screenshot({ path: outputPath("web-sync-home-desktop.png") });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: outputPath("web-sync-home-mobile.png") });
  await page.goto(base + "/hot.html");
  await page.getByRole("button", { name: "电影", exact: true }).click();
  await page.locator("#hotResults[aria-busy=false]").waitFor();
  assert.equal(browseQuery.get("type"), "movie");
  assert.equal(browseQuery.get("sort"), "hot");
  checks += 2;
  await page.getByRole("link", { name: "分类", exact: true }).click();
  await page.locator("#browseResults[aria-busy=false]").waitFor();
  await page.getByRole("button", { name: "2024", exact: true }).click();
  await page.getByRole("button", { name: "评分最高", exact: true }).click();
  await page.locator("#browseResults[aria-busy=false]").waitFor();
  assert.equal(browseQuery.get("year"), "2024");
  assert.equal(browseQuery.get("sort"), "rating");
  assert.equal(await page.locator("#yearOptions button[data-value='1900']").count(), 1);
  const previousCards = await page.locator(".browse-card").count();
  await page.route("**/api/douban/browse?**", route => route.fulfill({ status: 503, json: { error: "offline" } }));
  await page.getByRole("button", { name: "2025", exact: true }).click();
  await page.locator("#browseStatus").filter({ hasText: "暂时显示上次结果" }).waitFor();
  assert.equal(await page.locator(".browse-card").count(), previousCards);
  await page.unroute("**/api/douban/browse?**");
  await page.route("**/api/douban/browse?**", route => route.fulfill({ json: { ...catalog, has_more: false } }));
  await page.getByRole("button", { name: "2024", exact: true }).click();
  await page.locator("#browseResults[aria-busy=false]").waitFor();
  checks++;
  checks += 3;
  await page.screenshot({ path: outputPath("web-sync-catalog-mobile.png") });

  const film = catalog.items[0];
  const results = [
    { title: film.title, source: "测试来源", source_id: "ygdy:/voddetail/12.html", detail_url: "https://huolot.com/voddetail/12.html",
      playable: true, resource_type: "online", thumbnail_url: film.cover },
    { title: film.title + " 高清版", source: "测试来源", token: "both-token", playable: true, resource_type: "download", thumbnail_url: film.cover },
    { title: film.title + " 收藏版", source: "测试来源", token: "download-token", resource_type: "download", thumbnail_url: film.cover },
  ];
  await page.route("**/api/search", route => route.fulfill({ json: { results, sources: [], intent: {} } }));
  await page.goto(base + "/");
  await page.locator("#query").fill(film.title);
  await page.locator("#searchButton").click();
  await page.waitForURL("**/search.html?**");
  await page.locator(".result-card").last().waitFor();
  assert.equal(await page.locator(".result-card").count(), 3);
  assert.equal(await page.locator(".watch-button").count(), 2);
  assert.equal(await page.locator("button.download-button").count(), 2);
  assert.equal(await page.getByText("重新输入", { exact: true }).count(), 0);
  assert.equal(await page.getByText("测试来源").count(), 0);
  assert.equal(new URL(page.url()).pathname, "/search.html"); // Never auto-open the first result.
  checks += 6;
  await page.screenshot({ path: outputPath("web-sync-search-mobile.png") });
  let downloadedToken;
  await page.route("**/api/download", route => {
    downloadedToken = route.request().postDataJSON().token;
    return route.fulfill({ json: { engine: "xunlei_mcp", message: "已提交迅雷", title: film.title, destination: "/Volumes/video/downloads" } });
  });
  await page.locator("button.download-button").first().click();
  await page.getByRole("button", { name: "已提交迅雷", exact: true }).waitFor();
  assert.equal(downloadedToken, "both-token");
  checks++;
  let copiedToken;
  await page.route("**/api/download/copy", route => {
    copiedToken = route.request().postDataJSON().token;
    return route.fulfill({ json: { download_url: "magnet:?xt=urn:btih:test" } });
  });
  await page.getByRole("button", { name: "复制下载链接", exact: true }).first().click();
  await page.locator("#notice").filter({ hasText: /复制/ }).waitFor();
  assert.equal(copiedToken, "both-token");
  checks++;
  let mounted = false;
  await page.route("**/api/nas/mount", route => {
    mounted = true;
    return route.fulfill({ json: { message: "已挂载" } });
  });
  let manualURI;
  await page.route("**/api/download/xunlei", route => {
    manualURI = route.request().postDataJSON().download_uri;
    return route.fulfill({ json: { message: "已提交迅雷", title: "手动下载", destination: "/Volumes/video/downloads", engine: "xunlei_mcp" } });
  });
  await page.locator("#settingsButton").click();
  await page.locator("#mountNasButton").click();
  assert.equal(mounted, true);
  await page.locator("#magnetUrl").fill("magnet:?xt=urn:btih:manual");
  await page.locator("#xunleiSubmit").click();
  await page.locator("#magnetNotice").filter({ hasText: "已提交迅雷" }).waitFor();
  assert.equal(manualURI, "magnet:?xt=urn:btih:manual");
  assert.equal(await page.locator("#magnetUrl").inputValue(), "");
  checks += 3;
  await page.screenshot({ path: outputPath("web-sync-nas-mobile.png") });
  await page.locator("#closeSettings").click();

  let matchRequest;
  await page.route("**/api/playback/resolve", route => {
    matchRequest = route.request().postDataJSON();
    return route.fulfill({ json: { playable: true, page_url: "https://huolot.com/voddetail/12.html" } });
  });
  await page.route("**/test-sync.mp4", route => route.fulfill({ path: fixturePath("rotation-test.mp4"), contentType: "video/mp4" }));
  await page.route("**/api/player?**", route => route.fulfill({ json: {
    title: film.title, source: "测试来源", page_url: "https://huolot.com/voddetail/12.html",
    selected: "/vodplay/12-1-1.html", episodes: [{ path: "/vodplay/12-1-1.html", line: 1, label: "正片" }],
    kind: "video", url: base + "/test-sync.mp4",
  } }));
  await page.goto(base + "/");
  await page.locator(".browse-card").first().click();
  await page.waitForURL("**/player.html?**");
  await page.waitForFunction(() => document.querySelector("video")?.currentTime > 0);
  assert.equal(matchRequest.title, film.title);
  assert.equal(matchRequest.year, film.year);
  assert.equal(matchRequest.url, film.url);
  assert.equal(await page.locator("#sourceName").isVisible(), false);
  assert.ok((await page.locator("#downloadSearch").getAttribute("href")).includes("/search.html?"));
  checks += 5;
  await page.unroute("**/api/playback/resolve");
  await page.route("**/api/playback/resolve", route => route.fulfill({ json: { playable: false } }));
  await page.goto(base + `/player.html?${new URLSearchParams({ title: film.title, subject: film.url, year: film.year })}`);
  await page.locator("#retryPlay").waitFor({ state: "visible" });
  assert.equal(await page.locator("#searchPlayback").isVisible(), true);
  await page.locator("#searchPlayback").click();
  await page.waitForURL("**/search.html?**");
  checks++;
  // Theme persists across page navigation and does not leave mint seams in dark mode.
  await page.locator("#themeButton").click(); // system -> light
  await page.locator("#themeButton").click(); // light -> dark
  await page.getByRole("link", { name: "发现", exact: true }).click();
  await page.locator('html[data-theme="dark"]').waitFor();
  assert.equal(await page.locator("html").getAttribute("data-theme"), "dark");
  await page.screenshot({ path: outputPath("web-sync-home-dark.png") });
  checks++;
  assert.deepEqual(errors, []);
  checks++;
  console.log(JSON.stringify({ base, checks, errors, downloads: "mocked; no real transfers", visualEvidence: "web-sync-*.png" }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
