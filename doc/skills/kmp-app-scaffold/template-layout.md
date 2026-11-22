# 模板目录清单（KMP 双端，v1.0 草案）

这是待实现的模板文件清单；本文中的图标、测试、性能脚本与工程尚未生成。性能门禁规则见 [SKILL.md](SKILL.md)。

## 1. 占位符表

| 占位符 | 示例 | 作用范围 |
| --- | --- | --- |
| `__APP_NAME__` | 随心看 | `strings.xml`、`Info.plist`、README |
| `__PROJECT_NAME__` | SuiXinKan | Gradle `rootProject.name`、Xcode 工程名、scheme |
| `__PACKAGE_ID__` | dev.suixinkan | Android applicationId、Kotlin/Native 包名、Manifest `package` 引用 |
| `__PACKAGE_PATH__` | dev/suixinkan | Kotlin 源码目录路径 |
| `__BUNDLE_ID__` | dev.suixinkan | Xcode `PRODUCT_BUNDLE_IDENTIFIER` |
| `__BRAND_COLOR__` | #FF6740 | `Theme.kt` 品牌色常量 |
| `__TEMPLATE_VERSION__` | 1.0.0 | `template.version` 与 README |

替换规则：先替换内容再重命名文件/目录（`__PROJECT_NAME__.xcodeproj`、`__PACKAGE_PATH__`），替换后全库检索 `__` 确认清零。

## 2. 目录树

```
mobile/
├── settings.gradle.kts                     [必需] 模块声明、仓库配置
├── build.gradle.kts                        [必需] 插件版本集中声明
├── gradle.properties                       [必需] KMP / AndroidX 开关
├── gradle/wrapper + gradlew                [必需] 锁定 Gradle 版本
├── .xcode.env                              [必需] JAVA_HOME / GRADLE_USER_HOME / KONAN_DATA_DIR
├── local.properties.example                [必需] SDK 路径示例，不提交真实 local.properties
├── design/
│   ├── icons/*.svg                         [必需] 基础图标与品牌标识原稿
│   └── icon-manifest.json                  [必需] 资产 ID、设计节点、来源许可、导出参数、目标路径
├── docs/
│   ├── design-system.md                    [必需] token、图标与组件状态规范
│   ├── testing.md                          [必需] 单测/UI/平台持久化执行与结果位置
│   └── performance.md                      [必需] 场景、设备条件、采集方法、基线比较
├── quality/
│   ├── performance-budget.json             [必需] 指标单位、场景、绝对预算与退化阈值
│   └── baselines/                          [必需] 已验证模板基线及测量条件；首次采集后填充
│
├── shared/
│   ├── build.gradle.kts                    [必需] commonMain / androidMain / iosMain + commonTest
│   └── src/
│       ├── commonMain/kotlin/__PACKAGE_PATH__/
│       │   ├── app/App.kt                  [必需] 根 Composable、页面切换与返回栈
│       │   ├── app/AppState.kt             [必需] 全局状态：收藏、搜索历史、明暗模式
│       │   ├── ui/common/Theme.kt          [必需] 品牌色 + 明暗 ColorScheme
│       │   ├── ui/common/Typography.kt     [必需] 字号阶梯 11/13/14/15/18/22
│       │   ├── ui/common/Dimens.kt         [必需] 间距 4/8/12/16/20/24、圆角 8/12/16/胶囊
│       │   ├── ui/common/Icons.kt          [必需] 内置矢量图标（返回/搜索/关闭/收藏等）
│       │   ├── ui/common/IconTokens.kt     [必需] 图标视觉尺寸、线宽与方向策略
│       │   ├── ui/gallery/DesignGalleryScreen.kt [必需] 组件/图标状态、主题与字号展示
│       │   ├── ui/common/Components.kt     [必需] 按钮、输入框、列表项、海报卡、Chip
│       │   ├── ui/common/States.kt         [必需] 骨架屏、空态、错误态、加载
│       │   ├── ui/home/HomeScreen.kt       [必需] 首页列表（分页假数据 + 四态切换入口）
│       │   ├── ui/detail/DetailScreen.kt   [必需] 详情页（海报 + 简介 + 收藏按钮）
│       │   ├── ui/search/SearchScreen.kt   [必需] 历史胶囊 + 联想 + 直接搜索行
│       │   ├── data/SampleData.kt          [示例] 假数据唯一入口，删除即回归空壳
│       │   ├── data/SampleRepository.kt    [示例] 内存仓库，模拟延迟与失败
│       │   └── data/KeyValueStore.kt       [必需] expect 声明（收藏/历史持久化）
│       ├── androidMain/kotlin/__PACKAGE_PATH__/
│       │   └── data/KeyValueStore.android.kt   [必需] SharedPreferences 实现
│       ├── iosMain/kotlin/__PACKAGE_PATH__/
│       │   └── data/KeyValueStore.ios.kt       [必需] NSUserDefaults 实现
│       └── commonTest/kotlin/__PACKAGE_PATH__/
│           ├── NavigationTest.kt           [必需] 列表→详情→返回状态流转
│           ├── SampleRepositoryTest.kt     [示例] 分页、空结果、失败分支
│           ├── FavoritesTest.kt            [必需] 收藏写入/读取/去重
│           ├── SearchStateTest.kt          [必需] 去空格、取消、旧响应隔离
│           └── HistoryTest.kt              [必需] 去重、排序、删除与清空
│   ├── src/androidHostTest/                [必需] Android KV 适配器测试
│   ├── src/iosTest/                        [必需] iOS KV 适配器测试
│   └── src/jvmTest/                        [必需] 关键组件与图标状态的可重复 UI 测试
│
├── app/
│   ├── build.gradle.kts                    [必需] chinaDebug / chinaRelease 变体
│   └── src/main/
│       ├── AndroidManifest.xml             [必需] 单 Activity、最小权限
│       ├── kotlin/__PACKAGE_PATH__/MainActivity.kt  [必需] setContent { App() }
│       └── res/
│           ├── values/strings.xml          [必需] __APP_NAME__
│           ├── values/themes.xml           [必需] 启动主题，避免白屏闪烁
│           ├── mipmap-*/                   [必需] 完整 AppIcon 尺寸资源
│           └── drawable/                   [必需] 自适应图标前景/背景与启动标识
│
├── benchmark/
│   ├── build.gradle.kts                    [必需] 独立 Macrobenchmark 模块与优化测试构建
│   └── src/main/kotlin/__PACKAGE_PATH__/
│       ├── StartupBenchmark.kt             [必需] 冷/热启动 TTID、TTFD
│       └── NavigationScrollBenchmark.kt    [必需] 固定列表滚动、详情进入返回帧统计
│
├── iosApp/
│   ├── __PROJECT_NAME__.xcodeproj          [必需] 由模板工程重命名
│   ├── iOSApp.swift                        [必需] 入口，加载 ContentView
│   ├── ContentView.swift                   [必需] 承载 Compose UIViewController
│   ├── Info.plist                          [必需] 显示名、Bundle ID、竖屏方向
│   ├── Assets.xcassets                     [必需] AppIcon / 启动标识
│   ├── __PROJECT_NAME__UITests/
│   │   ├── AppSmokeTests.swift             [必需] 导航、搜索、主题与失败重试
│   │   └── LaunchPerformanceTests.swift    [必需] XCTest 启动测量场景
│   ├── build-shared.sh                     [必需] 调用 Gradle 产出 framework 并同步 Compose 资源
│   └── .xcode.env                          [必需] 与 mobile/.xcode.env 保持一致
│
└── scripts/
    ├── check-generated.sh                  [必需] 占位符残留、包名一致、关键文件存在
    ├── verify.sh                           [必需] shared/平台测试、Android 构建、iOS 编译与安装
    ├── check-icons.sh                      [必需] 资产完整性、来源记录与导出尺寸检查
    └── profile.sh                          [必需] 运行性能场景、保存原始样本、按条件比较预算
```

