# 熊猫视频 · PandaVideo

PandaVideo 提供影视榜单、分类搜索和在线播放，包含网页版以及 Android、iOS、macOS 客户端。本文按 2026-11-11 的工程状态整理；运行验证的日期、设备和限制见对应记录。

## 客户端与功能

| 客户端 | 技术 | 内容与播放 | 下载能力 |
| --- | --- | --- | --- |
| Android | Kotlin Multiplatform、Compose、Media3 | 豆瓣榜单，Pomo / 荐片资源搜索与内置播放 | 应用内 HLS / MP4 离线缓存 |
| iOS | Kotlin Multiplatform、Compose、AVPlayer | 与 Android 共用页面、搜索和来源解析 | 应用内 HLS / MP4 离线缓存 |
| macOS | Compose Desktop、JavaFX Media | 复用共享业务与页面，原生窗口全屏 | 不提供 NAS 下载 |
| 网页版 | Python 标准库、HTML / CSS / JavaScript | 榜单、搜索、阳光电影在线资源与浏览器播放 | 调用迅雷或复制下载链接，支持 NAS 部署 |

移动端与桌面端直接访问内容来源，无需启动 Python 服务。网页版使用自己的服务和来源适配器，目前仍保留 `huolot.com` 来源；移动端的 Pomo / 荐片切换机制不适用于网页版。

移动端支持收藏、观看记录、续播、搜索历史、深色模式、播放设置和迷你播放条。Android / iOS 还提供媒体会话、画中画及 DLNA 投屏，能力受平台、设备和媒体来源限制，详见 [客户端说明](mobile/README.md)。

## 工程结构

```text
PandaVideo/
├── README.md
├── doc/                      # 文档索引、AI 分析、历史规划、跨模块验证
│   ├── analysis/
│   ├── prd/
│   └── verification/
├── frontend/                 # 网页资源、Python 服务、NAS 部署与测试
│   ├── app.py
│   ├── static/
│   ├── tests/
│   ├── docs/
│   └── .env.example
├── mobile/                   # KMP / Compose 客户端
│   ├── app/                  # Android 宿主
│   ├── iosApp/               # iOS 宿主与 Xcode 工程
│   ├── desktop/              # macOS 宿主与 DMG 打包
│   ├── shared/               # 共享业务、UI 与平台实现
│   └── docs/
└── tools/                    # 图标生成等工具
```

## 启动网页版

需要 Python 3.9 或更高版本。以下命令在仓库根目录运行：

```sh
cp frontend/.env.example frontend/.env
chmod 600 frontend/.env
./frontend/run.sh
```

按需编辑 `frontend/.env`。默认访问 `http://127.0.0.1:8766`，无需模型 API Key 即可使用已启用的站点搜索。NAS 路径、迅雷和可选模型配置见 [网页版说明](frontend/README.md)，局域网部署见 [NAS 部署说明](frontend/NAS部署.md)。

## 构建客户端

Android 需要 JDK 21 和 Android SDK。在 `mobile/` 目录运行：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:installChinaDebug
```

iOS 使用 `mobile/iosApp/PandaVideo.xcodeproj` 和 `PandaVideo` scheme；macOS 使用 `desktop` Gradle 模块。完整环境、构建命令、产物路径和签名要求见 [客户端构建说明](mobile/README.md)。

## 测试与验证

网页版测试在仓库根目录运行：

```sh
PYTHONPATH=frontend python3 -m unittest discover -s frontend/tests
node --test frontend/tests/test_list_pagination.js
```

共享测试在已配置 JDK 21 的 `mobile/` 目录运行：

```sh
./gradlew :shared:testAndroidHostTest :shared:jvmTest
```

浏览器测试见 [浏览器自测说明](frontend/tests/browser/README.md)。真实来源、平台构建和设备播放应分别验证；单元测试通过不代表所有影片可播放。

2026-11-11 的备用源验证记录包含 362 项共享测试、双端构建和 Android 实机播放。iOS 完成构建、安装和启动，播放自动化因模拟器服务连接失败未执行，详见 [播放源验证记录](doc/verification/source-failover-20261111.md)。

## 文档入口

- [文档索引与维护规范](doc/README.md)
- [客户端使用与开发](mobile/README.md)
- [网页版使用与部署](frontend/README.md)
- [荐片客户端静态分析导读](doc/analysis/jianpian-analysis-20261111.md)
- [远程配置与备份](mobile/docs/远程配置与备份.md)
- [离线缓存说明](mobile/docs/离线缓存.md)
- [移动端实现与验证记录](mobile/docs/实现与验证记录.md)

AI 分析集中放在 `doc/analysis/`，产品规划放在 `doc/prd/`，跨模块验证放在 `doc/verification/`。模块专属文档保留在各自的 `docs/` 中，由文档索引统一导航。
