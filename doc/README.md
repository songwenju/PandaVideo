# PandaVideo 文档索引

更新日期：2026-11-11。此目录集中管理 AI 分析、产品规划和跨模块验证。项目入口见 [项目说明](../README.md)，使用与构建见 [客户端说明](../mobile/README.md) 和 [网页版说明](../frontend/README.md)。

## 阅读顺序

| 需要了解的内容 | 文档 | 适用范围 |
| --- | --- | --- |
| 当前产品与工程结构 | [项目说明](../README.md) | 各客户端功能边界与启动入口 |
| 客户端开发、播放与投屏 | [客户端说明](../mobile/README.md) | Android / iOS / macOS 当前实现 |
| 网站配置、迅雷与 NAS | [网页版说明](../frontend/README.md) | Python 服务与浏览器端 |
| 播放源测速和故障切换 | [验证记录](verification/source-failover-20261111.md) | 2026-11-11 的实现与运行证据 |
| 荐片功能及接口研究 | [静态分析导读](analysis/jianpian-analysis-20261111.md) | 外部客户端 ASAR 静态分析 |
| 早期产品设计背景 | [历史 PRD 导读](prd/suixinkan/prd-随心看.md) | 2026-11-09 的草案，不能作为当前验收标准 |

## AI 分析与证据

| 文件 | 内容 |
| --- | --- |
| [荐片分析 · Markdown 导读](analysis/jianpian-analysis-20261111.md) | 重写后的功能、请求、播放和缓存链路说明，注明证据限制 |
| [荐片分析 · 原始 HTML 全文](analysis/jianpian-analysis-20261111.html) | 原始分析报告与代码引用 |
| [荐片分析 · 原始证据附件](analysis/jianpian-analysis-20261111-attachments.zip) | 原始 Markdown、130 个接口清单、调用证据、样本和质量清单 |

这份分析针对荐片 macOS 5.0.4，未启动该客户端，也未发起动态业务请求。PandaVideo 后续的真实网络测试记录单独维护，不能据此扩大原始静态报告的结论。

## 产品规划

- [随心看历史 PRD 导读](prd/suixinkan/prd-随心看.md)：说明旧草案的目标、演变和当前适用边界。
- [随心看历史 PRD HTML 全文](prd/suixinkan/prd-suixinkan.html)：保留版本 1.4 的完整规划和交互示例，状态为历史待 Review 草案。

当前品牌为“熊猫视频 · PandaVideo”。历史规划中使用的产品名、导航和缺失功能列表不再代表当前实现。

## 验证与技术文档

| 文档 | 阅读提示 |
| --- | --- |
| [播放源备用、测速与自动切换](verification/source-failover-20261111.md) | 区分接口耗时、媒体探测、构建、启动与原生播放 |
| [移动端实现与验证记录](../mobile/docs/实现与验证记录.md) | 按批次记录；旧结果只适用于对应版本 |
| [Android 验证证据索引](../mobile/docs/evidence/README.md) | 历史取证文件与适用范围 |
| [网站完整自测报告](../frontend/docs/完整自测报告.md) | 历史网站测试记录 |
| [KMP / Compose 技术方案](../mobile/docs/KMP-Compose-Multiplatform技术方案.md) | 跨端实现与模块结构 |
| [Android / iOS 跨平台技术方案](../mobile/docs/Android-iOS跨平台技术方案.md) | 平台方案及演进背景 |
| [榜单数据库与海报缓存](../mobile/docs/榜单数据库与海报缓存.md) | 数据缓存设计 |
| [远程配置与备份](../mobile/docs/远程配置与备份.md) | 自部署 HTTPS JSON 与本地数据管理 |
| [离线缓存](../mobile/docs/离线缓存.md) | Android / iOS 任务管理、私有媒体与离线播放 |
| [投屏评估](../mobile/docs/投屏评估.md) | 投屏方案、平台和设备限制 |
| [macOS 播控与返回首页验证](../mobile/docs/macOS播控与返回首页验证.md) | 桌面端历史运行记录 |
| [网页版设计同步](../frontend/docs/网页版设计同步.md) | 网站设计与验证范围 |
| [NAS 部署](../frontend/NAS部署.md) | 服务运行和局域网配置 |

## 目录与维护规则

```text
doc/
├── README.md
├── analysis/                 # AI 分析导读、原始报告与证据
├── prd/                      # 产品规划及历史草案
│   └── suixinkan/
└── verification/             # 跨模块验证记录
```

1. 新文档开头写明日期、状态和范围；分别标注当前实现、运行结果、历史规划和待确认判断。
2. 一次性报告使用 `主题-YYYYMMDD.md`；配套 HTML、附件使用同一文件名主体。历史文件可保留原名。
3. 重写导读时保留原始证据；历史草案不得改写成已批准需求。分析发现不等于已经接入或运行成功。
4. 使用相对链接。移动 HTML 时同时移动 `assets/`、`_shared/` 等依赖，并检查本地链接。
5. 模块开发说明继续维护在 `frontend/docs/` 或 `mobile/docs/`；新增或移动文档同步更新本索引。
6. 构建产物、测试缓存和临时截图不混入文档正文目录。配置示例不得包含真实密钥、凭据或签名媒体地址。
