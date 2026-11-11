"""Verified movie search sites and bounded, same-site HTML adapters."""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from html.parser import HTMLParser
from urllib.parse import urlencode, urljoin, urlparse
from urllib.request import Request, HTTPRedirectHandler, build_opener
from media_search import category_type, infer_media_type


@dataclass(frozen=True)
class Source:
    id: str
    name: str
    features: str
    base: str = ""
    encoding: str = "gb18030"
    reason: str = ""


SOURCES = (
    Source("ygdy", "阳光电影", "电影、电视剧原站在线观看", "https://huolot.com", "utf-8"),
    Source("piaohua", "飘花电影网", "电影、电视剧资源", "https://www.piaohua.com", "utf-8"),
    Source("dygang", "电影港", "电影、电视剧、纪录片", "https://www.dygang.net"),
    Source("ys66", "66影视", "MP4 影视资源", "https://www.66ys.co"),
    Source("yyets", "人人影视", "欧美剧、日韩剧、字幕资源", reason="旧网址证书不匹配，待核实新网址"),
    Source("pianyuan", "片源网", "电影、电视剧资源", reason="旧网址跳转至其他站点，待核实新网址"),
    Source("uump4", "悠悠MP4", "MP4 影视资源", reason="旧网址访问超时，待核实新网址"),
    Source("btbtt", "BT之家", "BT 资源分享社区", reason="旧网址已变为跳转页，待核实新网址"),
    Source("m1080", "M1080", "高清电影资源", reason="旧网址内容已变更，待核实新网址"),
    Source("4ksj", "4K世界", "4K、蓝光资源", reason="旧网址访问超时，待核实新网址"),
)


class SourceError(Exception):
    pass


@dataclass
class SearchOutcome:
    items: list = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def catalog(enabled: bool = True) -> list[dict]:
    return [
        {"id": s.id, "name": s.name, "features": s.features,
         "supported": bool(s.base), "enabled": bool(s.base) and enabled,
         "url": s.base, "status": ("已接入" if enabled else "已停用") if s.base else "网址待更新",
         "download_supported": bool(s.base) and s.id != "ygdy",
         "reason": s.reason}
        for s in SOURCES
    ]


def same_site(url: str, base: str) -> bool:
    parsed, origin = urlparse(url), urlparse(base)
    return (
        parsed.scheme in {"http", "https"} and parsed.hostname == origin.hostname
        and parsed.port in {None, 80, 443} and not parsed.username
    )


class SiteRedirects(HTTPRedirectHandler):
    def __init__(self, base: str):
        self.base = base

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not same_site(newurl, self.base):
            raise SourceError("搜索站点跳转到其他域名，已停止请求")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_html(source: Source, url: str, *, form=None, timeout=6) -> str:
    if not same_site(url, source.base):
        raise SourceError("无效的来源地址")
    body = urlencode(form, encoding=source.encoding).encode("ascii") if form is not None else None
    headers = {"User-Agent": "Mozilla/5.0", "Referer": source.base + "/"}
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    try:
        with build_opener(SiteRedirects(source.base)).open(
            Request(url, data=body, headers=headers), timeout=timeout
        ) as response:
            data = response.read(2 * 1024 * 1024 + 1)
            if len(data) > 2 * 1024 * 1024:
                raise SourceError("站点页面过大")
            return data.decode(source.encoding, errors="replace")
    except SourceError:
        raise
    except Exception as exc:
        raise SourceError(f"站点暂时无法访问 ({type(exc).__name__})") from exc


class SearchLinks(HTMLParser):
    def __init__(self, source: Source):
        super().__init__()
        self.source = source
        self.results = []
        self.seen = set()
        self.anchor = None

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "a":
            self.anchor = [urljoin(self.source.base, values.get("href") or ""), []]
        elif tag == "img" and self.anchor and values.get("alt"):
            self.anchor[1].append(values["alt"])

    def handle_data(self, data):
        if self.anchor:
            self.anchor[1].append(data)

    def handle_endtag(self, tag):
        if tag != "a" or not self.anchor:
            return
        url, parts = self.anchor
        self.anchor = None
        title = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", "".join(parts))).strip()
        path = urlparse(url).path
        pattern = (
            r"^/html/[^/]+/\d{4}/\d{4}/\d+\.html$"
            if self.source.id == "piaohua"
            else r"^/[a-zA-Z0-9_-]+/\d{8}/\d+\.html?$"
        )
        if (title and title not in {"点击下载", "下载", "详情", "查看详情"}
                and same_site(url, self.source.base) and re.fullmatch(pattern, path)):
            canonical = urljoin(self.source.base, path)
            if canonical not in self.seen:
                self.seen.add(canonical)
                self.results.append({"title": title[:160], "detail_url": canonical})


def parse_search(source: Source, html: str) -> list[dict]:
    parser = SearchLinks(source)
    parser.feed(html)
    return parser.results


