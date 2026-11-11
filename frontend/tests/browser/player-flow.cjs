const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const { fixturePath, launchOptions } = require("./test-support.cjs");
const base = process.env.SITE_URL || "http://127.0.0.1:8766";
let checks = 0;
(async () => {
  const browser = await chromium.launch({
    ...launchOptions,
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 916 } });
    const errors = [];
    page.on("pageerror", e => errors.push(e.message));
    const episodes = [
      { path: "/vodplay/12-1-1.html", line: 1, label: "第1集" },
      { path: "/vodplay/12-1-2.html", line: 1, label: "第2集" },
      { path: "/vodplay/12-2-1.html", line: 2, label: "备用线路" },
    ];
    let broken = false;
    await page.route("**/test.mp4", r => r.fulfill({
      path: fixturePath("rotation-test.mp4"), contentType: "video/mp4",
    }));
    await page.route("**/broken.mp4", r => r.fulfill({ status: 404, body: "" }));
    await page.route("**/api/player?**", r => r.fulfill({ json: {
      title: "完整流程测试", source: "阳光电影", page_url: "https://huolot.com/voddetail/12.html",
      selected: new URL(r.request().url()).searchParams.get("episode") || episodes[0].path,
      episodes, kind: "video", url: base + (broken ? "/broken.mp4" : "/test.mp4"),
    } }));
    await page.goto(base + "/player.html?token=test");
    await page.waitForFunction(() => document.querySelector("video").currentTime > 0);
    await page.locator("#autoNext").uncheck();
    await page.locator("#dplayer").hover();
    await page.locator(".dplayer-play-icon").click();
    await page.waitForFunction(() => document.querySelector("video").paused);
    assert.equal(await page.locator("#playerMessage").isVisible(), false);
    checks += 2;
    await page.locator(".dplayer-play-icon").click();
    await page.waitForFunction(() => !document.querySelector("video").paused);
    checks++;
    // Drag the real progress bar, rather than calling a player seek API.
    await page.locator("#dplayer").hover();
    const bar = await page.locator(".dplayer-bar-wrap").boundingBox();
    await page.mouse.click(bar.x + bar.width * 0.4, bar.y + bar.height / 2);
    await page.waitForFunction(() => document.querySelector("video").currentTime >= 7);
    checks++;
    const initiallyMuted = await page.locator("video").evaluate(v => v.muted);
    await page.locator(".dplayer-volume-icon").click();
    assert.equal(await page.locator("video").evaluate(v => v.muted), !initiallyMuted);
    await page.locator(".dplayer-volume-icon").click();
    assert.equal(await page.locator("video").evaluate(v => v.muted), initiallyMuted);
    checks += 2;
    for (const speed of ["0.5", "1", "1.25", "1.5", "2"]) {
      await page.locator("#playbackRate").selectOption(speed);
      assert.equal(await page.locator("video").evaluate(v => v.playbackRate), Number(speed));
      checks++;
    }
    await page.locator("#playbackRate").selectOption("1");
    await page.locator("#episodeList button").first().click();
    await page.waitForFunction(() => document.querySelector("video").currentTime > 0 && !document.querySelector("video").paused);
    await page.locator("video").evaluate(v => v.currentTime = v.duration - 0.15);
    await page.waitForFunction(() => document.querySelector("video").ended);
    assert.equal(await page.locator("#currentEpisode").textContent(), "第1集");
    checks++;
    await page.locator("#autoNext").check();
    await page.locator("video").evaluate(v => { v.currentTime = v.duration - 0.15; return v.play(); });
    await page.waitForFunction(() => document.querySelector("#currentEpisode").textContent === "第2集");
    await page.waitForFunction(() => document.querySelector("video").currentTime > 0 && !document.querySelector("video").paused);
    assert.equal(await page.locator("#nextEpisode").isDisabled(), true);
    checks += 2;
    await page.locator("#lineTabs button").nth(1).click();
    await page.locator("#episodeList button").filter({ hasText: "备用线路" }).click();
    await page.waitForFunction(() => document.querySelector("#currentEpisode").textContent === "备用线路");
    await page.waitForFunction(() => !document.querySelector("video").paused);
    assert.equal(await page.locator("#previousEpisode").isDisabled(), true);
    assert.equal(await page.locator("#nextEpisode").isDisabled(), true);
    checks += 3;
    broken = true;
    await page.locator("#episodeList button").click();
    await page.locator("#retryPlay").waitFor({ state: "visible" });
    assert.match(await page.locator("#playerStatus").textContent(), /无法播放/);
    checks++;
    broken = false;
    await page.locator("#retryPlay").click();
    await page.waitForFunction(() => !document.querySelector("video").paused && document.querySelector("#playerMessage").hidden);
    checks++;
    for (let i = 0; i < 3; i++) {
      await page.locator("#fullscreen").click();
      await page.waitForFunction(() => Boolean(document.fullscreenElement));
      await page.keyboard.press("Escape");
      // Headless Chromium does not always process the OS escape shortcut.
      await page.evaluate(() => document.fullscreenElement ? document.exitFullscreen() : undefined);
      await page.waitForFunction(() => !document.fullscreenElement);
      await page.locator("#videoRotate").waitFor({ state: "hidden" });
      assert.equal(await page.locator("#videoRotate").isVisible(), false);
      checks++;
    }
    assert.deepEqual(errors, []);
    checks++;
    console.log(JSON.stringify({ base, checks, failures: 0,
      scope: "Pause resume seek mute speeds auto-next lines media-error retry repeated fullscreen" }));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
