"""Public Douban lists and explicitly attributed source-page ratings."""

import json
import re
import threading
import time
from datetime import date
from html import unescape
from html.parser import HTMLParser
from urllib.parse import urlencode, urlparse


LIST_LABELS = {
    "popular": "热门电影", "top250": "Top 250", "tv": "Top 剧集",
    "comedy": "Top 喜剧片", "anime": "Top 动画",
    "finance": "Top 金融电影", "nature": "Top 自然纪录片",
    "biography": "Top 人物传记",
    "war": "Top 战争片",
    "hollywood": "Top 好莱坞大片",
    "annual_tv": "年度热门电视剧",
}
HEADERS = {"User-Agent": "Mozilla/5.0", "Referer": "https://movie.douban.com/"}
PAGE_SIZE = 25
ANNUAL_TV_PAGE_SIZE = 20
BROWSE_TYPES = {
    "movie": "电影", "tv": "电视剧", "documentary": "纪录片", "animation": "动画",
}
BROWSE_GENRES = {
    "all": "全部题材", "drama": "剧情", "comedy": "喜剧", "action": "动作",
    "romance": "爱情", "scifi": "科幻", "mystery": "悬疑",
    "finance": "金融", "nature": "自然", "biography": "传记",
    "war": "战争",
    "hollywood": "好莱坞大片",
}
BROWSE_SORTS = {"hot": "U", "rating": "S", "latest": "R"}
TOP_LIST_FILTERS = {
    "tv": ("tv", "all"),
    "finance": ("movie", "finance"),
    "nature": ("documentary", "nature"),
    "biography": ("movie", "biography"),
    "war": ("movie", "war"),
    "hollywood": ("movie", "hollywood"),
    "comedy": ("movie", "comedy"),
    "anime": ("animation", "all"),
}


def list_page_size(kind):
    return 20 if kind in TOP_LIST_FILTERS or kind == "annual_tv" else PAGE_SIZE


def validate_browse(media_type, genre, year, sort, start):
    if (
        media_type not in BROWSE_TYPES or genre not in BROWSE_GENRES
        or sort not in BROWSE_SORTS or start < 0 or start > 10000 or start % 20
    ):
        raise ValueError("无效的分类筛选条件")
    if year != "all" and (
        not re.fullmatch(r"\d{4}", year)
        or not 1900 <= int(year) <= date.today().year
    ):
        raise ValueError("无效的年份")


def valid_rating(value):
    try:
        rating = float(value)
        return rating if 0 < rating <= 10 else None
    except (TypeError, ValueError):
        return None


def subject_url(value):
    parsed = urlparse(str(value or ""))
    if parsed.hostname == "movie.douban.com" and re.fullmatch(
        r"/subject/\d+/?", parsed.path
    ):
        return "https://movie.douban.com" + parsed.path.rstrip("/") + "/"
    return ""


def source_rating(html, title):
    text = re.sub(r"\s+", " ", unescape(re.sub(r"<[^>]*>", " ", html)))
    match = re.search(r"豆\s*瓣\s*评\s*分\s*[:：]?\s*([0-9]+(?:\.[0-9]+)?)", text)
    rating = valid_rating(match.group(1)) if match else None
    link = re.search(r"https?://movie\.douban\.com/subject/\d+/?", html)
    film = re.search(r"《([^》]+)》", title)
    keyword = film.group(1) if film else re.sub(r"^\[[^]]+\]\s*", "", title)
    return {
        "douban_rating": rating,
        "douban_rating_source": "来源站标注" if rating else "",
        "douban_url": subject_url(link.group(0)) if link else (
            "https://www.douban.com/search?" + urlencode({"q": keyword, "cat": "1002"})
        ),
    }


class Top250Parser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.items = []
        self.depth = 0
        self.current = {}
        self.text = []
        self.capture = ""

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "div":
            if self.depth:
                self.depth += 1
            elif "item" in (attrs.get("class") or "").split():
                self.depth = 1
                self.current = {}
                self.text = []
        if not self.depth:
            return
        if tag == "img" and not self.current.get("title"):
            self.current["title"] = attrs.get("alt", "").strip()
            cover = attrs.get("src", "")
            if urlparse(cover).scheme in {"http", "https"}:
                self.current["cover"] = cover
        elif tag == "a" and subject_url(attrs.get("href")):
            self.current["url"] = subject_url(attrs["href"])
        elif tag == "em":
            self.capture = "rank"
        elif tag == "span" and "rating_num" in (attrs.get("class") or "").split():
            self.capture = "rating"

    def handle_data(self, data):
        if self.depth:
            self.text.append(data)
            if self.capture:
                self.current[self.capture] = self.current.get(self.capture, "") + data

    def handle_endtag(self, tag):
        if tag in {"em", "span"}:
            self.capture = ""
        if tag == "div" and self.depth:
            self.depth -= 1
            if not self.depth and self.current.get("title") and self.current.get("url"):
                year = re.search(r"\b((?:18|19|20)\d{2})\s*/", " ".join(self.text))
                rank = self.current.get("rank", "").strip()
                self.items.append({
                    "title": self.current["title"],
                    "url": self.current["url"],
                    "rating": valid_rating(self.current.get("rating")),
                    "rank": int(rank) if rank.isdigit() else None,
                    "year": year.group(1) if year else "",
                    **({"cover": self.current["cover"]} if self.current.get("cover") else {}),
                })


