const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const { fixturePath, outputPath, launchOptions } = require("./test-support.cjs");
const base = process.env.SITE_URL || "http://127.0.0.1:8766";
const label = base.includes("127.0.0.1") ? "local" : "nas";
const results = [];
(async () => {
  const browser = await chromium.launch({
    ...launchOptions,
  });
  const episodes = [1, 2, 3].map(n => ({ path: `/vodplay/12-1-${n}.html`, line: 1, label: `第${n}集` }));
  async function setup(init, options = {}) {
    const p = await browser.newPage({ viewport: { width: 1440, height: 916 }, ...options });
    p.setDefaultTimeout(8000);
    p.setDefaultNavigationTimeout(15000);
    if (init) await p.addInitScript(init);
    const errors = [];
    p.on("pageerror", e => errors.push(e.message));
    p.testErrors = errors;
    await p.route("**/test.mp4", r => {
      const bytes = fs.readFileSync(fixturePath("rotation-test.mp4"));
      const range = /^bytes=(\d+)-(\d*)$/.exec(r.request().headers().range || "");
      if (!range) return r.fulfill({ body: bytes, contentType: "video/mp4",
        headers: { "Accept-Ranges": "bytes", "Content-Length": String(bytes.length) } });
      const start = Number(range[1]), end = range[2] ? Number(range[2]) : bytes.length - 1;
      return r.fulfill({ status: 206, body: bytes.subarray(start, end + 1), contentType: "video/mp4",
        headers: { "Accept-Ranges": "bytes", "Content-Range": `bytes ${start}-${end}/${bytes.length}`,
          "Content-Length": String(end - start + 1) } });
    });
    await p.route("**/api/player?**", r => r.fulfill({ json: {
      title: "边界验证", source: "阳光电影", page_url: "https://huolot.com/voddetail/12.html",
      selected: new URL(r.request().url()).searchParams.get("episode") || episodes[0].path,
      episodes, kind: "video", url: base + "/test.mp4",
    } }));
    return p;
  }
  async function go(p) {
    await p.goto(base + "/player.html?token=edge&title=边界验证");
    await p.waitForFunction(() => document.querySelector("video").currentTime > 0, null, { timeout: 8000 });
    await p.locator("#autoNext").uncheck();
    await p.locator("video").evaluate(v => v.loop = true);
  }
  async function run(name, fn) {
    try { await fn(); results.push({ name, status: "通过" }); }
    catch (e) { results.push({ name, status: "失败", error: e.message.slice(0, 500) }); }
  }
  try {
    const p = await setup();
    await go(p);
    await run("短暂 waiting 后恢复，不闪加载提示", async () => {
      const count = await p.evaluate(async () => {
        const v = document.querySelector("video"), root = document.querySelector("#dplayer");
        let count = 0;
        const o = new MutationObserver(() => { if (root.classList.contains("dplayer-loading")) count++; });
        o.observe(root, { attributes: true, attributeFilter: ["class"] });
        Object.defineProperty(v, "readyState", { value: 2, configurable: true });
        v.dispatchEvent(new Event("waiting"));
        await new Promise(r => setTimeout(r, 150));
        delete v.readyState;
        v.dispatchEvent(new Event("playing"));
        await new Promise(r => setTimeout(r, 550));
        o.disconnect();
        return count;
      });
      assert.equal(count, 0);
    });
    await run("有足够缓冲数据的 stalled 不显示加载提示", async () => {
      await p.locator("video").evaluate(v => v.dispatchEvent(new Event("stalled")));
      await p.waitForTimeout(650);
      assert.equal(await p.locator("#dplayer").evaluate(e => e.classList.contains("dplayer-loading")), false);
    });
    await run("低缓冲 stalled 显示加载，canplay 恢复后清除", async () => {
      await p.locator("video").evaluate(v => {
        Object.defineProperty(v, "readyState", { value: 2, configurable: true });
        v.dispatchEvent(new Event("stalled"));
      });
      await p.waitForFunction(() => document.querySelector("#dplayer").classList.contains("dplayer-loading"), null, { timeout: 2000 });
      await p.locator("video").evaluate(v => { delete v.readyState; v.dispatchEvent(new Event("canplay")); });
      assert.equal(await p.locator("#dplayer").evaluate(e => e.classList.contains("dplayer-loading")), false);
    });
    await run("等待加载期间切集，旧加载计时器不残留", async () => {
      await p.locator("video").evaluate(v => {
        Object.defineProperty(v, "readyState", { value: 2, configurable: true });
        v.dispatchEvent(new Event("waiting"));
      });
      await p.locator("#episodeList button").nth(1).click();
      await p.locator("video").evaluate(v => { delete v.readyState; });
      await p.waitForFunction(() => document.querySelector("#currentEpisode").textContent === "第2集" && !document.querySelector("video").paused);
      await p.waitForTimeout(650);
      assert.equal(await p.locator("#dplayer").evaluate(e => e.classList.contains("dplayer-loading")), false);
    });
    await run("刷新保留当前选集，URL 不依赖临时 token", async () => {
      const url = new URL(p.url());
      assert.equal(url.searchParams.has("token"), false);
      assert.equal(url.searchParams.get("episode"), episodes[1].path);
      await p.reload();
      await p.waitForFunction(() => document.querySelector("#currentEpisode").textContent === "第2集"
        && document.querySelector("video").currentTime > 0 && !document.querySelector("video").paused);
      await p.locator("#autoNext").uncheck();
      await p.locator("video").evaluate(v => v.loop = true);
    });
    await run("键盘空格暂停与恢复，方向键跳转进度", async () => {
      await p.waitForFunction(() => {
        const v = document.querySelector("video");
        return v.seekable.length && v.seekable.end(v.seekable.length - 1) >= 10;
      }, null, { timeout: 8000 });
      await p.locator("video").focus();
      await p.keyboard.press("Space");
      assert.equal(await p.locator("video").evaluate(v => v.paused), true);
      await p.locator("video").evaluate(v => v.currentTime = 3);
      const before = await p.locator("video").evaluate(v => v.currentTime);
      await p.keyboard.press("ArrowRight");
      const after = await p.evaluate(() => ({
        time: video.currentTime, duration: video.duration, focus: player.focus,
        active: document.activeElement.tagName, editable: document.activeElement.getAttribute("contenteditable"),
      }));
      assert.ok(after.time >= before + 4.8, JSON.stringify({ before, ...after }));
      await p.keyboard.press("Space");
      await p.waitForFunction(() => !document.querySelector("video").paused);
    });
    await run("退出播放页后重新打开，不残留全屏与加载遮罩", async () => {
      await p.locator("#fullscreen").click();
      await p.waitForFunction(() => Boolean(document.fullscreenElement));
      await p.goto(base + "/");
      await go(p);
      assert.equal(await p.evaluate(() => document.fullscreenElement), null);
      assert.equal(await p.locator("#videoRotate").isVisible(), false);
      assert.equal(await p.locator("#playerMessage").isVisible(), false);
    });
    await run("快速切集，迟到的第2集响应不会覆盖第3集", async () => {
      await p.unroute("**/api/player?**");
      let release, received;
      const arrival = new Promise(r => received = r);
      const delay = new Promise(r => release = r);
      await p.route("**/api/player?**", async r => {
        const selected = new URL(r.request().url()).searchParams.get("episode") || episodes[0].path;
        if (selected === episodes[1].path) { received(); await delay; }
        try { await r.fulfill({ json: {
          title: "并发验证", source: "阳光电影", page_url: "https://huolot.com/voddetail/12.html",
          selected, episodes, kind: "video", url: base + "/test.mp4",
        } }); } catch (_) { /* The old request was cancelled. */ }
      });
      await p.locator("#episodeList button").nth(1).click();
      await arrival;
      await p.locator("#episodeList button").nth(2).click();
      await p.waitForFunction(() => document.querySelector("#currentEpisode").textContent === "第3集" && !document.querySelector("video").paused);
      release();
      await p.waitForTimeout(250);
      assert.equal(await p.locator("#currentEpisode").textContent(), "第3集");
    });
    await run("接口 500 错误可见并可重试恢复", async () => {
      await p.unroute("**/api/player?**");
      let failed = true;
      await p.route("**/api/player?**", r => failed
        ? r.fulfill({ status: 500, json: { error: "测试接口暂不可用" } })
        : r.fulfill({ json: { title: "重试恢复", source: "本地视频", episodes: [], kind: "video", url: base + "/test.mp4" } }));
      await p.goto(base + "/player.html?token=edge");
      await p.locator("#retryPlay").waitFor({ state: "visible" });
      assert.match(await p.locator("#playerStatus").textContent(), /暂不可用/);
      failed = false;
      await p.locator("#retryPlay").click();
      await p.waitForFunction(() => !document.querySelector("video").paused && document.querySelector("#playerMessage").hidden);
    });
    await run("断网请求失败可重试恢复", async () => {
      await p.unroute("**/api/player?**");
      let offline = true;
      await p.route("**/api/player?**", r => offline ? r.abort("internetdisconnected") : r.fulfill({ json: {
        title: "网络恢复", source: "本地视频", episodes: [], kind: "video", url: base + "/test.mp4",
      } }));
      await p.goto(base + "/player.html?token=edge");
      await p.locator("#retryPlay").waitFor({ state: "visible" });
      offline = false;
      await p.locator("#retryPlay").click();
      await p.waitForFunction(() => !document.querySelector("video").paused && document.querySelector("#playerMessage").hidden);
    });
    await run("临时播放入口过期，重新搜索携带片名", async () => {
      await p.unroute("**/api/player?**");
      await p.route("**/api/player?**", r => r.fulfill({ status: 410, json: { error: "结果已过期" } }));
      await p.route("**/api/search", r => r.fulfill({ json: { results: [], sources: [], intent: {} } }));
      await p.goto(base + "/player.html?token=expired&title=边界片名");
      await p.locator("#retryPlay").click();
      await p.waitForURL("**/search.html?query=**");
      assert.equal(new URL(p.url()).searchParams.get("query"), "边界片名");
    });
    await run("没有未捕获 JavaScript 异常", async () => assert.deepEqual(p.testErrors, []));
    await p.close();
    const blocked = await setup(() => {
      const play = HTMLMediaElement.prototype.play;
      let manual = false;
      document.addEventListener("click", e => { if (e.isTrusted) manual = true; }, true);
      HTMLMediaElement.prototype.play = function () {
        return manual ? play.call(this) : Promise.reject(new DOMException("Test autoplay policy", "NotAllowedError"));
      };
    });
    await run("有声和静音自动播放均被阻止时，可手动播放", async () => {
      await blocked.goto(base + "/player.html?token=blocked");
      await blocked.waitForFunction(() => document.querySelector("#watchHint").textContent.includes("点击播放按钮"));
      assert.equal(await blocked.locator("#playerMessage").isVisible(), false);
      assert.equal(await blocked.locator("video").evaluate(v => v.paused), true);
      await blocked.locator("#dplayer").hover();
      await blocked.locator(".dplayer-play-icon").click();
      await blocked.waitForFunction(() => !document.querySelector("video").paused);
    });
    await blocked.close();
    const fallback = await setup(() => {
      Element.prototype.requestFullscreen = () => Promise.reject(new DOMException("Fullscreen denied", "NotAllowedError"));
    }, { viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
    await go(fallback);
    await run("原生全屏被拒绝时，页面全屏仍可旋转", async () => {
      await fallback.locator("#fullscreen").tap();
      await fallback.locator("#videoRotate").waitFor({ state: "visible" });
      assert.equal(await fallback.locator("#videoStage").evaluate(e => e.classList.contains("is-page-fullscreen")), true);
      await fallback.locator("#videoRotate").tap();
      await fallback.waitForFunction(() => document.querySelector("#videoRotate").getAttribute("aria-pressed") === "true");
    });
    await run("旋转后屏幕切横屏再切竖屏，不溢出且退出恢复", async () => {
      for (const [width, height] of [[844, 390], [390, 844]]) {
        await fallback.setViewportSize({ width, height });
        const metrics = await fallback.locator("#dplayer").evaluate(e => {
          const r = e.getBoundingClientRect();
          return { width: Math.round(r.width), height: Math.round(r.height), overflow: document.documentElement.scrollWidth - innerWidth };
        });
        assert.equal(metrics.width, width);
        assert.equal(metrics.height, height);
        assert.equal(metrics.overflow, 0);
      }
      if (await fallback.locator("#dplayer").evaluate(e => e.classList.contains("dplayer-hide-controller"))) {
        await fallback.locator("video").tap();
      }
      await fallback.waitForFunction(() => getComputedStyle(document.querySelector(".dplayer-controller")).opacity === "1");
      await fallback.locator(".dplayer-full-icon").tap();
      await fallback.waitForFunction(() => !document.querySelector("#dplayer").classList.contains("player-screen-full"));
      assert.equal(await fallback.locator("#dplayer").evaluate(e => e.classList.contains("is-rotated")), false);
    });
    await fallback.close();
    const unsupported = await setup(() => {
      HTMLMediaElement.prototype.canPlayType = () => "";
      document.addEventListener("DOMContentLoaded", () => { window.Hls.isSupported = () => false; });
    });
    await unsupported.unroute("**/api/player?**");
    await unsupported.route("**/api/player?**", r => r.fulfill({ json: {
      title: "不支持格式", source: "本地视频", episodes: [], kind: "hls", url: base + "/test.m3u8",
    } }));
    await run("不支持 HLS 的浏览器显示明确错误和重试", async () => {
      await unsupported.goto(base + "/player.html?token=unsupported");
      await unsupported.locator("#retryPlay").waitFor({ state: "visible" });
      assert.match(await unsupported.locator("#playerStatus").textContent(), /不支持/);
    });
    await unsupported.close();
  } finally {
    await browser.close();
    fs.writeFileSync(outputPath(`edge-${label}.json`), JSON.stringify({ base: label, results }, null, 2));
  }
  console.log(JSON.stringify({ environment: label, checks: results.length, failures: results.filter(r => r.status === "失败") }));
  if (results.some(r => r.status === "失败")) process.exitCode = 1;
})().catch(e => { console.error(e); process.exitCode = 1; });
