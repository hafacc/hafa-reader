import { beforeEach, describe, expect, test } from "bun:test";
import { IDBFactory } from "fake-indexeddb";
import { Database } from "./db.ts";
import { sampleEntry, sampleFeed } from "./sync/fixtures.ts";

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});

function request<Value>(pending: IDBRequest<Value>): Promise<Value> {
  return new Promise((resolve, reject) => {
    pending.onsuccess = (): void => resolve(pending.result);
    pending.onerror = (): void => reject(pending.error);
  });
}

/** Write a version 2 database, from before sync: rows have no `updatedAt` or `bodyHash`. */
async function makeVersionTwo(): Promise<void> {
  const opening = indexedDB.open("h-reader", 2);
  opening.onupgradeneeded = (): void => {
    const db = opening.result;
    db.createObjectStore("feeds", { keyPath: "id" });
    db.createObjectStore("entries", { keyPath: "id" }).createIndex(
      "feedId",
      "feedId",
    );
    db.createObjectStore("bodies");
    db.createObjectStore("settings");
    const { transaction } = opening;
    const { updatedAt: _feedTime, ...feed } = sampleFeed({ lastFetched: 77 });
    transaction?.objectStore("feeds").put(feed);
    const {
      updatedAt: _entryTime,
      bodyHash: _hash,
      ...entry
    } = sampleEntry({
      addedAt: 55,
    });
    transaction?.objectStore("entries").put(entry);
    transaction
      ?.objectStore("settings")
      .put(
        { sort: "oldest", hideWords: [], onlyWords: [], offTopics: [] },
        "all",
      );
  };
  const db = await request(opening);
  db.close();
}

/** Write a version 3 database, before entries kept their place in the feed. */
async function makeVersionThree(): Promise<void> {
  const opening = indexedDB.open("h-reader", 3);
  opening.onupgradeneeded = (): void => {
    const db = opening.result;
    db.createObjectStore("feeds", { keyPath: "id" });
    db.createObjectStore("entries", { keyPath: "id" }).createIndex(
      "feedId",
      "feedId",
    );
    db.createObjectStore("bodies");
    db.createObjectStore("settings");
    db.createObjectStore("tombstones");
    const { position: _position, ...entry } = sampleEntry({
      updatedAt: 123,
      read: true,
      bodyHash: "abc",
    });
    opening.transaction?.objectStore("entries").put(entry);
  };
  const db = await request(opening);
  db.close();
}

/** Write a version 4 database, before addresses were https only. */
async function makeVersionFour(): Promise<void> {
  const opening = indexedDB.open("h-reader", 4);
  opening.onupgradeneeded = (): void => {
    const db = opening.result;
    db.createObjectStore("feeds", { keyPath: "id" });
    db.createObjectStore("entries", { keyPath: "id" }).createIndex(
      "feedId",
      "feedId",
    );
    db.createObjectStore("bodies");
    db.createObjectStore("settings");
    db.createObjectStore("tombstones");
    opening.transaction?.objectStore("feeds").put(
      sampleFeed({
        url: "http://old.example/rss",
        siteUrl: "http://old.example/",
        icon: "data:image/png;base64,AAAA",
      }),
    );
    opening.transaction
      ?.objectStore("entries")
      .put(sampleEntry({ url: "http://old.example/1", imageUrl: "" }));
  };
  const db = await request(opening);
  db.close();
}

describe("Database", () => {
  test("the version 5 upgrade moves plain http to https and keeps ids", async () => {
    await makeVersionFour();
    const db = await Database.open();
    const { feeds, entries } = await db.loadAll();
    expect(feeds).toEqual([
      sampleFeed({
        url: "https://old.example/rss",
        siteUrl: "https://old.example/",
        icon: "data:image/png;base64,AAAA",
      }),
    ]);
    expect(entries).toEqual([
      sampleEntry({ url: "https://old.example/1", imageUrl: "" }),
    ]);
  });

  test("the version 4 upgrade gives entries a place and keeps the rest", async () => {
    await makeVersionThree();
    const db = await Database.open();
    const { entries } = await db.loadAll();
    expect(entries).toEqual([
      sampleEntry({ updatedAt: 123, read: true, bodyHash: "abc", position: 0 }),
    ]);
  });

  test("the version 3 upgrade stamps change times and adds tombstones", async () => {
    await makeVersionTwo();
    const before = Date.now();
    const db = await Database.open();
    const { feeds, entries } = await db.loadAll();
    expect(feeds[0]?.updatedAt).toBe(77);
    expect(entries[0]?.updatedAt).toBe(55);
    expect(entries[0]?.bodyHash).toBe("");
    expect(entries[0]?.position).toBe(0);
    const settings = await db.loadSettings();
    expect(Object.keys(settings)).toEqual(["all"]);
    expect(Object.values(settings)[0]?.updatedAt).toBeGreaterThanOrEqual(
      before,
    );
    expect(await db.loadTombstones()).toEqual(new Map());
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
