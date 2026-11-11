# 熊猫视频 · PandaVideo 客户端

更新日期：2026-11-11。适用范围：Android、iOS 与 macOS 当前工程。应用使用 Kotlin Multiplatform 和 Compose Multiplatform，共享页面、请求、解析、分页、用户数据及播放业务，直接访问内容来源，无需配置 Python 服务或 NAS 地址。

Android / iOS 安装标识为 `dev.pandavideo`，macOS 为 `dev.pandavideo.desktop`。更换旧安装标识后，系统会视为新应用，旧应用本地数据不会自动迁移。

## 页面与核心流程

主导航提供发现、最近热播、分类和我的；显示顺序受导航规则与个人配置影响。搜索放在主页面顶部，支持搜索历史、已加载内容联想和真实热播推荐。分类可组合类型、题材、国家或地区、年份与排序。

榜单与分类来自豆瓣，在线播放资源来自 Pomo（`https://pomo.mom/`）和荐片公开 HTTP 接口。点击作品后按片名、年份及已有演员信息匹配资源，再进入详情、切换线路和选集播放。未能直接匹配时，可回到搜索结果继续选择。

列表支持下拉刷新、末尾预加载、分页去重和缓存。后台检查只隐藏正常搜索确认无匹配资源的条目；检查未完成、超时、验证页或解析失败时保留内容，避免误删可播放影片。豆瓣榜单返回 HTTP 403 与播放源故障是两个独立问题。

## 播放源选择与恢复

- Pomo 与荐片并行搜索，优先使用最快的非空有效结果。只有全部来源正常返回且都无匹配时，才确认资源缺失。
- 荐片镜像按初始化和实际请求耗时排序，每 10 分钟重新探测。接口失败时尝试下一镜像，全部已知镜像失效时，在当次请求中刷新公开域名列表和 DNS 发现结果。
- 发现的候选只接受合法 HTTPS/443 地址；不同镜像分别初始化和签名。接口测速不代表视频下载带宽。
- 当前接入 HTTP HLS / MP4 线路，优先选择 HLS；未移植荐片包外的 FTP / P2P 引擎。荐片媒体地址在播放前检查可访问性。
- 解析失败先尝试同片同集的其他线路，再按片名、年份和集名匹配另一来源。原生播放报错最多自动恢复两次，排除失败地址并保留进度和播放意图；隐藏的播放页不启动新的自动恢复。

搜索与详情可缓存，媒体地址在播放时重新解析。收藏和进度保存来源、影片 ID 与剧集索引，不持久化签名媒体地址。旧阳光电影记录可按标题重新匹配；跨来源的剧集进度不自动映射。

详细运行数据见 [播放源测速与切换验证](../doc/verification/source-failover-20261111.md)。

## 播放、续播与个人数据

| 能力 | 当前行为 |
| --- | --- |
| 播控 | 播放 / 暂停、进度、倍速、线路、选集、下一集与全屏；播放时自动隐藏控件 |
| 手势 | 双击快进 / 快退或暂停，横向拖动进度，纵向调节音量 / 亮度，长按 2 倍速 |
| 全屏详情 | 侧面板展示简介、选集与继续发现；锁定控件与手势可防误触 |
| 续播 | 每 5 秒及切集、退出时保存位置；超过 97% 视为看完；支持下一集倒计时 |
| 迷你播放条 | 离开播放页保留会话并暂停；点击播放返回播放页继续，关闭按钮释放会话 |
| 预加载 | 提前解析同线路下一集，短时间内切集可复用；不预下载视频分片 |
| 进度预览 | Android / iOS 在拖动时尝试提取目标附近画面；不支持的流降级为时间提示 |
| 个人数据 | 本地资料、收藏、观看记录和搜索历史，支持管理及备份 |
| 离线缓存 | Android / iOS 在详情缓存当前集，从“我的 → 离线缓存”管理和离线播放 |
| 偏好 | 跟随系统 / 浅色 / 深色、默认倍速、音量与亮度记忆、睡眠定时 |

