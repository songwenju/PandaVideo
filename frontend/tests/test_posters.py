import base64
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock, patch

import app
import posters


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jYxkAAAAASUVORK5CYII="
)
PRIMARY = "https://img2.doubanio.com/view/photo/s_ratio_poster/public/p123.jpg"
ALTERNATIVE = "https://80sgod.njjyxs.com/upload/img/poster.jpg"


class PosterTests(unittest.TestCase):
    def test_image_origins_reject_local_hosts_and_foreign_redirects(self):
        for url in ("http://127.0.0.1/a", "http://localhost/a",
                    "http://192.168.1.2/a", "https://img2.doubanio.com.evil.example/a",
                    "https://img2.doubanio.com:8766/a", "file:///etc/passwd"):
            self.assertFalse(posters.approved_url(url))
        self.assertTrue(posters.approved_url(PRIMARY))
        self.assertTrue(posters.approved_url(ALTERNATIVE))
        with self.assertRaises(posters.PosterError):
            posters.PosterRedirects().redirect_request(
                None, None, 302, "", {}, "http://127.0.0.1/a")

    def test_downloader_sends_referer_and_rejects_non_image_response(self):
        response = Mock()
        response.read.return_value = PNG
        opener = Mock()
        opener.open.return_value.__enter__ = Mock(return_value=response)
        opener.open.return_value.__exit__ = Mock(return_value=False)
        with patch.object(posters, "build_opener", return_value=opener):
            data, mime = posters.fetch_image(PRIMARY)
            request = opener.open.call_args.args[0]
            self.assertEqual(request.get_header("Referer"), "https://movie.douban.com/")
            self.assertEqual(data, PNG)
            self.assertEqual(mime, "image/png")
            response.read.return_value = b"<html>Not an image</html>" * 10
            with self.assertRaises(posters.PosterError):
                posters.fetch_image(PRIMARY)

    def test_disk_cache_survives_new_service_instance(self):
        with tempfile.TemporaryDirectory() as directory:
            fetch = Mock(return_value=(PNG, "image/png"))
            fallback = Mock()
            first = posters.PosterCache(directory, fetch).get(PRIMARY, "影片", fallback)
            second = posters.PosterCache(directory, fetch).get(PRIMARY, "影片", fallback)
            self.assertEqual(first[2], "MISS")
            self.assertEqual(second[2], "HIT")
            self.assertEqual(first[0], second[0])
            fetch.assert_called_once_with(PRIMARY)
            fallback.assert_not_called()

    def test_unavailable_original_uses_alternative_and_caches_it(self):
        with tempfile.TemporaryDirectory() as directory:
            fetch = Mock(side_effect=[posters.PosterError("HTTP 418"), (PNG, "image/png")])
            fallback = Mock(return_value=ALTERNATIVE)
            cache = posters.PosterCache(directory, fetch)
            first = cache.get(PRIMARY, "影片", fallback)
            second = cache.get(PRIMARY, "影片", fallback)
            self.assertEqual(first[3], "80s")
            self.assertEqual(second[2], "HIT")
            self.assertEqual(fetch.call_count, 2)
            fallback.assert_called_once_with("影片")

    def test_missing_url_can_lookup_by_title(self):
        with tempfile.TemporaryDirectory() as directory:
            fetch = Mock(return_value=(PNG, "image/png"))
            result = posters.PosterCache(directory, fetch).get(
                "", "影片", Mock(return_value=ALTERNATIVE))
            self.assertEqual(result[3], "80s")
            fetch.assert_called_once_with(ALTERNATIVE)

    def test_cache_write_failure_does_not_hide_valid_remote_image(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = posters.PosterCache(directory, Mock(return_value=(PNG, "image/png")))
            with patch.object(cache, "_write", side_effect=PermissionError("read only")):
                result = cache.get(PRIMARY, "影片", Mock())
            self.assertEqual(result[0], PNG)
            self.assertEqual(result[2], "BYPASS")

    def test_negative_cache_avoids_repeated_unmatched_searches(self):
        with tempfile.TemporaryDirectory() as directory:
            fetch = Mock()
            fallback = Mock(return_value="")
            cache = posters.PosterCache(directory, fetch)
            for _ in range(2):
                with self.assertRaises(posters.PosterError):
                    cache.get("", "不存在的影片", fallback)
            fallback.assert_called_once()
            fetch.assert_not_called()

    def test_expired_images_remain_available_when_sources_are_offline(self):
        with tempfile.TemporaryDirectory() as directory:
            fetch = Mock(return_value=(PNG, "image/png"))
            clock = Mock(return_value=100)
            cache = posters.PosterCache(directory, fetch, clock)
            cache.get(PRIMARY, "影片", Mock())
            clock.return_value = 100 + posters.CACHE_TTL + 1
            fetch.side_effect = posters.PosterError("offline")
            result = cache.get(PRIMARY, "影片", Mock(return_value=""))
            self.assertEqual(result[2], "STALE")
            self.assertEqual(result[0], PNG)

    def test_concurrent_requests_for_same_poster_fetch_once(self):
        with tempfile.TemporaryDirectory() as directory:
            fetch = Mock(return_value=(PNG, "image/png"))
            cache = posters.PosterCache(directory, fetch)
            with ThreadPoolExecutor(max_workers=4) as pool:
                results = list(pool.map(lambda _: cache.get(PRIMARY, "影片", Mock()), range(4)))
            self.assertEqual(sum(r[2] == "MISS" for r in results), 1)
            fetch.assert_called_once()

    def test_cache_storage_limit_removes_oldest_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = posters.PosterCache(directory, Mock(return_value=(PNG, "image/png")))
            with patch.object(posters, "CACHE_LIMIT_BYTES", len(PNG)):
                cache.get(PRIMARY, "第一部", Mock())
                cache.get(PRIMARY, "第二部", Mock())
            self.assertEqual(len(list(Path(directory).glob("*.bin"))), 1)
            self.assertEqual(len(list(Path(directory).glob("*.json"))), 1)

    def test_alternative_lookup_requires_matching_title(self):
        html = """<ul class="clearfix search_list">
        <li><a href="/movie/24091">[电影] 星际穿越 (2014)</a></li></ul>"""
        detail = '<meta property="og:image" content="' + ALTERNATIVE + '">'
        with patch.object(app, "request_text", side_effect=[html, detail]):
            self.assertEqual(app.lookup_alternative_poster("星际穿越"), ALTERNATIVE)
        with patch.object(app, "request_text", return_value=html) as request:
            self.assertEqual(app.lookup_alternative_poster("另一部影片"), "")
            request.assert_called_once()


if __name__ == "__main__":
    unittest.main()
