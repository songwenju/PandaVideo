#!/usr/bin/env python3
"""Check the PandaVideo capability contract.

The registry in capabilities.json is the machine-readable source of truth for
capabilities that other changes must not silently alter. This script verifies
that every implementation file, code anchor, and guard test still exists, and
that the registry itself has not changed without an explicit --accept.

This is change detection, not a security boundary.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
REGISTRY = HERE / "capabilities.json"
SIGNATURE = HERE / "capabilities.sig"
WHITESPACE = re.compile(r"\s+")
REQUIRED_FIELDS = ("id", "title", "requirement", "implementation", "anchors", "guards")


def normalize(value: str) -> str:
    """Collapse whitespace so formatting changes do not break semantic anchors."""
    return WHITESPACE.sub(" ", value).strip()


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def load_registry() -> tuple[dict[str, Any], str]:
    if not REGISTRY.exists():
        raise SystemExit(f"FAIL: 缺少能力契约 {REGISTRY.relative_to(ROOT)}")
    raw = REGISTRY.read_bytes()
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SystemExit(f"FAIL: 无法解析 {REGISTRY.relative_to(ROOT)}: {error}")
    return data, hashlib.sha256(raw).hexdigest()


def signature_value() -> str | None:
    if not SIGNATURE.exists():
        return None
    for line in read_text(SIGNATURE).splitlines():
        line = line.strip()
        if line.startswith("sha256="):
            return line[len("sha256=") :].strip()
    return None


def check_entry(
    capability_id: str,
    section: str,
    entry: Any,
    failures: list[dict[str, str]],
) -> int:
    """Verify one anchor/guard entry; return the number of checked strings."""
    if not isinstance(entry, dict) or not isinstance(entry.get("file"), str):
        failures.append(
            {
                "capability": capability_id,
                "message": f"{section} 条目缺少字符串字段 file",
            }
        )
        return 0
    relative = entry["file"]
    path = ROOT / relative
    if not path.is_file():
        failures.append(
            {
                "capability": capability_id,
                "message": f"{section} 文件不存在: {relative}",
            }
        )
        return 0
    contents = normalize(read_text(path))
    contains = entry.get("contains")
    if not isinstance(contains, list) or not contains:
        failures.append(
            {
                "capability": capability_id,
                "message": f"{section} 条目没有 contains 列表: {relative}",
            }
        )
        return 0
    checked = 0
    for anchor in contains:
        if not isinstance(anchor, str) or not anchor.strip():
            failures.append(
                {
                    "capability": capability_id,
                    "message": f"{section} 条目包含空锚点: {relative}",
                }
            )
            continue
        checked += 1
        if normalize(anchor) not in contents:
            failures.append(
                {
                    "capability": capability_id,
                    "message": f"{section} 锚点缺失: {relative}\n      - {anchor}",
                }
            )
    return checked


def validate(data: dict[str, Any]) -> tuple[list[dict[str, str]], dict[str, int], list[str]]:
    failures: list[dict[str, str]] = []
    warnings: list[str] = []
    stats = {"capabilities": 0, "files": 0, "anchors": 0, "guards": 0}

    if data.get("schemaVersion") != 1:
        failures.append(
            {
                "capability": "(contract)",
                "message": f"不支持的 schemaVersion: {data.get('schemaVersion')!r}",
            }
        )

    capabilities = data.get("capabilities")
    if not isinstance(capabilities, list) or not capabilities:
        failures.append({"capability": "(contract)", "message": "capabilities 为空"})
        return failures, stats, warnings

    seen: set[str] = set()
    files: set[str] = set()
    for capability in capabilities:
        if not isinstance(capability, dict):
            failures.append(
                {"capability": "(contract)", "message": "capabilities 中存在非对象条目"}
            )
            continue
        missing = [field for field in REQUIRED_FIELDS if field not in capability]
        capability_id = capability.get("id")
        if missing:
            failures.append(
                {
                    "capability": str(capability_id or "(unknown)"),
                    "message": f"缺少字段: {', '.join(missing)}",
                }
            )
            continue
        if not isinstance(capability_id, str) or not capability_id.strip():
            failures.append(
                {"capability": "(contract)", "message": "能力 id 必须是非空字符串"}
            )
            continue
        if capability_id in seen:
            failures.append(
                {"capability": capability_id, "message": "能力 id 重复"}
            )
            continue
        seen.add(capability_id)
        stats["capabilities"] += 1

        implementation = capability["implementation"]
        if not isinstance(implementation, list) or not implementation:
            failures.append(
                {
                    "capability": capability_id,
                    "message": "implementation 必须是非空文件列表",
                }
            )
        else:
            for relative in implementation:
                if not isinstance(relative, str):
                    failures.append(
                        {
                            "capability": capability_id,
                            "message": "implementation 中包含非字符串路径",
                        }
                    )
                    continue
                files.add(relative)
                if not (ROOT / relative).is_file():
                    failures.append(
                        {
                            "capability": capability_id,
                            "message": f"实现文件不存在: {relative}",
                        }
                    )

        anchors = capability["anchors"]
        if not isinstance(anchors, list) or not anchors:
            failures.append(
                {
                    "capability": capability_id,
                    "message": "anchors 必须是非空列表",
                }
            )
        else:
            for entry in anchors:
                if isinstance(entry, dict) and isinstance(entry.get("file"), str):
                    files.add(entry["file"])
                stats["anchors"] += check_entry(
                    capability_id, "代码锚点", entry, failures
                )

        guards = capability["guards"]
        if not isinstance(guards, list):
            failures.append(
                {
                    "capability": capability_id,
                    "message": "guards 必须是列表（没有守护测试时填空列表）",
                }
            )
        elif not guards:
            warnings.append(f"{capability_id}: 没有守护测试，只保护代码锚点")
        else:
            for entry in guards:
                if isinstance(entry, dict) and isinstance(entry.get("file"), str):
                    files.add(entry["file"])
                stats["guards"] += check_entry(
                    capability_id, "守护测试", entry, failures
                )

    stats["files"] = len(files)

    # Keep the human-readable mirror from drifting away from the machine registry.
    doc = HERE / "CAPABILITIES.md"
    if not doc.is_file():
        failures.append(
            {"capability": "(contract)", "message": "缺少能力目录 CAPABILITIES.md"}
        )
    else:
        doc_text = read_text(doc)
        for capability_id in seen:
            if capability_id not in doc_text:
                failures.append(
                    {
                        "capability": capability_id,
                        "message": "CAPABILITIES.md 未登记该能力 id",
                    }
                )
    return failures, stats, warnings


def print_list(data: dict[str, Any]) -> None:
    capabilities = data.get("capabilities") or []
    print(f"能力契约: {len(capabilities)} 项")
    for capability in capabilities:
        if isinstance(capability, dict):
            print(f"  {capability.get('id', '?'):38} {capability.get('title', '')}")


def print_failures(failures: list[dict[str, str]]) -> None:
    print()
    print(f"FAIL: {len(failures)} 项能力契约检查未通过")
    for failure in failures:
        print()
        print(f"  [{failure['capability']}] {failure['message']}")
    print()
    print("处理建议:")
    print("  - 如果不是你有意修改的能力，先判断为其他窗口的误改，对账或回退。")
    print("  - 如果是有意的能力变更，同步更新代码、守护测试、capabilities.json、")
    print("    CAPABILITIES.md，然后运行:")
    print("      python3 graphify-out/check_capabilities.py --accept")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="校验 PandaVideo 能力契约未被静默改变。"
    )
    parser.add_argument(
        "--accept",
        action="store_true",
        help="在全部锚点通过后，显式接受 capabilities.json 的变更并重新签名。",
    )
    parser.add_argument("--list", action="store_true", help="列出已登记能力。")
    parser.add_argument("--json", action="store_true", help="输出机器可读结果。")
    args = parser.parse_args()

    data, digest = load_registry()

    if args.list:
        print_list(data)
        return 0

    failures, stats, warnings = validate(data)

    recorded = signature_value()
    if args.accept:
        if failures:
            if args.json:
                print(
                    json.dumps(
                        {
                            "ok": False,
                            "accepted": False,
                            "digest": digest,
                            "stats": stats,
                            "failures": failures,
                            "warnings": warnings,
                        },
                        ensure_ascii=False,
                        indent=2,
                    )
                )
            else:
                print("拒绝重新签名：契约仍有未通过项。先修复代码或锚点。")
                print_failures(failures)
            return 1
        SIGNATURE.write_text(f"sha256={digest}\n", encoding="utf-8")
        if args.json:
            print(
                json.dumps(
                    {
                        "ok": True,
                        "accepted": True,
                        "digest": digest,
                        "previous": recorded,
                        "stats": stats,
                        "warnings": warnings,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
        else:
            print(
                f"已接受能力契约: {stats['capabilities']} 项能力, "
                f"{stats['anchors']} 个锚点, {stats['guards']} 个守护测试"
            )
            if recorded and recorded != digest:
                print(f"  签名更新: {recorded[:12]} -> {digest[:12]}")
            elif not recorded:
                print(f"  建立签名: {digest[:12]}")
            else:
                print("  签名未变化")
            for warning in warnings:
                print(f"  WARN: {warning}")
        return 0

    if recorded is None:
        failures.insert(
            0,
            {
                "capability": "(contract)",
                "message": "缺少 capabilities.sig。审查契约后运行 --accept 建立签名。",
            },
        )
    elif recorded != digest:
        failures.insert(
            0,
            {
                "capability": "(contract)",
                "message": (
                    "契约签名不匹配：capabilities.json 已被修改但未显式接受。\n"
                    "      如果这不是你的修改，说明另一个窗口改了契约，请先对账；\n"
                    "      如果是有意的契约变更，先审查差异，再运行 --accept。"
                ),
            },
        )

    if args.json:
        print(
            json.dumps(
                {
                    "ok": not failures,
                    "accepted": False,
                    "digest": digest,
                    "recorded": recorded,
                    "stats": stats,
                    "failures": failures,
                    "warnings": warnings,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0 if not failures else 1

    if failures:
        print(
            f"能力契约检查: {stats['capabilities']} 项能力, "
            f"{stats['files']} 个文件, {stats['anchors']} 个锚点, "
            f"{stats['guards']} 个守护测试"
        )
        print_failures(failures)
        return 1

    print(
        f"OK: {stats['capabilities']} 项能力, {stats['files']} 个文件, "
        f"{stats['anchors']} 个锚点, {stats['guards']} 个守护测试全部通过"
    )
    for warning in warnings:
        print(f"  WARN: {warning}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
