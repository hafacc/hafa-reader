import { forgetAllStates } from "./sync/state.ts";
import type { Entry, Feed, SavedSettings } from "./types.ts";

const DB_NAME = "h-reader";
/** The baseline schema's version; a database from before it is emptied and made anew. */
const BASELINE_VERSION = 6;
/** The schema version; raise it together with a new step in `UPGRADES`. */
const DB_VERSION = 6;

/** A schema change from the version before. */
type UpgradeStep = (db: IDBDatabase, transaction: IDBTransaction) => void;

/**
 * Steps that bring a stored database up to date, keyed by the version each
 * one reaches. To change the schema, raise `DB_VERSION` and add a step for the
 * new version that changes stores or rewrites rows in place, keeping what the
 * person has stored.
 */
const UPGRADES: Readonly<Record<number, UpgradeStep>> = {};

/** The baseline schema, replacing whatever stores an older database had. */
function createBaseline(db: IDBDatabase): void {
  for (const name of Array.from(db.objectStoreNames)) {
    db.deleteObjectStore(name);
  }
  db.createObjectStore("feeds", { keyPath: "id" });
  db.createObjectStore("entries", { keyPath: "id" }).createIndex(
    "feedId",
    "feedId",
  );
  db.createObjectStore("bodies");
  db.createObjectStore("settings");
  db.createObjectStore("tombstones");
}

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

/** The reader's local database: feeds, entries, article bodies, view settings and tombstones. */
export class Database {
  private readonly db: IDBDatabase;

  private constructor(db: IDBDatabase) {
    this.db = db;
  }

  /**
   * Open the database: created on first use, emptied and made anew when older
   * than the baseline, and otherwise brought up to date by the upgrade steps
   * it has not had.
   */
  static open(
    version = DB_VERSION,
    upgrades: Readonly<Record<number, UpgradeStep>> = UPGRADES,
  ): Promise<Database> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, version);
      request.onupgradeneeded = (event): void => {
        const db = request.result;
        const { transaction } = request;
        if (event.oldVersion < BASELINE_VERSION) {
          if (event.oldVersion > 0) {
            // its data is gone, so each signed-in account syncs again from the start
            forgetAllStates();
          }
          createBaseline(db);
        }
        for (
          let step = Math.max(event.oldVersion, BASELINE_VERSION) + 1;
          step <= version;
          step += 1
        ) {
          if (transaction !== null) {
            upgrades[step]?.(db, transaction);
          }
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

  /** Close the connection. */
  close(): void {
    this.db.close();
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
