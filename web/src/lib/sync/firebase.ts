import { FirebaseError, initializeApp } from "firebase/app";
import {
  type Auth,
  browserLocalPersistence,
  browserPopupRedirectResolver,
  deleteUser,
  GoogleAuthProvider,
  indexedDBLocalPersistence,
  initializeAuth,
  onAuthStateChanged,
  reauthenticateWithPopup,
  signInWithPopup,
  signOut,
  type User,
} from "firebase/auth";
import {
  type CollectionReference,
  collection,
  type DocumentData,
  type DocumentReference,
  type DocumentSnapshot,
  doc,
  documentId,
  type Firestore,
  getDoc,
  getDocFromServer,
  getDocsFromServer,
  initializeFirestore,
  limit,
  onSnapshot,
  orderBy,
  persistentLocalCache,
  persistentMultipleTabManager,
  type QueryDocumentSnapshot,
  type QuerySnapshot,
  query,
  serverTimestamp,
  setDoc,
  startAfter,
  Timestamp,
  type Unsubscribe,
  where,
  writeBatch,
} from "firebase/firestore";
import type { FirebaseSettings } from "../config.ts";
import type { Entry, Feed, SavedSettings } from "../types.ts";
import { BODIES, bodyHash, matches, shareable } from "./bodies.ts";
import {
  type EntryDocument,
  entryDocument,
  feedFromDocument,
  parseEntry,
  parseSettings,
  parseSubscription,
  type Retention,
  settingsDocument,
  settingsDocumentId,
  settingsFromDocument,
  storableEntry,
  storableFeed,
  storableKey,
  subscriptionDocument,
  subscriptionTombstone,
} from "./documents.ts";
import {
  emptyChange,
  hasChanges,
  hasLocalData,
  type LocalChange,
  type RemoteChange,
  type SyncHost,
  type SyncReport,
  type SyncStatus,
} from "./host.ts";
import {
  droppedFeedIds,
  FEED_TOMBSTONE,
  mergeEntries,
  mergeRecords,
  SETTINGS_TOMBSTONE,
  subscribedEntries,
  tombstonesOf,
  unknownRemovals,
} from "./merge.ts";
import {
  BATCH_LIMIT,
  chunks,
  IN_LIMIT,
  PAGE_SIZE,
  type PageCursor,
  recentCutoff,
  settledRange,
  unlisted,
} from "./paging.ts";
import {
  ACCOUNT_KEY,
  listenFrom,
  needsChoice,
  parseState,
  type SyncState,
  stateKey,
} from "./state.ts";

/** Wait after a local change before writing, so bursts go in one batch. */
const FLUSH_DELAY_MS = 1500;
/** Wait before trying the account again after a failed read. */
const RETRY_MS = 30_000;
/** Pause between pages of entries older than the recent window. */
const OLDER_PAUSE_MS = 2000;
/** The entry listener starts this far before the newest change seen, in case of clock skew. */
const OVERLAP_MS = 10 * 60 * 1000;
/** The collections under `users/{uid}` that hold an account's data. */
const ACCOUNT_COLLECTIONS = ["subscriptions", "entries", "settings"];
/** No retention setting exists yet; entries stay until removed. */
const RETENTION: Retention = "forever";

/** How a sign-in attempt ended. */
export type SignInResult = "signed-in" | "closed" | "blocked";

/** The signed-in sync engine the app drives. */
export interface Engine {
  /** Sign in with Google in a pop-up window. */
  signIn: () => Promise<SignInResult>;
  /** With a different account waiting: replace this device's data with that account's, and sync it. */
  replaceLocal: () => Promise<void>;
  /** Sign out; data on this device stays. */
  signOut: () => Promise<void>;
  /**
   * Delete the signed-in account: its feeds, entries and settings in
   * Firestore, this device's sync progress for it, and the Google sign-in
   * itself, then sign out. Shared article text stays, and so does everything
   * on this device.
   */
  deleteAccount: () => Promise<void>;
  /** Send local changes, when signed in. */
  queue: (change: LocalChange) => void;
  /** Download a shared body and check its hash; empty when unavailable. */
  fetchBody: (hash: string) => Promise<string>;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, milliseconds);
  });
}

/** Resolve when the browser is back online, or after the retry wait. */
function retryLater(): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(done, RETRY_MS);
    function done(): void {
      globalThis.removeEventListener("online", done);
      clearTimeout(timer);
      resolve();
    }
    globalThis.addEventListener("online", done);
  });
}

function syncedAt(snapshot: DocumentSnapshot): number {
  const value: unknown = snapshot.get("syncedAt");
  return value instanceof Timestamp ? value.toMillis() : 0;
}

