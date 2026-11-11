# NAS 原生部署

需要 Python 3.9 或更高版本。Mac 和 NAS 共用本仓库源码，不需要 Docker 或 pip。

1. 在 DSM 套件中心安装 Python 3.9。
2. 将仓库上传到 NAS 的独立目录，例如 `/volume1/video/PandaVideo`，网站工程位于其 `frontend/` 子目录。执行任务的普通用户需要对此目录具有读写权限。
3. 在 `frontend/` 目录将 `.env.nas.example` 复制为 `.env`，修改 `NAS_DOWNLOAD_DIR` 为实际目录，按需填写 API 或迅雷 MCP 配置。默认监听 `0.0.0.0:8766`。
4. 在「控制面板 → 任务计划」创建开机触发的「用户定义的脚本」，使用有目录读写权限的用户，并填写：

   ```sh
   /bin/sh /volume1/video/PandaVideo/frontend/service.sh start
   ```

5. 手动运行一次，用局域网浏览器打开 `http://NAS地址:8766`，并检查 `/api/status`。

如果 Python 安装在自定义目录，可使用 `PYTHON_BIN=/路径/python3 /bin/sh /volume1/video/PandaVideo/frontend/service.sh start`。

状态和停止命令分别为 `service.sh status`、`service.sh stop`。日志位于 `.service/server.log`。程序启动不代表网页一定可用，需要检查端口和接口响应。

## 数据及升级

网站工程的 `frontend/` 目录中，`.env` 存放本地配置；`.cache` 存放海报缓存和播放链接 SQLite 数据库；`.service` 存放进程及日志。它们不提交到 GitHub。升级源码时保留这些目录和配置。如果从旧版平铺目录升级，将旧 `.env`、`.cache` 和 `.service` 一并迁移到 `frontend/`，并更新开机任务路径。

macOS 可以唤起本机迅雷。NAS 上未配置迅雷 MCP 时，网页提供“打开当前设备迅雷”的链接，下载位置由浏览设备的迅雷决定。

## 局域网域名

若希望用 `http://nas.com` 访问，需要同时完成：

- DSM「登录门户 → 高级 → 反向代理服务器」：来源 `HTTP nas.com:80`，目标 `HTTP 127.0.0.1:8766`。
- 局域网 DNS 添加 `nas.com` 的 A 记录，指向 NAS 的局域网 IPv4 地址。
- 访问设备使用上述局域网 DNS。只有修改单台电脑的 hosts，其他设备不会自动生效。

若路由器无法设置本地域名，可使用 NAS 的 DNS Server：创建 `nas.com` Master 区域，在根域名添加 A 记录，并启用供局域网查询的解析及上游转发。先验证本地域名和普通互联网域名都能解析，再修改客户端 DNS。

若 DSM 防火墙启用，应允许局域网访问网站端口，以及 DNS 的 UDP/TCP 53。此部署不需要公网端口转发。

`nas.com` 是公网域名，上述设置只在使用局域网 DNS 的设备上生效。