class DoubanLists:
    def __init__(self):
        self.cache = {}
        self.lock = threading.Lock()

    def browse(self, media_type, genre, year, sort, start, request_text):
        validate_browse(media_type, genre, year, sort, start)
        key = ("browse", media_type, genre, year, sort, start)
        now = time.time()
        with self.lock:
            cached = self.cache.get(key)
        if cached and now - cached["fetched_at"] < 3600:
            return {**cached, "cached": True, "stale": False}
        tags = [BROWSE_TYPES[media_type]]
        if genre != "all":
            tags.extend(["美国", "大片"] if genre == "hollywood" else [BROWSE_GENRES[genre]])
        parameters = {
            "sort": BROWSE_SORTS[sort], "range": "0,10",
            "tags": ",".join(tags), "start": start,
        }
        if year != "all":
            parameters["year_range"] = f"{year},{year}"
        try:
            url = "https://movie.douban.com/j/new_search_subjects?" + urlencode(parameters)
            data = json.loads(request_text(url, headers=HEADERS, timeout=12))
            if not isinstance(data.get("data"), list):
                raise ValueError("豆瓣返回了无效的列表")
            items = [
                {
                    "title": str(item.get("title") or "").strip(),
                    "url": subject_url(item.get("url")),
                    "rating": valid_rating(item.get("rate")),
                    "cover": str(item.get("cover") or "") if urlparse(
                        str(item.get("cover") or "")
                    ).scheme in {"http", "https"} else "",
                    "casts": [str(name) for name in (item.get("casts") or [])[:3]],
                }
                for item in data["data"]
                if isinstance(item, dict) and item.get("title") and subject_url(item.get("url"))
            ]
            items = [item for item in items if item["rating"] is not None]
            result = {
                "items": items, "start": start, "page_size": 20,
                "has_more": len(data["data"]) == 20,
                "filters": {
                    "type": media_type, "genre": genre, "year": year, "sort": sort,
                },
                "label": " · ".join(tags + ([year] if year != "all" else [])),
                "fetched_at": now, "cached": False, "stale": False,
            }
        except Exception:
            if cached:
                return {**cached, "cached": True, "stale": True}
            raise
        with self.lock:
            self.cache[key] = result
        return result

    def fetch(self, kind, start, request_text):
        if kind in TOP_LIST_FILTERS:
            media_type, genre = TOP_LIST_FILTERS[kind]
            result = self.browse(media_type, genre, "all", "rating", start, request_text)
            return {
                **result, "kind": kind, "label": LIST_LABELS[kind],
                "source_url": "https://movie.douban.com/explore",
            }
        year = date.today().year if kind == "annual_tv" else None
        page_size = list_page_size(kind)
        key = (kind, start, year)
        now = time.time()
        with self.lock:
            cached = self.cache.get(key)
        if cached and now - cached["fetched_at"] < 3600:
            return {**cached, "cached": True, "stale": False}
        try:
            if kind == "top250":
                url = "https://movie.douban.com/top250?" + urlencode({"start": start})
                parser = Top250Parser()
                parser.feed(request_text(url, headers=HEADERS, timeout=12))
                items = parser.items
            else:
                if kind == "annual_tv":
                    url = "https://movie.douban.com/j/new_search_subjects?" + urlencode({
                        "sort": "U", "range": "0,10", "tags": "电视剧",
                        "year_range": f"{year},{year}", "start": start,
                    })
                else:
                    url = "https://movie.douban.com/j/search_subjects?" + urlencode({
                        "type": "tv" if kind in {"tv", "anime"} else "movie",
                        "tag": {
                            "comedy": "喜剧", "anime": "日本动画",
                            "finance": "金融", "nature": "自然",
                        }.get(kind, "热门"),
                        "sort": "recommend",
                        "page_limit": PAGE_SIZE, "page_start": start,
                    })
                data = json.loads(request_text(url, headers=HEADERS, timeout=12))
                items = [
                    {
                        "title": str(item.get("title") or "").strip(),
                        "rating": valid_rating(item.get("rate")),
                        "url": subject_url(item.get("url")),
                        "year": str(year) if year else "",
                        "rank": None,
                    }
                    for item in data.get("data" if kind == "annual_tv" else "subjects", [])
                    if isinstance(item, dict) and item.get("title")
                    and subject_url(item.get("url"))
                ]
            if not items and start == 0:
                raise ValueError("empty list")
            upstream_count = len(items)
            items = [item for item in items if item["rating"] is not None]
            result = {
                "kind": kind,
                "label": f"{year} 年度热门电视剧" if year else LIST_LABELS[kind],
                "items": items, "start": start, "page_size": page_size,
                "has_more": upstream_count == page_size and (kind != "top250" or start < 225),
                "source_url": "https://movie.douban.com/top250" if kind == "top250"
                else "https://movie.douban.com/tv/" if kind in {"tv", "anime", "annual_tv"}
                else "https://movie.douban.com/explore",
                "fetched_at": now, "cached": False, "stale": False,
            }
        except Exception:
            if cached:
                return {**cached, "cached": True, "stale": True}
            raise
        with self.lock:
            self.cache[key] = result
        return result