function withoutEmptyHash(stored: EntryDocument): Record<string, unknown> {
  const { bodyHash: hash, expireAt, ...rest } = stored;
  const fields: Record<string, unknown> = { ...rest };
  if (hash !== "") {
    fields["bodyHash"] = hash;
  }
  if (expireAt !== undefined) {
    fields["expireAt"] = Timestamp.fromMillis(expireAt);
  }
  return fields;
}

interface Write {
  ref: DocumentReference;
  fields: Record<string, unknown>;
  merge: boolean;
}

/**
 * The parsed account copies in a listener snapshot, skipping this device's
 * own unsent writes. The first snapshot from the server after `reconciled`
 * was false lists every document, and `present` then names them all.
 */
function readSnapshot<Remote>(
  snapshot: QuerySnapshot,
  reconciled: boolean,
  parse: (snapshotDoc: QueryDocumentSnapshot) => [string, Remote] | undefined,
): { remote: [string, Remote][]; present: Set<string> | undefined } {
  const complete = !(reconciled || snapshot.metadata.fromCache);
  const docs = complete
    ? snapshot.docs
    : snapshot
        .docChanges()
        .filter((item) => item.type !== "removed")
        .map((item) => item.doc);
  const remote: [string, Remote][] = [];
  const present = new Set<string>();
  for (const snapshotDoc of docs) {
    const parsed = parse(snapshotDoc);
    if (parsed !== undefined) {
      present.add(parsed[0]);
      if (!snapshotDoc.metadata.hasPendingWrites) {
        remote.push(parsed);
      }
    }
  }
  return { remote, present: complete ? present : undefined };
}

/** Sync for one signed-in account. */
class Session {
  private readonly db: Firestore;
  private readonly uid: string;
  private readonly host: SyncHost;
  private readonly onStatus: () => void;
  private readonly state: SyncState;
  private readonly stops: Unsubscribe[] = [];
  private readonly feeds = new Map<string, Feed>();
  private readonly removedFeeds = new Map<string, number>();
  private readonly entries = new Map<string, Entry>();
  private readonly settings = new Map<string, SavedSettings>();
  private readonly removedSettings = new Map<string, number>();
  private readonly checkedBodies = new Set<string>();
  private bodyQueue: Promise<void> = Promise.resolve();
  /** Listener snapshots, handled one at a time in the order they came. */
  private handling: Promise<void> = Promise.resolve();
  /** Set when an entry snapshot failed to store; the saved cursor then stays where it was. */
  private cursorHeld = false;
  private flushTimer: ReturnType<typeof setTimeout> | undefined;
  private creating: Promise<void> | undefined;
  private subscriptionsReconciled: boolean;
  private settingsReconciled: boolean;
  private stopped: boolean;
  private outstanding: number;
  private merging: boolean;
  /** Listeners that stopped with an error and wait to be started again, by name. */
  private readonly broken = new Set<string>();
  /** Resolves once the subscriptions are known from the server, so no entry of a removed feed is uploaded. */
  private readonly subscriptionsReady = Promise.withResolvers<void>();
  /** Whether entries older than the recent window are still loading. */
  loadingOlder = false;
  /** Whether the last write, or a snapshot's storing, failed. */
  private writeFailed: boolean;

  constructor(
    db: Firestore,
    uid: string,
    host: SyncHost,
    onStatus: () => void,
  ) {
    this.db = db;
    this.uid = uid;
    this.host = host;
    this.onStatus = onStatus;
    this.state = parseState(this.loadState());
    this.subscriptionsReconciled = false;
    this.settingsReconciled = false;
    this.stopped = false;
    this.outstanding = 0;
    this.merging = false;
    this.writeFailed = false;
  }

  /** Whether something failed: a write, or a listener that has not come back yet. */
  get failed(): boolean {
    return this.writeFailed || this.broken.size > 0;
  }

  /** Whether anything is being read or written. */
  get busy(): boolean {
    return (
      this.merging ||
      this.outstanding > 0 ||
      this.flushTimer !== undefined ||
      this.creating !== undefined
    );
  }

  /** Listen for account changes, then load and upload what is missing. */
  start(): void {
    this.listenSubscriptions();
    this.listenSettings();
    this.listenEntries();
    this.mergeAll().catch((error: unknown) => this.fail(error));
    this.retryCreates();
  }

  /** Stop listening and forget this account's progress on this device. */
  stop(): void {
    this.stopped = true;
    for (const stop of this.stops) {
      stop();
    }
    clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    try {
      localStorage.removeItem(stateKey(this.uid));
    } catch {
      // storage can be blocked; nothing was saved then
    }
  }

