import unittest
import json
import threading
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
from unittest.mock import Mock, patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError

import app
import movie_sources as m


DETAIL = """<meta property="og:title" content="测试影片">
<a href="https://files.example/movie.mp4">MP4</a>
<a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567">BT</a>"""


def source(source_id):
    return next(s for s in m.SOURCES if s.id == source_id)


def search_page(s):
    path = "/html/dongzuo/2024/0114/52151.html" if s.id == "piaohua" else "/ys/20260106/58785.htm"
    return f'<title>{s.name}搜索</title><a href="{path}"><span>测试影片</span></a>'


class MovieSourceTests(unittest.TestCase):
    def test_catalog_reports_only_verified_sources_as_supported(self):
        catalog = m.catalog()
        self.assertEqual(len(catalog), 10)
        self.assertEqual({s["id"] for s in catalog if s["enabled"]},
                         {"ygdy", "piaohua", "dygang", "ys66"})
        self.assertTrue(all(not s["url"] and s["reason"] for s in catalog if not s["supported"]))
        self.assertFalse(any(s["enabled"] for s in m.catalog(False)))

    def test_search_parser_handles_nested_text_dedup_and_untrusted_links(self):
        s = source("piaohua")
        path = "/html/dongzuo/2024/0114/52151.html"
        html = (f'<a href="{path}"><b>测试</b>影片</a>'
                f'<a href="{path}?x=1"><img alt="测试影片"></a>'
                f'<a href="https://other.example{path}">广告</a>'
                '<a href="/list/1.html">导航</a>')
        self.assertEqual(m.parse_search(s, html), [
            {"title": "测试影片", "detail_url": s.base + path}])

    def test_empire_detail_paths_support_htm_and_html(self):
        s = source("ys66")
        html = ('<a href="/bd/20130804/25209.htm">影片一</a>'
                '<a href="/kehuanpian/20261108/57237.html">影片二</a>')
        self.assertEqual(len(m.parse_search(s, html)), 2)

    def test_piaohua_uses_utf8_get_and_real_download_links_bt_first(self):
        s = source("piaohua")
        fetch = Mock(side_effect=[search_page(s), DETAIL])
        outcome = m.search_source(s, "复仇者", app.parse_dytt_detail, fetch=fetch)
        request = fetch.call_args_list[0]
        self.assertIn("keyword=%E5%A4%8D%E4%BB%87%E8%80%85", request.args[1])
        self.assertNotIn("form", request.kwargs)
        self.assertEqual(s.encoding, "utf-8")
        self.assertEqual(len(outcome.items), 2)
        self.assertTrue(outcome.items[0]["download_url"].startswith("magnet:"))
        self.assertEqual(outcome.items[0]["source"], "飘花电影网")

    def test_empire_forms_use_chinese_keywords_and_correct_submit_case(self):
        for source_id in ("dygang", "ys66"):
            s = source(source_id)
            fetch = Mock(side_effect=[search_page(s), DETAIL])
            m.search_source(s, "复仇者", app.parse_dytt_detail, fetch=fetch)
            form = fetch.call_args_list[0].kwargs["form"]
            self.assertEqual(form["keyboard"], "复仇者")
            self.assertEqual(form["tbname"], "article")
            self.assertIn("Submit" if source_id == "dygang" else "submit", form)
            self.assertEqual(s.encoding, "gb18030")

    def test_collection_page_without_download_links_is_not_a_result(self):
        s = source("ys66")
        fetch = Mock(side_effect=[search_page(s), '<a href="/movie.html">跳转影片</a>'])
        self.assertEqual(m.search_source(s, "影片", app.parse_dytt_detail, fetch=fetch).items, [])

    def test_wrong_site_identity_is_rejected(self):
        s = source("dygang")
        with self.assertRaises(m.SourceError):
            m.search_source(s, "影片", app.parse_dytt_detail,
                            fetch=Mock(return_value="<title>广告跳转页</title>"))

    def test_redirects_and_fetch_reject_external_hosts(self):
        s = source("ys66")
        self.assertTrue(m.same_site(s.base + "/search.html", s.base))
        self.assertFalse(m.same_site("https://www.66ys.co.other.example/", s.base))
        with self.assertRaises(m.SourceError):
            m.SiteRedirects(s.base).redirect_request(None, None, 302, "", {},
                                                     "https://other.example/")
        with self.assertRaises(m.SourceError):
            m.fetch_html(s, "https://other.example/")

    def test_partial_detail_failure_retains_other_results(self):
        s = source("dygang")
        html = search_page(s) + '<a href="/ys/20260106/58786.htm">第二部</a>'
        fetch = Mock(side_effect=[html, m.SourceError("timeout"), DETAIL])
        outcome = m.search_source(s, "影片", app.parse_dytt_detail, fetch=fetch)
        self.assertEqual(len(outcome.items), 2)
        self.assertEqual(len(outcome.warnings), 1)

    def test_time_budget_prevents_further_detail_requests(self):
        s = source("dygang")
        fetch = Mock(return_value=search_page(s))
        clock = Mock(side_effect=[0, 20])
        outcome = m.search_source(s, "影片", app.parse_dytt_detail,
                                  fetch=fetch, budget=18, clock=clock)
        self.assertEqual(fetch.call_count, 1)
        self.assertTrue(outcome.warnings)

    def test_failed_source_does_not_hide_successful_source(self):
        config = SimpleNamespace(eightysgod_enabled=False, dytt_enabled=False,
                                 movie_sources_enabled=True, internet_archive_enabled=False)
        good = m.SearchOutcome([{
            "source": "电影港", "title": "测试影片",
            "download_url": "https://files.example/movie.mp4",
        }])
        def search(s, *args):
            if s.id == "dygang":
                return good
            raise m.SourceError("站点暂时无法访问")
        with (patch.object(app, "CONFIG", config),
              patch.object(app, "understand_query", return_value={"keywords": "影片"}),
              patch.object(app, "search_pexels", return_value=[]),
              patch.object(app, "search_pixabay", return_value=[]),
              patch.object(app, "search_source", side_effect=search)):
            response = app.search_all("影片")
        self.assertEqual(len(response["results"]), 1)
        self.assertNotIn("download_url", response["results"][0])
        self.assertEqual(len(response["errors"]), 3)
        states = {s["name"]: s["status"] for s in response["sources"]}
        self.assertEqual(states["电影港"], "ok")
        self.assertEqual(states["飘花电影网"], "error")

    def test_yangguang_search_returns_online_cards_without_recommendations(self):
        s = source("ygdy")
        html = """<title>阳光电影网</title>相关搜索结果
        <ul class="stui-vodlist__media">
        <li><a class="v-thumb" href="/voddetail/59266.html"
            title="星际穿越" data-original="https://feifan.51weizhang.cn/poster.jpg"></a>
            <a href="/voddetail/59266.html">星际穿越</a></li>
        <li><a class="v-thumb" href="https://other.example/voddetail/1.html" title="广告"></a></li>
        </ul><a class="v-thumb" href="/voddetail/99.html" title="推荐影片"></a>"""
        fetch = Mock(return_value=html)
        parse_detail = Mock()
        outcome = m.search_source(s, "星际穿越", parse_detail, fetch=fetch)
        self.assertEqual(len(outcome.items), 1)
        self.assertEqual(outcome.items[0]["resource_type"], "online")
        self.assertEqual(outcome.items[0]["detail_url"], "https://huolot.com/voddetail/59266.html")
        self.assertNotIn("download_url", outcome.items[0])
        self.assertIn("wd=%E6%98%9F%E9%99%85%E7%A9%BF%E8%B6%8A", fetch.call_args.args[1])
        parse_detail.assert_not_called()

    def test_yangguang_zero_results_is_valid_but_challenge_is_not(self):
        s = source("ygdy")
        result = m.search_source(s, "不存在", Mock(), fetch=Mock(
            return_value="<title>阳光电影网</title>相关搜索结果<ul></ul>"))
        self.assertEqual(result.items, [])
        with self.assertRaises(m.SourceError):
            m.search_source(s, "影片", Mock(), fetch=Mock(return_value="验证码"))
        catalog = next(i for i in m.catalog() if i["id"] == "ygdy")
        self.assertTrue(catalog["enabled"])
        self.assertFalse(catalog["download_supported"])

    def test_online_results_reject_download_and_copy_requests(self):
        registry = app.ResultRegistry()
        item = registry.register({"source": "阳光电影", "resource_type": "online",
                                  "title": "影片", "detail_url": "https://huolot.com/voddetail/1.html"})
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with (patch.object(app, "REGISTRY", registry),
                  patch.object(app, "submit_xunlei_download") as submit):
                for path in ("/api/download", "/api/download/copy"):
                    request = Request(f"http://127.0.0.1:{server.server_port}{path}",
                        data=json.dumps({"token": item["token"]}).encode(),
                        headers={"Content-Type": "application/json"})
                    with self.assertRaises(HTTPError) as error:
                        urlopen(request, timeout=3)
                    self.assertEqual(error.exception.code, 400)
                submit.assert_not_called()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
