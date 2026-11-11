const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const { fixturePath, launchOptions } = require("./test-support.cjs");
const base = process.env.SITE_URL || "http://127.0.0.1:8766";
(async () => {
  const browser = await chromium.launch({
    ...launchOptions,
  });
  let checks = 0;
  try {
    for (const userAgent of [
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1",
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15",
    ]) {
      const page = await browser.newPage({
        viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, userAgent,
      });
      const errors = [];
      page.on("pageerror", e => errors.push(e.message));
      await page.addInitScript(() => {
        Element.prototype.requestFullscreen = undefined;
        Element.prototype.webkitRequestFullscreen = undefined;
      });
      await page.route("**/api/player?**", r => r.fulfill({ json: {
        title: "触控验证", source: "本地视频", episodes: [], kind: "video", url: base + "/test.mp4",
      } }));
      await page.route("**/test.mp4", r => r.fulfill({
        path: fixturePath("rotation-test.mp4"), contentType: "video/mp4",
      }));
      await page.goto(base + "/player.html?token=test");
      await page.waitForFunction(() => !document.querySelector("video").paused);
      await page.waitForFunction(() => document.querySelector("#dplayer").classList.contains("dplayer-hide-controller"));
      await page.locator("video").tap();
      await page.waitForFunction(() => !document.querySelector("#dplayer").classList.contains("dplayer-hide-controller"));
      await page.waitForFunction(() => getComputedStyle(document.querySelector(".dplayer-controller")).opacity === "1");
      assert.equal(await page.locator("video").evaluate(v => v.paused), false);
      assert.equal(await page.locator(".dplayer-controller").evaluate(e => getComputedStyle(e).opacity), "1");
      assert.equal(await page.locator(".dplayer-full-in-icon").isVisible(), false);
      const tooltip = page.locator("[data-balloon]").first();
      if (await tooltip.count()) {
        assert.equal(await tooltip.evaluate(e => getComputedStyle(e, "::after").display), "none");
      }
      checks += 4;
      await page.locator(".dplayer-setting-icon").tap();
      await page.locator(".dplayer-setting-box-open").waitFor();
      assert.equal(await page.locator(".dplayer-setting-box-open").count(), 1);
      checks++;
      await page.locator(".dplayer-mask-show").tap();
      await page.locator("#fullscreen").tap();
      await page.locator("#videoRotate").waitFor({ state: "visible" });
      assert.equal(await page.locator("#videoRotate").textContent(), "旋转屏幕");
      await page.locator("#videoRotate").tap();
      await page.waitForFunction(() => document.querySelector("#videoRotate").getAttribute("aria-pressed") === "true");
      assert.equal(await page.locator("video").evaluate(v => v.paused), false);
      checks += 2;
      await page.locator("#videoRotate").tap();
      await page.locator(".dplayer-full-icon").tap();
      await page.locator("#videoRotate").waitFor({ state: "hidden" });
      checks++;
      await page.goto(base + "/catalog.html");
      await page.locator(".browse-card").first().waitFor();
      const beforeUrl = page.url();
      const card = page.locator(".browse-card").first();
      await card.scrollIntoViewIfNeeded();
      const client = await page.context().newCDPSession(page);
      const rect = await card.boundingBox();
      const x = rect.x + rect.width / 2;
      const y = Math.min(600, rect.y + 80);
      const beforeScroll = await page.evaluate(() => scrollY);
      await client.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x, y }] });
      for (const offset of [30, 80, 140, 200]) {
        await client.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x, y: y - offset }] });
      }
      await client.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
      await page.waitForFunction(before => scrollY > before, beforeScroll);
      assert.equal(page.url(), beforeUrl);
      assert.equal(await card.locator(".media-poster > img").evaluate(e => getComputedStyle(e).transform), "none");
      assert.equal(await card.evaluate(e => getComputedStyle(e).boxShadow), "none");
      assert.deepEqual(errors, []);
      checks += 4;
      await page.close();
    }
    const mouse = await browser.newPage({ viewport: { width: 1440, height: 916 } });
    await mouse.goto(base + "/catalog.html");
    const poster = mouse.locator(".browse-card .media-poster").first();
    await poster.waitFor();
    await poster.hover();
    await mouse.waitForFunction(() => getComputedStyle(document.querySelector(".browse-card .media-poster > img")).transform !== "none");
    checks++;
    await mouse.close();
    console.log(JSON.stringify({ base, checks, failures: 0,
      scope: "Touch taps, settings, fullscreen rotation, real touch swipe, tablet desktop UA, desktop hover" }));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