  /** Write queued changes now rather than after the usual wait, as when the page is hidden. */
  flushNow(): void {
    if (this.flushTimer !== undefined) {
      clearTimeout(this.flushTimer);
      this.flushTimer = undefined;
      this.flush();
    }
  }

  /** Take local changes; writes go out shortly in batches. */
  queue(change: LocalChange): void {
    for (const feed of change.feeds ?? []) {
      this.removedFeeds.delete(feed.id);
      this.feeds.set(feed.id, feed);
    }
    for (const [id, updatedAt] of change.removedFeeds ?? []) {
      this.feeds.delete(id);
      this.removedFeeds.set(id, updatedAt);
    }
    for (const entry of change.entries ?? []) {
      this.entries.set(entry.id, entry);
    }
    for (const [key, settings] of change.settings ?? []) {
      this.removedSettings.delete(key);
      this.settings.set(key, settings);
    }
    for (const [key, updatedAt] of change.removedSettings ?? []) {
      this.settings.delete(key);
      this.removedSettings.set(key, updatedAt);
    }
    const added = this.addPending(change.added ?? []);
    if (added || this.hasQueued()) {
      this.scheduleFlush();
    }
  }

  /** Remember entries to upload; whether there were any. */
  private addPending(added: readonly Entry[]): boolean {
    if (added.length > 0) {
      const pending = new Set(this.state.pending);
      for (const entry of added) {
        pending.add(entry.id);
      }
      this.state.pending = [...pending];
      this.saveState();
    }
    return added.length > 0;
  }

  private hasQueued(): boolean {
    return (
      this.feeds.size > 0 ||
      this.removedFeeds.size > 0 ||
      this.entries.size > 0 ||
      this.settings.size > 0 ||
      this.removedSettings.size > 0
    );
  }

  /** Try again to upload entries found here, as when back online. */
  retryCreates(): void {
    if (this.creating === undefined && this.state.pending.length > 0) {
      this.creating = this.createPending()
        .catch((error: unknown) => {
          console.debug("H Reader sync: upload waits for the next try", error);
        })
        .finally(() => {
          this.creating = undefined;
          this.onStatus();
        });
    }
  }

  private entriesCollection(): CollectionReference {
    return collection(this.db, "users", this.uid, "entries");
  }

  private loadState(): string | null {
    try {
      return localStorage.getItem(stateKey(this.uid));
    } catch {
      return null;
    }
  }

  private saveState(): void {
    // a stopped session's late work must not overwrite or revive its progress
    if (this.stopped) {
      return;
    }
    try {
      localStorage.setItem(stateKey(this.uid), JSON.stringify(this.state));
    } catch {
      // storage can be blocked; progress is then redone next time
    }
  }

  private fail(error: unknown): void {
    if (!this.stopped) {
      console.error("H Reader sync:", error);
      this.writeFailed = true;
      this.onStatus();
    }
  }

  /** Handle a snapshot after the ones before it; a failure is reported and the rest still run. */
  private serially(task: () => Promise<void>): void {
    this.handling = this.handling
      .then(task)
      .catch((error: unknown) => this.fail(error));
  }

  /**
   * Keep a listener running: one that stops with an error (Firestore then
   * calls it no more) is reported and started again after a wait.
   */
  private listen(
    name: string,
    subscribe: (onError: (error: Error) => void) => Unsubscribe,
  ): void {
    if (!this.stopped) {
      this.stops.push(
        subscribe((error) => {
          this.broken.add(name);
          this.fail(error);
          setTimeout(() => this.listen(name, subscribe), RETRY_MS);
        }),
      );
    }
  }

  /** Note that a listener is working again. */
  private heard(name: string): void {
    if (this.broken.delete(name)) {
      this.onStatus();
    }
  }

  private scheduleFlush(): void {
    if (this.flushTimer === undefined && !this.stopped) {
      this.flushTimer = setTimeout(() => {
        this.flushTimer = undefined;
        this.flush();
        this.retryCreates();
      }, FLUSH_DELAY_MS);
      this.onStatus();
    }
  }

  private listenSubscriptions(): void {
    const subscriptions = collection(
      this.db,
      "users",
      this.uid,
      "subscriptions",
    );
    this.listen("subscriptions", (onError) =>
      onSnapshot(
        subscriptions,
        { includeMetadataChanges: true },
        (snapshot) => {
          this.heard("subscriptions");
          this.serially(() => this.onSubscriptions(snapshot));
        },
        onError,
      ),
    );
  }

