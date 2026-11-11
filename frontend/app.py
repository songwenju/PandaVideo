#!/usr/bin/env python3
"""Local AI-assisted video search and Synology download service."""

from __future__ import annotations

import base64
import json
import mimetypes
import os
import re
import ssl
import subprocess
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from html import unescape
from html.parser import HTMLParser
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, quote, unquote, urlencode, urljoin, urlparse
from urllib.request import Request, urlopen

from xunlei_mcp import XunleiMcpClient, XunleiMcpError
from douban import DoubanLists, LIST_LABELS, PAGE_SIZE, source_rating, validate_browse, list_page_size
from movie_sources import SOURCES, SourceError, SearchOutcome, catalog as source_catalog, search_source, fetch_html
from posters import PosterCache, PosterError, normalized_title, approved_url
from player import PlayerError, media_type as playable_media_type, resolve_online
from playback_library import PlaybackLibrary, validate_item
from media_search import MEDIA_TYPES, infer_media_type, search_keyword


ROOT = Path(__file__).resolve().parent
STATIC_DIR = ROOT / "static"
ENV_FILE = ROOT / ".env"
MAX_BODY_BYTES = 64 * 1024
RESULT_TTL_SECONDS = 30 * 60
EIGHTYSGOD_BASE_URL = "https://www.80sgod.com"
EIGHTYSGOD_DETAIL_PATH = re.compile(
    r"^/(?:movie|ju|dm|zy|mv|video|course)/\d+$"
)
XUNLEI_NO_DEVICE_MESSAGE = "无可用下载设备，请检查迅雷是否运行并开启远程下载"
DYTT_BASE_URL = "https://www.dytt8899.com"
DYTT_DETAIL_PATH = re.compile(r"^/i/(?:\d+/)?\d+\.html$")


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip("\"'")
        if key and key not in os.environ:
            os.environ[key] = value


load_dotenv(ENV_FILE)


@dataclass(frozen=True)
class Config:
    host: str = os.getenv("APP_HOST", "127.0.0.1")
    port: int = int(os.getenv("APP_PORT", "8766"))
    ark_api_key: str = os.getenv("ARK_API_KEY", "")
    ark_model: str = os.getenv("ARK_MODEL", "")
    ark_base_url: str = os.getenv(
        "ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"
    ).rstrip("/")
    pexels_api_key: str = os.getenv("PEXELS_API_KEY", "")
    pixabay_api_key: str = os.getenv("PIXABAY_API_KEY", "")
    internet_archive_enabled: bool = (
        os.getenv("INTERNET_ARCHIVE_ENABLED", "false").lower() == "true"
    )
    eightysgod_enabled: bool = (
        os.getenv("EIGHTYSGOD_ENABLED", "true").lower() == "true"
    )
    dytt_enabled: bool = os.getenv("DYTT_ENABLED", "true").lower() == "true"
    movie_sources_enabled: bool = os.getenv("MOVIE_SOURCES_ENABLED", "true").lower() == "true"
    nas_download_dir: str = os.path.expanduser(os.getenv(
        "NAS_DOWNLOAD_DIR", os.getenv("ARIA2_DOWNLOAD_DIR", "/Volumes/video/downloads")
    ))
    nas_mount_url: str = os.getenv("NAS_MOUNT_URL", "").strip()
    xunlei_app_path: str = os.getenv(
        "XUNLEI_APP_PATH", "/Applications/Thunder.app"
    )
    xunlei_mcp_url: str = os.getenv("XUNLEI_MCP_URL", "").strip().strip("`")
    xunlei_mcp_target: str = os.getenv("XUNLEI_MCP_TARGET", "").strip()


CONFIG = Config()
DOUBAN_LISTS = DoubanLists()
POSTER_CACHE = PosterCache(os.getenv("POSTER_CACHE_DIR", str(ROOT / ".cache" / "posters")))
PLAYER_SOURCE = next(source for source in SOURCES if source.id == "ygdy")
PLAYBACK_LIBRARY = PlaybackLibrary(
    ROOT / ".cache" / "playback.sqlite3",
    lambda title: search_source(PLAYER_SOURCE, title, parse_dytt_detail).items,
    lambda page: resolve_online(page, lambda url: fetch_html(PLAYER_SOURCE, url, timeout=12)),
)


class AppError(Exception):
    def __init__(self, message: str, status: int = HTTPStatus.BAD_GATEWAY):
        super().__init__(message)
        self.status = int(status)


def request_json(
    url: str,
    *,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    data: dict[str, Any] | None = None,
    timeout: int = 20,
    verify_tls: bool = True,
) -> dict[str, Any]:
    body = None
    request_headers = {"User-Agent": "PandaVideoDownloader/1.0"}
    if headers:
        request_headers.update(headers)
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = Request(url, data=body, headers=request_headers, method=method)
    context = None
    if url.startswith("https://") and not verify_tls:
        context = ssl._create_unverified_context()
    for attempt in range(2):
        try:
            with urlopen(request, timeout=timeout, context=context) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:500]
            raise AppError(f"上游服务返回 HTTP {exc.code}: {detail}") from exc
        except (URLError, TimeoutError, OSError) as exc:
            if attempt == 0:
                time.sleep(0.3)
                continue
            reason = getattr(exc, "reason", str(exc))
            raise AppError(f"无法连接上游服务: {reason}") from exc
        except json.JSONDecodeError as exc:
            raise AppError("上游服务返回了无效 JSON") from exc
    raise AppError("无法连接上游服务")


