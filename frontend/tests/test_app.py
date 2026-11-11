import json
import threading
import unittest
from contextlib import ExitStack
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
from unittest.mock import patch
from urllib.request import Request, urlopen

import app


SEARCH_HTML = """
<html><body>
  <a href="/movie/99999">侧栏结果不应被收集</a>
  <ul class="clearfix search_list">
    <li>
      <a href="/movie/24091"><i></i>[电影] 绿皮书 (2018)</a>
    </li>
    <li><a href="/movie/24091">重复结果</a></li>
    <li><a href="/not-supported/1">无效结果</a></li>
  </ul>
</body></html>
"""

DETAIL_HTML = """
<html><head>
  <meta property="og:title" content="绿皮书" />
  <meta property="og:image" content="/poster.jpg" />
  <meta name="description" content="影片简介" />
</head><body>
  <input name="list[]" value="http://download.example/绿皮书-720p.mp4" />
  <input value="https://download.example/绿皮书-1080p.mp4?token=abc&amp;x=1"
         name="list[]" />
  <input name="list[]"
         value="jianpian://pathtype=url&amp;path=ftp://download.example/绿皮书.mp4" />
  <input name="list[]" value="thunder://not-a-direct-http-link" />
</body></html>
"""


class EightysgodParserTest(unittest.TestCase):
    def test_search_parser_only_collects_deduplicated_search_results(self):
        results = app.parse_80sgod_search(SEARCH_HTML)

        self.assertEqual(
            results,
            [
                {
                    "detail_url": "https://www.80sgod.com/movie/24091",
                    "title": "[电影] 绿皮书 (2018)",
                }
            ],
        )

    def test_detail_parser_collects_download_links(self):
        results = app.parse_80sgod_detail(
            DETAIL_HTML,
            "https://www.80sgod.com/movie/24091",
            "备用标题",
        )

        self.assertEqual(len(results), 3)
        self.assertEqual(results[0]["source"], "80s电影网")
        self.assertEqual(results[0]["title"], "绿皮书 · 绿皮书-720p.mp4")
        self.assertEqual(
            results[1]["download_url"],
            "https://download.example/%E7%BB%BF%E7%9A%AE%E4%B9%A6-1080p.mp4"
            "?token=abc&x=1",
        )
        self.assertEqual(results[0]["license"], "")
        self.assertEqual(results[0]["thumbnail_url"], "https://www.80sgod.com/poster.jpg")
        self.assertEqual(
            results[2]["download_url"],
            "ftp://download.example/%E7%BB%BF%E7%9A%AE%E4%B9%A6.mp4",
        )

    @patch("app.request_text")
    def test_search_posts_original_keyword_and_resolves_detail(self, request_text):
        request_text.side_effect = [SEARCH_HTML, DETAIL_HTML]

        results = app.search_80sgod("绿皮书", limit=1)

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["title"], "绿皮书 · 绿皮书-720p.mp4")
        request_text.assert_any_call(
            "https://www.80sgod.com/search",
            method="POST",
            form={"keyword": "绿皮书"},
            timeout=20,
        )
        request_text.assert_any_call(
            "https://www.80sgod.com/movie/24091", timeout=20
        )

    def test_search_all_registers_80sgod_result_without_exposing_url(self):
        item = {
            "source": "80s电影网",
            "source_id": "/movie/24091#1",
            "title": "绿皮书",
            "description": "",
            "creator": "",
            "duration": "",
            "width": 0,
            "height": 0,
            "size": 0,
            "license": "请核验版权",
            "detail_url": "https://www.80sgod.com/movie/24091",
            "download_url": "http://download.example/green-book.mp4",
        }
        config = SimpleNamespace(
            eightysgod_enabled=True,
            internet_archive_enabled=False,
        )
        registry = app.ResultRegistry()
        with ExitStack() as stack:
            stack.enter_context(patch.object(app, "CONFIG", config))
            stack.enter_context(patch.object(app, "REGISTRY", registry))
            stack.enter_context(
                patch.object(
                    app,
                    "understand_query",
                    return_value={
                        "keywords": "Green Book",
                        "orientation": "any",
                        "quality": "any",
                    },
                )
            )
            stack.enter_context(patch.object(app, "search_pexels", return_value=[]))
            stack.enter_context(patch.object(app, "search_pixabay", return_value=[]))
            search = stack.enter_context(
                patch.object(app, "search_80sgod", return_value=[item])
            )
            response = app.search_all("绿皮书")

        search.assert_called_once_with("绿皮书")
        self.assertEqual(len(response["results"]), 1)
        self.assertNotIn("download_url", response["results"][0])
        token = response["results"][0]["token"]
        self.assertEqual(registry.resolve(token)["download_url"], item["download_url"])


