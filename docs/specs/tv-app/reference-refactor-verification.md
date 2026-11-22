# 电视目录、Logo、系统静音与投屏接收验证

最新交付包：仓库根目录 `PandaVideo-TV.apk`。
大小及SHA-256见 `evidence/catalog-cast-20261121/build-summary.json`。

**主要改动版本已安装MiTV4并完成电视验证；最后的矢量箭头修正版尚未装回电视。**
`192.168.1.23:5555`早期ADB离线，后续恢复。主要版本完成传输，
`pm install -r`返回Success，启动包 `dev.pandavideo.tv`。
最后修正版完成全部构建及手机安装验证，但电视在传输时再次ADB离线，
重连未恢复。最终包为22,378,507字节，SHA-256：
`ceb69eef7486f695ea897cb1e36d258a050f31a90b29a62375e9926942fca887`。
电视已安装并实测的主要版本为22,359,719字节；
其后的矢量箭头与并行接收控件调整不冒充已完成电视验收。
电视为Android 6/API23、1280×720、armeabi-v7a。

## 编译与检查

- `:shared:testAndroidHostTest` 和 `:tv:testDebugUnitTest`：共47套、
  485个用例，484通过、1跳过、0失败、0错误。
- TV Release、手机China Debug、iOS真机Arm64和模拟器Arm64
  Debug framework构建通过。最终原始日志为 `final-build.log`。
- `:tv:lintRelease`：0错误、13警告，见 `lint-release.txt`。警告包括已有横屏/备份设置、
  版本目录建议、兼容drawable加载和自定义证书管理器提示。
  证书管理器保持完整系统与补充证书路径验证，相关6个host测试通过。
- 构建曾遇到Gradle进程退出及旧输出目录的Kotlin中间文件缺失，
  最终使用 `isolated-build.init.gradle` 将输出隔离到
  `/tmp/pandavideo-tv-vector-build/`。同时保留并修正了并行加入的接收端
  遥控器按键拦截；Activity标准入口的AndroidX限制提示仅在该方法局部处理。
- ZIP完整性与APK v1/v2签名验证通过，保留API23和armeabi-v7a支持。
- 能力检查仍是开始前的3项问题：未接受的契约签名及手机全屏
  5/10秒两个锚点。电视能力已同步当前实现和守护，不重新签名覆盖其他窗口修改。

原始最终构建日志、Lint结果、测试汇总、截图与协议状态见
`evidence/catalog-cast-20261121/`。

## 手机上的接收协议验证

目标：Android 13/API33手机 `b7256b56`，接收地址 `192.168.1.7`。
安装返回Success，启动独立包 `dev.pandavideo.tv`。

- 首页为六列竖向海报，默认精选Top250；“肖申克的救赎”等标题与评分可见。
- 首页分类弹窗列出与手机一致的16个选项。“高分剧集”“高分电影”
  “好莱坞”完整显示；修复前弹窗恢复设备密度而只显示一个字，修复后沿用
  电视画布密度，四列标签和左右留白正常。
- 热播展示与手机一致的6项，包括热门综艺和小品，影片使用六列网格。
- 分类展示电影、电视剧、纪录片、动画，以及题材、地区、年份、排序。
- 桌面Logo资源与内部Logo均来自同一192×192熊猫图片，
  TV横幅为320×180，熊猫图形中心位于160×90。

接收端通过主页面唯一“投屏”入口进入，等待页熊猫Logo居中。
使用局域网HTTP提供40秒H.264/AAC真实测试视频，直接请求同一AVTransport
协议验证接收端：

1. 设备描述返回“熊猫投屏 · 21121210C”，播放实际推进到1秒。
2. Pause返回PAUSED_PLAYBACK，时长40秒。
3. Seek到20秒后播放，再暂停，返回21秒；截图可见真实测试视频帧。
4. Stop返回STOPPED，等待页面显示“投屏已结束”。
5. Home退后台后，3秒期限内确认HTTP接收端口关闭；随后停止测试应用。

上述步骤是**手机接收APK + 协议客户端**验证。电视配对另见下节。
暂停视频时uiautomator偶发
未生成新层级，旧捕获脚本会读到等待页树；视频步骤仅引用截图和独立SOAP状态，
不引用这些陈旧XML/JSON。