  private async onSubscriptions(snapshot: QuerySnapshot): Promise<void> {
    const { remote, present } = readSnapshot(
      snapshot,
      this.subscriptionsReconciled,
      (snapshotDoc) => {
        const parsed = parseSubscription(snapshotDoc.data());
        return parsed && [snapshotDoc.id, parsed];
      },
    );
    this.subscriptionsReconciled ||= present !== undefined;
    const local = this.host.local();
    const localFeeds = new Map(local.feeds.map((feed) => [feed.id, feed]));
    const tombstones = tombstonesOf(local.tombstones, FEED_TOMBSTONE);
    const merged = mergeRecords(localFeeds, tombstones, remote, present);
    const change = emptyChange();
    change.removedFeeds = unknownRemovals(
      new Set(localFeeds.keys()),
      tombstones,
      remote,
    );
    for (const [id, copy] of merged.pull) {
      if (copy.deleted) {
        change.removedFeeds.set(id, copy.updatedAt);
      } else {
        const mine = local.feeds.find((feed) => feed.id === id);
        change.feeds.push(feedFromDocument(id, copy, mine));
      }
    }
    await this.applyRemote(change);
    this.queue({
      feeds: merged.push.map(([, feed]) => feed),
      removedFeeds: new Map(merged.pushRemoved),
    });
    if (this.subscriptionsReconciled) {
      this.subscriptionsReady.resolve();
    }
  }

  private listenSettings(): void {
    const settings = collection(this.db, "users", this.uid, "settings");
    this.listen("settings", (onError) =>
      onSnapshot(
        settings,
        { includeMetadataChanges: true },
        (snapshot) => {
          this.heard("settings");
          this.serially(() => this.onSettings(snapshot));
        },
        onError,
      ),
    );
  }

  private async onSettings(snapshot: QuerySnapshot): Promise<void> {
    const { remote, present } = readSnapshot(
      snapshot,
      this.settingsReconciled,
      (snapshotDoc) => {
        const parsed = parseSettings(snapshotDoc.data());
        return parsed && [parsed.key, parsed];
      },
    );
    this.settingsReconciled ||= present !== undefined;
    const local = this.host.local();
    const merged = mergeRecords(
      new Map(Object.entries(local.settings)),
      tombstonesOf(local.tombstones, SETTINGS_TOMBSTONE),
      remote,
      present,
    );
    const change = emptyChange();
    for (const [key, copy] of merged.pull) {
      if (copy.deleted) {
        change.removedSettings.set(key, copy.updatedAt);
      } else {
        change.settings.set(key, settingsFromDocument(copy));
      }
    }
    await this.applyRemote(change);
    this.queue({
      settings: new Map(merged.push),
      removedSettings: new Map(merged.pushRemoved),
    });
  }

  private listenEntries(): void {
    this.listen("entries", (onError) => {
      let stop: Unsubscribe = () => undefined;
      let stopped = false;
      this.entriesStart()
        .then((since) => {
          if (!stopped) {
            stop = this.watchEntries(since, onError);
          }
        })
        .catch(onError);
      return (): void => {
        stopped = true;
        stop();
      };
    });
  }

  /**
   * When the entry listener starts: the saved cursor less an overlap. With no
   * cursor yet, the newest server write time in the account is read and
   * saved first, so a device's wrong clock cannot hide changes.
   */
  private async entriesStart(): Promise<number> {
    if (this.state.cursor === 0) {
      await this.retrying(async () => {
        const newest = await getDocsFromServer(
          query(
            this.entriesCollection(),
            orderBy("syncedAt", "desc"),
            limit(1),
          ),
        );
        const [latest] = newest.docs;
        this.state.cursor = latest === undefined ? 0 : syncedAt(latest);
      });
      this.saveState();
    }
    return listenFrom(this.state.cursor, OVERLAP_MS);
  }

  private watchEntries(
    since: number,
    onError: (error: Error) => void,
  ): Unsubscribe {
    const changes = query(
      this.entriesCollection(),
      where("syncedAt", ">", Timestamp.fromMillis(since)),
    );
    return onSnapshot(
      changes,
      (snapshot) => {
        this.heard("entries");
        this.serially(async () => {
          try {
            await this.onEntries(snapshot);
          } catch (error) {
            this.cursorHeld = true;
            throw error;
          }
        });
      },
      onError,
    );
  }