def request_text(
    url: str,
    *,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    form: dict[str, str] | None = None,
    timeout: int = 20,
    verify_tls: bool = True,
    encoding: str | None = None,
    form_encoding: str = "utf-8",
) -> str:
    body = (
        urlencode(form, encoding=form_encoding).encode("ascii")
        if form is not None else None
    )
    request_headers = {"User-Agent": "PandaVideoDownloader/1.0"}
    if headers:
        request_headers.update(headers)
    if form is not None:
        request_headers["Content-Type"] = "application/x-www-form-urlencoded"
    request = Request(url, data=body, headers=request_headers, method=method)
    context = None
    if url.startswith("https://") and not verify_tls:
        context = ssl._create_unverified_context()
    for attempt in range(2):
        try:
            with urlopen(request, timeout=timeout, context=context) as response:
                charset = encoding or response.headers.get_content_charset() or "utf-8"
                return response.read().decode(charset, errors="replace")
        except HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:500]
            raise AppError(f"上游服务返回 HTTP {exc.code}: {detail}") from exc
        except (URLError, TimeoutError, OSError) as exc:
            if attempt == 0:
                time.sleep(0.3)
                continue
            reason = getattr(exc, "reason", str(exc))
            raise AppError(f"无法连接上游服务: {reason}") from exc
    raise AppError("无法连接上游服务")


def clean_text(value: Any, limit: int = 240) -> str:
    if isinstance(value, list):
        value = " ".join(str(item) for item in value)
    text = re.sub(r"<[^>]+>", " ", str(value or ""))
    return re.sub(r"\s+", " ", text).strip()[:limit]


def prepare_download_uri(value: Any) -> tuple[str, str]:
    download_uri = unescape(str(value or "").strip())
    if download_uri.lower().startswith("jianpian://"):
        match = re.search(r"(?:^|&)path=(.+)", download_uri, flags=re.IGNORECASE)
        if match:
            download_uri = match.group(1)

    parsed = urlparse(download_uri)
    parameters = parse_qs(parsed.query, keep_blank_values=True)
    display_name = clean_text((parameters.get("dn") or [""])[0], 160)
    filename = clean_text(unquote(Path(parsed.path).name), 160)
    title = display_name or filename or "下载任务"
    normalized_uri = quote(
        download_uri,
        safe=":/?&=%#@+;,-._~!$'()*[]",
    )
    return normalized_uri, title


def encode_thunder_uri(download_url: str) -> str:
    payload = f"AA{download_url}ZZ".encode("utf-8")
    return "thunder://" + base64.b64encode(payload).decode("ascii")


def prepare_xunlei_download_uri(value: Any) -> tuple[str, str]:
    download_uri = str(value or "").strip()
    normalized_uri, title = prepare_download_uri(download_uri)
    if download_uri.lower().startswith("jianpian://"):
        download_url = normalized_uri
        return encode_thunder_uri(download_url), title
    return download_uri, title


