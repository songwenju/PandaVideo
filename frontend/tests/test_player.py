import base64
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

import app
from player import PlayerError, media_type, resolve_online

DETAIL = """<a href="/vodplay/12-1-1.html">立即播放</a>
<ul><li><a href="/vodplay/12-1-1.html">第01集</a></li>
<li><a href="/vodplay/12-1-2.html">第02集</a></li></ul>
<a href="/vodplay/12-2-1.html">HD中字</a>
<a href="/vodplay/99-1-1.html">相关推荐</a>"""


def play(config=None):
    return "<script>var player_aaaa=" + json.dumps(config or {
        "url": "https://cdn.example/video/index.m3u8", "encrypt": 0,
    }) + "; unrelatedJavaScript()</script>"


class PlayerTests(unittest.TestCase):
    def test_playlist_deduplicates_and_excludes_recommendations(self):
        fetch = Mock(side_effect=[DETAIL, play()])
        result = resolve_online("https://huolot.com/voddetail/12.html", fetch)
        self.assertEqual(len(result["episodes"]), 3)
        self.assertEqual(result["episodes"][0]["label"], "第01集")
        self.assertEqual(result["kind"], "hls")
        self.assertEqual(result["selected"], "/vodplay/12-1-1.html")

    def test_selected_episode_fetches_matching_video(self):
        fetch = Mock(side_effect=[DETAIL, play()])
        result = resolve_online("https://huolot.com/voddetail/12.html", fetch, "/vodplay/12-1-2.html")
        self.assertEqual(fetch.call_args.args[0], "https://huolot.com/vodplay/12-1-2.html")
        self.assertEqual(result["selected"], "/vodplay/12-1-2.html")

    def test_untrusted_page_and_cross_movie_episode_are_rejected(self):
        fetch = Mock(return_value=DETAIL)
        for url in ("http://127.0.0.1/", "https://huolot.com.evil/voddetail/12.html",
                    "https://huolot.com/voddetail/12.html?redirect=evil",
                    "https://user@huolot.com/voddetail/12.html"):
            with self.assertRaises(PlayerError):
                resolve_online(url, fetch)
        fetch.assert_not_called()
        with self.assertRaises(PlayerError):
            resolve_online("https://huolot.com/voddetail/12.html", fetch, "/vodplay/99-1-1.html")
        self.assertEqual(fetch.call_count, 1)

    def test_encoded_public_url_decodes_without_executing_javascript(self):
        url = "https://cdn.example/movie.mp4"
        result = resolve_online("https://huolot.com/voddetail/12.html", Mock(side_effect=[
            DETAIL, play({"encrypt": 2, "url": base64.b64encode(url.encode()).decode()}),
        ]))
        self.assertEqual(result["url"], url)
        self.assertEqual(result["kind"], "video")

    def test_unsupported_and_restricted_playback_fail_clearly(self):
        for html in ("<script>var player_aaaa={url: runCode()}</script>",
                     play({"url": "javascript:alert(1)"}),
                     play({"url": "https://cdn.example/video.m3u8", "points": 5}),
                     "<html>无播放信息</html>"):
            with self.assertRaises(PlayerError):
                resolve_online("https://huolot.com/voddetail/12.html", Mock(side_effect=[DETAIL, html]))

    def test_direct_video_detection_preserves_download_only_resources(self):
        self.assertEqual(media_type("https://cdn.example/movie.MP4?token=x"), "video")
        for url in ("magnet:?xt=urn:btih:test", "ftp://cdn.example/movie.mp4",
                    "https://cdn.example/movie.torrent", "https://cdn.example/movie.mkv"):
            self.assertFalse(media_type(url))

    def test_http_player_api_token_expiry_and_video_csp(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            with patch.object(app, "REGISTRY", app.ResultRegistry()):
                item = app.REGISTRY.register({"source_id": "ygdy:/voddetail/12.html", "title": "测试剧集",
                    "detail_url": "https://huolot.com/voddetail/12.html"})
                self.assertTrue(item["playable"])
                with patch.object(app, "fetch_html", side_effect=[DETAIL, play()]):
                    with urlopen(base + "/api/player?" + urlencode({"token": item["token"]})) as response:
                        self.assertEqual(json.loads(response.read())["title"], "测试剧集")
                with self.assertRaises(HTTPError) as context:
                    urlopen(base + "/api/player?token=expired")
                self.assertEqual(context.exception.code, 410)
                direct = app.REGISTRY.register({"title": "直链", "download_url": "https://cdn.example/a.mp4"})
                with urlopen(base + "/api/player?token=" + direct["token"]) as response:
                    self.assertEqual(json.loads(response.read())["kind"], "video")
                with urlopen(base + "/player.html") as response:
                    self.assertIn("media-src", response.headers["Content-Security-Policy"])
                    self.assertEqual(response.headers["Cache-Control"], "no-store")
                with urlopen(base + "/") as response:
                    self.assertNotIn("connect-src 'self' https:", response.headers["Content-Security-Policy"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