  private async onEntries(snapshot: QuerySnapshot): Promise<void> {
    const remote: [string, EntryDocument][] = [];
    let newest = this.state.cursor;
    for (const item of snapshot.docChanges()) {
      if (item.type !== "removed" && !item.doc.metadata.hasPendingWrites) {
        const parsed = parseEntry(item.doc.data());
        if (parsed !== undefined) {
          remote.push([item.doc.id, parsed]);
        }
        newest = Math.max(newest, syncedAt(item.doc));
      }
    }
    if (remote.length > 0) {
      await this.mergeRemote(remote);
    }
    if (newest > this.state.cursor && !this.cursorHeld) {
      this.state.cursor = newest;
      this.saveState();
    }
  }

  /** Store account entries that are newer here, and queue local ones that are newer there. */
  private async mergeRemote(
    remote: readonly [string, EntryDocument][],
  ): Promise<void> {
    if (remote.length > 0) {
      const local = this.host.local();
      const merged = mergeEntries(
        new Map(local.entries.map((entry) => [entry.id, entry])),
        remote,
        droppedFeedIds(local.tombstones.keys()),
      );
      await this.applyRemote({ ...emptyChange(), entries: merged.pull });
      if (merged.push.length > 0) {
        this.queue({ entries: merged.push });
      }
    }
  }

  private async applyRemote(change: RemoteChange): Promise<void> {
    if (hasChanges(change) && !this.stopped) {
      await this.host.apply(change);
    }
  }

  /**
   * Read every account entry, newest first, merging each page and uploading
   * this device's entries in the span it settles. The first 90 days come
   * straight away; older pages follow with a pause between them. Progress is
   * saved, so a reload carries on where it stopped.
   */
  private async mergeAll(): Promise<void> {
    const cutoff = recentCutoff(Date.now());
    const seen = new Set<string>();
    let upper = this.state.page?.published ?? Number.POSITIVE_INFINITY;
    this.merging = !this.state.merged;
    this.onStatus();
    if (!this.state.merged) {
      await this.subscriptionsReady.promise;
    }
    while (!(this.state.merged || this.stopped)) {
      // biome-ignore lint/performance/noAwaitInLoops: pages are read one after another, each after the last
      const page = await this.readPage(this.state.page);
      for (const [id] of page) {
        seen.add(id);
      }
      await this.mergeRemote(page);
      const range = settledRange(
        upper,
        page.map(([, stored]) => stored),
        PAGE_SIZE,
      );
      await this.retrying(() =>
        this.create(unlisted(this.host.local().entries, seen, range)),
      );
      const last = page.at(-1);
      if (this.stopped) {
        // the page may not be stored or its entries uploaded; nothing is recorded
      } else if (range.low === Number.NEGATIVE_INFINITY || last === undefined) {
        this.state.merged = true;
        this.state.page = undefined;
      } else {
        this.state.page = { published: last[1].published, id: last[0] };
        upper = range.low;
      }
      this.saveState();
      if (!this.state.merged && upper < cutoff) {
        this.loadingOlder = true;
        this.onStatus();
        await sleep(OLDER_PAUSE_MS);
      }
    }
    this.merging = false;
    this.loadingOlder = false;
    this.onStatus();
  }

  /** One page of account entries after a cursor, retrying until it is read or sync stops. */
  private async readPage(
    after: PageCursor | undefined,
  ): Promise<[string, EntryDocument][]> {
    const ordered = [
      orderBy("published", "desc"),
      orderBy(documentId(), "desc"),
    ];
    const pageQuery =
      after === undefined
        ? query(this.entriesCollection(), ...ordered, limit(PAGE_SIZE))
        : query(
            this.entriesCollection(),
            ...ordered,
            startAfter(after.published, after.id),
            limit(PAGE_SIZE),
          );
    let page: [string, EntryDocument][] | undefined;
    while (page === undefined && !this.stopped) {
      try {
        // biome-ignore lint/performance/noAwaitInLoops: a failed read is retried after a wait
        const snapshot = await getDocsFromServer(pageQuery);
        page = [];
        for (const snapshotDoc of snapshot.docs) {
          const parsed = parseEntry(snapshotDoc.data());
          if (parsed !== undefined) {
            page.push([snapshotDoc.id, parsed]);
          }
        }
      } catch (error) {
        console.debug("H Reader sync: page read failed, retrying", error);
        await retryLater();
      }
    }
    return page ?? [];
  }

  /** Run a task until it succeeds or sync stops, waiting between tries. */
  private async retrying(task: () => Promise<void>): Promise<void> {
    let done = false;
    while (!(done || this.stopped)) {
      try {
        // biome-ignore lint/performance/noAwaitInLoops: a failed try is repeated after a wait
        await task();
        done = true;
      } catch (error) {
        console.debug("H Reader sync: retrying after", error);
        await retryLater();
      }
    }
  }

