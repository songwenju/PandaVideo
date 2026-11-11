---
name: kmp-dual-platform-verify
description: Verify mobile KMP changes via host tests plus Android APK and iOS framework builds. Use after editing shared, androidMain or iosMain Kotlin code. Do not use for frontend Python or web work.
---

# KMP 双端构建验证

把「Compose/Kotlin 改动 → host 测试 → Android APK → iOS Framework」固化为统一验收流程。
适用于 `mobile/` 下 KMP + Compose Multiplatform 工程（熊猫视频 / PandaVideo），每次改动共享层或平台层后、向用户报告完成前执行。

## 触发场景

- 修改 `mobile/shared/src/commonMain`、`androidMain`、`iosMain` 下的 Kotlin 代码。
- 用户要求验证双端、出包、跑测试，或准备声明某功能已完成。
- 不适用：`frontend/` 的 Python/Web 改动；纯文档修改只需做对应检查，不走本流程。

## 环境前置

所有 Gradle 命令均在 `mobile/` 目录执行，且必须显式提供两个环境变量（仓库内没有 `local.properties`）：

- `ANDROID_HOME=~/Library/Android/sdk`
- iOS / Kotlin Native 构建需要 JDK 21：`JAVA_HOME=$(/usr/libexec/java_home -v 21)`

## 标准验证流程

### 1. 静态检查

完成编辑后先调用 IDE 诊断，确认改动文件无 error/warning，再进入构建。

### 2. Host 测试（JVM 侧，最快反馈）

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew :shared:testAndroidHostTest
```

- 注意任务名是 `testAndroidHostTest`，本工程不存在 `testDebugUnitTest`。
- 怀疑缓存掩盖结果时强制重跑：先执行 `:shared:cleanTestAndroidHostTest`，或给脚本加 `--clean`。
- 结果 XML 在 `shared/build/test-results/testAndroidHostTest/TEST-*.xml`，逐套件核对 `tests / failures / errors`，要求 failures=0 且 errors=0。
- 现有套件：AppStateTest、ContentRepositoryTest、FormattingTest、ModelsTest、NavigationConfigTest、PlaybackTest、UserStoreBehaviorTest。新增 commonTest 用例会被自动纳入。
- 找不到任务名时用 `./gradlew :shared:tasks --all | grep -i test` 重新发现。

### 3. Android APK

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:assembleChinaDebug
```

- 产物：`app/build/outputs/apk/china/debug/pandavideo.apk`
- 核对产物 mtime 晚于被修改源码的 mtime；若仍为旧时间戳，说明命中缓存，执行 `:shared:clean` 后重编。

### 4. iOS Framework

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=~/Library/Android/sdk \
  ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

- 真机用 `linkDebugFrameworkIosArm64`，模拟器用 `linkDebugFrameworkIosSimulatorArm64`。
- 产物：`shared/build/bin/iosSimulatorArm64/debugFramework/SuiXinKanShared.framework`
- Framework 编译通过只代表共享层就绪；模拟器/真机界面更新仍需在 Xcode 中对 `iosApp/PandaVideo.xcodeproj` 按 ⌘R 重新部署，向用户说明这一步。

## 一键脚本

```bash
.trae/skills/kmp-dual-platform-verify/scripts/verify.sh           # 测试 + 双端构建
.trae/skills/kmp-dual-platform-verify/scripts/verify.sh --clean   # 先 clean shared 再全量验证
```

脚本自动设置环境变量、汇总各测试套件结果、打印 APK 与 Framework 路径和时间戳；任一步失败立即退出并返回非零码。

## 验收门槛

1. IDE 诊断零 error。
2. 全部 host 套件通过，failures/errors 均为 0，并报告套件数与用例总数。
3. Android `assembleChinaDebug` 成功，APK 时间戳已刷新。
4. iOS Framework link 成功，二进制时间戳已刷新。
5. 向用户汇报时给出产物绝对路径；iOS 侧提醒 Xcode ⌘R 部署。

## 常见坑与恢复

- **UP-TO-DATE 假象**：构建成功但产物时间戳没变。`touch` 改动过的源码或执行 `:shared:clean` 后重编，以产物 mtime 为准。
- **Kotlin/Native 缓存损坏**（iOS 编译报 klibrary/daemon 相关异常）：执行 `:shared:clean` 并重启 Kotlin daemon 后重试。
- **KLIB unique_name 重复 warning**（`/tmp/pandavideo-konan` 与 `~/.konan` 同名库）：已知良性警告，不影响构建，无需处理。
- **SDK location not found**：说明漏了 `ANDROID_HOME`，不要新建 `local.properties`，统一走环境变量。
- **终端数量上限 5**：长构建用前台等待；不要为同一模块并行开多个 Gradle 调用。
