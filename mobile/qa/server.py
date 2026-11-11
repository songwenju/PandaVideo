#!/usr/bin/env python3
"""Deterministic device QA API; never used by the production app."""
import argparse
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


class Handler(SimpleHTTPRequestHandler):
    def json_response(self, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == "/api/status":
            return self.json_response({"nas": {"download_dir": "QA", "directory_ready": True}, "xunlei": {}, "doubao": False})
        if parsed.path in {"/api/douban/list", "/api/douban/browse"}:
            return self.json_response({
                "items": [{"title": "MP4 播放验证", "url": "https://qa.example/mp4", "year": "2026", "rating": 9.0},
                          {"title": "HLS 播放验证", "url": "https://qa.example/hls", "year": "2026", "rating": 9.0}],
                "start": 0, "page_size": 25, "has_more": False, "label": "本地可控媒体验证",
            })
        if parsed.path == "/api/player":
            page = query.get("page", [self.server.origin + "/mp4"])[0]
            hls = page.endswith("/hls")
            selected = query.get("episode", ["a1"])[0]
            return self.json_response({
                "title": "HLS 播放验证" if hls else "MP4 播放验证",
                "url": self.server.origin + ("/stream.m3u8" if hls else "/clip.mp4"),
                "kind": "hls" if hls else "video", "page_url": page, "selected": selected,
                "episodes": [{"path": "a1", "label": "第 1 集", "line": 1},
                             {"path": "a2", "label": "第 2 集", "line": 1},
                             {"path": "b1", "label": "备用线路第 1 集", "line": 2}],
                "source": "本地 QA",
            })
        if parsed.path == "/api/poster":
            self.send_error(404)
            return
        super().do_GET()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        if self.path == "/api/playback/resolve":
            kind = "hls" if body["url"].endswith("/hls") else "mp4"
            return self.json_response({"playable": True, "page_url": self.server.origin + "/" + kind})
        if self.path == "/api/search":
            return self.json_response({"results": [], "sources": [], "errors": ["QA 来源错误示例"]})
        self.send_error(404)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--media-dir", required=True)
    parser.add_argument("--port", type=int, default=8767)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), lambda *a, **kw: Handler(*a, directory=args.media_dir, **kw))
    server.origin = f"http://127.0.0.1:{args.port}"
    server.serve_forever()
