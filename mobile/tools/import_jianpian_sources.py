"""Verify Jianpian identities for the bundled Top 250 and persist stable detail references."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

ASSETS = Path(__file__).resolve().parents[1] / "shared/src/commonMain/composeResources/files"
ORIGIN = "https://japi.zxfmj.com"
ALIASES = {"1959877": "悬崖上的金鱼姬"}


def request(path, secret=""):
    timestamp = str(int(time.time()))
    headers = {"platform": "pc", "version": "504", "timestamp": timestamp}
    if secret:
        headers["signature"] = hashlib.md5(("504" + timestamp + secret).encode()).hexdigest()
    with urlopen(Request(ORIGIN + "/api" + path, headers=headers), timeout=15) as response:
        root = json.load(response)
    if str(root.get("code")) != "1":
        raise ValueError("Jianpian rejected request")
    return root["data"]


def compatible_routes(detail):
    """Count only direct HTTP HLS/MP4 routes supported by the mobile player."""
    routes = detail.get("source_list_source") or []
    return sum(any(
        urlparse(str(row.get("url", ""))).scheme in ("https", "http")
        and urlparse(str(row.get("url", ""))).path.lower().endswith((".m3u8", ".mp4"))
        for row in (route.get("source_list") or []) if isinstance(row, dict)
    ) for route in routes if isinstance(route, dict))


def verify(item, secret):
    subject = re.fullmatch(r"https://movie\.douban\.com/subject/(\d+)/", item["url"])[1]
    keyword = ALIASES.get(subject, item["title"])
    rows = request("/v2/search/videoV2?" + urlencode({"key": keyword, "page": 1}), secret)
    if not isinstance(rows, list):
        raise ValueError("Invalid search response")
    for row in rows:
        if not isinstance(row, dict):
            continue
        # Exact release metadata narrows requests; the provider's Douban ID is the
        # final authority, allowing aliases without admitting same-title remakes.
        years = [str(value.get("year")) for value in row.get("years", []) if isinstance(value, dict)]
        if item["year"] not in years or re.search("解说|预告|花絮|片段", str(row.get("title", ""))):
            continue
        identifier = str(row.get("id", ""))
        if not re.fullmatch(r"[1-9]\d{0,11}", identifier):
            continue
        detail = request("/video/detailv2?id=" + identifier, secret)
        if not isinstance(detail, dict) or str(detail.get("douban_id")) != subject:
            continue
        if str(detail.get("year")) != item["year"] or str((detail.get("top_category") or {}).get("id")) in ("2", "4", "67"):
            continue
        count = compatible_routes(detail)
        if count:
            return {"subject": subject, "jianpian": identifier, "year": item["year"],
                    "is_series": False, "routes": count, "title": item["title"]}
    return {"subject": subject, "title": item["title"], "status": "unconfirmed"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True,
                        help="Intermediate verification results outside the repository.")
    parser.add_argument("--apply", action="store_true", help="Apply a completed capture without network requests.")
    args = parser.parse_args()
    with sqlite3.connect(ASSETS / "catalog-seed.db") as db:
        items = [json.loads(row[0]) for row in db.execute(
            "SELECT item FROM catalog_items WHERE page_key LIKE 'top250:%' ORDER BY page_key, position")]
    assert len(items) == 250 and len({item["url"] for item in items}) == 250
    if not args.apply:
        secret = request("/v2/sys/init")["secret"]

        def capture(item):
            try:
                return verify(item, secret)
            except Exception as error:
                return {"subject": item["url"].split("/")[-2], "title": item["title"],
                        "status": "unknown", "error": type(error).__name__}

        results = []
        with ThreadPoolExecutor(max_workers=4) as pool:
            for result in pool.map(capture, items):
                results.append(result)
                if len(results) % 25 == 0:
                    print(f"Checked {len(results)}/250, matched {sum('jianpian' in row for row in results)}", flush=True)
        args.capture.write_text(json.dumps({"verified_at": time.strftime("%Y-%m-%d"),
                                           "results": results}, ensure_ascii=False, indent=2) + "\n")
    capture = json.loads(args.capture.read_text())
    results = capture["results"]
    assert {row["subject"] for row in results} == {item["url"].split("/")[-2] for item in items}
    verified = {row["subject"]: row for row in results if "jianpian" in row}
    assert verified, "No verified sources; preserve existing database"
    identities_path = ASSETS / "catalog-playback-identities.json"
    identities = json.loads(identities_path.read_text())
    for subject, result in verified.items():
        identities["subjects"].setdefault(subject, {}).update(
            {key: result[key] for key in ("year", "is_series", "jianpian")})
    identities["jianpian_verification"] = {
        "verified_at": capture["verified_at"], "catalog_subjects": len(results),
        "verified_sources": len(verified), "unknown": sum(row.get("status") == "unknown" for row in results),
        "unconfirmed": sum(row.get("status") == "unconfirmed" for row in results),
        "method": "Exact provider Douban subject ID and release year; direct HTTP HLS/MP4 episode lists. "
                  "Media reachability is checked at playback time; unconfirmed does not mean unavailable.",
    }
    # A single transaction preserves ranking, artwork, metadata and Pomo fallbacks.
    # Persist detail IDs only: media URLs are transient and may expire before playback.
    with sqlite3.connect(ASSETS / "catalog-seed.db") as db:
        db.execute("CREATE TABLE IF NOT EXISTS playback_identities (key TEXT PRIMARY KEY, item TEXT NOT NULL)")
        for item in items:
            result = verified.get(item["url"].split("/")[-2])
            if result is None:
                continue
            pinned = dict(item, playback_page_url=ORIGIN + "/api/video/detailv2?id=" + result["jianpian"],
                          is_series=False)
            db.execute("INSERT OR REPLACE INTO playback_identities VALUES (?, ?)",
                       (item["url"], json.dumps(pinned, ensure_ascii=False)))
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    identities_path.write_text(json.dumps(identities, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(identities["jianpian_verification"], ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
