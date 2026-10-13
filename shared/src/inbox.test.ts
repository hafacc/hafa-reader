import { expect, test } from "bun:test";
import type { FeedEntry } from "./feed.ts";
import {
  drainPage,
  INBOX_ENTRY_LIMIT,
  type InboxRecord,
  mergePulled,
} from "./inbox.ts";
import type { PulledFeed } from "./protocol.ts";

function entry(guid: string): FeedEntry {
  return {
    guid,
    title: guid,
    url: "",
    published: 0,
    author: "",
    imageUrl: "",
    summary: "",
    categories: [],
    html: "",
  };
}

function pulled(title: string, guids: string[]): PulledFeed {
  return {
    feedId: "feed",
    feed: { title, siteUrl: "", iconUrl: "", entries: guids.map(entry) },
    etag: title,
    lastModified: "",
  };
}

test("a feed pulled again keeps the waiting entries it no longer shows", () => {
  const merged = mergePulled(
    pulled("old", ["b", "a"]),
    pulled("new", ["c", "b"]),
  );
  expect(merged.feed.title).toBe("new");
  expect(merged.etag).toBe("new");
  expect(merged.feed.entries.map((item) => item.guid)).toEqual(["c", "b", "a"]);
  expect(mergePulled(undefined, pulled("new", ["c"]))).toEqual(
    pulled("new", ["c"]),
  );
});

test("a waiting feed keeps at most the newest entries", () => {
  const many = Array.from(
    { length: INBOX_ENTRY_LIMIT },
    (_, index) => `old${index}`,
  );
  const merged = mergePulled(pulled("old", many), pulled("new", ["fresh"]));
  expect(merged.feed.entries).toHaveLength(INBOX_ENTRY_LIMIT);
  expect(merged.feed.entries[0]?.guid).toBe("fresh");
});

test("a drain loads and hands over the oldest records that fit, and at least one", async () => {
  const records = new Map<string, InboxRecord>(
    [3, 1, 2].map((seq) => [
      `feed${seq}`,
      { seq, pulled: pulled(`t${seq}`, ["a"]) },
    ]),
  );
  const seqs = Object.fromEntries(
    [...records].map(([feedId, record]) => [feedId, record.seq]),
  );
  const loaded: string[] = [];
  function load(feedId: string): Promise<InboxRecord | undefined> {
    loaded.push(feedId);
    return Promise.resolve(records.get(feedId));
  }
  const size = JSON.stringify(records.get("feed1")).length;
  const page = await drainPage(seqs, load, 2 * size);
  expect(page.records.map((record) => record.seq)).toEqual([1, 2]);
  expect(page.more).toBe(true);
  expect(loaded).toEqual(["feed1", "feed2", "feed3"]);
  const one = await drainPage(seqs, load, 1);
  expect(one.records.map((record) => record.seq)).toEqual([1]);
  expect((await drainPage(seqs, load, 10 * size)).more).toBe(false);
  expect(await drainPage({}, load, 10)).toEqual({ records: [], more: false });
  expect(
    (await drainPage({ gone: 1, ...seqs }, load, 10 * size)).records,
  ).toHaveLength(3);
});

test("a waiting feed drops carried-over entries to stay under the size limit", () => {
  const waiting = pulled("old", ["b", "a"]);
  const fresh = pulled("new", ["c"]);
  const whole = mergePulled(waiting, fresh);
  const limit = JSON.stringify(whole).length - 5;
  const trimmed = mergePulled(waiting, fresh, limit);
  expect(trimmed.feed.entries.map((item) => item.guid)).toEqual(["c", "b"]);
  expect(JSON.stringify(trimmed).length).toBeLessThanOrEqual(limit);
  expect(
    mergePulled(waiting, fresh, 1).feed.entries.map((item) => item.guid),
  ).toEqual(["c"]);
});
