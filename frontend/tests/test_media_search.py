import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import app
import movie_sources
from media_search import category_type, infer_media_type, search_keyword


class MediaSearchTests(unittest.TestCase):
    def test_partial_keywords_normalize_quotes_chinese_spacing_and_full_width(self):
        self.assertEqual(search_keyword(" 《星 际》 "), "星际")
        self.assertEqual(search_keyword(" Ｓｔａｒ Wars "), "Star Wars")
        self.assertEqual(search_keyword("庆余"), "庆余")

    def test_types_use_source_paths_and_explicit_episode_metadata(self):
        self.assertEqual(infer_media_type("https://www.80sgod.com/movie/123"), "movie")
        self.assertEqual(infer_media_type("https://www.80sgod.com/ju/123"), "tv")
        self.assertEqual(infer_media_type(title="2023年电视剧《三体》"), "tv")
        self.assertEqual(infer_media_type(title="2024年国产剧《庆余年》"), "tv")
        self.assertEqual(infer_media_type(title="科幻片《星际穿越》"), "movie")
        self.assertEqual(infer_media_type(html="<p>◎集　数：30</p>"), "tv")
        self.assertEqual(infer_media_type(title="未知影片", html="<p>剧情：演员拍摄电视剧</p>"), "")

    def test_online_categories_do_not_treat_countries_or_animation_as_movie_titles(self):
        for label in ("美国", "国产", "香港剧", "韩剧"):
            self.assertEqual(category_type(label), "tv")
        for label in ("科幻", "剧情片", "动画片"):
            self.assertEqual(category_type(label), "movie")
        for label in ("国产动漫", "综艺", "未知"):
            self.assertEqual(category_type(label), "")

    def test_online_card_metadata_stays_with_the_correct_result(self):
        source = next(s for s in movie_sources.SOURCES if s.id == "ygdy")
        html = '<ul class="stui-vodlist__media">'
        for index, (title, category) in enumerate([
            ("星际穿越", "科幻"), ("三体真人版", "国产"), ("三体动画版", "国产动漫"),
        ], 1):
            html += f'''<li><a class="v-thumb" href="/voddetail/{index}.html" title="{title}"></a>
                <p><span>类型：</span>{category}<span>地区：</span>大陆<span>年份：</span>2023</p></li>'''
        html += '</ul><a class="v-thumb" href="/voddetail/99.html" title="推荐广告"></a>'
        parser = movie_sources.YangguangSearchParser(source)
        parser.feed(html)
        self.assertEqual([item["media_type"] for item in parser.items], ["movie", "tv", ""])
        self.assertEqual(len(parser.items), 3)

    def test_partial_title_search_filters_type_without_exact_title_requirement(self):
        config = SimpleNamespace(eightysgod_enabled=True, dytt_enabled=False,
                                 movie_sources_enabled=False, internet_archive_enabled=True)
        movies = [
            {"title": "星际穿越", "detail_url": "https://www.80sgod.com/movie/1", "source": "80s电影网"},
            {"title": "星际迷航：发现号", "detail_url": "https://www.80sgod.com/ju/2", "source": "80s电影网"},
            {"title": "星际未知", "detail_url": "https://example.com/item", "source": "80s电影网"},
        ]
        with (patch.object(app, "CONFIG", config),
              patch.object(app, "search_80sgod", return_value=movies) as search,
              patch.object(app, "understand_query") as understand,
              patch.object(app, "search_pexels") as stock,
              patch.object(app, "search_internet_archive") as archive):
            result = app.search_all("《星 际》", "movie")
            search.assert_called_once_with("星际", media_type="movie")
            self.assertEqual([item["title"] for item in result["results"]], ["星际穿越"])
            self.assertEqual(result["sources"][0]["count"], 1)
            self.assertEqual(result["intent"]["match"], "partial")
            self.assertEqual(result["intent"]["media_type"], "movie")
            understand.assert_not_called()
            stock.assert_not_called()
            archive.assert_not_called()
            result = app.search_all("星际", "tv")
            self.assertEqual([item["title"] for item in result["results"]], ["星际迷航：发现号"])

    def test_online_source_filters_category_before_applying_result_limit(self):
        source = next(s for s in movie_sources.SOURCES if s.id == "ygdy")
        cards = '<title>阳光相关搜索结果</title><ul class="stui-vodlist__media">'
        for index, category in enumerate(["美国"] * 8 + ["科幻"] * 2, 1):
            cards += f'''<li><a class="v-thumb" href="/voddetail/{index}.html" title="星际 {index}"></a>
                <p>类型：{category} 地区：美国 年份：2020</p></li>'''
        cards += "</ul>"
        fetch = Mock(return_value=cards)
        outcome = movie_sources.search_source(source, "星际", app.parse_dytt_detail,
                                             fetch=fetch, media_type="movie")
        self.assertEqual([item["title"] for item in outcome.items], ["星际 9", "星际 10"])
        self.assertIn("tid=1", fetch.call_args.args[1])

    def test_all_mode_preserves_unclassified_results_and_stock_sources(self):
        config = SimpleNamespace(eightysgod_enabled=True, dytt_enabled=False,
                                 movie_sources_enabled=False, internet_archive_enabled=False)
        with (patch.object(app, "CONFIG", config),
              patch.object(app, "understand_query", return_value={"keywords": "star"}),
              patch.object(app, "search_80sgod", return_value=[{"title": "星际未知", "source": "80s电影网"}]),
              patch.object(app, "search_pexels", return_value=[]) as stock,
              patch.object(app, "search_pixabay", return_value=[])):
            result = app.search_all("星际")
            self.assertEqual(len(result["results"]), 1)
            stock.assert_called_once_with("star")

    def test_no_enabled_film_sources_is_a_valid_empty_result(self):
        config = SimpleNamespace(eightysgod_enabled=False, dytt_enabled=False,
                                 movie_sources_enabled=False, internet_archive_enabled=False)
        with patch.object(app, "CONFIG", config):
            self.assertEqual(app.search_all("星际", "movie")["results"], [])

    def test_http_search_accepts_one_character_and_rejects_invalid_types(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        def post(payload):
            return urlopen(Request(f"http://127.0.0.1:{server.server_port}/api/search",
                data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}))
        try:
            with patch.object(app, "search_all", return_value={"results": []}) as search:
                with post({"query": "星", "media_type": "movie"}) as response:
                    self.assertEqual(response.status, 200)
                search.assert_called_once_with("星", "movie")
                for payload in ({"query": "星", "media_type": "invalid"},
                                {"query": "星", "media_type": []}, {"query": "《》"}):
                    with self.assertRaises(HTTPError) as error:
                        post(payload)
                    self.assertEqual(error.exception.code, 400)
                self.assertEqual(search.call_count, 1)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