def launch_xunlei(download_uri: str) -> None:
    if sys.platform != "darwin":
        raise AppError("请在当前设备上打开迅雷，或配置迅雷远程下载", HTTPStatus.SERVICE_UNAVAILABLE)
    app_path = Path(CONFIG.xunlei_app_path)
    download_dir = Path(CONFIG.nas_download_dir)
    if not app_path.is_dir():
        raise AppError("未安装迅雷客户端", HTTPStatus.SERVICE_UNAVAILABLE)
    if not download_dir.is_dir() or not os.access(download_dir, os.W_OK):
        raise AppError(
            f"NAS 目标目录未挂载或不可写: {download_dir}",
            HTTPStatus.SERVICE_UNAVAILABLE,
        )
    try:
        subprocess.run(
            ["/usr/bin/open", "-a", app_path.stem, download_uri],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise AppError("无法唤起迅雷客户端", HTTPStatus.BAD_GATEWAY) from exc


def nas_directory_ready() -> bool:
    download_dir = Path(CONFIG.nas_download_dir)
    return download_dir.is_dir() and os.access(download_dir, os.W_OK)


def mount_nas() -> str:
    if sys.platform != "darwin":
        raise AppError("NAS 服务使用本地目录，无需挂载共享目录", HTTPStatus.BAD_REQUEST)
    if nas_directory_ready():
        return "NAS 目录已挂载"
    mount_url = CONFIG.nas_mount_url
    parsed = urlparse(mount_url)
    if parsed.scheme.lower() != "smb" or not parsed.netloc or not parsed.path:
        raise AppError(
            "未配置有效的 NAS_MOUNT_URL",
            HTTPStatus.SERVICE_UNAVAILABLE,
        )
    script = [
        "/usr/bin/osascript",
        "-e",
        "on run argv",
        "-e",
        "mount volume (item 1 of argv)",
        "-e",
        "end run",
        mount_url,
    ]
    try:
        subprocess.run(
            script,
            check=True,
            capture_output=True,
            text=True,
            timeout=25,
        )
    except subprocess.TimeoutExpired as exc:
        raise AppError("NAS 挂载超时，请检查网络或钥匙串凭据") from exc
    except (OSError, subprocess.CalledProcessError) as exc:
        raise AppError("NAS 挂载失败，请检查网络或钥匙串凭据") from exc
    for _ in range(20):
        if nas_directory_ready():
            return "NAS 挂载成功"
        time.sleep(0.25)
    raise AppError("NAS 已连接，但下载目录尚不可写")


def submit_xunlei_mcp_download(download_url: str) -> dict[str, str]:
    if not CONFIG.xunlei_mcp_url:
        raise AppError(
            "未配置迅雷 MCP，请设置 XUNLEI_MCP_URL",
            HTTPStatus.SERVICE_UNAVAILABLE,
        )

    client = XunleiMcpClient(CONFIG.xunlei_mcp_url)
    try:
        client.connect()
        devices: list[dict[str, Any]] = []
        page_token = ""
        seen_tokens: set[str] = set()
        while True:
            arguments: dict[str, Any] = {"page_size": 100}
            if page_token:
                arguments["page_token"] = page_token
            page = client.call_tool(
                "xunlei_download_list_device",
                arguments,
            )
            if not isinstance(page, dict):
                raise AppError("迅雷 MCP 返回了无效设备列表")
            devices.extend(page.get("device") or page.get("devices") or [])
            next_token = str(page.get("next_page_token") or "")
            if not next_token:
                break
            if next_token in seen_tokens:
                raise AppError("迅雷 MCP 设备分页异常")
            seen_tokens.add(next_token)
            page_token = next_token

        if not devices:
            raise AppError(
                XUNLEI_NO_DEVICE_MESSAGE,
                HTTPStatus.SERVICE_UNAVAILABLE,
            )

        preferred_target = CONFIG.xunlei_mcp_target
        device = next(
            (
                item
                for item in devices
                if str(item.get("target") or "") == preferred_target
            ),
            None,
        )
        if device is None and preferred_target:
            raise AppError(
                "配置的迅雷下载设备当前不可用",
                HTTPStatus.SERVICE_UNAVAILABLE,
            )
        if device is None:
            device = devices[0]
        target = str(device.get("target") or "")
        if not target:
            raise AppError("迅雷 MCP 设备缺少 target")

        checked = client.call_tool(
            "xunlei_download_check_urls",
            {"urls": [download_url]},
        )
        is_valid = isinstance(checked, list) and any(
            item.get("url") == download_url and item.get("message") == "valid"
            for item in checked
            if isinstance(item, dict)
        )
        if not is_valid:
            raise AppError("迅雷判定该下载链接无效", HTTPStatus.BAD_GATEWAY)

        client.call_tool(
            "xunlei_download_create",
            {"target": target, "urls": [download_url]},
        )
        device_name = clean_text(
            device.get("name")
            or device.get("device_name")
            or device.get("deviceName")
            or target,
            120,
        )
        return {"target": target, "device_name": device_name}
    except XunleiMcpError as exc:
        raise AppError(str(exc)) from exc
    finally:
        client.close()


def submit_xunlei_download(download_url: str) -> dict[str, str]:
    local_xunlei_ready = (
        sys.platform == "darwin"
        and Path(getattr(CONFIG, "xunlei_app_path", "")).is_dir()
    )
    if local_xunlei_ready:
        launch_uri, _ = prepare_xunlei_download_uri(download_url)
        launch_xunlei(launch_uri)
        return {
            "target": "local",
            "device_name": "本机迅雷",
            "engine": "xunlei_local",
        }

    if CONFIG.xunlei_mcp_url:
        try:
            device = submit_xunlei_mcp_download(download_url)
            return {**device, "engine": "xunlei_mcp"}
        except AppError as exc:
            if str(exc) != XUNLEI_NO_DEVICE_MESSAGE:
                raise

    launch_uri, _ = prepare_xunlei_download_uri(download_url)
    if sys.platform != "darwin":
        return {
            "target": "browser",
            "device_name": "当前浏览设备的迅雷",
            "engine": "xunlei_browser",
            "launch_uri": encode_thunder_uri(launch_uri)
            if launch_uri.startswith(("http://", "https://", "ftp://")) else launch_uri,
        }
    launch_xunlei(launch_uri)
    return {
        "target": "local",
        "device_name": "本机迅雷",
        "engine": "xunlei_local",
    }


class EightysgodSearchParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.results: list[dict[str, str]] = []
        self._search_list_depth = 0
        self._current_href = ""
        self._current_text: list[str] = []

    def handle_starttag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        attributes = dict(attrs)
        if tag == "ul":
            classes = set((attributes.get("class") or "").split())
            if self._search_list_depth:
                self._search_list_depth += 1
            elif "search_list" in classes:
                self._search_list_depth = 1
            return
        if tag != "a" or not self._search_list_depth:
            return
        href = attributes.get("href") or ""
        if EIGHTYSGOD_DETAIL_PATH.fullmatch(urlparse(href).path):
            self._current_href = href
            self._current_text = []

    def handle_data(self, data: str) -> None:
        if self._current_href:
            self._current_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._current_href:
            title = clean_text(" ".join(self._current_text), 160)
            if title:
                self.results.append({"href": self._current_href, "title": title})
            self._current_href = ""
            self._current_text = []
        if tag == "ul" and self._search_list_depth:
            self._search_list_depth -= 1


class EightysgodDetailParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.title = ""
        self.description = ""
        self.thumbnail = ""
        self.download_urls: list[str] = []

    def handle_starttag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        attributes = dict(attrs)
        if tag == "meta":
            property_name = (attributes.get("property") or "").lower()
            name = (attributes.get("name") or "").lower()
            content = attributes.get("content") or ""
            if property_name == "og:title":
                self.title = clean_text(content, 160)
            elif name == "description":
                self.description = clean_text(content)
            elif property_name == "og:image" or name == "twitter:image":
                if content.strip():
                    self.thumbnail = content.strip()
            return
        if tag == "img" and not self.thumbnail:
            image = attributes.get("data-src") or attributes.get("src") or ""
            if image and not re.search(
                r"(?:logo|loading|favicon|banner|avatar)", image, re.IGNORECASE
            ):
                self.thumbnail = image.strip()
            return
        if tag != "input" or attributes.get("name") != "list[]":
            return
        download_url = unescape(attributes.get("value") or "").strip()
        if download_url.startswith("jianpian://"):
            download_url, _ = prepare_download_uri(download_url)
        if urlparse(download_url).scheme not in {"http", "https", "ftp"}:
            return
        download_url = quote(
            download_url,
            safe=":/?&=%#@+;,-._~!$'()*",
        )
        if download_url not in self.download_urls:
            self.download_urls.append(download_url)


def thumbnail_url(value: str, detail_url: str) -> str:
    url = urljoin(detail_url, unescape(value).strip()) if value else ""
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return ""
    return quote(url, safe=":/?&=%#@+;,-._~!$'()*[]")


def parse_80sgod_search(html: str) -> list[dict[str, str]]:
    parser = EightysgodSearchParser()
    parser.feed(html)
    seen: set[str] = set()
    results = []
    for item in parser.results:
        detail_url = urljoin(EIGHTYSGOD_BASE_URL, item["href"])
        if detail_url in seen:
            continue
        seen.add(detail_url)
        results.append({"detail_url": detail_url, "title": item["title"]})
    return results


def lookup_alternative_poster(title: str) -> str:
    """Match the film title on 80s before accepting its poster."""
    html = request_text(
        f"{EIGHTYSGOD_BASE_URL}/search", method="POST",
        form={"keyword": title}, timeout=6,
    )
    match = next((item for item in parse_80sgod_search(html)
                  if normalized_title(item["title"]) == normalized_title(title)), None)
    if not match:
        return ""
    parser = EightysgodDetailParser()
    parser.feed(request_text(match["detail_url"], timeout=6))
    url = thumbnail_url(parser.thumbnail, match["detail_url"])
    return url if approved_url(url) else ""


def parse_80sgod_detail(
    html: str, detail_url: str, fallback_title: str
) -> list[dict[str, Any]]:
    parser = EightysgodDetailParser()
    parser.feed(html)
    base_title = parser.title or fallback_title
    results = []
    for index, download_url in enumerate(parser.download_urls):
        filename = clean_text(
            unquote(Path(urlparse(download_url).path).name), 160
        )
        title = base_title
        if len(parser.download_urls) > 1 and filename:
            title = f"{base_title} · {filename}"
        results.append(
            {
                "source": "80s电影网",
                "source_id": f"{urlparse(detail_url).path}#{index + 1}",
                "title": title,
                "description": parser.description,
                "media_type": infer_media_type(detail_url, base_title, html),
                "creator": "",
                "duration": "",
                "width": 0,
                "height": 0,
                "size": 0,
                "license": "",
                "detail_url": detail_url,
                "thumbnail_url": thumbnail_url(parser.thumbnail, detail_url),
                "download_url": download_url,
                **source_rating(html, base_title),
            }
        )
    return results


def fetch_80sgod_detail(item: dict[str, str]) -> list[dict[str, Any]]:
    detail_url = item["detail_url"]
    html = request_text(detail_url, timeout=20)
    return parse_80sgod_detail(html, detail_url, item["title"])


def search_80sgod(keywords: str, limit: int = 8, media_type: str = "all") -> list[dict[str, Any]]:
    html = request_text(
        f"{EIGHTYSGOD_BASE_URL}/search",
        method="POST",
        form={"keyword": keywords},
        timeout=20,
    )
    candidates = parse_80sgod_search(html)
    if media_type != "all":
        candidates = [
            item for item in candidates
            if infer_media_type(item["href"], item["title"]) == media_type
        ]
    candidates = candidates[:limit]
    if not candidates:
        return []

    resolved: dict[int, list[dict[str, Any]]] = {}
    failures = 0
    with ThreadPoolExecutor(max_workers=min(4, len(candidates))) as executor:
        future_map = {
            executor.submit(fetch_80sgod_detail, item): index
            for index, item in enumerate(candidates)
        }
        for future in as_completed(future_map):
            index = future_map[future]
            try:
                resolved[index] = future.result()
            except AppError:
                failures += 1

    results = [
        result
        for index in range(len(candidates))
        for result in resolved.get(index, [])
    ]
    if not results and failures == len(candidates):
        raise AppError("80s电影网详情页暂时无法访问")
    return results[:limit]


class DyttSearchParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.results: list[dict[str, str]] = []
        self._href = ""
        self._text: list[str] = []

    def handle_starttag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        attributes = dict(attrs)
        if tag != "a" or "ulink" not in (attributes.get("class") or "").split():
            return
        detail_url = urljoin(DYTT_BASE_URL, attributes.get("href") or "")
        parsed = urlparse(detail_url)
        if (
            parsed.scheme in {"http", "https"}
            and parsed.netloc == urlparse(DYTT_BASE_URL).netloc
            and DYTT_DETAIL_PATH.fullmatch(parsed.path)
        ):
            self._href = detail_url
            self._text = []

    def handle_data(self, data: str) -> None:
        if self._href:
            self._text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._href:
            title = clean_text(" ".join(self._text), 160)
            if title:
                self.results.append({"detail_url": self._href, "title": title})
            self._href = ""
            self._text = []


class DyttDetailParser(EightysgodDetailParser):
    def handle_starttag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        if tag in {"meta", "img"}:
            super().handle_starttag(tag, attrs)
        if tag != "a":
            return
        download_url = unescape(dict(attrs).get("href") or "").strip()
        scheme = urlparse(download_url).scheme.lower()
        if scheme in {"http", "https", "ftp"}:
            if not re.search(
                r"\.(?:mp4|mkv|avi|rmvb|rm|mov|webm|m4v|ts|torrent)$",
                urlparse(download_url).path,
                re.IGNORECASE,
            ):
                return
        elif scheme not in {"magnet", "thunder", "jianpian"}:
            return
        normalized, _ = prepare_download_uri(download_url)
        if normalized not in self.download_urls:
            self.download_urls.append(normalized)


def parse_dytt_search(html: str) -> list[dict[str, str]]:
    parser = DyttSearchParser()
    parser.feed(html)
    seen: set[str] = set()
    results = []
    for item in parser.results:
        if item["detail_url"] not in seen:
            seen.add(item["detail_url"])
            results.append(item)
    return results


def is_bittorrent_url(download_url: str) -> bool:
    parsed = urlparse(download_url)
    return parsed.scheme.lower() == "magnet" or parsed.path.lower().endswith(".torrent")


def parse_dytt_detail(
    html: str, detail_url: str, fallback_title: str
) -> list[dict[str, Any]]:
    parser = DyttDetailParser()
    parser.feed(html)
    parser.download_urls.sort(key=lambda url: not is_bittorrent_url(url))
    return [
        {
            "source": "电影天堂",
            "source_id": f"{urlparse(detail_url).path}#{index + 1}",
            "title": (parser.title or fallback_title) + (
                f" · 下载方式 {index + 1}" if len(parser.download_urls) > 1 else ""
            ),
            "description": parser.description,
            "media_type": infer_media_type(detail_url, parser.title or fallback_title, html),
            "creator": "",
            "duration": "",
            "width": 0,
            "height": 0,
            "size": 0,
            "license": "",
            "detail_url": detail_url,
            "thumbnail_url": thumbnail_url(parser.thumbnail, detail_url),
            "download_url": download_url,
            **source_rating(html, parser.title or fallback_title),
        }
        for index, download_url in enumerate(parser.download_urls)
    ]


def fetch_dytt_detail(item: dict[str, str]) -> list[dict[str, Any]]:
    html = request_text(item["detail_url"], encoding="gb18030", timeout=20)
    return parse_dytt_detail(html, item["detail_url"], item["title"])


def search_dytt(keywords: str, limit: int = 8) -> list[dict[str, Any]]:
    html = request_text(
        f"{DYTT_BASE_URL}/e/search/index.php",
        method="POST",
        form={
            "classid": "0",
            "show": "title,smalltext",
            "tempid": "1",
            "keyboard": keywords,
            "Submit": "立即搜索",
        },
        encoding="gb18030",
        form_encoding="gb18030",
        timeout=20,
    )
    candidates = parse_dytt_search(html)[:limit]
    if not candidates:
        return []
    resolved: dict[int, list[dict[str, Any]]] = {}
    failures = 0
    with ThreadPoolExecutor(max_workers=min(4, len(candidates))) as executor:
        future_map = {
            executor.submit(fetch_dytt_detail, item): index
            for index, item in enumerate(candidates)
        }
        for future in as_completed(future_map):
            try:
                resolved[future_map[future]] = future.result()
            except AppError:
                failures += 1
    if not resolved and failures == len(candidates):
        raise AppError("电影天堂详情页暂时无法访问")
    results = [
        result
        for index in range(len(candidates))
        for result in resolved.get(index, [])
    ]
    results.sort(key=lambda item: not is_bittorrent_url(item["download_url"]))
    return results[:limit]


def parse_json_object(text: str) -> dict[str, Any]:
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else {}
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if not match:
            return {}
        try:
            value = json.loads(match.group(0))
            return value if isinstance(value, dict) else {}
        except json.JSONDecodeError:
            return {}


def understand_query(raw_query: str) -> dict[str, Any]:
    fallback = {
        "keywords": raw_query.strip(),
        "orientation": "any",
        "quality": "any",
    }
    if not CONFIG.ark_api_key or not CONFIG.ark_model:
        return fallback

    payload = {
        "model": CONFIG.ark_model,
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是公开视频素材搜索解析器。把用户需求转换成 JSON，只返回字段："
                    "keywords（适合素材站搜索的简短英文关键词）、orientation"
                    "（any/landscape/portrait/square）、quality（any/hd/4k）。"
                    "不要添加版权结论，不要输出其他字段。"
                ),
            },
            {"role": "user", "content": raw_query.strip()},
        ],
    }
    response = request_json(
        f"{CONFIG.ark_base_url}/chat/completions",
        method="POST",
        headers={"Authorization": f"Bearer {CONFIG.ark_api_key}"},
        data=payload,
        timeout=45,
    )
    try:
        content = response["choices"][0]["message"]["content"]
        parsed = parse_json_object(content)
    except (KeyError, IndexError, TypeError):
        return fallback
    keywords = clean_text(parsed.get("keywords"), 120) or fallback["keywords"]
    orientation = parsed.get("orientation", "any")
    quality = parsed.get("quality", "any")
    return {
        "keywords": keywords,
        "orientation": orientation
        if orientation in {"any", "landscape", "portrait", "square"}
        else "any",
        "quality": quality if quality in {"any", "hd", "4k"} else "any",
    }


