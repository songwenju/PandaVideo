# 电视版验证记录 · 2026-11-21

本文件保留早期版本的电视实测记录。后续目录统一、Logo、系统静音与内置投屏
的最新包及验证范围见 [reference-refactor-verification.md](reference-refactor-verification.md)；
下文旧包大小、哈希和电视安装结论不适用于最新交付包。

设备：`192.168.1.23:5555`，MiTV4_ANSM0，Android 6 / API 23，
armeabi-v7a，1280 × 720。包名 `dev.pandavideo.tv`。

## 已验证

- 共享 Android host 测试：42 个 suite，465 个测试，464 通过、
  1 跳过、0 失败；包含
  `TvRemoteTest` 的十秒步长、首尾边界、未知时间轴及溢出边界。
- Android 手机 China Debug 与电视 Debug 构建通过。
- iOS Arm64 与 Simulator Arm64 的 Debug framework 编译通过。
- 电视 Release 开启 R8 及资源压缩，构建通过；约 21 MiB，
  DEX 约 4.9 MB。包含 armeabi-v7a 原生库、最低 SDK 23、
  Leanback 入口及非必需触屏配置。
- APK ZIP 完整性及 v1/v2 签名验证通过。当前为本地开发分发证书，
  Release 与 Debug 同签，可保留应用数据升级。
- 电视专属能力已登记。能力检查仍有原有签名不匹配及手机
  全屏 5/10 秒两个锚点冲突，未执行 `--accept` 覆盖这些差异。

## 实机过程

首次从构建输出目录传输调试包时，后续构建替换了同一个输出文件，
电视报告 ZIP 本地文件头损坏及 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`。
改为构建完成后复制固定 APK，再进行传输。旧系统对大型 Debug DEX
首次优化耗时较多；改以精简 Release 包作为最终交付版本。

电视 Release 已安装并启动，真实海报、白色标题、橙色筛选及遥控器
焦点正常显示。精选切换香港地区后展示对应电影，再切换电视剧，
展示《巾帼枭雄之义海豪情》《大时代》等内容，地区筛选仍保留。

《肖申克的救赎》实际源已进入播放，片长 142:33，暂停时进度
0:39。遥控器焦点移动与确认快进/后退验证为
`0:39 → 0:49 → 0:39`；选集窗口能够聚焦线路及 HD中字条目，
恢复播放后观察到进度 1:01。返回目录后播放记录可见，重进影片
观察到非零进度 0:39，源恢复过程出现另一片长 137:52；跨源版本
的精确进度一致性尚未验证。

《巾帼枭雄之义海豪情》进入播放，观察到 0:43 / 44:38；
遥控器可打开线路/剧集窗口，聚焦并点击高清线路2，
随后点击第02集所在列的条目。第02集的最终选中态复核期间，
投屏接收应用再次切到前台，因此不记录为完整通过。

设备的 Amlogic AVC 解码器报告首个输出（51ms），硬件视频层为
1920×1080、启用状态 1，播放时 `output_fps=0x16`（22fps）。
ADB 截图中的视频区域为黑色，不能当作可见首帧证据；
真实电视面板的画面、声音仍需人工观看验收。对应解码/硬件层
数据与控制界面截图见 `evidence/`。

Android 6 缺少 ISRG Root X1，已补官方根证书并保留系统信任链
校验与默认主机名校验。实机探针验证源站 HTTPS 成功，
错误主机名、过期证书、自签证书仍被拒绝。无需关闭证书校验。

搜索输入已能使用电视系统键盘。实机发现输入框右键只移动光标，
补充右键转到搜索按钮及 IME 搜索动作。输入“1917”后关闭系统
键盘，右键成功聚焦搜索按钮，确认后返回搜索结果；精确聚焦
“1917 / HD中字”条目并确认，播放后暂停观察到 `0:14 / 119:10`。
返回搜索页保留关键词及网格滚动位置。搜索源可能同时返回简介含
关键词的其他内容，依源结果展示，未声称全部都是精确标题匹配。
缩小网格单元，解决搜索页首排海报过高、片名被裁切的问题。

同时有投屏接收应用占用电视，部分步骤被前台检查中止，未操作
接收应用。旧系统 `uiautomator dump` 的断开阶段会报告系统进程
SecurityException；与电视应用自身崩溃区分，不计为应用崩溃。

## 复现命令

在 `mobile/` 中使用 JDK 21 和 Android SDK：

```sh
./gradlew :tv:assembleRelease
adb -s 192.168.1.23:5555 install -r tv/build/outputs/apk/release/tv-release.apk
adb -s 192.168.1.23:5555 shell am start -n dev.pandavideo.tv/.TvActivity
python3 ../docs/specs/tv-app/capture.py home
```

本次为避免干扰其他开发窗口，实际构建输出位于
`/tmp/pandavideo-tv-build/`，完整构建日志位于 `/tmp/pandavideo-tv-*.log`。
原始截图和 UI/媒体会话快照保存到本目录的 `evidence/`。
人工视觉及遥控器体验验收仍待用户实际体验反馈。

最终构建日志：`evidence/final-build.log`（原始
`/tmp/pandavideo-tv-layout-build.log`），27 秒构建成功。
交付文件：仓库根目录 `PandaVideo-TV.apk`，21,944,470 字节，
SHA-256：
`aa86e610d6436cf151907d93f01edf3bdf9d7273a5d38e230280e99525b0c42f`。

最终安装返回 `Success`，启动并截图复核 `delivery-home.png`：
六列海报、片名和评分完整处于列表视口内。随后在追加搜索页布局
截图时 ADB 变为 offline，重连报告 `No route to host`；
未把此项追加复核记为通过。此前搜索提交与“1917”播放已通过，
最终网格调整只改变海报列宽。真机面板画面/声音及断连后的追加
搜索布局复核仍需人工或设备恢复后的验证。
