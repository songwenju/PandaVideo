"""Local poster cache with approved image origins and matching-source fallback."""
from __future__ import annotations

import hashlib
import json
import re
import tempfile
import threading
import time
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_IMAGE_BYTES = 4 * 1024 * 1024
CACHE_LIMIT_BYTES = 128 * 1024 * 1024
CACHE_TTL = 30 * 86400
APPROVED_HOSTS = {
    "feifan.51weizhang.cn",
    "80sgod.njjyxs.com", "www.80sgod.com", "www.dytt8899.com",
    "www.piaohua.com", "www.dygang.net", "www.66ys.co",
}


class PosterError(Exception):
    pass


def approved_url(url):
    try:
        parsed = urlparse(url)
        host = (parsed.hostname or "").lower()
        return (
            parsed.scheme in {"http", "https"} and not parsed.username
            and not parsed.password and parsed.port in {None, 80, 443}
            and (host in APPROVED_HOSTS or host.endswith(".doubanio.com"))
        )
    except ValueError:
        return False


def image_type(data):
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    raise PosterError("来源未返回有效图片")


class PosterRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not approved_url(newurl):
            raise PosterError("图片跳转地址不可用")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_image(url):
    if not approved_url(url):
        raise PosterError("不支持的图片来源")
    host = urlparse(url).hostname
    referer = (
        "https://movie.douban.com/" if host.endswith(".doubanio.com")
        else "https://www.80sgod.com/" if host == "80sgod.njjyxs.com"
        else f"{urlparse(url).scheme}://{host}/"
    )
    try:
        with build_opener(PosterRedirects()).open(
            Request(url, headers={"User-Agent": "Mozilla/5.0", "Referer": referer}),
            timeout=6,
        ) as response:
            data = response.read(MAX_IMAGE_BYTES + 1)
        if len(data) > MAX_IMAGE_BYTES or len(data) < 32:
            raise PosterError("图片大小不符合要求")
        return data, image_type(data)
    except PosterError:
        raise
    except Exception as exc:
        raise PosterError("图片来源暂时不可用") from exc


def normalized_title(title):
    title = re.sub(r"^\[[^]]+\]\s*", "", str(title))
    title = re.sub(r"\s*[（(]\d{4}[)）]\s*$", "", title)
    return re.sub(r"[\s《》]", "", title).casefold()


class PosterCache:
    def __init__(self, directory, fetch=fetch_image, clock=time.time):
        self.directory = Path(directory)
        self.fetch = fetch
        self.clock = clock
        self.locks = [threading.Lock() for _ in range(32)]
        self.slots = threading.BoundedSemaphore(4)
        self.misses = {}
        self.miss_lock = threading.Lock()
        self.prune_lock = threading.Lock()

    def _read(self, key):
        try:
            metadata = json.loads((self.directory / f"{key}.json").read_text())
            data = (self.directory / f"{key}.bin").read_bytes()
            mime = image_type(data)
            if (not 32 <= len(data) <= MAX_IMAGE_BYTES
                    or not isinstance(metadata.get("created_at"), (int, float))
                    or not isinstance(metadata.get("source"), str)):
                return None
            return data, mime, metadata
        except (OSError, ValueError, PosterError):
            return None

    def _atomic(self, path, data):
        with tempfile.NamedTemporaryFile(dir=self.directory, delete=False) as file:
            temporary = Path(file.name)
            file.write(data)
        try:
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)

    def _write(self, key, data, source):
        self.directory.mkdir(parents=True, exist_ok=True)
        self._atomic(self.directory / f"{key}.bin", data)
        self._atomic(self.directory / f"{key}.json", json.dumps({
            "created_at": self.clock(), "source": source,
        }).encode())
        with self.prune_lock:
            files = sorted(self.directory.glob("*.bin"), key=lambda p: p.stat().st_mtime)
            total = sum(p.stat().st_size for p in files)
            for file in files:
                if total <= CACHE_LIMIT_BYTES:
                    break
                total -= file.stat().st_size
                file.unlink(missing_ok=True)
                file.with_suffix(".json").unlink(missing_ok=True)

    def _store(self, key, data, source):
        try:
            self._write(key, data, source)
            return "MISS"
        except OSError:
            # A disk error must not hide an image that was fetched successfully.
            return "BYPASS"

    def get(self, url, title, fallback_lookup):
        key = hashlib.sha256(f"{url}\n{title}".encode()).hexdigest()
        with self.locks[int(key[:8], 16) % len(self.locks)]:
            cached = self._read(key)
            if cached and self.clock() - cached[2]["created_at"] < CACHE_TTL:
                return cached[0], cached[1], "HIT", cached[2]["source"]
            with self.miss_lock:
                if self.misses.get(key, 0) > self.clock():
                    if cached:
                        return cached[0], cached[1], "STALE", cached[2]["source"]
                    raise PosterError("暂无匹配海报")
            if not self.slots.acquire(timeout=20):
                if cached:
                    return cached[0], cached[1], "STALE", cached[2]["source"]
                raise PosterError("海报服务繁忙，请稍后重试")
            try:
                if url and approved_url(url):
                    try:
                        data, mime = self.fetch(url)
                        source = "Douban" if urlparse(url).hostname.endswith(".doubanio.com") else "Original"
                        cache = self._store(key, data, source)
                        return data, mime, cache, source
                    except PosterError:
                        pass
                if title:
                    try:
                        alternative = fallback_lookup(title)
                        if alternative and approved_url(alternative):
                            data, mime = self.fetch(alternative)
                            cache = self._store(key, data, "80s")
                            return data, mime, cache, "80s"
                    except Exception:
                        pass
                with self.miss_lock:
                    if len(self.misses) >= 1000:
                        self.misses.pop(next(iter(self.misses)))
                    self.misses[key] = self.clock() + 600
                if cached:
                    return cached[0], cached[1], "STALE", cached[2]["source"]
                raise PosterError("暂无匹配海报")
            finally:
                self.slots.release()
