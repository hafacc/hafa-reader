import { beforeEach, describe, expect, test } from "bun:test";
import { IDBFactory } from "fake-indexeddb";
import { Database } from "./db.ts";
import { sampleEntry, sampleFeed } from "./sync/fixtures.ts";
import { stateKey } from "./sync/state.ts";

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});

function request<Value>(pending: IDBRequest<Value>): Promise<Value> {
  return new Promise((resolve, reject) => {
    pending.onsuccess = (): void => resolve(pending.result);
    pending.onerror = (): void => reject(pending.error);
  });
}

/** Write a database from before the baseline, holding a feed, and sync progress for an account. */
async function makeOlderVersion(): Promise<void> {
  const opening = indexedDB.open("h-reader", 5);
  opening.onupgradeneeded = (): void => {
    opening.result.createObjectStore("feeds", { keyPath: "id" });
    opening.transaction?.objectStore("feeds").put(sampleFeed());
  };
  const db = await request(opening);
  db.close();
  localStorage.setItem(stateKey("account"), '{"merged":true}');
}

describe("Database", () => {
  test("a database from before the baseline opens empty and works, and syncs again from the start", async () => {
    await makeOlderVersion();
    const db = await Database.open();
    expect(await db.loadAll()).toEqual({ feeds: [], entries: [] });
    expect(localStorage.getItem(stateKey("account"))).toBeNull();
    await db.putEntries([sampleEntry()], new Map([["entry-1", "body"]]));
    await db.putTombstones(new Map([["feed:a", 1]]));
    expect((await db.loadAll()).entries).toEqual([sampleEntry()]);
    expect(await db.loadTombstones()).toEqual(new Map([["feed:a", 1]]));
    db.close();
  });

  test("a later upgrade step runs once and keeps the data", async () => {
    const baseline = await Database.open();
    await baseline.putFeed(sampleFeed());
    await baseline.putEntries([sampleEntry()], new Map([["entry-1", "body"]]));
    baseline.close();
    let runs = 0;
    const upgraded = await Database.open(7, {
      7: (): void => {
        runs += 1;
      },
    });
    expect(runs).toBe(1);
    expect(await upgraded.loadAll()).toEqual({
      feeds: [sampleFeed()],
      entries: [sampleEntry()],
    });
    expect(await upgraded.getBody("entry-1")).toBe("body");
    upgraded.close();
    await Database.open(7, {
      7: (): void => {
        runs += 1;
      },
    });
    expect(runs).toBe(1);
  });

  test("entries keep their bodies, and removing a feed removes both", async () => {
    const db = await Database.open();
    const kept = sampleEntry({ id: "kept", feedId: "other" });
    const gone = sampleEntry({ id: "gone" });
    await db.putFeed(sampleFeed());
    await db.putEntries(
      [kept, gone],
      new Map([
        ["kept", "<p>kept</p>"],
        ["gone", "<p>gone</p>"],
      ]),
    );
    expect(await db.getBody("gone")).toBe("<p>gone</p>");
    await db.deleteFeed("feed-1");
    const { feeds, entries } = await db.loadAll();
    expect(feeds).toEqual([]);
    expect(entries.map((entry) => entry.id)).toEqual(["kept"]);
    expect(await db.getBody("gone")).toBe("");
    expect(await db.getBody("kept")).toBe("<p>kept</p>");
  });

  test("tombstones are recorded and forgotten", async () => {
    const db = await Database.open();
    await db.putTombstones(
      new Map([
        ["feed:a", 1],
        ["settings:all", 2],
      ]),
    );
    await db.putTombstones(new Map([["feed:a", undefined]]));
    expect(await db.loadTombstones()).toEqual(new Map([["settings:all", 2]]));
  });

  test("clear removes everything", async () => {
    const db = await Database.open();
    await db.putFeed(sampleFeed());
    await db.putEntries([sampleEntry()], new Map([["entry-1", "x"]]));
    await db.putTombstones(new Map([["feed:a", 1]]));
    await db.clear();
    expect(await db.loadAll()).toEqual({ feeds: [], entries: [] });
    expect(await db.getBody("entry-1")).toBe("");
    expect(await db.loadTombstones()).toEqual(new Map());
  });
});
