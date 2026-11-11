# Android 验证证据

日期：2026-11-09。设备为 PICO B3110 / Android 16，应用窗口 1080×1920。

01–25 属于旧 v0.2 服务客户端。本次 v0.3 独立应用的有效证据为：

| 文件 | 内容 |
| --- | --- |
| 33-standalone-final-catalog.json | 直连豆瓣真实榜单，ROM spatial semantic snapshot |
| 34-standalone-search-form.json | 榜单标题传入搜索，不要求服务地址 |
| 35-standalone-search-results.json | 直连阳光电影，1 条可播放真实结果 |
| 36-standalone-playback.json | 原生正在播放、0:06 / 143:47、线路与剧集标签 |
| standalone-ios-primary.png | iOS 模拟器有效主屏，真实榜单与海报 |
| standalone-final-checks.log | 两平台各 21 个共享测试、Android 安装/lint、Apple Framework |
| standalone-ios-*-build.log | 最终 iOS 模拟器与未签名真机包构建 |

其他 26–32 或副屏文件是取证过程诊断记录，不作为功能通过证据。当前 iPhone unavailable，iOS 真机和真实原生播放尚未验收。

本目录 XML 来自 `adb exec-out uiautomator dump`，包含辅助功能节点、文本和控件边界；不是截图。点击位置依据 UI 树计算。PICO 可使用 `input tap x y layerId --layerid`，图层编号从当时 UI 树读取。

| 文件 | 内容 |
| --- | --- |
| 01–03 | 首次启动、设置及 QA 连接状态 |
| 04–05 | QA 发现条目及 MP4 开始播放 |
| 06–07 | MP4 已自动进入第 2 集，末集播放结束；06 的文件名是操作意图，实际状态以 XML 为准 |
| 08 | 手动返回第 1 集后暂停 |
| 09–10 | 1.5× 倍速、备用线路选集后暂停 |
| 11 | 全屏承载区域及退出入口 |
| 12 | 退出后重新启动，仍加载 QA 服务 |
| 13–14 | HLS 从 0 秒播放到 8 秒后暂停 |
| 15–16 | 分类界面和纪录片/喜剧筛选 |
| 17–18 | 空搜索校验和来源部分失败提示 |
| 19–20 | 恢复正式服务地址及 Top 250 |
| 21 | 最终 APK 安装后启动，仍保留正式服务与榜单 |
| 22–23 | 最终 APK 末集结束显示重新播放，点击后当前集重新播放到 6 秒 |
| 24–25 | 重播复验后再次保存正式服务，最终停留在 Top 250 |

测试通过的含义及未验证范围见 [实现与验证记录](../实现与验证记录.md)。没有在此目录保存视频源站内容、凭据或完整设备日志。

## 2026-11-10 功能批次 J–M

- `features-ios-arm64-build.log`：最终 arm64 iOS 模拟器 App 构建成功日志。
- `features-ios-launch.png`：最终 App 真实榜单启动截图。
- `features-ios-dark.png`：跟随系统深色模式的播放详情与原生画面。
- `features-spatial-target.json`：PICO 真实榜单与底部导航节点。
- 其他 `features-*` 文件记录 UI dump、泛化 Xcode 构建及页面切换尝试的限制，不作为全部 UI 流程通过的证据。
