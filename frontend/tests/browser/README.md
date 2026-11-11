# 浏览器自测

这些脚本覆盖播放器边界、缓冲与全屏、搜索后点击播放、触屏交互、播放控制和响应式布局。历史报告见 [完整自测报告](../../docs/完整自测报告.md)，本次设计改动见 [网页版设计同步](../../docs/网页版设计同步.md)。

媒体夹具已归入 `fixtures/`，不依赖原来的临时目录。运行结果与截图默认写入系统临时目录中的 `pandavideo-selftest/`，可用 `NAS_TEST_OUTPUT` 指定其他位置。

## 安装与运行

要求 Node.js 20 或更新版本，以及一个可访问的 NAS 视频网站。

在网站工程的 `frontend/tests/browser/` 目录运行：

```sh
npm install
SITE_URL=http://127.0.0.1:8766 npm test
SITE_URL=http://127.0.0.1:8766 npm run test:web
```

macOS 默认使用已安装的 Google Chrome。其他环境可以指定 `BROWSER_PATH`，或安装 Playwright Chromium：

```sh
npx playwright install chromium
```

在 `frontend/` 目录可以单独运行某项：

```sh
SITE_URL=http://127.0.0.1:8766 node tests/browser/player-edge.cjs
SITE_URL=http://127.0.0.1:8766 node tests/browser/player-stability.cjs
SITE_URL=http://127.0.0.1:8766 node tests/browser/autoplay-dplayer.cjs
SITE_URL=http://127.0.0.1:8766 node tests/browser/touch-controls.cjs
SITE_URL=http://127.0.0.1:8766 node tests/browser/player-flow.cjs
SITE_URL=http://127.0.0.1:8766 node tests/browser/check.cjs
```

`npm test` 对播放器 API 与媒体使用受控夹具；首页和分类页的部分检查仍需要网站能正常获取列表数据。脚本不会启动网站服务。

`test:web` 检查 320、390、720、840、1440px 布局、搜索不自动打开首个结果、NAS 弹窗、下载/复制/挂载/手动链接请求、失败保留结果、海报进入播放和外观持久化。下载相关请求全部 mock，不会发起真实传输。截图保存为 `web-sync-*.png`。

## 真实 HLS

```sh
SITE_URL=http://127.0.0.1:8766 npm run test:real
```

默认验证本机网站的桌面与手机触屏模拟。要额外验证 NAS，在运行时传入 `NAS_SITE_URL`：

```sh
SITE_URL=http://127.0.0.1:8766 NAS_SITE_URL=http://NAS主机:8766 npm run test:real
```

真实源站可能不可用或网络不稳定，失败需要结合媒体与网络状态分析。手机模拟不是实体手机或 Safari/WebKit 的兼容性验证。
