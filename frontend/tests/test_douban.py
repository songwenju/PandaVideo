import json
import unittest
import threading
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import urlopen
from urllib.parse import parse_qs, urlparse
from unittest.mock import Mock, patch

import app
from douban import DoubanLists, Top250Parser, source_rating, subject_url, validate_browse


TOP_HTML = """
<div class="item"><div class="pic"><em>1</em>
<a href="https://movie.douban.com/subject/1292052/">
<img alt="肖申克的救赎" src="https://img.example/shawshank.jpg"></a></div>
<div class="info"><div class="bd"><p>导演：某某<br>1994 / 美国 / 剧情</p>
<div><span class="rating_num">9.7</span></div></div></div></div>
"""


class DoubanTest(unittest.TestCase):
    def test_source_page_rating_is_attributed_and_bounded(self):
        result = source_rating(
            "<p>◎豆　瓣　评　分　7.6/10 from 173354 users</p>", "《影片》"
        )
        self.assertEqual(result["douban_rating"], 7.6)
        self.assertEqual(result["douban_rating_source"], "来源站标注")
        self.assertIn("cat=1002", result["douban_url"])
        self.assertIsNone(source_rating("豆瓣评分 12", "影片")["douban_rating"])
        self.assertIsNone(source_rating("豆瓣评分 暂无评分", "影片")["douban_rating"])

    def test_source_subject_link_is_preserved(self):
        result = source_rating(
            '<a href="https://movie.douban.com/subject/1292052/">豆瓣</a>', "影片"
        )
        self.assertEqual(result["douban_url"], "https://movie.douban.com/subject/1292052/")
        self.assertEqual(subject_url("https://evil.example/subject/1/"), "")

    def test_top250_parses_rank_year_rating(self):
        parser = Top250Parser()
        parser.feed(TOP_HTML)
        self.assertEqual(parser.items, [{
            "title": "肖申克的救赎", "url": "https://movie.douban.com/subject/1292052/",
            "rating": 9.7, "rank": 1, "year": "1994",
            "cover": "https://img.example/shawshank.jpg",
        }])

    def test_hot_list_ignores_untrusted_subject_urls_and_missing_scores(self):
        fetch = Mock(return_value=json.dumps({"subjects": [
            {"title": "新片", "rate": "", "url": "https://movie.douban.com/subject/123/"},
            {"title": "广告", "rate": "10", "url": "https://evil.example/subject/1/"},
        ]}))
        result = DoubanLists().fetch("popular", 0, fetch)
        self.assertEqual(result["items"], [])
        self.assertFalse(result["has_more"])

    def test_missing_ratings_are_filtered_without_losing_pagination(self):
        for method, count in (("browse", 20), ("list", 25)):
            with self.subTest(method=method):
                items = [{
                    "title": f"作品 {index}",
                    "rate": "8.2" if index == 0 else "",
                    "url": f"https://movie.douban.com/subject/{index + 1}/",
                } for index in range(count)]
                service = DoubanLists()
                fetch = Mock(return_value=json.dumps({
                    "data" if method == "browse" else "subjects": items,
                }))
                result = service.browse("movie", "all", "all", "hot", 0, fetch) if method == "browse" else service.fetch("popular", 0, fetch)
                self.assertEqual(len(result["items"]), 1)
                self.assertEqual(result["items"][0]["rating"], 8.2)
                self.assertTrue(result["has_more"])

    def test_all_unrated_browse_page_remains_valid_and_can_advance(self):
        fetch = Mock(return_value=json.dumps({"data": [{
            "title": f"未评分 {index}", "rate": None,
            "url": f"https://movie.douban.com/subject/{index + 1}/",
        } for index in range(20)]}))
        result = DoubanLists().browse("tv", "all", "all", "hot", 0, fetch)
        self.assertEqual(result["items"], [])
        self.assertTrue(result["has_more"])

    def test_list_cache_prevents_repeated_upstream_requests(self):
        service = DoubanLists()
        fetch = Mock(return_value=TOP_HTML)
        first = service.fetch("top250", 0, fetch)
        second = service.fetch("top250", 0, fetch)
        fetch.assert_called_once()
        self.assertFalse(first["cached"])
        self.assertTrue(second["cached"])

    def test_genre_lists_use_douban_tags_and_separate_caches(self):
        service = DoubanLists()
        fetch = Mock(return_value=json.dumps({"data": [{
            "title": "影片", "rate": "8.0",
            "url": "https://movie.douban.com/subject/123/",
        }]}))
        for kind, tags, label in (
            ("comedy", "电影,喜剧", "Top 喜剧片"), ("anime", "动画", "Top 动画"),
            ("finance", "电影,金融", "Top 金融电影"),
            ("nature", "纪录片,自然", "Top 自然纪录片"),
            ("biography", "电影,传记", "Top 人物传记"),
            ("war", "电影,战争", "Top 战争片"),
            ("hollywood", "电影,美国,大片", "Top 好莱坞大片"),
            ("tv", "电视剧", "Top 剧集"),
        ):
            with self.subTest(kind=kind):
                result = service.fetch(kind, 20, fetch)
                query = parse_qs(urlparse(fetch.call_args.args[0]).query)
                self.assertEqual(query["tags"], [tags])
                self.assertEqual(query["sort"], ["S"])
                self.assertEqual(query["start"], ["20"])
                self.assertEqual(result["label"], label)
                self.assertEqual(result["kind"], kind)
                self.assertEqual(result["page_size"], 20)
        self.assertEqual(fetch.call_count, 8)

    def test_expired_cache_is_marked_stale_after_upstream_failure(self):
        service = DoubanLists()
        with patch("douban.time.time", return_value=100):
            service.fetch("top250", 0, Mock(return_value=TOP_HTML))
        with patch("douban.time.time", return_value=4000):
            result = service.fetch("top250", 0, Mock(side_effect=app.AppError("offline")))
        self.assertTrue(result["stale"])
        self.assertEqual(result["fetched_at"], 100)

    def test_annual_tv_filters_current_year_and_uses_twenty_item_pages(self):
        fetch = Mock(return_value=json.dumps({"data": [{
            "title": f"年度剧集 {index}", "rate": "8.0",
            "url": f"https://movie.douban.com/subject/{index + 1}/",
        } for index in range(20)]}))
        with patch("douban.date") as today:
            today.today.return_value.year = 2026
            result = DoubanLists().fetch("annual_tv", 20, fetch)
        query = parse_qs(urlparse(fetch.call_args.args[0]).query)
        self.assertEqual(query["year_range"], ["2026,2026"])
        self.assertEqual(query["tags"], ["电视剧"])
        self.assertEqual(query["sort"], ["U"])
        self.assertEqual(query["start"], ["20"])
        self.assertEqual(result["page_size"], 20)
        self.assertTrue(result["has_more"])
        self.assertEqual(result["items"][0]["year"], "2026")
        self.assertIn("2026", result["label"])

    def test_empty_challenge_page_does_not_become_a_successful_list(self):
        with self.assertRaises(ValueError):
            DoubanLists().fetch("top250", 0, Mock(return_value="<html>登录</html>"))

    def test_browse_combines_type_genre_year_and_sort_and_keeps_caches_separate(self):
        service = DoubanLists()
        fetch = Mock(return_value=json.dumps({"data": [{
            "title": "自然纪录片", "rate": "9.1",
            "url": "https://movie.douban.com/subject/123/", "cover": "https://img.example/poster.jpg",
        }]}))
        result = service.browse("documentary", "nature", "2025", "rating", 20, fetch)
        query = parse_qs(urlparse(fetch.call_args.args[0]).query)
        self.assertEqual(query["tags"], ["纪录片,自然"])
        self.assertEqual(query["year_range"], ["2025,2025"])
        self.assertEqual(query["sort"], ["S"])
        self.assertEqual(query["start"], ["20"])
        self.assertEqual(result["items"][0]["rating"], 9.1)
        service.browse("documentary", "nature", "2025", "rating", 20, fetch)
        self.assertEqual(fetch.call_count, 1)
        service.browse("documentary", "nature", "all", "hot", 0, fetch)
        self.assertEqual(fetch.call_count, 2)

    def test_browse_empty_filter_result_is_valid_but_challenge_response_is_not(self):
        empty = DoubanLists().browse(
            "movie", "all", "all", "hot", 0, Mock(return_value='{"data":[]}')
        )
        self.assertFalse(empty["has_more"])
        self.assertEqual(empty["items"], [])
        with self.assertRaises(ValueError):
            DoubanLists().browse(
                "movie", "all", "all", "hot", 0, Mock(return_value='{"message":"login"}')
            )

    def test_browse_rejects_invalid_filters_before_request(self):
        for filters in (
            ("invalid", "all", "all", "hot", 0),
            ("tv", "all", "all", "hot", 25),
            ("tv", "all", "3000", "hot", 0),
        ):
            with self.subTest(filters=filters), self.assertRaises(ValueError):
                validate_browse(*filters)

    def test_browse_api_passes_filters_and_rejects_invalid_page(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            root = f"http://127.0.0.1:{server.server_port}/api/douban/browse"
            with patch.object(app.DOUBAN_LISTS, "browse", return_value={"items": []}) as browse:
                with urlopen(root + "?type=tv&year=2025&start=20") as response:
                    self.assertEqual(response.status, 200)
                browse.assert_called_once_with("tv", "all", "2025", "hot", 20, app.request_text)
                with self.assertRaises(HTTPError) as raised:
                    urlopen(root + "?start=25")
                self.assertEqual(raised.exception.code, 400)
                raised.exception.close()
                self.assertEqual(browse.call_count, 1)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_top_lists_and_top250_use_their_own_page_sizes(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            root = f"http://127.0.0.1:{server.server_port}/api/douban/list"
            with patch.object(app.DOUBAN_LISTS, "fetch", return_value={"items": []}) as fetch:
                with urlopen(root + "?kind=nature&start=20") as response:
                    self.assertEqual(response.status, 200)
                fetch.assert_called_once_with("nature", 20, app.request_text)
                with self.assertRaises(HTTPError) as raised:
                    urlopen(root + "?kind=nature&start=25")
                self.assertEqual(raised.exception.code, 400)
                raised.exception.close()
                with urlopen(root + "?kind=top250&start=25") as response:
                    self.assertEqual(response.status, 200)
                self.assertEqual(fetch.call_count, 2)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_movie_parser_exposes_rating_to_search_results(self):
        html = '<input name="list[]" value="ftp://example/movie.mp4"><p>豆瓣评分 8.2</p>'
        item = app.parse_80sgod_detail(html, "https://www.80sgod.com/movie/1", "影片")[0]
        self.assertEqual(item["douban_rating"], 8.2)
        self.assertEqual(app.ResultRegistry().register(item)["douban_rating"], 8.2)


if __name__ == "__main__":
    unittest.main()
