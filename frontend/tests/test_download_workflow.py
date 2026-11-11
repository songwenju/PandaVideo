import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import app


class DownloadWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), app.RequestHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def test_status_reports_nas_without_querying_download_services(self):
        with patch.object(app, "request_json", side_effect=AssertionError("unexpected RPC")):
            with urlopen(self.base + "/api/status") as response:
                status = json.load(response)
        self.assertNotIn("aria2", status)
        self.assertEqual(status["nas"]["download_dir"], app.CONFIG.nas_download_dir)
        self.assertIn("directory_ready", status["nas"])
        self.assertIn("xunlei", status)

    def test_retired_queue_and_download_endpoints_do_not_launch_clients(self):
        endpoints = [
            ("/api/tasks", None),
            ("/api/tasks/retry", {"gid": "old-task"}),
            ("/api/tasks/delete", {"gid": "old-task"}),
            ("/api/download/link", {"download_uri": "https://example.com/video.mp4"}),
            ("/api/download/magnet", {"magnet_uri": "magnet:?xt=urn:btih:test"}),
        ]
        with patch.object(app, "launch_xunlei") as launch:
            for path, payload in endpoints:
                with self.subTest(path=path):
                    request = Request(
                        self.base + path,
                        data=None if payload is None else json.dumps(payload).encode(),
                        headers={"Content-Type": "application/json"},
                    )
                    with self.assertRaises(HTTPError) as caught:
                        urlopen(request)
                    self.assertEqual(caught.exception.code, 404)
                    caught.exception.close()
            launch.assert_not_called()
