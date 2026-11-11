# 本地媒体验证

**历史工具说明：**本目录服务用于 v0.2 的 Python API 客户端验证。v0.3 移动应用已取消服务地址设置，不再连接此服务；不要按下文给正式应用配置 QA 地址。当前共享网络与解析测试位于 `shared/src/commonTest`，通过 Ktor MockEngine 验证，原生播放器测试与真实来源设备验证结果见 `docs/实现与验证记录.md`。

`server.py` 是测试 API，使用本机生成的样片，不访问影视源站，不接入正式应用。

先准备媒体目录并生成 12 秒 MP4 / HLS：

```sh
mkdir -p /tmp/pandavideo-media-qa
ffmpeg -y -f lavfi -i 'testsrc2=size=640x360:rate=24' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 12 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac \
  -movflags +faststart /tmp/pandavideo-media-qa/clip.mp4
ffmpeg -y -i /tmp/pandavideo-media-qa/clip.mp4 -c copy \
  -hls_time 3 -hls_list_size 0 /tmp/pandavideo-media-qa/stream.m3u8
python3 qa/server.py --media-dir /tmp/pandavideo-media-qa
```

Android USB 设备：

```sh
adb reverse tcp:8767 tcp:8767
```

在客户端设置 `http://127.0.0.1:8767`，验证并保存。发现页会显示 MP4/HLS 两个测试条目，每个条目提供两集主线路和一集备用线路。验证暂停、倍速、手动选集、同线路连播、全屏和退出释放。

iOS 模拟器也可使用该地址。真机需要把服务监听地址及媒体 origin 改为设备可访问的局域网 IP。

验证结束后在设置中保存正式服务地址，并停止 QA 服务。这个简易文件服务器不模拟源站鉴权、DRM、复杂 Range 行为或网络抖动。