  private async createPending(): Promise<void> {
    const ids = new Set(this.state.pending);
    const found = this.host
      .local()
      .entries.filter((entry) => ids.has(entry.id));
    await this.create(found);
    const pending = new Set(this.state.pending);
    for (const id of ids) {
      pending.delete(id);
    }
    this.state.pending = [...pending];
    this.saveState();
  }

  /**
   * Upload entries the account may lack. Each is looked up first, by id, so
   * an entry another device already added is merged instead of overwritten.
   */
  private async create(found: readonly Entry[]): Promise<void> {
    const entries = subscribedEntries(found, this.host.local()).filter(
      storableEntry,
    );
    const missing: Entry[] = [];
    const remote: [string, EntryDocument][] = [];
    for (const group of chunks(entries, IN_LIMIT)) {
      const ids = group.map((entry) => entry.id);
      // biome-ignore lint/performance/noAwaitInLoops: lookups go one at a time to keep reads steady
      const snapshot = await getDocsFromServer(
        query(this.entriesCollection(), where(documentId(), "in", ids)),
      );
      for (const snapshotDoc of snapshot.docs) {
        const parsed = parseEntry(snapshotDoc.data());
        if (parsed !== undefined) {
          remote.push([snapshotDoc.id, parsed]);
        }
      }
      const known = new Set(snapshot.docs.map((snapshotDoc) => snapshotDoc.id));
      missing.push(...group.filter((entry) => !known.has(entry.id)));
    }
    await this.mergeRemote(remote);
    for (const group of chunks(missing, BATCH_LIMIT)) {
      // biome-ignore lint/performance/noAwaitInLoops: one batch at a time keeps memory and bandwidth steady
      const hashed = await this.withHashes(group);
      await this.commit(
        hashed.map((entry) => ({
          ref: doc(this.entriesCollection(), entry.id),
          fields: withoutEmptyHash(entryDocument(entry, RETENTION)),
          merge: false,
        })),
      );
      for (const entry of hashed) {
        this.shareBody(entry);
      }
    }
  }

  /** Copies of entries with their body hash filled in where it was never worked out. */
  private async withHashes(entries: readonly Entry[]): Promise<Entry[]> {
    const hashes = new Map<string, string>();
    const result = await Promise.all(
      entries.map(async (entry) => {
        let hash = entry.bodyHash;
        if (hash === "") {
          hash = await bodyHash(await this.host.body(entry.id));
          if (hash !== "") {
            hashes.set(entry.id, hash);
          }
        }
        return { ...entry, bodyHash: hash };
      }),
    );
    if (hashes.size > 0) {
      await this.host.saveHashes(hashes);
    }
    return result;
  }

  /** Upload an entry's body unless the account already has it; one at a time. */
  private shareBody(entry: Entry): void {
    const hash = entry.bodyHash;
    if (hash !== "" && !this.checkedBodies.has(hash)) {
      this.checkedBodies.add(hash);
      this.bodyQueue = this.bodyQueue
        .then(async () => {
          const ref = doc(this.db, BODIES, hash);
          const existing = await getDocFromServer(ref);
          if (!existing.exists()) {
            const text = await this.host.body(entry.id);
            if (shareable(text) && (await matches(hash, text))) {
              await setDoc(ref, { text });
            }
          }
        })
        .catch((error: unknown) => {
          this.checkedBodies.delete(hash);
          console.debug("H Reader sync: body upload failed", error);
        });
    }
  }

  /** Write everything queued, in batches; never waits for the server. */
  private flush(): void {
    if (this.stopped) {
      return;
    }
    const writes: Write[] = [];
    const subscriptions = collection(
      this.db,
      "users",
      this.uid,
      "subscriptions",
    );
    const settings = collection(this.db, "users", this.uid, "settings");
    for (const feed of [...this.feeds.values()].filter(storableFeed)) {
      writes.push({
        ref: doc(subscriptions, feed.id),
        fields: { ...subscriptionDocument(feed) },
        merge: false,
      });
    }
    for (const [id, updatedAt] of this.removedFeeds) {
      writes.push({
        ref: doc(subscriptions, id),
        fields: { ...subscriptionTombstone(updatedAt) },
        merge: false,
      });
      this.dropRemoteEntries(id);
    }
    for (const [key, saved] of [...this.settings].filter(([viewKey]) =>
      storableKey(viewKey),
    )) {
      writes.push({
        ref: doc(settings, settingsDocumentId(key)),
        fields: { ...settingsDocument(key, saved, saved.updatedAt) },
        merge: false,
      });
    }
    for (const [key, updatedAt] of [...this.removedSettings].filter(
      ([viewKey]) => storableKey(viewKey),
    )) {
      writes.push({
        ref: doc(settings, settingsDocumentId(key)),
        fields: { ...settingsDocument(key, undefined, updatedAt) },
        merge: false,
      });
    }
    const pending = new Set(this.state.pending);
    for (const entry of this.entries.values()) {
      // an entry still waiting to be uploaded keeps its mark change for a later flush
      if (!pending.has(entry.id)) {
        if (storableEntry(entry)) {
          writes.push({
            ref: doc(this.entriesCollection(), entry.id),
            fields: withoutEmptyHash(entryDocument(entry, RETENTION)),
            merge: true,
          });
        }
        this.entries.delete(entry.id);
      }
    }
    this.feeds.clear();
    this.removedFeeds.clear();
    this.settings.clear();
    this.removedSettings.clear();
    this.commit(writes).catch((error: unknown) => this.fail(error));
    this.onStatus();
  }