class YangguangSearchParser(HTMLParser):
    """Read search cards only, excluding recommendations and navigation."""
    def __init__(self, source):
        super().__init__()
        self.source = source
        self.depth = 0
        self.items = []
        self.seen = set()
        self.card = None
        self.card_text = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "ul":
            if self.depth:
                self.depth += 1
            elif "stui-vodlist__media" in (values.get("class") or "").split():
                self.depth = 1
        if tag == "li" and self.depth:
            self.card = None
            self.card_text = []
        if tag != "a" or not self.depth or "v-thumb" not in (values.get("class") or "").split():
            return
        url = urljoin(self.source.base, values.get("href") or "")
        title = (values.get("title") or "").strip()
        if (not title or not same_site(url, self.source.base)
                or not re.fullmatch(r"/voddetail/\d+\.html", urlparse(url).path)
                or url in self.seen):
            return
        self.seen.add(url)
        self.items.append({
            "source": self.source.name, "source_id": "ygdy:" + urlparse(url).path,
            "title": title[:160], "detail_url": url, "resource_type": "online",
            "description": "该来源提供原站在线观看，暂无直接下载地址。",
            "thumbnail_url": values.get("data-original") or "",
            "width": 0, "height": 0,
        })
        self.card = self.items[-1]

    def handle_data(self, data):
        if self.depth:
            self.card_text.append(data)

    def handle_endtag(self, tag):
        if tag == "li" and self.card is not None:
            text = " ".join(self.card_text)
            category = re.search(r"类型\s*[:：]\s*(.*?)(?=地区\s*[:：]|年份\s*[:：]|$)", text)
            self.card["media_type"] = category_type(category[1]) if category else ""
            self.card = None
            self.card_text = []
        if tag == "ul" and self.depth:
            self.depth -= 1


def search_source(source: Source, query: str, parse_detail, *, fetch=fetch_html,
                  limit=8, budget=18, clock=time.monotonic, media_type="all") -> SearchOutcome:
    if not source.base:
        raise SourceError(source.reason)
    deadline = clock() + budget
    if source.id == "ygdy":
        parameters = {"wd": query}
        if media_type in {"movie", "tv"}:
            parameters["tid"] = "1" if media_type == "movie" else "2"
        url = source.base + "/vodsearch/-------------.html?" + urlencode(parameters)
        html = fetch(source, url, timeout=min(6, budget))
        if "阳光" not in html or "相关搜索结果" not in html:
            raise SourceError("搜索页面不可用或需要站点验证")
        parser = YangguangSearchParser(source)
        parser.feed(html)
        items = parser.items
        if media_type != "all":
            items = [item for item in items if item.get("media_type") == media_type]
        return SearchOutcome(items[:limit])
    if source.id == "piaohua":
        url = source.base + "/plus/search.php?" + urlencode(
            {"kwtype": "0", "keyword": query, "searchtype": "影视搜索"})
        html = fetch(source, url, timeout=min(6, budget))
    else:
        html = fetch(source, source.base + "/e/search/index.php", form={
            "tempid": "1", "tbname": "article", "show": "title,smalltext",
            "keyboard": query, "Submit" if source.id == "dygang" else "submit": "搜索",
        }, timeout=min(6, budget))
    candidates = parse_search(source, html)
    if media_type != "all":
        candidates = [
            item for item in candidates
            if infer_media_type(item["detail_url"], item["title"]) in {"", media_type}
        ]
    # Recognize a real search page, including a valid zero-results response.
    markers = ("飘花",) if source.id == "piaohua" else (
        ("电影港",) if source.id == "dygang" else ("66影视", "66电影", "66影"))
    if not any(marker in html for marker in markers):
        raise SourceError("页面与来源名称不匹配，暂不使用该结果")
    outcome = SearchOutcome()
    failed = 0
    for candidate in candidates[:3]:
        remaining = deadline - clock()
        if remaining <= 0:
            outcome.warnings.append("部分详情页未读取：已达到搜索时间上限")
            break
        try:
            detail = fetch(source, candidate["detail_url"], timeout=min(6, remaining))
            items = parse_detail(detail, candidate["detail_url"], candidate["title"])
            for item in items:
                item["source"] = source.name
                item["source_id"] = source.id + ":" + item["source_id"]
            outcome.items.extend(items)
        except SourceError:
            failed += 1
    if failed:
        outcome.warnings.append(f"{failed} 个详情页暂时无法读取")
    if candidates and failed == min(3, len(candidates)):
        raise SourceError("搜索可用，但详情页暂时无法读取")
    outcome.items.sort(key=lambda i: not (
        i["download_url"].lower().startswith("magnet:")
        or urlparse(i["download_url"]).path.lower().endswith(".torrent")))
    outcome.items = outcome.items[:limit]
    return outcome
