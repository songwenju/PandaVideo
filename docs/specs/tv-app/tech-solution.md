# 电视版技术方案

- 新增 `mobile/tv` Android 应用，包名 `dev.pandavideo.tv`、最低 API 23、
  横屏、Leanback 桌面入口、touchscreen 非必需。手机版 `:app` 继续最低 API 26。
- 电视界面放在共享模块的 `androidMain`，复用 `AppState`、目录过滤、搜索、
  `PlaybackSession`、`AndroidPlatform`、Media3 和本地 SQLite。
  电视使用 Compose TV 的焦点组件及独立大屏布局。
- 共享 Android 库最低 SDK 降到 23；检查 API 24/26 以上的平台调用，
  旧版网络监测使用 `registerNetworkCallback`，新版保留默认网络回调。
  依赖的最低 SDK 和 armeabi-v7a 库必须以 Gradle 及 APK 实物检查为准。
- 电视端采用深色背景、暖橙品牌色、大字号、海报焦点描边，
  独立顶部导航与横向筛选，不复用触屏手势或移动端底部导航。
- 实机发现 Android 6 根证书库不含 ISRG Root X1，Pomo 与荐片的
  YR1/YR2 → Root YR → ISRG Root X1 链均被拒绝。`AndroidSourceTls`
  仅在 API 23 补充官方根证书并固定指纹；Ktor/Coil 显式使用该信任管理器，
  Media3 的原生 HTTPS 使用同一进程默认 socket factory。保留 PKIX、
  有效期及默认主机名校验，不覆盖主机名验证器。
- 不引入 NAS 依赖；不把接收端投屏应用冒充电视视频客户端。
- 能力契约基线存在签名未接受和全屏 5/10 秒锚点冲突，新增电视能力时保留
  原签名并报告冲突，不使用 `--accept` 掩盖其他窗口改动。

工程选择依据是当前代码与设备实测；用户已授权开发及已连接设备的安装验证。
人工 Spec/Tech/QA Review 均待用户对实际产物反馈，不伪记为通过。
