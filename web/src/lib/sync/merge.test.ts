import { describe, expect, test } from "bun:test";
import { entryDocument } from "./documents.ts";
import { sampleEntry } from "./fixtures.ts";
import {
  decide,
  droppedFeedIds,
  localVersion,
  mergeEntries,
  mergeRecords,
  subscribedEntries,
  tombstonesOf,
  unknownRemovals,
  type Version,
} from "./merge.ts";

describe("decide", () => {
  test("newer wins", () => {
    const old = { updatedAt: 1, deleted: false };
    const fresh = { updatedAt: 2, deleted: false };
    expect(decide(fresh, old)).toBe("push");
    expect(decide(old, fresh)).toBe("pull");
  });

  test("equal times change nothing, so pulled changes are not sent back", () => {
    const version = { updatedAt: 1, deleted: false };
    expect(decide(version, { ...version })).toBe("keep");
  });

  test("records only on one side", () => {
    expect(decide(undefined, { updatedAt: 1, deleted: false })).toBe("pull");
    expect(decide({ updatedAt: 1, deleted: false }, undefined)).toBe("push");
    expect(decide(undefined, { updatedAt: 1, deleted: true })).toBe("keep");
    expect(decide({ updatedAt: 1, deleted: true }, undefined)).toBe("keep");
    expect(decide(undefined, undefined)).toBe("keep");
  });

  test("a newer unsubscribe beats an older subscription and back", () => {
    const tombstone = localVersion(undefined, 5);
    expect(decide(tombstone, { updatedAt: 4, deleted: false })).toBe("push");
    expect(decide(tombstone, { updatedAt: 6, deleted: false })).toBe("pull");
    expect(decide({ updatedAt: 4, deleted: false }, tombstone)).toBe("pull");
  });

  test("a record outranks its tombstone", () => {
    expect(localVersion({ updatedAt: 1 }, 9)).toEqual({
      updatedAt: 1,
      deleted: false,
    });
    expect(localVersion(undefined, undefined)).toBeUndefined();
  });
});

describe("mergeEntries", () => {
  test("adds new, updates older, pushes newer, skips equal", () => {
    const older = sampleEntry({ id: "older", updatedAt: 1, bodyHash: "h" });
    const newer = sampleEntry({ id: "newer", updatedAt: 9 });
    const same = sampleEntry({ id: "same", updatedAt: 3 });
    const local = new Map([older, newer, same].map((item) => [item.id, item]));
    const fresh = sampleEntry({ id: "fresh" });
    const result = mergeEntries(
      local,
      [
        [
          "older",
          entryDocument({ ...older, read: true, updatedAt: 2, bodyHash: "" }),
        ],
        ["newer", entryDocument({ ...newer, updatedAt: 1 })],
        ["same", entryDocument(same)],
        ["fresh", entryDocument(fresh)],
      ],
      new Set(),
    );
    expect(result.pull).toEqual([
      { ...older, read: true, updatedAt: 2 },
      fresh,
    ]);
    expect(result.push).toEqual([newer]);
  });

  test("a pulled update to a reactive local entry can be stored", () => {
    const plain = sampleEntry({ updatedAt: 1 });
    // stands in for Svelte's state proxy around a stored entry
    const reactive = new Proxy(
      { ...plain, categories: new Proxy([...plain.categories], {}) },
      {},
    );
    const result = mergeEntries(
      new Map([[plain.id, reactive]]),
      [[plain.id, entryDocument({ ...plain, read: true, updatedAt: 2 })]],
      new Set(),
    );
    const [pulled] = result.pull;
    expect(pulled?.read).toBe(true);
    expect(() => structuredClone(pulled)).not.toThrow();
  });

  test("an account entry beats one this device has only just found", () => {
    const found = sampleEntry({ updatedAt: 0 });
    const result = mergeEntries(
      new Map([[found.id, found]]),
      [[found.id, entryDocument({ ...found, read: true, updatedAt: 5 })]],
      new Set(),
    );
    expect(result.pull).toEqual([{ ...found, read: true, updatedAt: 5 }]);
    expect(result.push).toEqual([]);
  });

  test("skips new entries of unsubscribed feeds", () => {
    const result = mergeEntries(
      new Map(),
      [["gone", entryDocument(sampleEntry({ feedId: "gone-feed" }))]],
      droppedFeedIds(["feed:gone-feed", "settings:all"]),
    );
    expect(result.pull).toEqual([]);
  });
});

describe("mergeRecords", () => {
  function version(updatedAt: number, deleted = false): Version {
    return { updatedAt, deleted };
  }

  test("pulls newer, pushes newer records and removals", () => {
    const local = new Map([
      ["old", { updatedAt: 1 }],
      ["new", { updatedAt: 5 }],
    ]);
    const tombstones = new Map([["gone", 9]]);
    const result = mergeRecords(local, tombstones, [
      ["old", version(2)],
      ["new", version(3)],
      ["gone", version(4)],
      ["elsewhere", version(1, true)],
    ]);
    expect(result.pull).toEqual([["old", version(2)]]);
    expect(result.push).toEqual([["new", { updatedAt: 5 }]]);
    expect(result.pushRemoved).toEqual([["gone", 9]]);
  });

  test("a complete listing sends records the account lacks", () => {
    const local = new Map([
      ["listed", { updatedAt: 1 }],
      ["missing", { updatedAt: 1 }],
    ]);
    const result = mergeRecords(
      local,
      new Map(),
      [["listed", version(1)]],
      new Set(["listed"]),
    );
    expect(result.push).toEqual([["missing", { updatedAt: 1 }]]);
  });

  test("unknownRemovals keeps account removals this device never had", () => {
    const removals = unknownRemovals(
      new Set(["kept"]),
      new Map([["known", 1]]),
      [
        ["kept", version(2, true)],
        ["known", version(3, true)],
        ["live", version(4)],
        ["never", version(5, true)],
      ],
    );
    expect([...removals]).toEqual([["never", 5]]);
  });

  test("tombstonesOf strips the prefix", () => {
    const found = tombstonesOf(
      new Map([
        ["feed:a", 1],
        ["settings:all", 2],
      ]),
      "feed:",
    );
    expect([...found]).toEqual([["a", 1]]);
  });
});

test("only entries of feeds still subscribed are uploaded", () => {
  const kept = sampleEntry({ id: "kept", feedId: "live" });
  const removed = sampleEntry({ id: "removed", feedId: "gone" });
  const orphan = sampleEntry({ id: "orphan", feedId: "unknown" });
  expect(
    subscribedEntries([kept, removed, orphan], {
      feeds: [{ id: "live" }, { id: "gone" }],
      tombstones: new Map([["feed:gone", 5]]),
    }),
  ).toEqual([kept]);
});