class MagnetDownloadTest(unittest.TestCase):
    HEX_HASH = "0123456789abcdef0123456789abcdef01234567"

    def test_prepare_download_uri_preserves_magnet_and_name(self):
        magnet_uri = (
            f"MAGNET:?xt=urn:btih:{self.HEX_HASH}"
            "&dn=Authorized%20Archive&tr=https%3A%2F%2Ftracker.example%2Fannounce"
        )

        normalized, title = app.prepare_download_uri(magnet_uri)

        self.assertTrue(normalized.startswith("MAGNET:?"))
        self.assertIn(self.HEX_HASH, normalized)
        self.assertEqual(title, "Authorized Archive")

    def test_prepare_download_uri_accepts_arbitrary_uri(self):
        normalized, title = app.prepare_download_uri(
            "custom://host/path/file.bin?token=abc#part"
        )

        self.assertEqual(
            normalized,
            "custom://host/path/file.bin?token=abc#part",
        )
        self.assertEqual(title, "file.bin")

    def test_prepare_download_uri_extracts_and_encodes_jianpian_url(self):
        jianpian_uri = (
            "jianpian://pathtype=url&path="
            "ftp://a.gbl.114s.com:20320/1302/"
            "精装追女仔之3狼之一族-1989_蓝光国粤双语中字.mp4"
        )

        normalized, title = app.prepare_download_uri(jianpian_uri)

        self.assertEqual(
            normalized,
            "ftp://a.gbl.114s.com:20320/1302/"
            "%E7%B2%BE%E8%A3%85%E8%BF%BD%E5%A5%B3%E4%BB%94%E4%B9%8B3"
            "%E7%8B%BC%E4%B9%8B%E4%B8%80%E6%97%8F-1989_"
            "%E8%93%9D%E5%85%89%E5%9B%BD%E7%B2%A4%E5%8F%8C%E8%AF%AD"
            "%E4%B8%AD%E5%AD%97.mp4",
        )
        self.assertEqual(
            title,
            "精装追女仔之3狼之一族-1989_蓝光国粤双语中字.mp4",
        )