  /** Commit writes in batches of at most 500, each stamped with the server time. */
  private async commit(writes: readonly Write[]): Promise<void> {
    await Promise.all(
      chunks(writes, BATCH_LIMIT).map(async (group) => {
        const batch = writeBatch(this.db);
        for (const write of group) {
          batch.set(
            write.ref,
            { ...write.fields, syncedAt: serverTimestamp() },
            { merge: write.merge },
          );
        }
        this.outstanding += 1;
        this.onStatus();
        try {
          await batch.commit();
          this.writeFailed = false;
        } finally {
          this.outstanding -= 1;
          this.onStatus();
        }
      }),
    );
  }

  /** Delete the account's entries of an unsubscribed feed, listed by the server rather than the cache. */
  private dropRemoteEntries(feedId: string): void {
    this.retrying(async () => {
      // subscribed again meanwhile: its entries are wanted after all
      if (this.host.local().tombstones.has(`${FEED_TOMBSTONE}${feedId}`)) {
        const snapshot = await getDocsFromServer(
          query(this.entriesCollection(), where("feedId", "==", feedId)),
        );
        await Promise.all(
          chunks(snapshot.docs, BATCH_LIMIT).map((group) => {
            const batch = writeBatch(this.db);
            for (const snapshotDoc of group) {
              batch.delete(snapshotDoc.ref);
            }
            return batch.commit();
          }),
        );
      }
    }).catch((error: unknown) => this.fail(error));
  }
}

function isCancel(error: unknown): boolean {
  return (
    error instanceof FirebaseError &&
    (error.code === "auth/popup-closed-by-user" ||
      error.code === "auth/cancelled-popup-request")
  );
}

function isBlocked(error: unknown): boolean {
  return error instanceof FirebaseError && error.code === "auth/popup-blocked";
}

function needsRecentLogin(error: unknown): boolean {
  return (
    error instanceof FirebaseError &&
    error.code === "auth/requires-recent-login"
  );
}

/** Delete a Google sign-in, signing in again first when Firebase asks for a recent one. */
async function deleteSignIn(current: User): Promise<void> {
  try {
    await deleteUser(current);
  } catch (error) {
    if (needsRecentLogin(error)) {
      await reauthenticateWithPopup(current, new GoogleAuthProvider());
      await deleteUser(current);
    } else {
      throw error;
    }
  }
}

/** Delete every document of one collection, a page at a time. */
async function deleteCollection(
  db: Firestore,
  path: CollectionReference,
): Promise<void> {
  let page = await getDocsFromServer(query(path, limit(BATCH_LIMIT)));
  while (!page.empty) {
    const batch = writeBatch(db);
    for (const snapshotDoc of page.docs) {
      batch.delete(snapshotDoc.ref);
    }
    // biome-ignore lint/performance/noAwaitInLoops: each page is read after the last is gone
    await batch.commit();
    page = await getDocsFromServer(query(path, limit(BATCH_LIMIT)));
  }
}

function lastAccount(): string {
  try {
    return localStorage.getItem(ACCOUNT_KEY) ?? "";
  } catch {
    return "";
  }
}

function rememberAccount(uid: string): void {
  try {
    localStorage.setItem(ACCOUNT_KEY, uid);
  } catch {
    // storage can be blocked; a later different account is then not noticed
  }
}

function forgetAccount(): void {
  try {
    localStorage.removeItem(ACCOUNT_KEY);
  } catch {
    // storage can be blocked; nothing was saved then
  }
}

function forgetProgress(uid: string): void {
  try {
    localStorage.removeItem(stateKey(uid));
  } catch {
    // storage can be blocked; nothing was saved then
  }
}

