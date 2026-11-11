import json
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import app
from playback_library import PlaybackLibrary
from player import PlayerError, resolve_online

ITEM = {"title": "星际穿越", "url": "https://movie.douban.com/subject/1889243/", "year": "2014"}
CANDIDATE = {"title": "星际穿越", "detail_url": "https://huolot.com/voddetail/12.html"}
RESOLVED = {"page_url": "https://huolot.com/vodplay/12-1-1.html", "year": "2014"}


class PlaybackLibraryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "playback.sqlite3"
        self.search = Mock(return_value=[CANDIDATE])
        self.resolve = Mock(return_value=RESOLVED)
        self.now = 100000
        self.library = PlaybackLibrary(self.path, self.search, self.resolve, lambda: self.now)

    def test_saved_link_survives_new_instance_and_expired_search_tokens(self):
        result = self.library.lookup(ITEM)
        self.assertTrue(result["playable"])
        self.assertNotIn("token=", result["play_url"])
        second = PlaybackLibrary(self.path, Mock(side_effect=AssertionError), Mock(side_effect=AssertionError),
                                 lambda: self.now)
        cached = second.lookup(ITEM)
        self.assertTrue(cached["cached"])
        self.assertEqual(result["play_url"], cached["play_url"])

    def test_concurrent_requests_search_once(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(self.library.lookup, [ITEM] * 8))
        self.assertTrue(all(item["playable"] for item in results))
        self.search.assert_called_once()

    def test_title_year_and_ambiguous_matches_never_show_play(self):
        cases = [
            ([{**CANDIDATE, "title": "《星际穿越》中的科学"}], RESOLVED, ITEM),
            ([CANDIDATE], {**RESOLVED, "year": "2020"}, ITEM),
            ([CANDIDATE], {**RESOLVED, "year": ""}, ITEM),
            ([CANDIDATE, {**CANDIDATE, "detail_url": "https://huolot.com/voddetail/13.html"}],
             RESOLVED, {**ITEM, "year": ""}),
        ]
        for index, (candidates, resolved, item) in enumerate(cases):
            library = PlaybackLibrary(Path(self.directory.name) / f"case{index}.sqlite3",
                                      Mock(return_value=candidates), Mock(return_value=resolved))
            self.assertFalse(library.lookup(item)["playable"])

    def test_unsupported_media_is_not_playable(self):
        self.resolve.side_effect = PlayerError("unsupported")
        self.assertFalse(self.library.lookup(ITEM)["playable"])

    def test_negative_expiry_and_changed_year_trigger_refresh(self):
        self.search.return_value = []
        self.assertFalse(self.library.lookup(ITEM)["playable"])
        self.assertTrue(self.library.lookup(ITEM)["cached"])
        self.now += 86401
        self.search.return_value = [CANDIDATE]
        self.assertTrue(self.library.lookup(ITEM)["playable"])
        self.library.lookup({**ITEM, "year": "2015"})
        self.assertEqual(self.search.call_count, 3)

    def test_transient_failure_does_not_persist_absence(self):
        self.search.side_effect = OSError("temporary network failure")
        with self.assertRaises(OSError):
            self.library.lookup(ITEM)
        self.search.side_effect = None
        self.assertTrue(self.library.lookup(ITEM)["playable"])

    def test_positive_links_are_rechecked_after_seven_days(self):
        self.library.lookup(ITEM)
        self.now += 7 * 86400 + 1
        self.library.lookup(ITEM)
        self.assertEqual(self.search.call_count, 2)

    def test_year_is_read_from_detail_metadata(self):
        detail = '<span>年份：</span><a href="/vodsearch/2014.html">2014</a><a href="/vodplay/12-1-1.html">HD</a>'
        config = '<script>var player_aaaa={"url":"https://cdn.example/video.mp4"};</script>'
        result = resolve_online(CANDIDATE["detail_url"], Mock(side_effect=[detail, config]))
        self.assertEqual(result["year"], "2014")

    def test_http_api_validates_and_returns_persisted_link(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        endpoint = f"http://127.0.0.1:{server.server_port}/api/playback/resolve"
        def post(payload):
            return urlopen(Request(endpoint, data=json.dumps(payload).encode(),
                                   headers={"Content-Type": "application/json"}))
        try:
            with patch.object(app, "PLAYBACK_LIBRARY", self.library):
                with post(ITEM) as response:
                    self.assertTrue(json.loads(response.read())["playable"])
                for invalid in ({**ITEM, "url": "https://evil.example/subject/1/"},
                                {**ITEM, "year": "bad"}, {**ITEM, "title": []}):
                    with self.assertRaises(HTTPError) as context:
                        post(invalid)
                    self.assertEqual(context.exception.code, 400)
                self.now += 7 * 86400 + 1
                self.resolve.side_effect = OSError("temporary")
                with self.assertRaises(HTTPError) as context:
                    post(ITEM)
                self.assertEqual(context.exception.code, 502)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