def select_archive_video(identifier: str) -> dict[str, Any] | None:
    metadata = request_json(
        f"https://archive.org/metadata/{quote(identifier, safe='')}", timeout=20
    )
    item_meta = metadata.get("metadata", {})
    candidates = []
    for file_info in metadata.get("files", []):
        name = str(file_info.get("name", ""))
        if not name.lower().endswith((".mp4", ".m4v", ".webm")):
            continue
        try:
            size = int(file_info.get("size", 0))
        except (TypeError, ValueError):
            size = 0
        candidates.append((size, name, file_info))
    if not candidates:
        return None
    _, file_name, file_info = max(candidates, key=lambda item: item[0])
    download_url = (
        f"https://archive.org/download/{quote(identifier, safe='')}/"
        f"{quote(file_name, safe='')}"
    )
    license_url = clean_text(
        item_meta.get("licenseurl") or item_meta.get("rights"), 300
    )
    return {
        "source": "Internet Archive",
        "source_id": identifier,
        "title": clean_text(item_meta.get("title"), 160) or identifier,
        "description": clean_text(item_meta.get("description")),
        "creator": clean_text(item_meta.get("creator"), 100),
        "duration": clean_text(file_info.get("length"), 32),
        "width": int(file_info.get("width") or 0),
        "height": int(file_info.get("height") or 0),
        "size": int(file_info.get("size") or 0),
        "license": license_url or "请在素材详情页核验授权范围",
        "detail_url": f"https://archive.org/details/{quote(identifier, safe='')}",
        "download_url": download_url,
    }


