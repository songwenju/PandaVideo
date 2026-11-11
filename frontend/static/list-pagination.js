/* Combine fixed-size source pages without losing items at page boundaries. */
(() => {
  const PAGE_SIZES = [10, 20, 25, 50, 100];

  class ListPagination {
    constructor(clock = () => Date.now()) {
      this.clock = clock;
      this.cache = new Map();
      this.totals = new Map();
    }

    async read({ key, start, limit, nativeSize, fixedTotal = null, signal }, fetchPage) {
      if (!PAGE_SIZES.includes(limit) || start < 0 || start % limit) {
        throw new Error("无效的每页条数或页码");
      }
      const known = this.totals.get(key);
      let total = fixedTotal ?? (known && this.clock() - known.time < 3600000 ? known.value : null);
      const first = Math.floor(start / nativeSize) * nativeSize;
      const end = Math.min(start + limit, total ?? Infinity, 10000 + nativeSize);
      const items = [];
      let metadata;
      let hasMore = false;
      for (let offset = first; offset < end; offset += nativeSize) {
        signal?.throwIfAborted();
        const cacheKey = `${key}:${offset}`;
        let entry = this.cache.get(cacheKey);
        if (!entry || this.clock() - entry.time >= 3600000) {
          const data = await fetchPage(offset, signal);
          signal?.throwIfAborted();
          if (!Array.isArray(data.items)) throw new Error("列表数据格式异常");
          entry = { data, time: this.clock() };
          this.cache.set(cacheKey, entry);
          if (this.cache.size > 1000) this.cache.delete(this.cache.keys().next().value);
        }
        const data = entry.data;
        metadata = metadata ? {
          ...metadata,
          stale: metadata.stale || data.stale,
          fetched_at: Math.min(metadata.fetched_at, data.fetched_at),
        } : data;
        // Missing ratings are already filtered upstream. Keep each source block
        // in its original slot range so changing page sizes cannot skip a film.
        data.items.forEach((item, index) => {
          const position = offset + index;
          if (position >= start && position < end) {
            items.push({ ...item, list_rank: position + 1 });
          }
        });
        if (Number.isInteger(data.total) && data.total >= 0 && fixedTotal === null) {
          total = data.total;
        }
        if (!data.has_more) {
          if (fixedTotal === null) total = offset + data.items.length;
          hasMore = start + limit < total;
          break;
        }
        hasMore = start + limit < offset + nativeSize || data.has_more;
      }
      if (total !== null) {
        this.totals.set(key, { value: total, time: this.clock() });
        hasMore = start + limit < total;
      }
      if (start + limit >= 10000 + nativeSize) hasMore = false;
      return {
        ...metadata, items, start, page_size: limit,
        total, total_pages: total === null ? null : Math.ceil(total / limit),
        has_more: hasMore,
      };
    }
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { ListPagination, PAGE_SIZES };
  } else {
    window.DoubanPagination = { ListPagination, PAGE_SIZES };
  }
})();
