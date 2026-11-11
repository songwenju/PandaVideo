# 荐片 macOS 5.0.4 静态分析导读

分析日期：2026-11-11。对象：荐片客户端 `app.asar`。状态：静态分析，质量状态 `limited`；未运行目标客户端，动态业务请求数为 0。

本文为重写后的阅读版。完整分析、代码引用与证据保留在 [原始 HTML 报告](jianpian-analysis-20261111.html) 和 [原始附件 ZIP](jianpian-analysis-20261111-attachments.zip)。PandaVideo 后续接入的实网结果见 [播放源验证记录](../verification/source-failover-20261111.md)，与本报告的静态证据分别使用。

## 主要结论

样本实现影视分类、搜索、详情、在线播放、短视频、榜单、收藏与观看历史。业务域名动态发现，请求依赖初始化 secret、MD5 签名和按请求规则注入的用户 token。

HTTP 媒体直接交给播放器；非 HTTP 资源依赖 ASAR 外部的 `JPEngine2`。下载队列和控制代码存在，但未确认正常界面的开启入口。因此不能仅凭下载代码宣称此版本可以完成下载。

共有 130 个业务请求封装，其中 99 个找到静态调用点，31 个仅找到封装。调用点不保证分支开启、接口可用或用户可以进入。会员相关代码存在，但全局 `vipSwitch` 为 false，不能认定会员线路已经开放。

## 样本与证据范围

| 项目 | 结果 |
| --- | --- |
| 包内名称 / 版本 | `jianpian` / `5.0.4`，来自 `package.json` |
| 入口 | `src/main/index.js` |
| ASAR 大小 | 10,417,170 字节 |
| SHA-256 | `c8b0d5c2fe0843da97d2a763d329cc4bae079e71456450e3f6eb531236d191d5` |
| 文件检查 | 解包 786 个文件，校验路径与字节边界 |
| JavaScript 检查 | 56 个资源通过语法解析，格式化副本与原文件做词法 token 校验 |
| 运行限制 | 原分析环境为 Linux，缺少完整 macOS 应用外壳及 `JPEngine2` |

用户指定的应用标识为 `com.jianpian.macos`，ASAR 不含 `Info.plist`，无法独立确认 bundle identifier、签名或 Electron 运行时版本。样本中没有 unpacked 条目不等于应用没有包外依赖。

原报告中的 `F:` 表示 `formatted/dist/assets/`，`S:` 表示 `extracted/src/`，`H:` 表示 `extracted/dist/index.html`。格式化代码行号用于复核，不是原开发源码行号。

## 功能结构

| 功能 | 静态确认的行为 |
| --- | --- |
| 首页与分类 | 默认进入推荐分类，服务端返回导航、轮播、子标签和模板模块 |
| 筛选与搜索 | 动态筛选键、关键词、分类和分页；顶部有候选词及本地搜索历史 |
| 长视频详情 | 简介、评分、演员、相关推荐、来源、分集、收藏与评论 |
| 播放器 | 进度、音量、倍速、上下集、换源、全屏、画中画及跳片头片尾 |
| 短视频 | 列表、垂直切换、合集、点赞、收藏、分享和评论 |
| 发现 | 上映表、专题、排行榜、求片榜；其他路由不一定有可见导航入口 |
| 个人中心 | 收藏与历史分页、批量删除，登录条件下上报播放记录 |
| 设置与反馈 | 自启、关闭行为、更新模式、恢复默认及反馈提交 |
| 下载 | 选集组件、持久化队列和本地引擎调度；常规入口未确认 |

播放器有 30 秒未就绪后的错误和换线路逻辑。库中存在 MP4 / HLS / DASH 支持路径，不证明实际影片采用这些格式，也不证明换源成功。

## 域名发现与请求契约

客户端依次利用最近有效域名、缓存候选、固定候选、远程列表、DNS TXT 和日期派生候选发现业务服务器。

| 用途 | 地址或规则 | 边界 |
| --- | --- | --- |
| 固定候选 | `https://japi.zxfmj.com` | 不是本次实测选中的主域 |
| 列表分发 | `https://jdomain.oss-accelerate.aliyuncs.com/domain.txt` | 客户端预期逗号分隔文本 |
| DNS JSON | `dns.alidns.com/resolve`、`doh.pub/dns-query`、`1.1.1.1/dns-query` | 查询 `pyvemnlu.cc` 的 TXT；未验证响应兼容性 |
| 域名候选 | 随机六位子域、日期与包内常量派生域名 | 构造逻辑不证明注册或可用 |
| 图片与广告域 | 配置接口返回 `imgDomain` 候选 | 运行时替换图片主机，失败换候选 |
| 本地媒体引擎 | `http://127.0.0.1:{port}`，默认 5800 | 端口从 `JPEngine2.lock` 读取，属于本地服务 |

上述候选机制属于荐片原客户端。PandaVideo 接入时限制 HTTPS/443，并未照搬所有 HTTP/IP 候选逻辑。本地 5800 端口也不是外网备用 API 端口。

