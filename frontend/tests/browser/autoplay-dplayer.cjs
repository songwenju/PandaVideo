const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const { fixturePath, outputPath, launchOptions } = require("./test-support.cjs");
const base = process.env.SITE_URL || "http://127.0.0.1:8766";
const media = fixturePath("rotation-test.mp4");
let checks = 0;
(async () => {
  const browser = await chromium.launch({
    ...launchOptions,
    headless: true,
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 916 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.route("**/test-video.mp4", route => route.fulfill({ path: media, contentType: "video/mp4" }));
    const episodes = [1, 2].map(n => ({ path: `/vodplay/12-1-${n}.html`, line: 1, label: `第${n}集` }));
    await page.route("**/api/player?**", route => {
      const selected = new URL(route.request().url()).searchParams.get("episode") || episodes[0].path;
      return route.fulfill({ json: {
        title: "测试影片", source: "阳光电影", page_url: "https://huolot.com/voddetail/12.html",
        selected, episodes, kind: "video", url: base + "/test-video.mp4",
      } });
    });
    await page.route("**/api/search", route => route.fulfill({ json: {
      intent: { keywords: "测试影片" }, sources: [], results: [{
        title: "测试影片", source: "阳光电影", source_id: "ygdy:/voddetail/12.html",
        detail_url: "https://huolot.com/voddetail/12.html", playable: true, resource_type: "online",
      }],
    } }));
    await page.goto(base);
    await page.locator("#query").fill("测试影片");
    await page.locator("#searchButton").click();
    await page.waitForURL("**/search.html?**");
    await page.locator(".watch-button").click();
    await page.waitForURL("**/player.html?**");
    await page.waitForFunction(() => {
      const v = document.querySelector("video");
      return v && v.currentTime > 0 && !v.paused;
    });
    assert.equal(await page.locator(".dplayer").count(), 1);
    assert.equal(await page.locator("#playerMessage").isVisible(), false);
    assert.equal(await page.locator("#videoRotate").isVisible(), false);
    checks += 4;
    await page.locator("#playbackRate").selectOption("1.5");
    assert.equal(await page.locator("video").evaluate(v => v.playbackRate), 1.5);
    checks++;
    await page.locator("#nextEpisode").click();
    await page.waitForFunction(() => document.querySelector("#currentEpisode").textContent === "第2集");
    await page.waitForFunction(() => !document.querySelector("video").paused && document.querySelector("video").currentTime > 0);
    checks++;
    await page.locator("#previousEpisode").click();
    await page.waitForFunction(() => document.querySelector("#currentEpisode").textContent === "第1集");
    checks++;
    // The source's DPlayer fullscreen button must reveal rotation within fullscreen.
    await page.locator("#dplayer").hover();
    await page.locator(".dplayer-full-icon").click();
    await page.waitForFunction(() => document.fullscreenElement !== null);
    await page.locator("#dplayer").hover();
    await page.locator("#videoRotate").waitFor({ state: "visible", timeout: 3000 });
    assert.equal(await page.locator("#videoRotate").isVisible(), true);
    await page.locator("#videoRotate").click();
    await page.waitForFunction(() => document.querySelector("#videoRotate").getAttribute("aria-pressed") === "true");
    assert.ok(await page.evaluate(() => Boolean(document.fullscreenElement)));
    checks += 3;
    await page.locator("#dplayer").hover();
    await page.locator(".dplayer-full-icon").click();
    await page.waitForFunction(() => document.fullscreenElement === null);
    await page.locator("#videoRotate").waitFor({ state: "hidden", timeout: 3000 });
    assert.equal(await page.locator("#videoRotate").isVisible(), false);
    assert.equal(await page.locator("#dplayer").evaluate(e => e.classList.contains("is-rotated")), false);
    checks += 2;
    for (const width of [320, 390, 840, 1440]) {
      await page.setViewportSize({ width, height: 916 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth), 0);
      checks++;
    }
    await page.screenshot({ path: outputPath("dplayer-updated.png") });
    assert.deepEqual(errors, []);
    checks++;
    await page.unroute("**/api/player?**");
    await page.route("**/api/player?**", route => route.fulfill({ status: 410, json: { error: "搜索结果已过期" } }));
    await page.goto(base + "/player.html?token=expired&title=测试影片");
    await page.locator("#retryPlay").waitFor({ state: "visible" });
    assert.equal(await page.locator("#retryPlay").textContent(), "重新搜索");
    assert.equal(await page.locator("#videoRotate").isVisible(), false);
    checks += 2;
    await page.unroute("**/api/player?**");
    await page.route("**/dplayer-test*", route => {
      const name = new URL(route.request().url()).pathname.split("/").pop();
      return route.fulfill({ path: fixturePath(name),
        contentType: name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t" });
    });
    await page.route("**/api/player?**", route => route.fulfill({ json: {
      title: "HLS 测试", source: "阳光电影", page_url: "https://huolot.com/voddetail/12.html",
      episodes: [], kind: "hls", url: base + "/dplayer-test.m3u8",
    } }));
    await page.goto(base + "/player.html?token=hls");
    await page.waitForFunction(() => document.querySelector("video").currentTime > 0 && !document.querySelector("video").paused);
    assert.equal(await page.locator("#playerMessage").isVisible(), false);
    checks++;
    const muted = await browser.newPage();
    await muted.addInitScript(() => {
      const originalPlay = HTMLMediaElement.prototype.play;
      HTMLMediaElement.prototype.play = function () {
        return !this.muted
          ? Promise.reject(new DOMException("Audible autoplay blocked", "NotAllowedError"))
          : originalPlay.call(this);
      };
    });
    await muted.route("**/test-video.mp4", route => route.fulfill({ path: media, contentType: "video/mp4" }));
    await muted.route("**/api/player?**", route => route.fulfill({ json: {
      title: "静音自动播放", source: "阳光电影", episodes: [], kind: "video", url: base + "/test-video.mp4",
    } }));
    await muted.goto(base + "/player.html?token=muted");
    await muted.waitForFunction(() => {
      const v = document.querySelector("video");
      return v.muted && !v.paused && v.currentTime > 0;
    });
    assert.match(await muted.locator("#watchHint").textContent(), /静音自动播放/);
    checks++;
    await muted.close();
    await page.unroute("**/api/search");
    await page.route("**/api/search", route => route.fulfill({ json: { results: [], sources: [], intent: {} } }));
    await page.goto(base);
    await page.locator("#query").fill("没有匹配");
    await page.locator("#searchButton").click();
    await page.waitForURL("**/search.html?**");
    await page.waitForFunction(() => !document.querySelector("#searchButton").disabled);
    assert.equal(new URL(page.url()).pathname, "/search.html");
    assert.equal(await page.locator(".result-card").count(), 0);
    checks += 2;
    console.log(JSON.stringify({ base, checks, autoplay: true, dplayer: true, fullscreenOnlyRotation: true, errors }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
