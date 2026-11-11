import json
import subprocess
import threading
import unittest
from email.message import Message
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
from unittest.mock import MagicMock, patch
from urllib.parse import parse_qs
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import app


SEARCH_HTML = """
<a class="ulink" href="/i/119423.html">龙与地下城</a>
<a class="ulink" href="/i/119423.html">重复</a>
<a class="ulink" href="/html/game/1.html">游戏</a>
<a class="ulink" href="https://other.example/i/1.html">外站</a>
<a class="ulink" href="//other.example/i/2.html">外站</a>
<a href="/i/3.html">导航</a>
"""
DETAIL_HTML = """
<meta name="description" content="影片简介">
<img src="https://img.dytt8899.com/poster.jpg">
<a href="jianpian://pathtype=url&amp;path=ftp://download.example/影片.mp4">荐片</a>
<a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&amp;dn=影片">磁力</a>
<a href="https://download.example/install.exe">广告</a>
<a href="javascript:alert(1)">广告</a>
<a href="https://download.example/trailer.mp4">视频</a>
"""


def post_api(path, payload):
    server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        request = Request(
            f"http://127.0.0.1:{server.server_port}{path}",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=5) as response:
            return json.loads(response.read())
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


class DyttTest(unittest.TestCase):
    def test_search_filters_external_and_non_movie_links(self):
        self.assertEqual(app.parse_dytt_search(SEARCH_HTML), [{
            "detail_url": "https://www.dytt8899.com/i/119423.html",
            "title": "龙与地下城",
        }])

    def test_detail_extracts_downloads_and_ignores_advertisements(self):
        results = app.parse_dytt_detail(
            DETAIL_HTML, "https://www.dytt8899.com/i/119423.html", "龙与地下城"
        )
        self.assertEqual(len(results), 3)
        self.assertEqual(results[0]["source"], "电影天堂")
        self.assertEqual(results[0]["description"], "影片简介")
        self.assertEqual(results[0]["thumbnail_url"], "https://img.dytt8899.com/poster.jpg")
        self.assertTrue(results[0]["download_url"].startswith("magnet:?"))
        self.assertTrue(results[1]["download_url"].startswith("ftp://"))
        self.assertNotIn("影片", results[1]["download_url"])

    def test_bt_links_precede_direct_downloads_with_stable_order(self):
        html = """
        <a href="ftp://example.com/movie.mp4">直链</a>
        <a href="https://example.com/movie.TORRENT?key=1">BT种子</a>
        <a href="magnet:?xt=urn:btih:test">磁力</a>
        <a href="https://example.com/movie.mkv">备用直链</a>
        """
        results = app.parse_dytt_detail(html, app.DYTT_BASE_URL + "/i/1.html", "影片")
        self.assertEqual([item["download_url"] for item in results], [
            "https://example.com/movie.TORRENT?key=1",
            "magnet:?xt=urn:btih:test",
            "ftp://example.com/movie.mp4",
            "https://example.com/movie.mkv",
        ])
        self.assertTrue(results[0]["title"].endswith("下载方式 1"))

    def test_poster_metadata_overrides_image_fallback_and_rejects_unsafe_urls(self):
        html = """
        <img src="/loading.gif">
        <img data-src="/fallback.jpg">
        <meta property="og:image" content="//images.example/影片.jpg">
        <a href="magnet:?xt=urn:btih:test">下载</a>
        """
        item = app.parse_dytt_detail(html, app.DYTT_BASE_URL + "/i/1.html", "影片")[0]
        self.assertEqual(
            item["thumbnail_url"], "https://images.example/%E5%BD%B1%E7%89%87.jpg"
        )
        self.assertEqual(app.thumbnail_url("javascript:alert(1)", app.DYTT_BASE_URL), "")
        self.assertEqual(app.thumbnail_url("data:image/png;base64,test", app.DYTT_BASE_URL), "")

    def test_search_prioritizes_bt_before_result_limit(self):
        candidates = [
            {"detail_url": app.DYTT_BASE_URL + "/i/1.html", "title": "第一部"},
            {"detail_url": app.DYTT_BASE_URL + "/i/2.html", "title": "第二部"},
        ]
        def detail(item):
            if item["title"] == "第一部":
                return [{"download_url": "ftp://example.com/one.mp4"}] * 2
            return [{"download_url": "magnet:?xt=urn:btih:two"}]
        with (
            patch.object(app, "request_text", return_value=""),
            patch.object(app, "parse_dytt_search", return_value=candidates),
            patch.object(app, "fetch_dytt_detail", side_effect=detail),
        ):
            results = app.search_dytt("影片", limit=2)
        self.assertEqual(results[0]["download_url"], "magnet:?xt=urn:btih:two")

    def test_request_encodes_form_and_decodes_response_in_gb18030(self):
        response = MagicMock()
        response.headers = Message()
        response.read.return_value = "中文搜索结果".encode("gb18030")
        response.__enter__.return_value = response
        with patch.object(app, "urlopen", return_value=response) as open_url:
            text = app.request_text(
                app.DYTT_BASE_URL, method="POST", form={"keyboard": "龙与地下城"},
                encoding="gb18030", form_encoding="gb18030",
            )
        self.assertEqual(text, "中文搜索结果")
        body = open_url.call_args.args[0].data.decode("ascii")
        self.assertEqual(parse_qs(body, encoding="gb18030")["keyboard"], ["龙与地下城"])

    def test_search_posts_original_chinese_and_resolves_detail(self):
        with patch.object(app, "request_text", side_effect=[SEARCH_HTML, DETAIL_HTML]) as request:
            results = app.search_dytt("龙与地下城", limit=2)
        self.assertEqual(len(results), 2)
        kwargs = request.call_args_list[0].kwargs
        self.assertEqual(kwargs["form"]["keyboard"], "龙与地下城")
        self.assertEqual(kwargs["form_encoding"], "gb18030")
        self.assertEqual(request.call_args_list[1].kwargs["encoding"], "gb18030")

    def test_provider_failure_preserves_other_results(self):
        config = SimpleNamespace(
            eightysgod_enabled=True, dytt_enabled=True, internet_archive_enabled=False,
        )
        with (
            patch.object(app, "CONFIG", config),
            patch.object(app, "understand_query", return_value={"keywords": "movie"}),
            patch.object(app, "search_pexels", return_value=[]),
            patch.object(app, "search_pixabay", return_value=[]),
            patch.object(app, "search_80sgod", return_value=[{
                "source": "80s电影网", "title": "保留结果", "download_url": "ftp://example/a.mp4"
            }]),
            patch.object(app, "search_dytt", side_effect=app.AppError("无法访问")),
        ):
            result = app.search_all("中文影片")
        self.assertEqual(result["results"][0]["title"], "保留结果")
        self.assertIn("电影天堂", result["errors"][0])

    def test_combined_search_places_dytt_bt_first(self):
        config = SimpleNamespace(
            eightysgod_enabled=True, dytt_enabled=True, internet_archive_enabled=False,
        )
        with (
            patch.object(app, "CONFIG", config),
            patch.object(app, "REGISTRY", app.ResultRegistry()),
            patch.object(app, "understand_query", return_value={"keywords": "movie"}),
            patch.object(app, "search_pexels", return_value=[{
                "source": "Pexels", "title": "素材", "download_url": "https://example/a.mp4",
            }]),
            patch.object(app, "search_pixabay", return_value=[]),
            patch.object(app, "search_80sgod", return_value=[]),
            patch.object(app, "search_dytt", return_value=[
                {"source": "电影天堂", "title": "直链", "download_url": "ftp://example/a.mp4"},
                {"source": "电影天堂", "title": "BT", "download_url": "magnet:?xt=urn:btih:test"},
            ]),
        ):
            result = app.search_all("影片")
        self.assertEqual(result["results"][0]["title"], "BT")

    def test_dytt_download_routes_to_xunlei(self):
        registry = app.ResultRegistry()
        item = registry.register({
            "source": "电影天堂", "title": "影片",
            "download_url": "magnet:?xt=urn:btih:test",
        })
        with (
            patch.object(app, "REGISTRY", registry),
            patch.object(app, "submit_xunlei_download", return_value={
                "engine": "xunlei_local", "device_name": "本机迅雷"
            }) as submit,
        ):
            result = post_api("/api/download", {"token": item["token"]})
        submit.assert_called_once_with("magnet:?xt=urn:btih:test")
        self.assertEqual(result["engine"], "xunlei_local")
        self.assertEqual(result["message"], "下载链接已发送给迅雷")


    def test_removed_engine_is_rejected_without_launching_xunlei(self):
        for source in ("80s电影网", "电影天堂"):
            with self.subTest(source=source):
                registry = app.ResultRegistry()
                url = "ftp://download.example/movie.mp4"
                item = registry.register({
                    "source": source, "title": "影片", "download_url": url,
                })
                with (
                    patch.object(app, "REGISTRY", registry),
                    patch.object(app, "submit_xunlei_download") as xunlei,
                ):
                    with self.assertRaises(HTTPError) as caught:
                        post_api("/api/download", {"token": item["token"], "engine": "aria2"})
                    self.assertEqual(caught.exception.code, 400)
                    caught.exception.close()
                xunlei.assert_not_called()


