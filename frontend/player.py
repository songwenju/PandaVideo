"""Resolve public online video metadata without executing upstream scripts."""
import base64
import json
import re
from html import unescape
from html.parser import HTMLParser
from urllib.parse import unquote, urljoin, urlparse

BASE = "https://huolot.com"
PAGE_PATH = re.compile(r"^/vod(?:detail/\d+|play/\d+-\d+-\d+)\.html$")
PLAY_PATH = re.compile(r"^/vodplay/(\d+)-(\d+)-(\d+)\.html$")


class PlayerError(ValueError):
    pass


def page_url(value):
    parsed = urlparse(str(value))
    if (parsed.scheme != "https" or parsed.netloc != "huolot.com"
            or parsed.query or parsed.fragment or not PAGE_PATH.fullmatch(parsed.path)):
        raise PlayerError("无效的播放页面")
    return BASE + parsed.path


def media_type(value):
    parsed = urlparse(str(value or ""))
    if parsed.scheme not in {"https", "http"} or not parsed.hostname or parsed.username:
        return ""
    path = parsed.path.lower()
    if path.endswith(".m3u8"):
        return "hls"
    if path.endswith((".mp4", ".webm", ".ogg", ".m4v", ".mov")):
        return "video"
    return ""


class EpisodeParser(HTMLParser):
    def __init__(self, movie_id):
        super().__init__(convert_charrefs=True)
        self.movie_id = movie_id
        self.episodes = {}
        self.current = None
        self.text = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            path = dict(attrs).get("href", "")
            match = PLAY_PATH.fullmatch(path)
            self.current = path if match and match[1] == self.movie_id else None
            self.text = []

    def handle_data(self, data):
        if self.current:
            self.text.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self.current:
            label = " ".join("".join(self.text).split())[:80]
            # Playlist labels replace generic "立即播放" links to the same URL.
            self.episodes[self.current] = label or "播放"
            self.current = None


def resolve_online(value, fetch, episode=""):
    original = page_url(value)
    movie_id = re.search(r"/vod(?:detail|play)/(\d+)", original)[1]
    detail = f"{BASE}/voddetail/{movie_id}.html"
    html = fetch(detail)
    metadata = unescape(re.sub(r"<[^>]+>", "", html))
    year_match = re.search(r"年份\s*[：:]\s*((?:18|19|20)\d{2})", metadata)
    parser = EpisodeParser(movie_id)
    parser.feed(html)
    episodes = [
        {"path": path, "label": label, "line": int(PLAY_PATH.fullmatch(path)[2])}
        for path, label in parser.episodes.items()
    ]
    if not episodes:
        raise PlayerError("原站暂无可播放剧集")
    if episode:
        selected = str(episode)
    elif PLAY_PATH.fullmatch(urlparse(original).path):
        selected = urlparse(original).path
    else:
        selected = episodes[0]["path"]
    if selected not in parser.episodes:
        raise PlayerError("该剧集不在当前影片的播放列表中")
    play_html = fetch(BASE + selected)
    match = re.search(r"\bvar\s+player_\w+\s*=\s*", play_html)
    if not match:
        raise PlayerError("原站未提供可读取的视频地址")
    try:
        config, _ = json.JSONDecoder().raw_decode(play_html[match.end():].lstrip())
        if not isinstance(config, dict):
            raise ValueError()
        if config.get("points") or config.get("trysee"):
            raise PlayerError("此资源需要在原站完成观看授权")
        url = str(config.get("url") or "")
        encrypt = int(config.get("encrypt", 0))
        if encrypt == 1:
            url = unquote(url)
        elif encrypt == 2:
            url = unquote(base64.b64decode(url, validate=True).decode("utf-8"))
        elif encrypt != 0:
            raise ValueError()
    except PlayerError:
        raise
    except (ValueError, TypeError, UnicodeError) as exc:
        raise PlayerError("原站播放信息格式异常") from exc
    kind = media_type(url)
    if not kind:
        raise PlayerError("此播放线路暂不支持内置播放，请使用原站观看")
    return {"url": url, "kind": kind, "episodes": episodes,
            "selected": selected, "page_url": BASE + selected,
            "source": "阳光电影", "year": year_match[1] if year_match else ""}
