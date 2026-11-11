const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const { fixturePath, outputPath, launchOptions } = require("./test-support.cjs");
const base = process.env.SITE_URL || "http://127.0.0.1:8766";
let checks = 0;
(async () => {
  const browser = await chromium.launch({
    ...launchOptions,
    headless: true,
  });
  try {
    for (const touch of [false, true]) {
      const page = await browser.newPage({
        viewport: touch ? { width: 390, height: 844 } : { width: 1440, height: 916 },
        hasTouch: touch, isMobile: touch,
        ...(touch ? { userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1" } : {}),
      });
      const errors = [];
      page.on("pageerror", e => errors.push(e.message));
      await page.addInitScript(touch => {
        // Reproduce browsers exposing a coarse media clock: playback still advances.
        const clock = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "currentTime");
        Object.defineProperty(HTMLMediaElement.prototype, "currentTime", {
          get() { return Math.floor(clock.get.call(this) * 4) / 4; },
          set(v) { clock.set.call(this, v); },
        });
        if (touch) {
          Element.prototype.requestFullscreen = undefined;
          Element.prototype.webkitRequestFullscreen = undefined;
        }
      }, touch);
      await page.route("**/test.mp4", r => r.fulfill({
        path: fixturePath("rotation-test.mp4"), contentType: "video/mp4",
      }));
      await page.route("**/api/player?**", r => r.fulfill({ json: {
        title: "播放稳定性测试", source: "本地视频", episodes: [], kind: "video", url: base + "/test.mp4",
      } }));
      await page.goto(base + "/player.html?token=test");
      await page.waitForFunction(() => document.querySelector("video").currentTime > 0);
      const flashes = await page.evaluate(async () => {
        const root = document.querySelector("#dplayer");
        const states = [];
        const observer = new MutationObserver(() => {
          if (root.classList.contains("dplayer-loading")) states.push(performance.now());
        });
        observer.observe(root, { attributes: true, attributeFilter: ["class"] });
        await new Promise(resolve => setTimeout(resolve, 1800));
        observer.disconnect();
        return states.length;
      });
      console.log(JSON.stringify({ touch, normalPlaybackLoadingFlashes: flashes }));
      if (!process.env.DIAGNOSE) assert.equal(flashes, 0, "Advancing media must not flash the buffering icon");
      checks++;
      await page.locator("#fullscreen")[touch ? "tap" : "click"]();
      await page.waitForFunction(() => document.querySelector("#dplayer").classList.contains("player-screen-full"));
      await page.waitForFunction(() => document.querySelector("#dplayer").classList.contains("dplayer-hide-controller"), null, { timeout: 5000 });
      await page.waitForFunction(() => getComputedStyle(document.querySelector(".dplayer-controller")).opacity === "0");
      const idle = await page.evaluate(() => {
        const stage = document.querySelector("#videoStage").getBoundingClientRect();
        const control = getComputedStyle(document.querySelector(".dplayer-controller"));
        const bar = getComputedStyle(document.querySelector(".dplayer-bar-wrap"));
        const p = document.elementFromPoint(innerWidth / 2, innerHeight - 1);
        return { full: stage.width === innerWidth && stage.height === innerHeight,
          controlsHidden: control.opacity === "0", barHidden: bar.display === "none",
          bottomInsidePlayer: Boolean(p?.closest("#videoStage")) };
      });
      console.log(JSON.stringify({ touch, idle }));
      assert.deepEqual(idle, { full: true, controlsHidden: true, barHidden: true, bottomInsidePlayer: true });
      await page.screenshot({ path: outputPath(`fullscreen-idle-${touch ? "phone" : "desktop"}.png`) });
      checks += 4;
      if (touch) await page.locator("video").tap();
      else await page.locator("#dplayer").hover();
      await page.locator("#videoRotate").waitFor({ state: "visible" });
      await page.locator("#videoRotate")[touch ? "tap" : "click"]();
      await page.waitForFunction(() => document.querySelector("#videoRotate").getAttribute("aria-pressed") === "true");
      checks++;
      if (touch) await page.locator("#videoRotate").tap();
      await page.locator(".dplayer-full-icon")[touch ? "tap" : "click"]();
      await page.waitForFunction(() => !document.querySelector("#dplayer").classList.contains("player-screen-full"));
      assert.equal(await page.locator("#videoRotate").isVisible(), false);
      checks++;
      // Actual buffering receives delayed feedback, then clears on recovery.
      await page.evaluate(() => {
        const v = document.querySelector("video");
        Object.defineProperty(v, "readyState", { configurable: true, value: 2 });
        v.dispatchEvent(new Event("waiting"));
      });
      await page.waitForFunction(() => document.querySelector("#dplayer").classList.contains("dplayer-loading"));
      await page.locator("video").evaluate(v => { delete v.readyState; v.dispatchEvent(new Event("playing")); });
      await page.waitForFunction(() => !document.querySelector("#dplayer").classList.contains("dplayer-loading"));
      checks++;
      await page.locator("video").evaluate(v => { v.pause(); v.dispatchEvent(new Event("waiting")); });
      await page.waitForTimeout(650);
      assert.equal(await page.locator("#dplayer").evaluate(e => e.classList.contains("dplayer-loading")), false);
      checks++;
      // A tap reveals touch controls, which must also auto-hide afterwards.
      await page.locator("video").evaluate(v => v.play());
      if (touch) {
        await page.waitForFunction(() => document.querySelector("#dplayer").classList.contains("dplayer-hide-controller"));
        await page.locator("video").tap();
        await page.waitForFunction(() => !document.querySelector("#dplayer").classList.contains("dplayer-hide-controller"));
        await page.waitForFunction(() => document.querySelector("#dplayer").classList.contains("dplayer-hide-controller"));
        checks++;
      }
      assert.deepEqual(errors, []);
      checks++;
      await page.close();
    }
    console.log(JSON.stringify({ base, checks, failures: 0 }));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
