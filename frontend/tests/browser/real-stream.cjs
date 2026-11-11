const { chromium } = require("playwright");
const fs = require("node:fs");
const assert = require("node:assert/strict");
const { outputPath, launchOptions } = require("./test-support.cjs");
const localBase = process.env.SITE_URL || "http://127.0.0.1:8766";
const nasBase = process.env.NAS_SITE_URL;
(async () => {
  const browser = await chromium.launch({
    ...launchOptions,
  });
  const results = [];
  try {
    const scenarios = process.env.ONLY_LOCAL_PHONE ? [
      ["本机 手机模拟", localBase, true],
    ] : [
      ["本机", localBase, false],
      ["本机 手机模拟", localBase, true],
      ...(nasBase ? [["NAS", nasBase, false], ["NAS 手机模拟", nasBase, true]] : []),
    ];
    for (const [environment, base, touch] of scenarios) {
      const p = await browser.newPage(touch ? {
        viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true,
        userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1",
      } : { viewport: { width: 1440, height: 916 } });
      if (touch) await p.addInitScript(() => {
        Element.prototype.requestFullscreen = undefined;
        Element.prototype.webkitRequestFullscreen = undefined;
      });
      const errors = [];
      p.on("pageerror", e => errors.push(e.message));
      let progress;
      try {
        await p.goto(base + "/player.html?page=https%3A%2F%2Fhuolot.com%2Fvodplay%2F82465-1-1.html");
        await p.waitForFunction(() => document.querySelector("video").currentTime > 1, null, { timeout: 25000 });
        if (touch) {
          await p.locator("#fullscreen").tap();
          await p.locator("#videoRotate").tap();
          await p.waitForFunction(() => document.querySelector("#videoRotate").getAttribute("aria-pressed") === "true");
          await p.waitForFunction(() => getComputedStyle(document.querySelector(".dplayer-controller")).opacity === "0");
          assert.equal(await p.locator(".dplayer-bar-wrap").evaluate(e => getComputedStyle(e).display), "none");
        }
        progress = await p.evaluate(async seconds => {
          const v = document.querySelector("video");
          const start = v.currentTime;
          let loadingEntries = 0;
          let last = document.querySelector("#dplayer").classList.contains("dplayer-loading");
          const observer = new MutationObserver(() => {
            const now = document.querySelector("#dplayer").classList.contains("dplayer-loading");
            if (now && !last) loadingEntries++;
            last = now;
          });
          observer.observe(document.querySelector("#dplayer"), { attributes: true, attributeFilter: ["class"] });
          await new Promise(r => setTimeout(r, seconds * 1000));
          observer.disconnect();
          return { observedSeconds: seconds, advancedSeconds: Math.round((v.currentTime - start) * 10) / 10,
            loadingEntries, paused: v.paused, overlayHidden: document.querySelector("#playerMessage").hidden };
        }, touch ? 8 : 20);
        assert.ok(progress.advancedSeconds >= progress.observedSeconds * 0.75);
        assert.equal(progress.paused, false);
        assert.equal(progress.overlayHidden, true);
        assert.deepEqual(errors, []);
        results.push({ environment, status: "通过", ...progress, errors });
      } catch (e) { results.push({ environment, status: "失败", ...progress, error: e.message }); }
      await p.close();
    }
  } finally {
    await browser.close();
    fs.writeFileSync(outputPath(`${process.env.ONLY_LOCAL_PHONE ? "real-phone-local" : "real-stream-results"}.json`), JSON.stringify(results, null, 2));
  }
  console.log(JSON.stringify(results));
  if (results.some(r => r.status !== "通过")) process.exitCode = 1;
})().catch(e => { console.error(e); process.exitCode = 1; });
