"""Preserve available Douban category pages from a local PandaVideo website as JSON."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import time
from urllib.parse import urlencode, quote
from urllib.request import urlopen

TYPES = {"movie": "电影", "tv": "电视剧", "animation": "动画", "documentary": "纪录片"}
GENRES = {"all": "", "comedy": "喜剧", "nature": "自然", "drama": "剧情",
          "action": "动作", "romance": "爱情", "scifi": "科幻", "mystery": "悬疑",
          "finance": "金融", "biography": "传记", "war": "战争", "hollywood": "美国,大片"}
CATEGORIES = {
    "tv": ("tv", "all"), "comedy": ("movie", "comedy"), "anime": ("animation", "all"),
    "nature": ("documentary", "nature"), "movie": ("movie", "all"),
    "documentary": ("documentary", "all"),
    **{key: ("movie", key) for key in GENRES if key not in ("all", "comedy", "nature")},
}


def source_key(media_type, genre, start, minimum, sort="rating"):
    tags = ",".join(filter(None, [TYPES[media_type], GENRES[genre]]))
    return "https://movie.douban.com/j/new_search_subjects?" + urlencode(
        {"sort": {"rating": "S", "hot": "U", "latest": "R"}[sort],
         "range": f"{minimum},10", "tags": tags, "start": str(start)},
        quote_via=quote,
    )


def capture(base, category, selection, sort="rating"):
    media_type, genre = selection
    pages, report = {}, {"type": media_type, "genre": genre, "sort": sort, "pages": 0,
                        "items": 0, "high_rated_items": 0}
    seen = set()
    high_prefix = sort == "rating"
    for start in range(0, 10001, 20):
        address = base.rstrip("/") + "/api/douban/browse?" + urlencode({
            "type": media_type, "genre": genre, "year": "all", "sort": sort, "start": start,
        })
        try:
            with urlopen(address, timeout=40) as response:
                data = json.load(response)
            if data.get("stale"):
                raise ValueError("website returned stale data")
            if data["start"] != start or data["page_size"] != 20:
                raise ValueError("unexpected pagination")
            items = []
            for row in data["items"]:
                score = row.get("rating")
                if not row.get("title") or not row.get("url", "").startswith("https://movie.douban.com/subject/"):
                    raise ValueError("invalid subject identity")
                if score is None or not 0 < float(score) <= 10:
                    raise ValueError("invalid rating")
                items.append({key: row[key] for key in ("title", "url", "year", "rating", "cover", "casts")
                              if key in row} | {"kind": media_type})
            identities = {item["url"] for item in items}
            if identities and identities.issubset(seen):
                raise ValueError("source repeated an earlier page")
            seen.update(identities)
            saved_at = int(data["fetched_at"] * 1000)
            page = {"items": items, "start": start, "page_size": 20,
                    "has_more": data["has_more"], "label": data.get("label", ""), "stale": False}
            pages[source_key(media_type, genre, start, 0, sort)] = {"page": page, "savedAt": saved_at}
            high = [item for item in items if item["rating"] >= 8]
            # Rating ordering makes the 8+ prefix share the upstream offset. Keep its cursor,
            # and stop at the first lower score; never recycle Top250 to fill unavailable rows.
            more = bool(data["has_more"] and len(high) == len(items) and items)
            if high_prefix and high:
                entry = {"page": page | {"items": high, "has_more": more}, "savedAt": saved_at}
                pages[source_key(media_type, genre, start, 8)] = entry
                if media_type == "tv" and genre == "all" and start < 300:
                    pages[f"tv:{start}"] = entry
            high_prefix = high_prefix and more
            report["pages"] += 1
            report["items"] += len(items)
            report["high_rated_items"] += len(high)
            print(f"{category}: offset={start}, high-rated={len(high)}", flush=True)
            if not data["has_more"]:
                report["stopped"] = "source exhausted"
                break
        except Exception as error:
            report["stopped"] = str(error)
            report["failed_start"] = start
            print(f"{category}: stopped at {start}: {error}", flush=True)
            break
    else:
        report["stopped"] = "upstream offset limit reached"
    return pages, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8766")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    pages, coverage = {}, {}
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = {name: pool.submit(capture, args.base, name, selection)
                   for name, selection in CATEGORIES.items()}
        futures.update({f"{media_type}:{sort}": pool.submit(
            capture, args.base, f"{media_type}:{sort}", (media_type, "all"), sort,
        ) for media_type in TYPES for sort in ("hot", "latest")})
        for name, future in futures.items():
            result, coverage[name] = future.result()
            pages.update(result)
    if not pages:
        raise SystemExit("No verified website pages available; previous snapshot preserved")
    retained = {}
    if args.output.exists():
        previous = json.loads(args.output.read_text(encoding="utf-8"))
        if previous.get("source") == args.base:
            # A temporary site failure must not delete previously verified pages. Each
            # retained entry keeps its original timestamp, so it remains expired fallback.
            retained = {key: entry for key, entry in previous.get("pages", {}).items()
                        if key not in pages}
    document = {"schema_version": 1, "captured_at": int(time.time() * 1000),
                "source": args.base, "coverage": coverage,
                "retained_previous_keys": list(retained), "pages": retained | pages}
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {len(pages)} cache keys to {args.output}", flush=True)


if __name__ == "__main__":
    main()
