import unittest
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.request import urlopen

import app


ROOT = Path(__file__).resolve().parents[1]


class CatalogTest(unittest.TestCase):
    def test_homepage_keeps_lists_and_uses_shared_navigation(self):
        html = (ROOT / "static" / "index.html").read_text(encoding="utf-8")
        self.assertIn('data-douban-list="full"', html)
        for kind in ("finance", "nature", "biography", "top250", "tv", "comedy", "anime"):
            self.assertIn(f'data-douban-kind="{kind}"', html)
        self.assertNotIn('data-douban-kind="popular"', html)
        self.assertNotIn("年度热门电视剧", html)
        shell = (ROOT / "static" / "site.js").read_text(encoding="utf-8")
        self.assertIn('"/hot.html", "最近热播"', shell)
        self.assertIn('"/catalog.html", "分类"', shell)
        self.assertIn("NAS 下载", shell)
        catalog = (ROOT / "static" / "catalog.html").read_text(encoding="utf-8")
        self.assertNotIn("年度热门电视剧", catalog)
        for media_type in ("movie", "tv", "documentary", "animation"):
            self.assertIn(f'data-browse-type="{media_type}"', catalog)
        for field in ("browseGenre", "browseYear", "browseSort"):
            self.assertIn(f'id="{field}"', catalog)

    def test_homepage_removes_secondary_catalog_entry(self):
        html = (ROOT / "static" / "index.html").read_text(encoding="utf-8")

        self.assertNotIn("可下载内容", html)
        self.assertNotIn("浏览全部", html)
        self.assertIn("影视精选", html)
        self.assertNotIn("动画精选", html)
        self.assertNotIn("轻松喜剧", html)
        self.assertNotIn("The Great Train Robbery 1903", html)

    def test_catalog_removes_static_content_groups(self):
        html = (ROOT / "static" / "catalog.html").read_text(encoding="utf-8")

        self.assertEqual(html.count('href="/?query='), 0)
        self.assertNotIn("NASA", html)
        self.assertNotIn("wildlife%20animals%20nature", html)
        self.assertNotIn("经典影片", html)
        self.assertNotIn("公共领域与开放影视档案中的经典作品", html)
        self.assertNotIn("航天与科学", html)
        self.assertNotIn("自然与城市素材", html)
        self.assertNotIn("适合剪辑、演示和背景画面的开放许可视频", html)

    def test_catalog_supports_search_and_combined_filters(self):
        html = (ROOT / "static" / "catalog.html").read_text(encoding="utf-8")

        self.assertNotIn('action="/#search-results"', html)
        self.assertIn('id="siteHeader"', html)
        search = (ROOT / "static" / "search.html").read_text(encoding="utf-8")
        self.assertIn('id="searchForm"', search)
        self.assertNotIn("data-search-type", search)
        self.assertNotIn("Wikimedia Commons", html)
        self.assertIn("支持组合筛选", html)
        self.assertRegex(html, r'src="/catalog\.js\?v=\d+"')

    def test_static_asset_versions_and_mobile_overflow_guards(self):
        index = (ROOT / "static" / "index.html").read_text(encoding="utf-8")
        app_script = (ROOT / "static" / "app.js").read_text(encoding="utf-8")
        styles = (ROOT / "static" / "styles.css").read_text(encoding="utf-8")

        self.assertRegex(index, r'src="/app\.js\?v=\d+"')
        self.assertIn('button.textContent = "已发送迅雷"', app_script)
        self.assertNotIn("等待迅雷确认", app_script)
        self.assertRegex(index, r'href="/styles\.css\?v=\d+"')
        self.assertNotIn("原站地址", app_script)
        self.assertIn(".result-source-link", styles)
        self.assertNotIn("rightsConfirmed", index)
        self.assertNotIn("/api/tasks", app_script)
        self.assertNotIn("添加到 aria2", app_script)
        self.assertNotIn("aria2 运行中", app_script)
        self.assertNotIn('id="taskList"', index)
        shell = (ROOT / "static" / "site.js").read_text(encoding="utf-8")
        self.assertIn('id="xunleiSubmit" type="submit"', shell)
        self.assertIn('id="mountNasButton"', shell)
        self.assertIn('"/api/download"', app_script)
        self.assertIn('"/api/download/copy"', app_script)
        self.assertIn('"/api/download/xunlei"', app_script)
        self.assertIn("overflow-wrap: anywhere;", styles)
        self.assertIn(".movie-search-row", styles)

    def test_html_responses_do_not_cache_old_page_content(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for path in ("/", "/catalog.html?v=9", "/hot.html", "/search.html", "/player.html"):
                with urlopen(f"http://127.0.0.1:{server.server_port}{path}") as response:
                    self.assertEqual(response.headers.get("Cache-Control"), "no-store")
                    if path.startswith("/catalog"):
                        self.assertIn("熊猫视频 · 分类", response.read().decode())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
