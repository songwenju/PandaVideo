# PandaVideo 播放源测速与故障切换验证

日期：2026-11-11。范围：共享来源解析、荐片镜像、Pomo 备用、平台构建与原生播放。网络数据来自当时本机网络；Android 使用 B3110 实机，iOS 使用 iPhone 15 模拟器。本次文档重写引用既有结果，未重新执行应用测试。

## 结论与验收范围

362 项共享测试通过，真实来源搜索、HLS 清单及分片读取通过，排除荐片媒体地址后切换 Pomo 通过。Android 完成安装、启动和实机播放；iOS 完成共享 Framework、应用构建、安装和启动，原生播放自动化未进入执行阶段。

| 验证项 | 结果 | 证据边界 |
| --- | --- | --- |
| Android host 共享测试 | 16 个套件，169 项，0 failures / errors | 共享逻辑测试 |
| JVM 共享测试 | 22 个套件，193 项，0 failures / errors | 含显式开启的实网探测 |
| 荐片实际播放解析 | 成功 | `ContentRepository.player` 解析，读取 HLS 主 / 子清单与分片 |
| 跨来源备用 | 成功 | 排除实际荐片详情中的全部媒体地址后，真实请求切换 Pomo，HLS 有效 |
| Android 构建与安装 | 成功 | `:app:installChinaDebug` 安装至 1 台实机 |
| Android 原生播放 | 成功 | 搜索 `Avatar`，播放《阿凡达》，Media3 为 `PLAYING`，位置持续增长 |
| iOS Framework / 应用构建 | 成功 | 共享模拟器 Framework 和 Xcode 应用 |
| iOS 安装与启动 | 成功 | 进程启动，截图确认主界面显示 |
| iOS 原生播放自动化 | 未执行 | runner 构建成功，模拟器服务连接失败 |

## 实现行为

Pomo 与荐片并行搜索，使用最快的非空有效结果；部分来源失败不视为“确认无资源”。荐片已知镜像包括 `https://japi.zxfmj.com` 和 `https://pv2026.zxfmj.com`，按初始化及实际请求耗时排序，每 10 分钟重新测速。

失败时切换下一镜像，全部已知镜像失败时，在同一次请求中刷新公开域名与 DNS 发现列表。发现接口仅提供候选，不用于搜索或媒体播放。只接纳合法 HTTPS/443 地址，各镜像独立初始化、签名，密钥只保留在内存。

解析失败先尝试同片同集其他线路，再按片名、年份和集名匹配另一来源。荐片接入 HTTP HLS / MP4，媒体地址播放前做可访问性检查，未接入客户端 FTP / P2P 引擎。

原生错误最多自动恢复两次，排除失败地址，保留位置和播放 / 暂停意图；隐藏或关闭页面、旧媒体事件不会启动新恢复。手动重试清除当次排除列表。收藏和进度保存稳定标识，不保存签名视频地址。

## 接口测速

关键词为 `春夏秋冬又一春`，单位为毫秒，只统计成功请求。

| 镜像搜索，curl | 第 1 次 | 第 2 次 | 第 3 次 | 平均 |
| --- | ---: | ---: | ---: | ---: |
| japi.zxfmj.com | 199 | 295 | 221 | 238 |
| pv2026.zxfmj.com | 398 | 387 | 379 | 388 |

| 生产网络客户端，Ktor / OkHttp | 第 1 次 | 第 2 次 | 第 3 次 | 平均 |
| --- | ---: | ---: | ---: | ---: |
| 荐片 | 695 | 123 | 425 | 414 |
| Pomo | 1359 | 1146 | 2156 | 1554 |

两张表来自不同客户端，不应直接横向比较。首次荐片请求含初始化；此前 Pomo 后续请求曾降至 73–90 ms，说明连接、缓存和网络会影响选择。应用使用运行时排序，不永久锁定本次较快镜像。

这些数据不代表媒体下载带宽、长期可用性或其他设备的网络表现。

## 设备证据与限制

Android 媒体会话先后显示播放位置 19,621 ms 和 32,508 ms，无播放错误；当次 crash buffer 无输出。这证明该设备、该影片和当时来源完成播放，不代表全部影片验收。

iOS 自动化连接 `com.apple.instruments.deviceservice.lockdown` 失败，报告 `DTServiceHubClient failed to bless service hub`。测试未开始执行，挂起命令已停止。应用构建与主界面显示成功不能替代 iOS 原生播放验证。

当次产物路径：

| 平台 | 路径，相对仓库根目录 |
| --- | --- |
| Android APK | `mobile/app/build/outputs/apk/china/debug/pandavideo.apk` |
| iOS App | `mobile/build/backup-source-ios/Build/Products/Debug-iphonesimulator/PandaVideo.app` |

产物可由后续构建覆盖或清理，路径不表示当前工作区仍保留该文件。该记录不作为发布签名、iOS 真机或全部设备的验收结论。

## 复测命令

在 `mobile/` 目录设置 JDK 21 和 Android SDK：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$HOME/Library/Android/sdk"
/bin/sh iosApp/build-shared.sh :shared:testAndroidHostTest :shared:jvmTest
```

实网探测需要显式开启，默认单元测试跳过此项：

```sh
PANDAVIDEO_LIVE_SOURCES=1 /bin/sh iosApp/build-shared.sh \
  :shared:jvmTest --tests dev.pandavideo.LiveSourceProbeTest
```

实网探测仅输出耗时和来源名，不输出密钥或媒体 URL。复测时分别记录测试、媒体清单、设备启动与实际播放，不能用接口响应替代视频验证。

相关文档：[客户端说明](../../mobile/README.md)、[实现与验证记录](../../mobile/docs/实现与验证记录.md)、[荐片静态分析导读](../analysis/jianpian-analysis-20261111.md)。