业务路径通常拼接为 `selected_base/api` 加相对接口路径。初始化调用 `/v2/sys/init`，消费 `data.secret`；签名为：

```text
MD5("504" + 秒级时间戳 + 初始化 secret)
```

客户端平台参数为 `pc`，版本为 `504`；用户 token 按原始 URL 的规则注入。静态实现不能证明服务端鉴权效果，也不能据此认定匿名浏览必须登录。HTTPS 传输与 MD5 请求签名不等于内容采用应用层加密。

## 核心业务接口

下表路径相对 `selected_base/api`。这是静态请求契约，未取得真实响应样本；完整方法、参数、响应消费和调用点见附件。

| 用途 | 方法与路径 |
| --- | --- |
| 初始化与配置 | GET `/v2/sys/init`、`/v2/settings/appAuthConfig`、`/v2/settings/homeCategory` |
| 分类模块 | GET `/customTags/list`、`/pc_dyTag/list`、`/pc_dyTag/tpl{template}_list`、`/slide/list` |
| 搜索 | GET `/search/recommend`、`/v2/search/videoV2`；搜索读取关键词、分类和分页 |
| 筛选 | GET `/crumb/filterOptions`、`/crumb/list`、`/crumb/shortList` |
| 长视频 | GET `/video/detailv2`、`/video/guessRecommend` |
| 短视频 | GET `/play`、`/detail`、`/narratecollection` |
| 发现与榜单 | GET `/discover/list`、`/special/list`、`/special/detail`、`/weekRank/list`、`/ask/list` |
| 收藏 | GET `/v2/nUser/getCollectSingle`、`/nUser/getCollect`；POST `/nUser/collectVideo`、`/nUser/cancelCollectVideoBatch` |
| 历史 | GET `/v2/view/list`；POST `/usersV2/viewVideo`、`/usersV2/deleteViewCollection` |
| 下载记录 | POST `/nUser/downloadVideo`；此接口记录行为，不传输视频字节 |
| 更新与反馈 | GET `/v2/settings/updateAd`；POST `/feedback` |

详情消费 `source_list_source`，各线路内有 `source_list`，分集至少读取 `url`、`weight` 和 `source_name`。这些字段来自客户端消费逻辑，不是经过服务端验证的完整 schema。

## 播放与本地引擎

HTTP 链路为：搜索或分类 → 详情 → 来源与分集 URL → VideoPlayer → Video.js → 媒体服务器。

非 HTTP 链路为：启动包外 `JPEngine2` → 读取锁文件端口 → POST `/api/v1/create/{resource_b64url}` → 构造 `/p2p/{resource_b64url}` → 以 `video/mp4` 交给播放器。资源使用 UTF-8 Base64 URL 编码，不是加密；实际 MIME、Range 和上游协议未验证。

引擎控制实现包含 `setpath`、`pause`、`resume`、`cancel`、`delete`、下载大小和速度查询；控制使用 POST，成功时读取文本。`get_task_list` 和 `set_p2p_play_timeout` 只找到封装。

ASAR 不包含引擎二进制，因此不能还原其 TCP / UDP、FTP、P2P 调度、节点、分片、加密或磁盘格式，也不能证明资源可播放。

## 下载、缓存与历史

下载逻辑按资源 URL 去重，保存详情和队列元数据；只有 `ftp://` 被纳入 FTP 调度，默认并发为 2。暂停、恢复、取消和删除存在实现，选集下载弹窗初始关闭且未找到开启赋值；顶栏移动端下载二维码不属于影视下载管理。

本地存储包含域名缓存、账号状态、搜索历史、选源选集记忆、详情缓存、任务元数据和播放器偏好。`electron-store` 保存窗口和更新设置。详情缓存不是视频字节缓存，真实磁盘目录及视频落盘未确认。

收藏和历史使用云端接口；本地选源选集记忆不能视为完整观看历史。长视频详情仅在登录条件下上报播放结束记录，不能宣称每秒同步或跨设备续播已验证。

启动更新按 Mac 选择 `mac_download_url`，手动检查路径未传 Mac 标识并读取 PC 字段。字段选择不一致需要运行验证，不能直接断言一定安装错误平台的软件包。

## 原始证据与后续验证

[附件 ZIP](jianpian-analysis-20261111-attachments.zip) 中的重要文件：

- `jianpian_report.md`：原始完整 Markdown 报告。
- `network_interfaces.md` / `.json` / `.csv`：130 个业务接口、方法、参数与调用点。
- `network_auxiliary.json`：域名发现、图片、统计和本地引擎等辅助通道。
- `code_evidence.md` / `static_paths.json`：代码片段和调用路径。
- `source_verification.json` / `analysis_manifest.json`：代码校验与样本说明。
- `quality_status.json` / `pending_dynamic_validations.json`：质量限制和未执行的动态项目。

需要完整应用、目标 macOS 环境及 `JPEngine2` 才能验证真实启动、界面入口、选中域名、媒体播放、下载落盘和更新行为。PandaVideo 的后续 HTTP 接口测试仅验证它实际接入的链路，不覆盖荐片完整客户端。
