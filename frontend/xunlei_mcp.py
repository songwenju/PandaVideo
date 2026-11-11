"""Minimal legacy SSE client for Xunlei's MCP download service."""

from __future__ import annotations

import json
import threading
from queue import Empty, Queue
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


class XunleiMcpError(Exception):
    pass


class XunleiMcpClient:
    def __init__(self, sse_url: str, timeout: int = 20) -> None:
        self.sse_url = sse_url
        self.timeout = timeout
        self.events: Queue[tuple[str, Any]] = Queue()
        self.response: Any = None
        self.message_url = ""
        self.request_id = 0

    def connect(self) -> None:
        thread = threading.Thread(target=self._listen, daemon=True)
        thread.start()
        event, data = self._next_event()
        if event != "endpoint":
            raise XunleiMcpError("迅雷 MCP 未返回消息端点")
        self.message_url = urljoin(self.sse_url, str(data))
        result = self._request(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {
                    "name": "nas-video-downloader",
                    "version": "1.0",
                },
            },
        )
        if not isinstance(result, dict):
            raise XunleiMcpError("迅雷 MCP 初始化失败")
        self._post(
            {
                "jsonrpc": "2.0",
                "method": "notifications/initialized",
                "params": {},
            }
        )

    def close(self) -> None:
        if self.response is not None:
            self.response.close()

    def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        result = self._request(
            "tools/call",
            {"name": name, "arguments": arguments},
        )
        if not isinstance(result, dict):
            raise XunleiMcpError("迅雷 MCP 返回了无效工具结果")
        content = result.get("content") or []
        text = next(
            (
                str(item.get("text") or "")
                for item in content
                if item.get("type") == "text"
            ),
            "",
        )
        if result.get("isError"):
            raise XunleiMcpError(text[:300] or "迅雷 MCP 工具调用失败")
        try:
            return json.loads(text)
        except json.JSONDecodeError as exc:
            raise XunleiMcpError("迅雷 MCP 返回了无效工具数据") from exc

    def _listen(self) -> None:
        try:
            request = Request(
                self.sse_url,
                headers={
                    "Accept": "text/event-stream",
                    "User-Agent": "PandaVideoDownloader/1.0",
                },
            )
            self.response = urlopen(request, timeout=self.timeout)
            event_name = ""
            data_lines: list[str] = []
            while True:
                raw_line = self.response.readline()
                if not raw_line:
                    raise XunleiMcpError("迅雷 MCP 连接已关闭")
                line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                if not line:
                    if data_lines:
                        self.events.put((event_name, "\n".join(data_lines)))
                    event_name = ""
                    data_lines = []
                elif line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].strip())
        except XunleiMcpError as exc:
            self.events.put(("error", exc))
        except HTTPError as exc:
            self.events.put(
                ("error", XunleiMcpError(f"迅雷 MCP 返回 HTTP {exc.code}"))
            )
        except (URLError, TimeoutError, OSError, ValueError, AttributeError):
            self.events.put(("error", XunleiMcpError("无法连接迅雷 MCP")))

    def _next_event(self) -> tuple[str, Any]:
        try:
            event, data = self.events.get(timeout=self.timeout)
        except Empty as exc:
            raise XunleiMcpError("迅雷 MCP 响应超时") from exc
        if event == "error":
            if isinstance(data, XunleiMcpError):
                raise data
            raise XunleiMcpError("无法连接迅雷 MCP")
        return event, data

    def _post(self, payload: dict[str, Any]) -> None:
        request = Request(
            self.message_url,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json",
                "User-Agent": "PandaVideoDownloader/1.0",
            },
            method="POST",
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                response.read()
        except HTTPError as exc:
            raise XunleiMcpError(
                f"迅雷 MCP 返回 HTTP {exc.code}"
            ) from exc
        except (URLError, TimeoutError, OSError) as exc:
            raise XunleiMcpError("迅雷 MCP 请求失败") from exc

    def _request(self, method: str, params: dict[str, Any]) -> Any:
        self.request_id += 1
        request_id = self.request_id
        self._post(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": method,
                "params": params,
            }
        )
        while True:
            event, raw_data = self._next_event()
            if event != "message":
                continue
            try:
                message = json.loads(str(raw_data))
            except json.JSONDecodeError:
                continue
            if message.get("id") != request_id:
                continue
            if message.get("error"):
                error = message["error"]
                detail = str(error.get("message") or "")[:300]
                raise XunleiMcpError(detail or "迅雷 MCP 请求失败")
            return message.get("result")