Android / iOS 全屏播放切换横屏，退出后恢复竖屏。离开可见播放页或进入后台会暂停；画中画窗口仍可见时允许继续播放。重新显示页面不会自行恢复用户已暂停的视频。关闭播放器才彻底释放会话和原生资源。

## 平台能力与投屏

| 平台 | 播放器 | 平台功能与限制 |
| --- | --- | --- |
| Android | Media3 | 媒体通知与锁屏控制、画中画、触感、DLNA |
| iOS | AVPlayer / AVPlayerLayer | 锁屏控制、画中画、触感、DLNA 与 AirPlay 入口 |
| macOS | JavaFX Media，macOS 后端为 AVFoundation | 窗口全屏、软件音量；亮度及触感为空实现，暂不提供画中画和 DLNA |

DLNA 要求手机和电视位于可互通的局域网，电视开启接收功能。电视接受播放后手机暂停，控件切换为电视播放、进度和结束投屏；结束后保留电视进度，手机需手动继续。切换影片、剧集或离开播放页会结束当前投屏。视频由电视直接读取来源地址，应用不转码、不代理视频。

iOS SSDP 多播发现需要 Apple 批准的 `com.apple.developer.networking.multicast` entitlement，当前工程未配置。可使用“按电视 IP 搜索”尝试单播发现，并授予本地网络访问权限；电视必须响应单播 SSDP。访客网络、AP 隔离、格式不兼容或来源限制都可能导致失败。详细方案见 [投屏评估](docs/投屏评估.md)。

Android / iOS 提供应用内 HLS / MP4 缓存，支持排队、暂停、重试、删除和已缓存剧集离线播放；任务中断后恢复为暂停。macOS 暂未接入。使用方法、存储和平台限制见 [离线缓存说明](docs/离线缓存.md)。

客户端不提供私人 NAS 媒体索引、DRM 或观看进度云同步。NAS 下载由 [网页版](../frontend/README.md) 提供。远程规则和更新通知需配置自部署 HTTPS JSON，见 [远程配置与备份](docs/远程配置与备份.md)。

## 工程结构

以下路径及构建命令均以 `mobile/` 为工作目录。

```text
app/                         # Android 宿主
iosApp/                      # SwiftUI 宿主、Xcode 工程与共享构建脚本
desktop/                     # Compose Desktop 宿主与 DMG 配置
shared/src/
  commonMain/                # app、core、data、ui
  androidMain/               # Media3、OkHttp、Android 存储及平台服务
  iosMain/                   # AVPlayer、Darwin、iOS 存储及平台服务
  jvmMain/                   # JavaFX、OkHttp、Preferences、桌面桥接
  commonTest/                # 解析、状态、规则、用户数据及来源测试
gradle/                      # 版本目录与 Wrapper
docs/                        # 模块方案与验证记录
```

## Android 构建与安装

需要 JDK 21、Android SDK 和可连接的设备或模拟器。最低 Android API 26。

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:installChinaDebug
```

Android Studio 打开本目录。SDK 路径也可通过不入库的 `local.properties` 中的 `sdk.dir` 指定。Debug APK 位于 `app/build/outputs/apk/china/debug/pandavideo.apk`；发布变体为 ChinaRelease，签名需单独配置。

## iOS 构建与运行

使用 Apple Silicon Mac、Xcode 和 JDK 21。共享代码最低 iOS 15，宿主部署版本以 Xcode Build Settings 为准。打开 `iosApp/PandaVideo.xcodeproj`，选择 `PandaVideo` scheme。共享静态 Framework 名称仍为 `SuiXinKanShared.framework`，由 Build Phase 脚本生成，无需 CocoaPods。

```sh
/bin/sh iosApp/build-shared.sh :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/PandaVideo.xcodeproj -scheme PandaVideo \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build
```

脚本读取不入库的 `.xcode.env`；未设置 `JAVA_HOME` 时通过 `java_home -v 21` 查找。可配置 `JAVA_HOME`、`ANDROID_HOME`、`GRADLE_USER_HOME` 和 `KONAN_DATA_DIR`。终端和 Xcode 使用同一脚本可保持缓存配置一致。

不要交替使用不同 `KONAN_DATA_DIR` 构建同一份中间产物，否则可能出现 Kotlin/Native 平台库 `duplicate unique names`。更换缓存目录后，通过脚本对目标平台重新编译，例如：

```sh
/bin/sh iosApp/build-shared.sh \
  :shared:compileKotlinIosArm64 :shared:linkDebugFrameworkIosArm64 --rerun-tasks
