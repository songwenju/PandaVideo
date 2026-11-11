const test = require("node:test");
const assert = require("node:assert/strict");
const { ListPagination, PAGE_SIZES } = require("../static/list-pagination.js");

function source(total, nativeSize, calls) {
  return async (start) => {
    calls.push(start);
    return {
      items: Array.from({ length: Math.min(nativeSize, Math.max(0, total - start)) },
        (_, index) => ({ title: `Film ${start + index + 1}`, rank: start + index + 1 })),
      has_more: start + nativeSize < total, fetched_at: 123, label: "Top",
    };
  };
}

test("all selectable sizes cover Top 250 exactly once and stop on the last page", async () => {
  for (const limit of PAGE_SIZES) {
    const pagination = new ListPagination();
    const calls = [];
    const fetch = source(250, 25, calls);
    const ranks = [];
    for (let start = 0; start < 250; start += limit) {
      const page = await pagination.read({ key: "top250", start, limit, nativeSize: 25, fixedTotal: 250 }, fetch);
      assert.equal(page.total_pages, Math.ceil(250 / limit));
      assert.equal(page.items.length, Math.min(limit, 250 - start));
      assert.equal(page.has_more, start + limit < 250);
      ranks.push(...page.items.map(item => item.rank));
    }
    assert.deepEqual(ranks, Array.from({ length: 250 }, (_, index) => index + 1));
    assert.deepEqual(calls, Array.from({ length: 10 }, (_, index) => index * 25));
  }
});

test("unknown totals become known at the terminal source page and survive size changes", async () => {
  const pagination = new ListPagination();
  const calls = [];
  const fetch = source(47, 20, calls);
  const options = { key: "finance", limit: 25, nativeSize: 20 };
  const first = await pagination.read({ ...options, start: 0 }, fetch);
  assert.equal(first.total_pages, null);
  assert.equal(first.items.length, 25);
  const second = await pagination.read({ ...options, start: 25 }, fetch);
  assert.equal(second.total_pages, 2);
  assert.equal(second.items.length, 22);
  assert.equal(second.has_more, false);
  assert.deepEqual([...first.items, ...second.items].map(item => item.rank),
    Array.from({ length: 47 }, (_, index) => index + 1));
  const resized = await pagination.read({ ...options, start: 0, limit: 10 }, fetch);
  assert.equal(resized.total_pages, 5);
  assert.deepEqual(calls, [0, 20, 40]);
});

test("filtered source blocks retain every rated item across mismatched page boundaries", async () => {
  const pagination = new ListPagination();
  const fetch = async (start) => ({
    items: Array.from({ length: start < 40 ? 13 : 7 }, (_, index) => ({ title: `${start}:${index}` })),
    has_more: start < 40, fetched_at: 123,
  });
  const names = [];
  for (const start of [0, 25]) {
    const page = await pagination.read({ key: "filtered", start, limit: 25, nativeSize: 20 }, fetch);
    names.push(...page.items.map(item => item.title));
  }
  assert.equal(names.length, 33);
  assert.equal(new Set(names).size, 33);
});

test("an unrated full source page does not falsely indicate the end", async () => {
  const pagination = new ListPagination();
  const page = await pagination.read({ key: "unrated", start: 0, limit: 10, nativeSize: 20 },
    async () => ({ items: [], has_more: true, fetched_at: 123 }));
  assert.equal(page.items.length, 0);
  assert.equal(page.total, null);
  assert.equal(page.has_more, true);
});

test("a partial last page exposes the exact count without extra requests", async () => {
  const calls = [];
  const page = await new ListPagination().read({ key: "short", start: 0, limit: 100, nativeSize: 20 },
    source(7, 20, calls));
  assert.equal(page.total_pages, 1);
  assert.equal(page.total, 7);
  assert.equal(page.has_more, false);
  assert.deepEqual(calls, [0]);
});

test("upstream failures and aborted reads never populate the page cache", async () => {
  const pagination = new ListPagination();
  const options = { key: "error", start: 0, limit: 25, nativeSize: 25 };
  await assert.rejects(pagination.read(options, async () => { throw new Error("offline"); }));
  const controller = new AbortController();
  await assert.rejects(pagination.read({ ...options, signal: controller.signal }, async () => {
    controller.abort();
    return { items: [], has_more: false, fetched_at: 123 };
  }), { name: "AbortError" });
  const calls = [];
  const result = await pagination.read(options, source(5, 25, calls));
  assert.equal(result.items.length, 5);
  assert.deepEqual(calls, [0]);
});

test("cached counts expire with source pages", async () => {
  let now = 0;
  const pagination = new ListPagination(() => now);
  const options = { key: "changing", start: 0, limit: 25, nativeSize: 20 };
  const calls = [];
  assert.equal((await pagination.read(options, source(7, 20, calls))).total, 7);
  now += 3600001;
  const result = await pagination.read(options, source(40, 20, calls));
  assert.equal(result.total, 40);
  assert.equal(result.items.length, 25);
  assert.deepEqual(calls, [0, 0, 20]);
});

test("invalid size and misaligned offsets are rejected before source access", async () => {
  for (const options of [{ limit: 17, start: 0 }, { limit: 25, start: -25 }, { limit: 25, start: 10 }]) {
    await assert.rejects(new ListPagination().read({ key: "invalid", nativeSize: 25, ...options },
      () => assert.fail("Source should not be called")));
  }
});