def search_internet_archive(keywords: str, limit: int = 8) -> list[dict[str, Any]]:
    terms = [term for term in re.split(r"\s+", keywords) if term][:8]
    text_query = " AND ".join(f'"{term.replace(chr(34), "")}"' for term in terms)
    query = (
        "mediatype:movies AND "
        "(collection:opensource_movies OR collection:prelinger) AND "
        f"({text_query or quote(keywords)})"
    )
    params = [
        ("q", query),
        ("fl[]", "identifier"),
        ("rows", str(limit)),
        ("page", "1"),
        ("output", "json"),
        ("sort[]", "downloads desc"),
    ]
    response = request_json(
        "https://archive.org/advancedsearch.php?" + urlencode(params), timeout=25
    )
    identifiers = [
        clean_text(doc.get("identifier"), 120)
        for doc in response.get("response", {}).get("docs", [])
        if doc.get("identifier")
    ]
    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = {
            executor.submit(select_archive_video, identifier): identifier
            for identifier in identifiers
        }
        for future in as_completed(futures):
            try:
                item = future.result()
                if item:
                    results.append(item)
            except AppError:
                continue
    return results[:limit]


def select_pexels_file(files: list[dict[str, Any]]) -> dict[str, Any] | None:
    mp4_files = [
        item
        for item in files
        if item.get("link") and str(item.get("file_type", "")).lower() == "video/mp4"
    ]
    if not mp4_files:
        return None
    under_4k = [
        item for item in mp4_files if int(item.get("width") or 0) <= 3840
    ] or mp4_files
    return max(
        under_4k,
        key=lambda item: int(item.get("width") or 0) * int(item.get("height") or 0),
    )


