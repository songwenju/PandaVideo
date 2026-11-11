# 熊猫视频 · PandaVideo 网页版

更新日期：2026-11-11。网页版运行于 Mac 或 NAS，提供榜单、影视搜索、浏览器播放及迅雷下载链接处理。服务仅依赖 Python 3.9 或更高版本的标准库，页面资源随工程提供。

以下路径和命令以 `frontend/` 为工作目录。客户端直接访问来源的机制见 [客户端说明](../mobile/README.md)；本网页仍使用自己的来源适配器，未同步接入移动端 Pomo / 荐片自动切换。

## 功能与来源

| 功能 | 当前行为 |
| --- | --- |
| 内容浏览 | 发现、最近热播、分类和海报网格；支持题材、年份、排序及分页 |
| 影视搜索 | 聚合启用的站点，独立搜索页展示电影和剧集 |
| 在线播放 | 阳光电影详情解析及 HTTP(S) 视频直链；选集、线路、倍速、全屏和自动下一集 |
| 下载处理 | 调用迅雷、复制下载链接或手动粘贴链接，不维护自己的下载队列 |
| NAS | 检查挂载状态、提供挂载入口和部署方式 |
| 外观 | 暖橙与薄荷配色、深浅色模式、手机底部导航 |
| 海报 | 服务代取和缓存；缺失或加载失败时展示图形占位 |

默认站点搜索包含 80s、电影天堂、阳光电影（`huolot.com`）、飘花、电影港和 66 影视，无需 API Key。可选豆包 / 火山方舟接口用于理解搜索词；未配置时使用本地规则。Internet Archive、Pexels、Pixabay 为可选来源。

首页默认 Top 250。“Top 好莱坞大片”为本站组合筛选，榜单条目不保证存在下载或播放资源。搜索评分来自来源页标注；海报点击后尝试匹配播放资源，未匹配时可继续搜索。

来源可用性随网络和站点变化。人人影视、片源网、悠悠 MP4、BT 之家、M1080 和 4K 世界的旧网址尚未满足接入条件，当前不参与搜索。

## 首次启动

```sh
cp .env.example .env
chmod 600 .env
./run.sh
```

启动前按需编辑 `.env`，已有配置无需再次复制。默认访问 `http://127.0.0.1:8766`，按 `Ctrl+C` 停止。与后台服务同时运行会产生端口冲突。

## 配置项

以 [.env.example](.env.example) 为完整模板，配置文件保留在本地。

| 配置 | 用途 |
| --- | --- |
| `APP_HOST` / `APP_PORT` | 默认 `127.0.0.1` / `8766`；NAS 局域网访问按部署文档配置 |
| `ARK_API_KEY` / `ARK_MODEL` / `ARK_BASE_URL` | 可选模型 API Key、模型或推理接入点及兼容接口地址 |
| `EIGHTYSGOD_ENABLED` | 80s 搜索开关，默认开启 |
| `DYTT_ENABLED` | 电影天堂搜索开关，默认开启 |
| `MOVIE_SOURCES_ENABLED` | 阳光电影、飘花、电影港、66 影视开关，默认开启 |
| `INTERNET_ARCHIVE_ENABLED` | Internet Archive 开关，默认关闭 |
| `PEXELS_API_KEY` / `PIXABAY_API_KEY` | 可选素材来源密钥 |
| `NAS_DOWNLOAD_DIR` / `NAS_MOUNT_URL` | NAS 下载目标与 SMB 挂载地址 |
| `XUNLEI_APP_PATH` | 本机迅雷路径 |
| `XUNLEI_MCP_URL` / `XUNLEI_MCP_TARGET` | 迅雷 MCP 与可选目标设备 |

电影天堂、电影港和 66 影视搜索页使用 GB 编码，服务负责处理中文。新增下载来源限制详情读取数量和总时间，各来源并行请求；部分站点失败时保留其他结果。站点搜索保留 TLS 校验并拒绝跨域跳转。

旧 `ARIA2_DOWNLOAD_DIR` 仅兼容读取为 NAS 目标目录，新配置使用 `NAS_DOWNLOAD_DIR`。Finder 挂载 NAS 后，需在迅雷中设置实际保存路径；唤起迅雷不会自动修改它的下载目录。

## 在线播放与下载

在线结果通过“播放”或海报进入内置播放器。阳光电影公开播放页中的地址以 JSON 静态解析，不执行原站脚本。HLS 使用随包提供的 hls.js 1.6.13，Safari 可使用原生 HLS；HTTP(S) MP4 / WebM 直链也提供播放入口。

影片和选集信息写入页面地址，刷新可重新打开当前选集。媒体是否可播取决于来源有效性、浏览器格式支持和服务器跨域许可；失败时可重试或使用已有原站入口。

下载结果提供“迅雷下载”和“复制下载链接”。磁力、种子、FTP、迅雷链接以及可解包的 `jianpian://` 链接交由迅雷处理。只有在线观看入口的结果隐藏下载按钮。搜索结果令牌过期后需重新搜索。

macOS 可调用本机迅雷，其他环境可配置迅雷 MCP 选择设备并创建任务。“已发送迅雷”表示已交给客户端，迅雷仍可能要求确认；下载进度在迅雷中查看，网页不自动搬运文件。

## 缓存

海报缓存默认位于运行目录 `.cache/posters`，保留 30 天、容量上限 128 MB，可通过 `POSTER_CACHE_DIR` 修改。服务请求豆瓣海报时设置 Referer，图片失效后尝试从 80s 匹配同名海报。

缓存命中不请求外站；外站临时不可用时可继续使用过期缓存，无匹配结果暂存 10 分钟。后台安装脚本保留图片缓存，播放链接保存在本地 SQLite 数据库。缓存链接不保证媒体地址永久有效。

## macOS 后台服务

```sh
./install-launch-agent.sh
```

安装脚本同步到 `~/Library/Application Support/PandaVideoDownloader`，使用 LaunchAgent `com.local.nas-video-downloader.app` 启动网站。更新源码后再次执行安装脚本。该服务标识保留兼容命名。

重启：

```sh
launchctl kickstart -k "gui/$(id -u)/com.local.nas-video-downloader.app"
```

停止：

```sh
launchctl bootout "gui/$(id -u)/com.local.nas-video-downloader.app"
```

日志位于 `~/Library/Logs/PandaVideoDownloader/app.log` 和 `app-error.log`。升级脚本会移除本项目旧下载及暂存 LaunchAgent，保留已下载文件与历史状态。

NAS 原生运行、开机任务与局域网域名见 [NAS 部署说明](NAS部署.md)。

## 测试与相关文档

在仓库根目录运行：

```sh
PYTHONPATH=frontend python3 -m unittest discover -s frontend/tests
node --test frontend/tests/test_list_pagination.js
```

- [浏览器自测说明](tests/browser/README.md)
- [网页版设计同步](docs/网页版设计同步.md)
- [完整自测报告](docs/完整自测报告.md)
- [项目文档索引](../doc/README.md)

历史测试报告只适用于对应版本与网络。站点搜索成功、链接解析成功和浏览器实际播放是不同验证步骤。
