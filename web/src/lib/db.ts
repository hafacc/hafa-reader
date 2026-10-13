import type { Entry, Feed, SavedSettings } from "./types.ts";
import { upgradeHttp } from "./urls.ts";

const DB_NAME = "h-reader";
const DB_VERSION = 5;

function settle<Value>(request: IDBRequest<Value>): Promise<Value> {
  return new Promise((resolve, reject) => {
    request.onsuccess = (): void => resolve(request.result);
    request.onerror = (): void => reject(request.error);
  });
}

function finish(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = (): void => resolve();
    transaction.onerror = (): void => reject(transaction.error);
    transaction.onabort = (): void => reject(transaction.error);
  });
}

function hasNumber(row: Record<string, unknown>, field: string): boolean {
  return typeof row[field] === "number";
}

/** Rewrite each of a store's rows that `fill` changes, keeping everything else. */
function stamp(
  store: IDBObjectStore,
  fill: (row: Record<string, unknown>) => Record<string, unknown>,
): void {
  const cursorRequest = store.openCursor();
  cursorRequest.onsuccess = (): void => {
    const cursor = cursorRequest.result;
    if (cursor) {
      const value: unknown = cursor.value;
      if (typeof value === "object" && value !== null) {
        const row: Record<string, unknown> = { ...value };
        const filled = fill(row);
        if (JSON.stringify(filled) !== JSON.stringify(row)) {
          cursor.update(filled);
        }
      }
      cursor.continue();
    }
  };
}

function positive(value: unknown): number | undefined {
  return typeof value === "number" && value > 0 ? value : undefined;
}

/**
 * Bring older rows up to date: a change time (version 3: when added or
 * fetched, else now), an entry's place in its feed (version 4: unknown, so
 * 0), and plain http addresses upgraded to https (version 5), ids kept.
 */
function stampRows(transaction: IDBTransaction, now: number): void {
  stamp(transaction.objectStore("entries"), (row) => ({
    ...row,
    bodyHash: typeof row["bodyHash"] === "string" ? row["bodyHash"] : "",
    updatedAt: hasNumber(row, "updatedAt")
      ? row["updatedAt"]
      : (positive(row["addedAt"]) ?? now),
    position: hasNumber(row, "position") ? row["position"] : 0,
    url: upgradeHttp(row["url"]),
    imageUrl: upgradeHttp(row["imageUrl"]),
  }));
  stamp(transaction.objectStore("feeds"), (row) => ({
    ...row,
    updatedAt: hasNumber(row, "updatedAt")
      ? row["updatedAt"]
      : (positive(row["lastFetched"]) ?? now),
    url: upgradeHttp(row["url"]),
    siteUrl: upgradeHttp(row["siteUrl"]),
    icon: upgradeHttp(row["icon"]),
  }));
  stamp(transaction.objectStore("settings"), (row) => ({
    ...row,
    updatedAt: hasNumber(row, "updatedAt") ? row["updatedAt"] : now,
  }));
}

/** The reader's local database: feeds, entries, article bodies, view settings and tombstones. */
export class Database {
  private readonly db: IDBDatabase;

  private constructor(db: IDBDatabase) {
    this.db = db;
  }