class XunleiDownloadTest(unittest.TestCase):
    HEX_HASH = "0123456789abcdef0123456789abcdef01234567"

    def test_search_result_routes_80sgod_download_to_xunlei(self):
        registry = app.ResultRegistry()
        registered = registry.register(
            {
                "source": "80s电影网",
                "title": "绿皮书",
                "download_url": "ftp://download.example/green-book.mp4",
            }
        )
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        request = Request(
            f"http://127.0.0.1:{server.server_port}/api/download",
            data=json.dumps({"token": registered["token"]}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        config = SimpleNamespace(nas_download_dir="/Volumes/video/downloads")

        try:
            with (
                patch.object(app, "CONFIG", config),
                patch.object(app, "REGISTRY", registry),
                patch.object(
                    app,
                    "submit_xunlei_download",
                    return_value={
                        "target": "test-target",
                        "device_name": "我的电脑",
                        "engine": "xunlei_mcp",
                    },
                ) as submit_xunlei,
            ):
                with urlopen(request, timeout=5) as response:
                    payload = json.loads(response.read().decode("utf-8"))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        submit_xunlei.assert_called_once_with(
            "ftp://download.example/green-book.mp4"
        )
        self.assertEqual(payload["engine"], "xunlei_mcp")
        self.assertEqual(payload["title"], "绿皮书")
        self.assertEqual(payload["destination"], "我的电脑")

    def test_search_result_routes_other_sources_to_xunlei(self):
        registry = app.ResultRegistry()
        registered = registry.register(
            {
                "source": "Other Provider",
                "title": "示例视频",
                "download_url": "https://download.example/video.webm",
            }
        )
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        request = Request(
            f"http://127.0.0.1:{server.server_port}/api/download",
            data=json.dumps({"token": registered["token"]}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        config = SimpleNamespace(nas_download_dir="/Volumes/video/downloads")

        try:
            with (
                patch.object(app, "CONFIG", config),
                patch.object(app, "REGISTRY", registry),
                patch.object(app, "submit_xunlei_download", return_value={
                    "engine": "xunlei_local", "device_name": "本机迅雷",
                }) as submit_xunlei,
            ):
                with urlopen(request, timeout=5) as response:
                    payload = json.loads(response.read().decode("utf-8"))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        submit_xunlei.assert_called_once_with("https://download.example/video.webm")
        self.assertEqual(payload["engine"], "xunlei_local")
        self.assertNotIn("gid", payload)

    def test_xunlei_mcp_discovers_device_checks_url_then_creates_task(self):
        download_url = "ftp://download.example/green-book.mp4"
        client = unittest.mock.MagicMock()
        client.call_tool.side_effect = [
            {
                "next_page_token": "",
                "device": [
                    {
                        "target": "test-target",
                        "name": "我的电脑",
                    }
                ],
            },
            [
                {
                    "url": download_url,
                    "file_name": "green-book.mp4",
                    "message": "valid",
                }
            ],
            {"task_id": "test-task"},
        ]
        config = SimpleNamespace(
            xunlei_mcp_url="https://mcp.example/sse/key",
            xunlei_mcp_target="",
        )

        with (
            patch.object(app, "CONFIG", config),
            patch.object(app, "XunleiMcpClient", return_value=client),
        ):
            result = app.submit_xunlei_mcp_download(download_url)

        client.connect.assert_called_once_with()
        self.assertEqual(
            client.call_tool.call_args_list,
            [
                unittest.mock.call(
                    "xunlei_download_list_device",
                    {"page_size": 100},
                ),
                unittest.mock.call(
                    "xunlei_download_check_urls",
                    {"urls": [download_url]},
                ),
                unittest.mock.call(
                    "xunlei_download_create",
                    {
                        "target": "test-target",
                        "urls": [download_url],
                    },
                ),
            ],
        )
        client.close.assert_called_once_with()
        self.assertEqual(
            result,
            {"target": "test-target", "device_name": "我的电脑"},
        )

    def test_xunlei_submission_falls_back_to_local_client_without_mcp_device(self):
        download_url = "ftp://download.example/green-book.mp4"
        client = unittest.mock.MagicMock()
        client.call_tool.return_value = {
            "next_page_token": "",
            "device": [],
        }
        config = SimpleNamespace(
            xunlei_mcp_url="https://mcp.example/sse/key",
            xunlei_mcp_target="",
        )

        with (
            patch.object(app, "CONFIG", config),
            patch.object(app, "XunleiMcpClient", return_value=client),
            patch.object(app, "launch_xunlei") as launch_xunlei,
        ):
            result = app.submit_xunlei_download(download_url)

        launch_xunlei.assert_called_once_with(download_url)
        self.assertEqual(
            result,
            {
                "target": "local",
                "device_name": "本机迅雷",
                "engine": "xunlei_local",
            },
        )

    def test_xunlei_submission_uses_local_client_immediately_on_mac(self):
        download_url = "ftp://download.example/green-book.mp4"
        config = SimpleNamespace(
            xunlei_app_path="/Applications/Thunder.app",
            xunlei_mcp_url="https://mcp.example/sse/key",
        )

        with (
            patch.object(app, "CONFIG", config),
            patch.object(app.sys, "platform", "darwin"),
            patch.object(app.Path, "is_dir", return_value=True),
            patch.object(app, "launch_xunlei") as launch_xunlei,
            patch.object(app, "submit_xunlei_mcp_download") as submit_mcp,
        ):
            result = app.submit_xunlei_download(download_url)

        launch_xunlei.assert_called_once_with(download_url)
        submit_mcp.assert_not_called()
        self.assertEqual(result["engine"], "xunlei_local")

    def test_xunlei_accepts_magnet_without_rewriting(self):
        magnet_uri = f"magnet:?xt=urn:btih:{self.HEX_HASH}&dn=Authorized"

        launch_uri, title = app.prepare_xunlei_download_uri(magnet_uri)

        self.assertEqual(launch_uri, magnet_uri)
        self.assertEqual(title, "Authorized")

    def test_xunlei_wraps_jianpian_ftp_as_thunder_uri(self):
        jianpian_uri = (
            "jianpian://pathtype=url&path="
            "ftp://download.example/合法资源.mp4"
        )

        launch_uri, title = app.prepare_xunlei_download_uri(jianpian_uri)

        self.assertTrue(launch_uri.startswith("thunder://"))
        self.assertEqual(title, "合法资源.mp4")

    def test_xunlei_accepts_arbitrary_direct_url(self):
        download_uri = "https://download.example/file.mp4?token=abc"

        launch_uri, title = app.prepare_xunlei_download_uri(download_uri)

        self.assertEqual(launch_uri, download_uri)
        self.assertEqual(title, "file.mp4")

    def test_launch_xunlei_uses_registered_application(self):
        config = SimpleNamespace(
            xunlei_app_path="/Applications/Thunder.app",
            nas_download_dir="/Volumes/video/downloads",
        )
        launch_uri = f"magnet:?xt=urn:btih:{self.HEX_HASH}"

        with (
            patch.object(app, "CONFIG", config),
            patch.object(app.Path, "is_dir", return_value=True),
            patch.object(app.os, "access", return_value=True),
            patch.object(app.subprocess, "run") as run,
        ):
            app.launch_xunlei(launch_uri)

        run.assert_called_once_with(
            ["/usr/bin/open", "-a", "Thunder", launch_uri],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )

    def test_xunlei_api_validates_and_launches_client(self):
        magnet_uri = f"magnet:?xt=urn:btih:{self.HEX_HASH}&dn=Authorized"
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        request = Request(
            f"http://127.0.0.1:{server.server_port}/api/download/xunlei",
            data=json.dumps({"download_uri": magnet_uri}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        config = SimpleNamespace(nas_download_dir="/Volumes/video/downloads")

        try:
            with patch.object(app, "CONFIG", config), patch.object(
                app, "launch_xunlei"
            ) as launch_xunlei:
                with urlopen(request, timeout=5) as response:
                    payload = json.loads(response.read().decode("utf-8"))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        launch_xunlei.assert_called_once_with(magnet_uri)
        self.assertEqual(payload["message"], "下载链接已发送给迅雷")
        self.assertEqual(payload["title"], "Authorized")
        self.assertEqual(payload["destination"], "/Volumes/video/downloads")


if __name__ == "__main__":
    unittest.main()