```

模拟器使用相应的 `IosSimulatorArm64` 任务。可用模拟器取决于本机安装的 runtime，运行 `xcrun simctl list devices available` 查看；真机运行需配置自己的 Team 和签名。

`Info.plist` 的 `CADisableMinimumFrameDurationOnPhone=true` 是当前 Compose 启动要求，删除可能触发 `PlistSanityCheck` 异常。

## macOS 构建与打包

当前 JavaFX 原生依赖为 Apple Silicon 构件，不需要 VLC。开发运行：

```sh
/bin/sh iosApp/build-shared.sh :desktop:run
```

桌面目标字节码为 JVM 17，打包使用带 `jpackage` 的完整 JDK 17：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
"$JAVA_HOME/bin/jpackage" --version
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  :desktop:createDistributable :desktop:packageDmg
```

| 产物 | 路径 |
| --- | --- |
| macOS 应用 | `desktop/build/compose/binaries/main/app/熊猫视频.app` |
| DMG | `desktop/build/compose/binaries/main/dmg/熊猫视频-1.0.0.dmg` |

窗口默认 1280 × 860。用户偏好使用 `java.util.prefs.Preferences` 保存，节点为 `dev/pandavideo/suixinkan_user`；外链通过系统浏览器打开。当前包未做 Developer ID 签名与公证，首次打开可能需要在系统隐私与安全性设置中允许。

## 图标维护

统一图标源为仓库根目录的 `frontend/static/favicon.svg`，使用熊猫头像和暖橙至薄荷青渐变。生成命令在仓库根目录执行：

```sh
python3 tools/generate_app_icons.py
python3 tools/generate_desktop_icon.py
```

第一个脚本需要 Pillow 和 resvg-py，生成 Android / iOS / 网页资源；第二个需要 Pillow，生成 macOS PNG / ICNS。Android 包含自适应及单色图标，iOS 由系统裁切方形图标，桌面图标保留透明留白。修改源图时同步检查 `frontend/static/site.js` 中的导航 SVG。

## 测试与验收

在 JDK 21 环境中执行共享测试及 Android 检查：

```sh
./gradlew :shared:testAndroidHostTest :shared:jvmTest
./gradlew :app:lintChinaDebug :app:testChinaDebugUnitTest
```

真实来源探测必须显式开启：

```sh
PANDAVIDEO_LIVE_SOURCES=1 /bin/sh iosApp/build-shared.sh \
  :shared:jvmTest --tests dev.pandavideo.LiveSourceProbeTest
```

iOS 共享测试需可用的模拟器环境：

```sh
/bin/sh iosApp/build-shared.sh :shared:iosSimulatorArm64Test
```

2026-11-11 的来源切换验证通过 169 项 Android host 测试和 193 项 JVM 测试，完成 Android 实机播放以及 iOS 构建、安装、启动。iOS 原生播放自动化因模拟器服务连接失败未执行。此处引用既有记录，不表示此次文档改写重新运行了应用测试。

按功能批次的证据见 [实现与验证记录](docs/实现与验证记录.md)。构建、安装、启动、真实媒体播放和发布签名是不同验收步骤；历史样片或旧服务版本的结果不能作为当前来源的完整验收。
