"""Persist validated Top-list playback matches independently of search tokens."""
import re
import sqlite3
import threading
import time
from pathlib import Path
from urllib.parse import urlencode, urlparse

from player import PlayerError, page_url
from posters import normalized_title


def validate_item(item):
    title = item.get("title")
    url = item.get("url")
    year = str(item.get("year") or "")
    if not isinstance(title, str) or not title.strip() or len(title) > 160:
        raise ValueError("无效的影片名称")
    if not isinstance(url, str):
        raise ValueError("无效的豆瓣条目")
    parsed = urlparse(url)
    if (parsed.scheme != "https" or parsed.netloc != "movie.douban.com"
            or not re.fullmatch(r"/subject/\d+/?", parsed.path)
            or parsed.query or parsed.fragment):
        raise ValueError("无效的豆瓣条目")
    if year and not re.fullmatch(r"(?:18|19|20)\d{2}", year):
        raise ValueError("无效的影片年份")
    return "https://movie.douban.com" + parsed.path.rstrip("/") + "/", title.strip(), year


class PlaybackLibrary:
    def __init__(self, path, search, resolve, clock=time.time):
        self.path = Path(path)
        self.search = search
        self.resolve = resolve
        self.clock = clock
        self.locks = [threading.Lock() for _ in range(32)]
        self.slots = threading.BoundedSemaphore(4)

    def _connect(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=15)
        connection.execute("""CREATE TABLE IF NOT EXISTS playback (
            subject TEXT PRIMARY KEY, title TEXT NOT NULL, year TEXT NOT NULL,
            page TEXT NOT NULL, checked REAL NOT NULL)""")
        return connection

    def lookup(self, item):
        subject, title, year = validate_item(item)
        with self.locks[hash(subject) % len(self.locks)]:
            with self._connect() as connection:
                cached = connection.execute(
                    "SELECT title, year, page, checked FROM playback WHERE subject = ?",
                    (subject,),
                ).fetchone()
            if cached and cached[:2] == (title, year):
                ttl = 7 * 86400 if cached[2] else 86400
                if self.clock() - cached[3] < ttl:
                    return self._result(title, cached[2], cached[3], True)
            # A failed upstream request must not be cached as an absent resource.
            with self.slots:
                candidates = {
                    candidate["detail_url"]: candidate
                    for candidate in self.search(title)
                    if normalized_title(candidate.get("title")) == normalized_title(title)
                    and candidate.get("detail_url")
                }
                matches = []
                if year or len(candidates) == 1:
                    for url in candidates:
                        try:
                            resolved = self.resolve(page_url(url))
                        except PlayerError:
                            continue
                        if year and str(resolved.get("year") or "") != year:
                            continue
                        matches.append(page_url(resolved["page_url"]))
                selected = matches[0] if len(matches) == 1 else ""
            checked = self.clock()
            with self._connect() as connection:
                connection.execute("INSERT OR REPLACE INTO playback VALUES (?, ?, ?, ?, ?)",
                                   (subject, title, year, selected, checked))
            return self._result(title, selected, checked, False)

    @staticmethod
    def _result(title, page, checked, cached):
        return {
            "playable": bool(page), "page_url": page, "checked_at": checked,
            "cached": cached,
            "play_url": "/player.html?" + urlencode({"page": page, "title": title}) if page else "",
        }