def search_pexels(keywords: str, limit: int = 8) -> list[dict[str, Any]]:
    if not CONFIG.pexels_api_key:
        return []
    url = "https://api.pexels.com/videos/search?" + urlencode(
        {"query": keywords, "per_page": limit, "page": 1}
    )
    response = request_json(
        url, headers={"Authorization": CONFIG.pexels_api_key}, timeout=20
    )
    results = []
    for video in response.get("videos", []):
        selected = select_pexels_file(video.get("video_files", []))
        if not selected:
            continue
        user = video.get("user") or {}
        results.append(
            {
                "source": "Pexels",
                "source_id": str(video.get("id", "")),
                "title": f"Pexels 视频 {video.get('id', '')}",
                "description": "",
                "creator": clean_text(user.get("name"), 100),
                "duration": str(video.get("duration") or ""),
                "width": int(selected.get("width") or 0),
                "height": int(selected.get("height") or 0),
                "size": 0,
                "license": "Pexels License，下载和使用前请核验最新条款",
                "detail_url": str(video.get("url") or ""),
                "download_url": str(selected.get("link") or ""),
            }
        )
    return results


def search_pixabay(keywords: str, limit: int = 8) -> list[dict[str, Any]]:
    if not CONFIG.pixabay_api_key:
        return []
    url = "https://pixabay.com/api/videos/?" + urlencode(
        {
            "key": CONFIG.pixabay_api_key,
            "q": keywords,
            "per_page": limit,
            "safesearch": "true",
        }
    )
    response = request_json(url, timeout=20)
    results = []
    for video in response.get("hits", []):
        variants = video.get("videos") or {}
        selected = (
            variants.get("large")
            or variants.get("medium")
            or variants.get("small")
            or variants.get("tiny")
        )
        if not selected or not selected.get("url"):
            continue
        results.append(
            {
                "source": "Pixabay",
                "source_id": str(video.get("id", "")),
                "title": clean_text(video.get("tags"), 160)
                or f"Pixabay 视频 {video.get('id', '')}",
                "description": "",
                "creator": clean_text(video.get("user"), 100),
                "duration": str(video.get("duration") or ""),
                "width": int(selected.get("width") or 0),
                "height": int(selected.get("height") or 0),
                "size": int(selected.get("size") or 0),
                "license": "Pixabay Content License，下载和使用前请核验最新条款",
                "detail_url": str(video.get("pageURL") or ""),
                "download_url": str(selected.get("url") or ""),
            }
        )
    return results


class ResultRegistry:
    def __init__(self) -> None:
        self._items: dict[str, tuple[float, dict[str, Any]]] = {}

    def register(self, item: dict[str, Any]) -> dict[str, Any]:
        token = uuid.uuid4().hex
        self._items[token] = (time.time(), item)
        visible = {key: value for key, value in item.items() if key != "download_url"}
        visible["token"] = token
        visible["playable"] = str(item.get("source_id", "")).split(":")[0] == "ygdy" or bool(playable_media_type(item.get("download_url")))
        return visible

    def resolve(self, token: str) -> dict[str, Any]:
        now = time.time()
        self._items = {
            key: value
            for key, value in self._items.items()
            if now - value[0] <= RESULT_TTL_SECONDS
        }
        entry = self._items.get(token)
        if not entry:
            raise AppError("搜索结果已过期，请重新搜索", HTTPStatus.GONE)
        return entry[1]


REGISTRY = ResultRegistry()


def search_all(raw_query: str, media_type: str = "all") -> dict[str, Any]:
    if media_type not in MEDIA_TYPES:
        raise AppError("无效的影视类型", HTTPStatus.BAD_REQUEST)
    raw_query = search_keyword(raw_query)
    intent = understand_query(raw_query) if media_type == "all" else {
        "keywords": raw_query, "orientation": "any", "quality": "any",
    }
    intent = {**intent, "media_type": media_type, "match": "partial"}
    keywords = intent["keywords"]
    providers = [
        ("Pexels", lambda: search_pexels(keywords)),
        ("Pixabay", lambda: search_pixabay(keywords)),
    ] if media_type == "all" else []
    if CONFIG.eightysgod_enabled:
        providers.append(("80s电影网", lambda: search_80sgod(
            raw_query, **({"media_type": media_type} if media_type != "all" else {})
        )))
    if getattr(CONFIG, "dytt_enabled", False):
        providers.append(("电影天堂", lambda: search_dytt(raw_query)))
    if getattr(CONFIG, "movie_sources_enabled", False):
        for source in SOURCES:
            if source.base:
                providers.append((source.name, lambda s=source: search_source(
                    s, raw_query, parse_dytt_detail,
                    **({"media_type": media_type} if media_type != "all" else {}))))
    if CONFIG.internet_archive_enabled and media_type == "all":
        providers.append(
            ("Internet Archive", lambda: search_internet_archive(keywords))
        )
    items: list[dict[str, Any]] = []
    errors: list[str] = []
    outcomes: dict[str, dict] = {}
    with ThreadPoolExecutor(max_workers=max(1, len(providers))) as executor:
        future_map = {executor.submit(search): name for name, search in providers}
        for future in as_completed(future_map):
            name = future_map[future]
            try:
                outcome = future.result()
                found = outcome.items if isinstance(outcome, SearchOutcome) else outcome
                found = [
                    {**item, "media_type": item.get("media_type") or infer_media_type(
                        item.get("detail_url", ""), item.get("title", "")
                    )}
                    for item in found
                ]
                if media_type != "all":
                    found = [item for item in found if item["media_type"] == media_type]
                warnings = outcome.warnings if isinstance(outcome, SearchOutcome) else []
                items.extend(found)
                errors.extend(f"{name}: {warning}" for warning in warnings)
                outcomes[name] = {"name": name, "count": len(found),
                                  "status": "partial" if warnings else "ok"}
            except AppError as exc:
                errors.append(f"{name}: {exc}")
                outcomes[name] = {"name": name, "count": 0, "status": "error"}
            except Exception as exc:
                errors.append(f"{name}: {exc}" if isinstance(exc, SourceError)
                              else f"{name}: 上游请求失败 ({type(exc).__name__})")
                outcomes[name] = {"name": name, "count": 0, "status": "error"}
    items.sort(
        key=lambda item: (
            not (
                item.get("source") in {"电影天堂", "飘花电影网", "电影港", "66影视"}
                and is_bittorrent_url(item.get("download_url", ""))
            ),
            item.get("source") != "Pexels",
            -(int(item.get("width") or 0) * int(item.get("height") or 0)),
        )
    )
    return {
        "intent": intent,
        "results": [REGISTRY.register(item) for item in items[:72]],
        "errors": errors,
        "sources": [outcomes[name] for name, _ in providers],
    }


