"""Refresh the verified Douban homepage SQLite snapshot and its original poster assets."""
import concurrent.futures
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "shared/src/commonMain/composeResources/files"


def fetch(address):
    command = ["curl", "-fsSL", "--max-time", "25", "-A", "Mozilla/5.0"]
    if "doubanio.com" in urllib.parse.urlparse(address).netloc:
        command += ["-H", "Referer: https://movie.douban.com/"]
    return subprocess.run(command + [address], check=True, capture_output=True).stdout


def top_page(start):
    text = ""
    for suffix in ("", "&filter="):
        try:
            candidate = fetch(f"https://movie.douban.com/top250?start={start}{suffix}").decode("utf-8")
            if 'class="rating_num"' in candidate:
                text = candidate
                break
        except subprocess.CalledProcessError:
            continue
    items = []
    for block in re.findall(r'<li>\s*<div class="item">(.*?)</li>', text, re.S):
        subject = re.search(r'href="(https://movie.douban.com/subject/\d+/)"', block)
        image = re.search(r'<img[^>]+>', block)
        rating = re.search(r'class="rating_num"[^>]*>([\d.]+)', block)
        if not subject or not image or not rating:
            raise ValueError("Invalid Top 250 card")
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', image.group()))
        info = re.search(r'<p[^>]*>(.*?)</p>', block, re.S)
        year = re.search(r'\b(?:19|20)\d{2}\b', info.group(1)) if info else None
        items.append({
            "title": html.unescape(attrs["alt"]),
            "url": subject.group(1),
            "year": year.group() if year else "",
            "rating": float(rating.group(1)),
            "cover": html.unescape(attrs["src"]),
            "casts": [],
        })
    if len(items) != 25:
        raise ValueError(f"Top 250 page {start}: expected 25, got {len(items)}")
    return items


def tv_page(start):
    data = []
    parameters = {"sort": "S", "range": "0,10", "tags": "电视剧", "start": start}
    for safe in (",", ""):
        address = "https://movie.douban.com/j/new_search_subjects?" + urllib.parse.urlencode(parameters, safe=safe)
        try:
            data = json.loads(fetch(address))["data"]
            if data:
                break
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            continue
    if len(data) != 20:
        raise ValueError(f"TV page {start}: expected 20, got {len(data)}")
    return [{
        "title": item["title"], "url": item["url"], "year": "",
        "rating": float(item["rate"]), "cover": item["cover"],
        "casts": item.get("casts", [])[:3],
    } for item in data]


def main(resume=False):
    pages = []
    staging = ROOT / "build/catalog-import-pages"
    staging.mkdir(exist_ok=True)
    for kind, size, count in [("top250", 25, 250), ("tv", 20, 300)]:
        for start in range(0, count, size):
            checkpoint = staging / f"{kind}-{start}.json"
            if resume and checkpoint.exists():
                items = json.loads(checkpoint.read_text(encoding="utf-8"))
            else:
                items = top_page(start) if kind == "top250" else tv_page(start)
                checkpoint.write_text(json.dumps(items, ensure_ascii=False), encoding="utf-8")
            pages.append((f"{kind}:{start}", {
                "items": items, "start": start, "page_size": size,
                "has_more": start + size < count if kind == "top250" else True,
                "label": "Top 250" if kind == "top250" else "电视剧",
                "stale": False,
            }))
            print(f"{kind}: {start + size}/{count}", flush=True)
            time.sleep(0.3)
    for kind in ("top250", "tv"):
        urls = [item["url"] for key, page in pages if key.startswith(kind + ":") for item in page["items"]]
        if len(urls) != len(set(urls)):
            raise ValueError(f"Duplicate subjects in {kind} snapshot")

    ASSETS.mkdir(parents=True, exist_ok=True)
    database = ASSETS / "catalog-seed.db"
    temporary = ASSETS / "catalog-seed.db.tmp"
    if temporary.exists():
        temporary.unlink()
    timestamp = int(min(path.stat().st_mtime for path in staging.glob("*.json")) * 1000)
    with sqlite3.connect(temporary) as db:
        db.execute("CREATE TABLE catalog_pages (key TEXT PRIMARY KEY, metadata TEXT NOT NULL, saved_at INTEGER NOT NULL)")
        db.execute("CREATE TABLE catalog_items (page_key TEXT NOT NULL, position INTEGER NOT NULL, item TEXT NOT NULL, PRIMARY KEY(page_key, position))")
        for key, page in pages:
            metadata = dict(page, items=[])
            db.execute("INSERT INTO catalog_pages VALUES (?, ?, ?)", (key, json.dumps(metadata, ensure_ascii=False), timestamp))
            db.executemany("INSERT INTO catalog_items VALUES (?, ?, ?)", [
                (key, index, json.dumps(item, ensure_ascii=False)) for index, item in enumerate(page["items"])
            ])
    temporary.replace(database)
    covers = {item["cover"] for _, page in pages for item in page["items"]}
    poster_dir = ASSETS / "posters"
    poster_dir.mkdir(exist_ok=True)

    def download(address):
        filename = hashlib.sha256(address.encode()).hexdigest() + ".jpg"
        target = poster_dir / filename
        try:
            if not target.exists():
                image = fetch(address)
                if not (image.startswith(b"\xff\xd8\xff") or image.startswith(b"\x89PNG")
                        or (image.startswith(b"RIFF") and image[8:12] == b"WEBP")):
                    raise ValueError("Not an image")
                target.write_bytes(image)
            return address, f"files/posters/{filename}"
        except Exception:
            return address, None

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        mapping = {url: path for url, path in executor.map(download, sorted(covers)) if path}
    (ASSETS / "catalog-posters.json").write_text(json.dumps(mapping, ensure_ascii=False), encoding="utf-8")
    (ASSETS / "catalog-provenance.json").write_text(json.dumps({
        "captured_at_ms": timestamp, "sources": [
            "https://movie.douban.com/top250?start={start}&filter=",
            "https://movie.douban.com/j/new_search_subjects?sort=S&range=0%2C10&tags=电视剧&start={start}",
        ], "top250": 250, "tv": 300, "posters": len(mapping), "missing_posters": len(covers) - len(mapping),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved 550 subjects, {len(mapping)}/{len(covers)} posters", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resume", action="store_true", help="Resume an interrupted capture from local page checkpoints.")
    main(resume=parser.parse_args().resume)