## 3. 设计系统清单（组件粒度）

- 按钮：主按钮（品牌色实心）、次按钮（描边）、文字按钮
- 输入：胶囊搜索框（`BasicTextField` + 自绘 decorationBox）、普通输入框
- 展示：海报卡（2:3）、列表项（图标 + 标题 + 可选副标题 + 尾部箭头）、Chip/历史词胶囊
- 容器：TopBar、BottomTab（3 项以内）、BottomSheet、Dialog、Snackbar
- 反馈：骨架屏、空态、错误态、分页加载、下拉刷新
- 状态：明暗两套 token，主题切换入口放在设置项或 TopBar 调试入口
- 图标：基础操作、导航、反馈、品牌标识四组；交付 SVG 原稿、共享矢量实现与平台 AppIcon；视频播控按业务选择扩展
- 展示页：组件与图标全状态预览，入口放开发设置；生产构建不展示调试入口

## 3.1 测试与性能交付清单

| 类别 | 必须交付 | 验收依据 |
| --- | --- | --- |
| 图标 | 真实矢量资产、来源清单、选中/禁用态、深浅色展示 | 双端截图、方向与数字不误镜像、无缺失资产 |
| 单测 | 业务规则、分页失败、重试取消、旧响应隔离 | 测试 XML；失败/错误为零，跳过项单列 |
| 平台测试 | Android/iOS KV 适配器、重建后数据保留 | 对应平台的运行结果，不能只测内存假实现 |
| UI 回归 | 搜索、返回、重试、主题、大字体、图标、安全区 | 固定数据与设备条件下的行为和视觉证据 |
| 启动/流畅度 | Android Macrobenchmark、iOS XCTest/Instruments 场景 | 优化构建、真机样本、帧统计、trace |
| 内存/包体 | 重复导航、图片缓存、APK/App 大小 | 固定场景的基线和预算比较 |
| 功耗 | 播放/后台持续任务的专项采集入口（按业务启用） | 设备与负载条件；无电气测量时不声称精确瓦数 |

结果至少包含设备、OS、构建标识、刷新率、数据/缓存条件、样本数、中位数、P95 和原始文件路径。模拟器数据单列；无工具、无设备或没有基线时明确标记未测/待建立基线。

## 4. 示例业务链路

1. 首页：20 条假数据分页，下拉刷新复用同一仓库
2. 详情：点击海报进入，展示简介与收藏按钮，返回保持列表位置
3. 搜索：历史胶囊（可删除、可清空）+ 联想 + 置顶直接搜索行
4. 收藏：本地 KV 持久化，重启后仍可见
5. 四态：首页提供调试开关切换骨架/空/错误/成功，便于验收

## 5. 删除示例业务的步骤

1. 删除 `data/SampleData.kt`、`data/SampleRepository.kt`、`SampleRepositoryTest.kt`
2. 把 `HomeScreen` / `DetailScreen` 的数据源替换为仓库接口的空实现
3. 删除 `AppState` 中的示例收藏与搜索历史字段
4. 重新执行 `scripts/check-generated.sh` 与 `scripts/verify.sh`

## 6. 版本与维护

- `template.version` 采用语义化版本；组件增删与目录变更记入模板仓库 CHANGELOG。
- skill 只声明支持区间，例如 `template >= 1.0.0 < 2.0.0`。
- 模板新增依赖时必须同步锁定版本，禁止动态版本号。