/** Start Firebase and follow the signed-in account, reporting status as it changes. */
export function startFirebase(
  settings: FirebaseSettings,
  host: SyncHost,
  report: (update: SyncReport) => void,
): Engine {
  const app = initializeApp(settings);
  const auth: Auth = initializeAuth(app, {
    persistence: [indexedDBLocalPersistence, browserLocalPersistence],
    popupRedirectResolver: browserPopupRedirectResolver,
  });
  const db = initializeFirestore(app, {
    localCache: persistentLocalCache({
      tabManager: persistentMultipleTabManager(),
    }),
  });
  let session: Session | undefined;
  let user: User | null = null;
  let known = false;
  // a different account than last time, waiting for the person's choice
  let waiting = false;
  // changes made before the first answer about who is signed in
  let early: LocalChange[] = [];

  function status(): SyncStatus {
    let current: SyncStatus = "off";
    if (!known) {
      current = "connecting";
    } else if (user === null) {
      current = "off";
    } else if (waiting) {
      current = "waiting";
    } else if (!navigator.onLine) {
      current = "offline";
    } else if (session?.failed === true) {
      current = "error";
    } else if (session?.busy === true) {
      current = "syncing";
    } else {
      current = "synced";
    }
    return current;
  }

  function update(): void {
    report({
      status: status(),
      email: user?.email ?? "",
      loadingOlder: session?.loadingOlder ?? false,
    });
  }

  globalThis.addEventListener("online", () => {
    session?.retryCreates();
    update();
  });
  globalThis.addEventListener("offline", update);
  // queued changes would otherwise wait out the flush delay and be lost with the page
  globalThis.addEventListener("pagehide", () => session?.flushNow());
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
      session?.flushNow();
    }
  });

  function begin(uid: string, changes: readonly LocalChange[]): void {
    rememberAccount(uid);
    session = new Session(db, uid, host, update);
    session.start();
    for (const change of changes) {
      session.queue(change);
    }
  }

  onAuthStateChanged(auth, (next) => {
    known = true;
    if (next?.uid !== user?.uid) {
      session?.stop();
      session = undefined;
      user = next;
      waiting =
        next !== null &&
        needsChoice(lastAccount(), next.uid, hasLocalData(host.local()));
      if (next !== null && !waiting) {
        begin(next.uid, early);
      }
    }
    early = [];
    update();
  });
  update();

  return {
    async signIn(): Promise<SignInResult> {
      let result: SignInResult = "closed";
      try {
        await signInWithPopup(auth, new GoogleAuthProvider());
        result = "signed-in";
      } catch (error) {
        if (isBlocked(error)) {
          result = "blocked";
        } else if (!isCancel(error)) {
          throw error;
        }
      }
      return result;
    },
    async replaceLocal(): Promise<void> {
      if (waiting && user !== null) {
        const { uid } = user;
        // taken at once, so a second click does nothing
        waiting = false;
        await host.clear();
        // this account's saved progress belonged to data that is gone now
        forgetProgress(uid);
        // the account may have signed out meanwhile
        if (user?.uid === uid && session === undefined) {
          begin(uid, []);
        }
        update();
      }
    },
    async deleteAccount(): Promise<void> {
      const current = auth.currentUser;
      if (current !== null) {
        waiting = false;
        // nothing may be written back while the account is emptied
        session?.stop();
        session = undefined;
        for (const name of ACCOUNT_COLLECTIONS) {
          // biome-ignore lint/performance/noAwaitInLoops: collections go one after another
          await deleteCollection(
            db,
            collection(db, "users", current.uid, name),
          );
        }
        forgetProgress(current.uid);
        if (lastAccount() === current.uid) {
          forgetAccount();
        }
        await deleteSignIn(current);
        await signOut(auth);
      }
    },
    async signOut(): Promise<void> {
      waiting = false;
      session?.flushNow();
      session?.stop();
      session = undefined;
      await signOut(auth);
    },
    queue(change: LocalChange): void {
      if (known) {
        session?.queue(change);
      } else {
        early.push(change);
      }
    },
    async fetchBody(hash: string): Promise<string> {
      let text = "";
      if (user !== null && hash !== "") {
        try {
          const snapshot = await getDoc(doc(db, BODIES, hash));
          const data: DocumentData | undefined = snapshot.data();
          const value: unknown = data?.["text"];
          text = typeof value === "string" ? value : "";
        } catch (error) {
          console.debug("H Reader sync: body download failed", error);
          text = "";
        }
      }
      return (await matches(hash, text)) ? text : "";
    },
  };
}