  /** Open the database, creating it on first use. */
  static open(): Promise<Database> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = (): void => {
        const db = request.result;
        if (!db.objectStoreNames.contains("feeds")) {
          db.createObjectStore("feeds", { keyPath: "id" });
          const entries = db.createObjectStore("entries", { keyPath: "id" });
          entries.createIndex("feedId", "feedId");
          db.createObjectStore("bodies");
        }
        if (!db.objectStoreNames.contains("settings")) {
          db.createObjectStore("settings");
        }
        if (!db.objectStoreNames.contains("tombstones")) {
          db.createObjectStore("tombstones");
        }
        const { transaction } = request;
        if (transaction) {
          stampRows(transaction, Date.now());
        }
      };
      request.onsuccess = (): void => {
        const db = request.result;
        // another tab opening a newer version waits until this one lets go;
        // this tab then reloads to run the new version too
        db.onversionchange = (): void => {
          db.close();
          location.reload();
        };
        resolve(new Database(db));
      };
      request.onerror = (): void => reject(request.error);
    });
  }

  /** Read every feed and every entry. */
  async loadAll(): Promise<{ feeds: Feed[]; entries: Entry[] }> {
    const transaction = this.db.transaction(["feeds", "entries"], "readonly");
    const feeds = settle<Feed[]>(transaction.objectStore("feeds").getAll());
    const entries = settle<Entry[]>(
      transaction.objectStore("entries").getAll(),
    );
    return { feeds: await feeds, entries: await entries };
  }

  /** Read the saved settings of every feed, group and the main views. */
  async loadSettings(): Promise<Record<string, SavedSettings>> {
    const transaction = this.db.transaction("settings", "readonly");
    const store = transaction.objectStore("settings");
    const keys = await settle<IDBValidKey[]>(store.getAllKeys());
    const values = await settle<SavedSettings[]>(store.getAll());
    return Object.fromEntries(
      keys.map((key, index) => [String(key), values[index]]),
    );
  }

  /** Save the settings for one feed, group or main view. */
  async putSettings(key: string, settings: SavedSettings): Promise<void> {
    const transaction = this.db.transaction("settings", "readwrite");
    transaction.objectStore("settings").put(settings, key);
    await finish(transaction);
  }

  /** Remove the settings of one feed, group or main view. */
  async deleteSettings(key: string): Promise<void> {
    const transaction = this.db.transaction("settings", "readwrite");
    transaction.objectStore("settings").delete(key);
    await finish(transaction);
  }

  /** Save a feed. */
  async putFeed(feed: Feed): Promise<void> {
    const transaction = this.db.transaction("feeds", "readwrite");
    transaction.objectStore("feeds").put(feed);
    await finish(transaction);
  }

  /** Remove a feed with all of its entries and bodies. */
  async deleteFeed(feedId: string): Promise<void> {
    const transaction = this.db.transaction(
      ["feeds", "entries", "bodies", "settings"],
      "readwrite",
    );
    transaction.objectStore("feeds").delete(feedId);
    transaction.objectStore("settings").delete(`feed:${feedId}`);
    const entries = transaction.objectStore("entries");
    const keys = await settle(entries.index("feedId").getAllKeys(feedId));
    for (const key of keys) {
      entries.delete(key);
      transaction.objectStore("bodies").delete(key);
    }
    await finish(transaction);
  }

  /** Save new entries together with their bodies. */
  async putEntries(
    entries: Entry[],
    bodies: ReadonlyMap<string, string>,
  ): Promise<void> {
    const transaction = this.db.transaction(["entries", "bodies"], "readwrite");
    for (const entry of entries) {
      transaction.objectStore("entries").put(entry);
      transaction
        .objectStore("bodies")
        .put(bodies.get(entry.id) ?? "", entry.id);
    }
    await finish(transaction);
  }

  /** Save changed entries, leaving their bodies alone. */
  async updateEntries(entries: Entry[]): Promise<void> {
    const transaction = this.db.transaction("entries", "readwrite");
    for (const entry of entries) {
      transaction.objectStore("entries").put(entry);
    }
    await finish(transaction);
  }

  /** Read an article body, or an empty string if there is none. */
  async getBody(entryId: string): Promise<string> {
    const transaction = this.db.transaction("bodies", "readonly");
    const body = await settle<string | undefined>(
      transaction.objectStore("bodies").get(entryId),
    );
    return body ?? "";
  }

  /** Save a body for an entry, as when it is downloaded. */
  async putBody(entryId: string, body: string): Promise<void> {
    const transaction = this.db.transaction("bodies", "readwrite");
    transaction.objectStore("bodies").put(body, entryId);
    await finish(transaction);
  }

  /** Remove everything stored. */
  async clear(): Promise<void> {
    const names = ["feeds", "entries", "bodies", "settings", "tombstones"];
    const transaction = this.db.transaction(names, "readwrite");
    for (const name of names) {
      transaction.objectStore(name).clear();
    }
    await finish(transaction);
  }

  /** Read every tombstone: when each removed feed or setting was removed. */
  async loadTombstones(): Promise<Map<string, number>> {
    const transaction = this.db.transaction("tombstones", "readonly");
    const store = transaction.objectStore("tombstones");
    const keys = await settle<IDBValidKey[]>(store.getAllKeys());
    const values = await settle<number[]>(store.getAll());
    return new Map(keys.map((key, index) => [String(key), values[index] ?? 0]));
  }

  /** Record removals, or forget them when the time is undefined. */
  async putTombstones(changes: Map<string, number | undefined>): Promise<void> {
    const transaction = this.db.transaction("tombstones", "readwrite");
    const store = transaction.objectStore("tombstones");
    for (const [key, updatedAt] of changes) {
      if (updatedAt === undefined) {
        store.delete(key);
      } else {
        store.put(updatedAt, key);
      }
    }
    await finish(transaction);
  }
}