class RequestHandler(SimpleHTTPRequestHandler):
    server_version = "PandaVideoDownloader/1.0"

    def translate_path(self, path: str) -> str:
        clean_path = urlparse(path).path
        if clean_path == "/":
            clean_path = "/index.html"
        target = (STATIC_DIR / clean_path.lstrip("/")).resolve()
        if STATIC_DIR.resolve() not in target.parents and target != STATIC_DIR.resolve():
            return str(STATIC_DIR / "__not_found__")
        return str(target)

    def end_headers(self) -> None:
        if urlparse(self.path).path in {"/", "/index.html", "/catalog.html", "/hot.html", "/search.html", "/player.html"}:
            self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        player_request = urlparse(self.path).path == "/player.html"
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self' https://unpkg.com; "
            "style-src 'self'; img-src 'self' https: http:; "
            + ("connect-src 'self' https: http:; media-src 'self' blob: https: http:; worker-src 'self' blob:"
               if player_request else "connect-src 'self'"),
        )
        super().end_headers()

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stdout.write(
            f"[{self.log_date_time_string()}] {self.client_address[0]} {fmt % args}\n"
        )

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise AppError("无效请求长度", HTTPStatus.BAD_REQUEST) from exc
        if length <= 0 or length > MAX_BODY_BYTES:
            raise AppError("请求体为空或过大", HTTPStatus.BAD_REQUEST)
        try:
            value = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise AppError("请求 JSON 无效", HTTPStatus.BAD_REQUEST) from exc
        if not isinstance(value, dict):
            raise AppError("请求必须是 JSON 对象", HTTPStatus.BAD_REQUEST)
        return value

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/api/player":
            parameters = parse_qs(urlparse(self.path).query)
            try:
                token = parameters.get("token", [""])[0]
                episode = parameters.get("episode", [""])[0]
                item = REGISTRY.resolve(token) if token else {}
                page = item.get("detail_url") if str(item.get("source_id", "")).split(":")[0] == "ygdy" else parameters.get("page", [""])[0]
                if page:
                    data = resolve_online(page, lambda url: fetch_html(PLAYER_SOURCE, url, timeout=12), episode)
                elif item and playable_media_type(item.get("download_url")):
                    data = {"url": item["download_url"], "kind": playable_media_type(item["download_url"]),
                            "episodes": [], "selected": "", "page_url": item.get("detail_url", ""),
                            "source": item.get("source", "")}
                else:
                    raise PlayerError("此资源不支持在线播放，请搜索后选择播放入口")
                data["title"] = clean_text(item.get("title") or parameters.get("title", ["视频播放"])[0], 160)
                self.send_json(HTTPStatus.OK, data)
            except PlayerError as exc:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            except AppError as exc:
                self.send_json(exc.status, {"error": str(exc)})
            except Exception:
                self.send_json(HTTPStatus.BAD_GATEWAY, {"error": "播放信息暂时无法读取，请稍后重试"})
            return
        if path == "/api/poster":
            parameters = parse_qs(urlparse(self.path).query)
            url = parameters.get("url", [""])[0][:2048]
            title = clean_text(parameters.get("title", [""])[0], 160)
            try:
                data, mime, cache, source = POSTER_CACHE.get(
                    url, title, lookup_alternative_poster)
            except PosterError:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "暂无匹配海报"})
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "public, max-age=86400")
            self.send_header("X-Poster-Cache", cache)
            self.send_header("X-Poster-Source", source)
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if path == "/api/douban/browse":
            parameters = parse_qs(urlparse(self.path).query)
            media_type = parameters.get("type", ["movie"])[0]
            genre = parameters.get("genre", ["all"])[0]
            year = parameters.get("year", ["all"])[0]
            sort = parameters.get("sort", ["hot"])[0]
            try:
                start = int(parameters.get("start", ["0"])[0])
                validate_browse(media_type, genre, year, sort, start)
            except ValueError:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "无效的分类筛选条件"})
                return
            try:
                self.send_json(HTTPStatus.OK, DOUBAN_LISTS.browse(
                    media_type, genre, year, sort, start, request_text
                ))
            except Exception:
                self.send_json(HTTPStatus.BAD_GATEWAY, {
                    "error": "影视列表暂时无法读取，请稍后重试", "items": [],
                })
            return
        if path == "/api/douban/list":
            parameters = parse_qs(urlparse(self.path).query)
            kind = parameters.get("kind", ["popular"])[0]
            try:
                start = int(parameters.get("start", ["0"])[0])
                page_size = list_page_size(kind)
                if (
                    kind not in LIST_LABELS or start < 0 or start > 10000
                    or start % page_size or (kind == "top250" and start > 225)
                ):
                    raise ValueError()
            except ValueError:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "无效的豆瓣列表或页码"})
                return
            try:
                self.send_json(
                    HTTPStatus.OK, DOUBAN_LISTS.fetch(kind, start, request_text)
                )
            except Exception:
                self.send_json(
                    HTTPStatus.BAD_GATEWAY,
                    {"error": "豆瓣列表暂时无法读取，请稍后重试", "items": []},
                )
            return
        if path == "/api/status":
            self.send_json(
                HTTPStatus.OK,
                {
                    "doubao": bool(CONFIG.ark_api_key and CONFIG.ark_model),
                    "providers": {
                        "internet_archive": CONFIG.internet_archive_enabled,
                        "eightysgod": CONFIG.eightysgod_enabled,
                        "dytt": CONFIG.dytt_enabled,
                        "pexels": bool(CONFIG.pexels_api_key),
                        "pixabay": bool(CONFIG.pixabay_api_key),
                        **{s.id: CONFIG.movie_sources_enabled for s in SOURCES if s.base},
                    },
                    "movie_sources": source_catalog(CONFIG.movie_sources_enabled),
                    "nas": {
                        "download_dir": CONFIG.nas_download_dir,
                        "directory_ready": nas_directory_ready(),
                        "mount_configured": bool(CONFIG.nas_mount_url),
                        "mount_supported": sys.platform == "darwin",
                    },
                    "xunlei": {
                        "installed": sys.platform == "darwin" and Path(CONFIG.xunlei_app_path).is_dir(),
                        "browser_launch": sys.platform != "darwin",
                        "mcp_configured": bool(CONFIG.xunlei_mcp_url),
                    },
                },
            )
            return
        if path.startswith("/api/"):
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "接口不存在"})
            return
        super().do_GET()

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        try:
            payload = self.read_json()
            if path == "/api/playback/resolve":
                try:
                    validate_item(payload)
                except ValueError as exc:
                    raise AppError(str(exc), HTTPStatus.BAD_REQUEST) from exc
                try:
                    result = PLAYBACK_LIBRARY.lookup(payload)
                except Exception:
                    raise AppError("播放资源暂时无法读取，请稍后重试", HTTPStatus.BAD_GATEWAY)
                self.send_json(HTTPStatus.OK, result)
                return
            if path == "/api/nas/mount":
                message = mount_nas()
                self.send_json(
                    HTTPStatus.OK,
                    {
                        "message": message,
                        "download_dir": CONFIG.nas_download_dir,
                    },
                )
                return
            if path == "/api/search":
                query = clean_text(payload.get("query"), 300)
                if not search_keyword(query).strip():
                    raise AppError("请输入片名或搜索关键词", HTTPStatus.BAD_REQUEST)
                media_type = payload.get("media_type", "all")
                if not isinstance(media_type, str) or media_type not in MEDIA_TYPES:
                    raise AppError("无效的影视类型", HTTPStatus.BAD_REQUEST)
                self.send_json(HTTPStatus.OK, search_all(query, media_type))
                return
            if path == "/api/download/copy":
                item = REGISTRY.resolve(str(payload.get("token", "")))
                if not item.get("download_url"):
                    raise AppError("该结果仅提供原站观看，暂无下载链接", HTTPStatus.BAD_REQUEST)
                self.send_json(HTTPStatus.OK, {"download_url": item["download_url"]})
                return
            if path == "/api/download":
                item = REGISTRY.resolve(str(payload.get("token", "")))
                if not item.get("download_url"):
                    raise AppError("该结果仅提供原站观看，暂无下载链接", HTTPStatus.BAD_REQUEST)
                engine = payload.get("engine", "auto")
                if engine not in ("auto", "xunlei"):
                    raise AppError("无效的下载引擎", HTTPStatus.BAD_REQUEST)
                submission = submit_xunlei_download(item["download_url"])
                self.send_json(
                    HTTPStatus.OK,
                    {
                        "message": "请点击链接打开当前设备的迅雷"
                        if submission["engine"] == "xunlei_browser"
                        else "下载链接已发送给迅雷"
                        if submission["engine"] == "xunlei_local"
                        else "资源已通过 MCP 提交给迅雷",
                        "destination": submission["device_name"],
                        "title": item["title"],
                        "engine": submission["engine"],
                        "launch_uri": submission.get("launch_uri", ""),
                    },
                )
                return
            if path == "/api/download/xunlei":
                launch_uri, title = prepare_xunlei_download_uri(
                    payload.get("download_uri")
                )
                if sys.platform == "darwin":
                    launch_xunlei(launch_uri)
                elif launch_uri.startswith(("http://", "https://", "ftp://")):
                    launch_uri = encode_thunder_uri(launch_uri)
                self.send_json(
                    HTTPStatus.OK,
                    {
                        "message": "下载链接已发送给迅雷" if sys.platform == "darwin" else "请点击链接打开当前设备的迅雷",
                        "destination": CONFIG.nas_download_dir if sys.platform == "darwin" else "当前浏览设备",
                        "title": title,
                        "engine": "xunlei_local" if sys.platform == "darwin" else "xunlei_browser",
                        "launch_uri": launch_uri if sys.platform != "darwin" else "",
                    },
                )
                return
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "接口不存在"})
        except AppError as exc:
            self.send_json(exc.status, {"error": str(exc)})
        except Exception as exc:
            print(f"Unhandled error: {exc}", file=sys.stderr)
            self.send_json(
                HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "服务内部错误"}
            )


def main() -> None:
    if CONFIG.host not in {"127.0.0.1", "::1", "localhost"}:
        print(
            "警告：服务正在监听非本机地址，请自行配置防火墙和访问认证。",
            file=sys.stderr,
        )
    mimetypes.add_type("text/javascript", ".js")
    server = ThreadingHTTPServer((CONFIG.host, CONFIG.port), RequestHandler)
    print(f"NAS 视频下载服务：http://{CONFIG.host}:{CONFIG.port}")
    print(f"目标目录：{CONFIG.nas_download_dir}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