## 电视布局与真实手机配对

- `tv-home-actual`、`tv-hot-actual`、`tv-browse-actual`：遥控器切换
  首页、热播、分类，六列海报、标题、评分及完整筛选可见。
- `tv-home-options-actual`：电视上16个首页选项完整显示，
  “高分剧集”“高分电影”“好莱坞”无裁切。
- 旧电视字体不能显示Unicode下拉三角，最终改为16dp矢量Chevron，
  保持文字左右16dp与图标间距8dp。修正版手机截图为
  `vector-final-phone-home`，接收页启动截图为 `vector-final-phone-receiver`；
  此修正版电视追加复核因ADB离线尚未完成。
- 内置接收页Logo居中；退出接收后恢复主页面的筛选和焦点。
- 手机实际包 `dev.pandavideo` 打开《1917》，在“投屏到电视”中发现
  “熊猫投屏 · MiTV4-ANSM0”，确认电视前台是
  `dev.pandavideo.tv/.cast.ReceiverActivity`，连接成功后手机展示独立遥控器。
- TV返回PLAYING，位置6:13、片长1:58:49；手机暂停后双方为
  PAUSED_PLAYBACK、6:37；手机快进10秒后保持暂停、6:47。
- 手机退出投屏后双方显示“投屏已结束”，接收状态STOPPED、6:47。
  返回电视首页后原海报焦点恢复。

相应截图与独立SOAP状态为 `sender-discovered-tv`、
`sender-tv-connected`、`sender-tv-paused`、`sender-cast-ended`、
`tv-phone-paused.json`、`tv-phone-seek.json`、`tv-phone-stopped.json`。
电视硬件视频层在ADB截图中仍可能为黑色，不把截图当作实际面板首帧证明。

## 系统音量范围与实机证据

此前MiTV音频快照中，STREAM_MUSIC的speaker索引0、Muted=false，
同时列出speaker/spdif/aux_line。AudioFlinger另一次快照显示媒体流-42dB，
但当时暂停、没有活动音轨；这些证据不能证明同一时刻的声学根因。

现在两个电视播放器都设置USAGE_MEDIA，遥控器控制STREAM_MUSIC。
系统音量0或显式静音时，播放器增益强制0；正音量恢复1，保留系统音量曲线。
三个新增host用例覆盖零索引但未静音、正索引显式静音和正音量恢复。
实机补充了同步证据：接收播放《1917》时遥控器音量
`2 → 0 → 2`，音量0的STREAM_MUSIC仍是Muted=false，但唯一活动音轨
左右声道增益均为`-inf`，AVTransport仍返回PLAYING；恢复音量2后增益
恢复0dB。这里0dB指播放器音轨增益，系统总体音量曲线仍另外生效。
对应 `tv-audio-zero.txt`、`tv-flinger-zero.txt`、`tv-zero-playback.json`
及restored文件。

普通播放《肖申克的救赎》也完成相同验证：唯一活动44100Hz音轨在
音量0时左右增益`-inf`，恢复音量后为0dB。对应
`tv-ordinary-zero-verified-*` 和 `tv-ordinary-restored-verified-flinger.txt`。
早期 `tv-ordinary-flinger-zero.txt` 发生在加载中、没有活动音轨，
不用于证明静音。验证后恢复系统原音量2，返回首页。
本次证据证明解码器输出静音与恢复；未进行电视扬声器声学录音。

## 复现

在 `mobile/` 目录以JDK21和Android SDK运行：

```sh
./gradlew :shared:testAndroidHostTest :tv:testDebugUnitTest :tv:assembleRelease :tv:lintRelease \
  :app:assembleChinaDebug :shared:linkDebugFrameworkIosArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64
```

接收协议复现使用 `verify-integrated-cast.py`，分别执行start、seek、
stop和background阶段；start需要`--media`指定局域网测试媒体。
background在Home之后检查，允许Activity动画与网络工作线程完成关闭。
`capture-current.py`使用唯一层级文件，避免读取旧树，并兼容Android 6
只有包名的Dialog窗口标题。
