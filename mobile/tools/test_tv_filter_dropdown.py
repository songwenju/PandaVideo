#!/usr/bin/env python3
"""Verify TV focus-activated filter rows and the remaining Featured dropdown.

Run against an installed debug TV application:
    python3 mobile/tools/test_tv_filter_dropdown.py --serial DEVICE --adb /path/to/adb
"""

import argparse
import re
import subprocess
import time
import unittest
import xml.etree.ElementTree as ET


class TvFilterDropdownTest(unittest.TestCase):
    adb = "adb"
    serial = ""

    def command(self, *args):
        return subprocess.check_output(
            [self.adb, "-s", self.serial, *args], text=True, timeout=15
        )

    def snapshot(self):
        path = "/sdcard/pandavideo-tv-dropdown-test.xml"
        # A cold TV launch can briefly expose no accessibility root. Retry that
        # transient state before reading the dump, which may belong to an old page.
        for attempt in range(3):
            result = self.command("shell", "uiautomator", "dump", path)
            if "ERROR:" not in result and "Killed" not in result:
                return ET.fromstring(self.command("exec-out", "cat", path))
            if attempt == 2:
                self.fail(f"Unable to obtain current UI tree: {result}")
            time.sleep(0.25)

    @staticmethod
    def focused_labels(root):
        return {
            child.get("text", "")
            for node in root.iter("node")
            if node.get("focused") == "true"
            for child in node.iter("node")
            if child.get("text")
        }

    def wait_focus(self, text):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            root = self.snapshot()
            labels = self.focused_labels(root)
            if text in labels:
                return root
            time.sleep(0.15)
        self.fail(f"Expected focus on {text!r}, found {sorted(labels)!r}")

    def assert_selected(self, root, text):
        self.assertTrue(any(
            node.get("selected") == "true" and
            any(child.get("text") == text for child in node.iter("node"))
            for node in root.iter("node")
        ), f"Expected selected filter {text!r}")

    def tap_text(self, text, prefix=False):
        root = self.snapshot()
        candidates = [
            node for node in root.iter("node")
            if (node.get("text", "").startswith(text)
                if prefix else node.get("text") == text)
        ]
        self.assertTrue(candidates, f"Missing control {text!r}")
        bounds = list(map(int, re.findall(r"\d+", candidates[0].get("bounds", ""))))
        self.assertEqual(4, len(bounds))
        self.command("shell", "input", "tap",
                     str((bounds[0] + bounds[2]) // 2),
                     str((bounds[1] + bounds[3]) // 2))

    def key(self, key):
        self.command("shell", "input", "keyevent", str(key))

    def test_select_cancel_and_restore_far_year(self):
        self.command("shell", "am", "force-stop", "dev.pandavideo.tv")
        self.command("shell", "am", "start", "-n", "dev.pandavideo.tv/.TvActivity")
        self.assert_selected(self.wait_focus("热播剧集"), "热播剧集")
        self.key(22)  # Right switches type without pressing confirm.
        self.assert_selected(self.wait_focus("热门电影"), "热门电影")
        self.key(22)
        self.assert_selected(self.wait_focus("热门综艺"), "热门综艺")

        self.tap_text("分类")
        self.tap_text("全部年份")
        self.wait_focus("全部年份")
        for _ in range(18):
            self.key(22)
        root = self.snapshot()
        years = [text for text in self.focused_labels(root)
                 if re.fullmatch(r"\d{4}", text)]
        self.assertEqual(1, len(years), "Far year must remain visible and focused")
        year = years[0]
        self.assert_selected(root, "全部年份")  # Focus alone must not filter Browse.
        self.key(20)
        root = self.snapshot()
        self.assertTrue(self.focused_labels(root) & {"热门", "豆瓣评分", "最新上映"},
                        "Down must reach the sorting row without traversing hidden years")
        self.assert_selected(root, "全部年份")
        self.key(19)
        root = self.snapshot()
        years = [text for text in self.focused_labels(root)
                 if re.fullmatch(r"\d{4}", text)]
        self.assertEqual(1, len(years))
        year = years[0]
        self.assert_selected(root, "全部年份")
        self.key(23)  # Only confirmation applies the focused Browse filter.
        root = self.snapshot()
        self.assert_selected(root, year)
        self.tap_text("喜剧")
        self.assert_selected(self.wait_focus("喜剧"), year)
        self.tap_text("精选")
        self.tap_text("内容：", prefix=True)
        self.wait_focus("精选")
        self.key(20)
        self.wait_focus("高分剧集")
        self.key(4)
        self.wait_focus("内容：精选")  # Dismissal must not apply the focused option.
        self.tap_text("分类")
        self.assert_selected(self.snapshot(), year)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    TvFilterDropdownTest.adb = args.adb
    TvFilterDropdownTest.serial = args.serial
    unittest.main(argv=["test_tv_filter_dropdown"])
