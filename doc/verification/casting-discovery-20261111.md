# 投屏设备发现修复与验证

日期：2026-11-11。状态：代码、共享测试、Android 实机协议测试及双端构建已验证；用户电视发现与真实投屏仍待实测。

## 问题与证据

用户反馈同一网络下搜索不到投屏设备。尚未收到用户的发送端系统及电视品牌型号，因此不能将以下代码问题直接认定为该电视的最终根因。

| 代码问题 | 依据 | 修复 |
| --- | --- | --- |
| 搜索仅发送 MediaRenderer:1 请求 | 原 `ssdpRequest` 只有一个 ST；新增回归用例在原行为下失败 | 同时查询 MediaRenderer:1、AVTransport:1 与 ssdp:all，仍校验设备描述中的 MediaRenderer/AVTransport |
| 定向搜索使用电视 IP 作为 HOST 头 | 原 Android/iOS 把 UDP 目标地址传入请求构造函数 | HOST 固定为 SSDP 的 239.255.255.250:1900，电视 IP 仅作为 UDP 发送目标 |
| Android 未明确选择本地网络及网卡 | 原 DatagramSocket 使用系统默认网络 | 绑定已连接 Wi-Fi/有线网络、IPv4 地址及网卡，避免默认移动网络/VPN 路由影响 |
| 两次搜索报文连续发送 | 原代码立即发送两次后只等待 | Android 间隔约一秒重试；接收窗口五秒 |
| 慢设备信息请求阻塞全部结果 | 原界面在 awaitAll 后一次性发布；新增快速/慢速描述用例失败 | 每台可用电视立即显示；设备描述获取总预算八秒，四个并发请求 |
| 具体搜索错误被通用提示覆盖 | 新增权限/网络异常用例在原行为下失败 | 保留应用自己生成的安全错误文案，不显示外部异常内容 |

iOS 首次本地网络权限提示期间可能拒绝初始报文，现在在五秒窗口内每秒重试，仍保留取消检查与原生 socket 释放。此修改不授予 Apple 组播 entitlement。

## 用户可见行为

- 重新搜索期间保留已显示的电视；新结果及时出现，搜索结束后替换为本轮验证的设备。
- iOS AirPlay 入口放在 DLNA 列表上方，使用系统路由选择器独立搜索。
- DLNA 列表明确标注接收协议，空态提示开启电视投屏或输入电视 IP。
- 没有可用局域网、无法读取设备描述、本地网络访问失败分别给出可操作提示。
- 本次未修改连接、播放、暂停、seek 与结束投屏的协议。

## 验证结果

构建读取 `mobile/.xcode.env`，使用 `mobile/tools/feature-verification.init.gradle` 及独立输出目录 `mobile/build/casting-discovery-verify`。

| 验证项 | 结果 | 验证边界 |
| --- | --- | --- |
| 原搜索范围回归 | 原行为 11 个协议用例中 1 个失败；修复后 11 个全部通过 | 覆盖搜索类型及标准请求头 |
| 原列表阻塞、提示丢失回归 | 原行为 7 个会话用例中 2 个失败；修复后 7 个全部通过 | Mock HTTP 描述、虚拟时间及错误提示 |
| 完整 Android host 测试 | 17 套件、184 用例，0 失败、0 错误、0 跳过 | 共享业务和会话测试 |
| Android ChinaDebug 构建及安装 | 成功，安装到 PICO B3110 / Android 16 | 真实应用安装，不代表电视配对成功 |
| iOS Simulator Arm64 Framework | 编译及链接成功 | 未部署 iOS App，未验证真实 AirPlay 或 iPhone SSDP |
| Android 实机投屏协议测试 | 2 用例，0 失败、0 错误、0 跳过 | 回环、真实 Wi-Fi 地址、UDP 请求/应答、只响应 AVTransport 的模拟接收器、重复响应去重 |

设备测试使用随机 UDP 端口，不占用系统现有 1900 端口。Wi-Fi 用例在 PICO 自身 Wi-Fi IPv4 地址上运行模拟接收器，验证了实际网卡绑定和收发，但没有跨路由器或与用户电视通信。

主要命令（在 `mobile/` 中执行）：

```sh
/bin/sh iosApp/build-shared.sh \
  --init-script tools/feature-verification.init.gradle \
  -PverificationBuildRoot=build/casting-discovery-verify \
  :shared:testAndroidHostTest :app:installChinaDebug \
  :shared:linkDebugFrameworkIosSimulatorArm64

/bin/sh iosApp/build-shared.sh \
  --init-script tools/feature-verification.init.gradle \
  -PverificationBuildRoot=build/casting-discovery-verify \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.pandavideo.AndroidDlnaDeviceTest \
  :shared:connectedAndroidDeviceTest
```

产物及测试结果：

- APK：`mobile/build/casting-discovery-verify/app/outputs/apk/china/debug/pandavideo.apk`
- Framework：`mobile/build/casting-discovery-verify/shared/bin/iosSimulatorArm64/debugFramework/SuiXinKanShared.framework`
- Host XML：`mobile/build/casting-discovery-verify/shared/test-results/testAndroidHostTest/`
- 实机 XML：`mobile/build/casting-discovery-verify/shared/outputs/androidTest-results/connected/androidMain/`

## 尚未验证的实际条件

用户电视品牌、型号、接收协议、同网段可达性及路由器隔离设置仍未知。“同一 Wi-Fi”不保证设备间单播或组播互通。

iOS 工程当前未配置 `com.apple.developer.networking.multicast` entitlement；自动 DLNA 搜索仍有系统限制。需要 Apple 批准该能力并配置匹配的签名才能解决这个权限边界。现有 AirPlay 入口和定向电视 IP 搜索不等同于 DLNA 自动搜索；后者还要求电视响应单播 SSDP，并允许本地网络访问。

下一步以用户发送端系统和电视型号为依据，在实际接收器上验证发现、设备信息获取和投屏连接。不得将本次模拟接收器测试扩大为厂商电视兼容性结论。
