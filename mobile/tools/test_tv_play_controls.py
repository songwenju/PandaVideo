#!/usr/bin/env python3
"""Check captured native TV controls for icon centering and redundant seek buttons.

Capture an expanded control bar with uiautomator, then pass its XML path.
Run once for the playing (Pause icon) and paused (Play icon) presentations.
"""

import argparse
import re
import unittest
import xml.etree.ElementTree as ET


class TvPlayControlsTest(unittest.TestCase):
    xml_path = ""

    def setUp(self):
        self.root = ET.parse(self.xml_path).getroot()

    def test_seek_uses_remote_without_toolbar_buttons(self):
        texts = {n.get("text", "") for n in self.root.iter("node")}
        self.assertIn("选集 / 线路", texts, "Expanded playback toolbar must be visible")
        self.assertFalse(texts & {"后退 10 秒", "前进 10 秒"})

    def test_play_and_pause_icon_is_centered_in_its_circle(self):
        parents = {child: node for node in self.root.iter() for child in node}
        icons = [n for n in self.root.iter("node")
                 if n.get("content-desc") in {"播放", "暂停"}]
        self.assertEqual(1, len(icons), "Expected one visible playback state icon")
        icon = icons[0]
        container = parents[icon]
        while container.get("clickable") != "true" and container in parents:
            container = parents[container]

        def bounds(node):
            values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            self.assertEqual(4, len(values))
            return values

        x1, y1, x2, y2 = bounds(container)
        ix1, iy1, ix2, iy2 = bounds(icon)
        self.assertAlmostEqual(x2 - x1, y2 - y1, delta=1, msg="Button must be square")
        self.assertAlmostEqual((x1 + x2) / 2, (ix1 + ix2) / 2, delta=1)
        self.assertAlmostEqual((y1 + y2) / 2, (iy1 + iy2) / 2, delta=1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", required=True)
    args = parser.parse_args()
    TvPlayControlsTest.xml_path = args.xml
    unittest.main(argv=["test_tv_play_controls"])