class CopyLinkTest(unittest.TestCase):
    def test_copy_returns_original_url_without_submitting_download(self):
        registry = app.ResultRegistry()
        url = "magnet:?xt=urn:btih:test&dn=影片"
        item = registry.register({"title": "影片", "download_url": url})
        self.assertNotIn("download_url", item)
        with (
            patch.object(app, "REGISTRY", registry),
            patch.object(app, "submit_xunlei_download") as xunlei,
        ):
            result = post_api("/api/download/copy", {"token": item["token"]})
        self.assertEqual(result, {"download_url": url})
        xunlei.assert_not_called()

    def test_copy_expired_result_returns_gone(self):
        registry = app.ResultRegistry()
        with patch.object(app.time, "time", return_value=0):
            item = registry.register({"download_url": "https://example.com/video.mp4"})
        with patch.object(app, "REGISTRY", registry):
            with self.assertRaises(HTTPError) as raised:
                post_api("/api/download/copy", {"token": item["token"]})
        self.assertEqual(raised.exception.code, 410)
        self.assertIn("已过期", json.loads(raised.exception.read())["error"])
        raised.exception.close()


class MountTest(unittest.TestCase):
    def test_already_mounted_does_not_run_applescript(self):
        with (
            patch.object(app, "nas_directory_ready", return_value=True),
            patch.object(app.subprocess, "run") as run,
        ):
            self.assertEqual(app.mount_nas(), "NAS 目录已挂载")
        run.assert_not_called()

    def test_mount_checks_directory_after_command(self):
        with (
            patch.object(app, "CONFIG", SimpleNamespace(nas_mount_url="smb://nas.example/video")),
            patch.object(app, "nas_directory_ready", side_effect=[False, True]),
            patch.object(app.subprocess, "run") as run,
        ):
            self.assertEqual(app.mount_nas(), "NAS 挂载成功")
        self.assertEqual(run.call_args.args[0][-1], "smb://nas.example/video")
        self.assertEqual(run.call_args.kwargs["timeout"], 25)

    def test_mount_error_does_not_expose_credentials(self):
        url = "smb://user:private-password@nas.example/video"
        with (
            patch.object(app, "CONFIG", SimpleNamespace(nas_mount_url=url)),
            patch.object(app, "nas_directory_ready", return_value=False),
            patch.object(app.subprocess, "run", side_effect=subprocess.CalledProcessError(1, [url])),
        ):
            with self.assertRaises(app.AppError) as caught:
                app.mount_nas()
        self.assertNotIn("private-password", str(caught.exception))

    def test_mount_api_reports_success(self):
        with (
            patch.object(app, "CONFIG", SimpleNamespace(nas_download_dir="/Volumes/test/downloads")),
            patch.object(app, "mount_nas", return_value="NAS 挂载成功"),
        ):
            result = post_api("/api/nas/mount", {})
        self.assertEqual(result["message"], "NAS 挂载成功")
        self.assertEqual(result["download_dir"], "/Volumes/test/downloads")


if __name__ == "__main__":
    unittest.main()
