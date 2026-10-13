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
  deleteField,
  doc,
  documentId,
  type FieldValue,
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
  type WriteBatch,
  waitForPendingWrites,
  where,
  writeBatch,
} from "firebase/firestore";
import type { DeletionBackend } from "../account-deletion.svelte.ts";
import type { FirebaseSettings } from "../config.ts";
import type { Entry } from "../types.ts";
import { BODIES, matches, shareable } from "./bodies.ts";
import {
  ACCOUNT_SETTINGS_ID,
  accountWinner,
  bodyExpiry,
  type EntryDocument,
  entryDocument,
  feedFromDocument,
  needsRestamp,
  parseAccount,
  parseEntry,
  parseSettings,
  parseSubscription,
  type Retention,
  restampedExpiry,
  retentionDays,
  retentionFromDays,
  settingsDocument,
  settingsDocumentId,
  settingsFromDocument,
  storableEntry,
  storableFeed,
  storableKey,
  subscriptionDocument,
  subscriptionTombstone,
} from "./documents.ts";
import { errorSummary, isRefused } from "./errors.ts";
import { Health } from "./health.ts";
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
import { Outbox } from "./outbox.ts";
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
  type Restamp,
  type SyncState,
  stateAfterStop,
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
/** Longest wait at sign-out for written changes to reach the account. */
const SIGN_OUT_WAIT_MS = 5000;

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
  /** Choose how long the account keeps entries, and apply it to those it has. */
  setRetention: (retention: Retention) => void;
  /** Upload a body this device holds if the account no longer has it. */
  restoreBody: (hash: string, text: string) => void;
  /** Stop listening to sign-in, the browser and the account, keeping progress, as before a hot reload. */
  dispose: () => void;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, milliseconds);
  });
}

/** Wait for a task, but no longer than a limit; its failure is not waited on either. */
async function within(
  task: Promise<void>,
  milliseconds: number,
): Promise<void> {
  await Promise.race([task.catch(() => undefined), sleep(milliseconds)]);
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

/** An entry's fields to write; merged over the account's copy, a missing expiry clears the one there. */
function entryFields(
  stored: EntryDocument,
  merge: boolean,
): Record<string, unknown> {
  const { bodyHash: hash, expireAt, ...rest } = stored;
  const fields: Record<string, unknown> = { ...rest };
  if (hash !== "") {
    fields["bodyHash"] = hash;
  }
  if (expireAt !== undefined) {
    fields["expireAt"] = Timestamp.fromMillis(expireAt);
  } else if (merge) {
    fields["expireAt"] = deleteField();
  }
  return fields;
}

/** The `expireAt` an account entry must be given under a retention; undefined when it is right already. */
function expiryChange(
  snapshotDoc: QueryDocumentSnapshot,
  retention: Retention,
): Timestamp | FieldValue | undefined {
  const parsed = parseEntry(snapshotDoc.data());
  const current: unknown = snapshotDoc.get("expireAt");
  const change =
    parsed &&
    restampedExpiry(
      parsed,
      current instanceof Timestamp ? current.toMillis() : undefined,
      retention,
    );
  let value: Timestamp | FieldValue | undefined;
  if (change === null) {
    value = deleteField();
  } else if (change !== undefined) {
    value = Timestamp.fromMillis(change);
  }
  return value;
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
  private readonly outbox = new Outbox();
  private readonly checkedBodies = new Set<string>();
  private bodyQueue: Promise<void> = Promise.resolve();
  /** Listener snapshots, handled one at a time in the order they came. */
  private handling: Promise<void> = Promise.resolve();
  /** Set when an entry snapshot failed to store; the saved cursor then stays where it was until the listener starts again. */
  private cursorHeld = false;
  private flushTimer: ReturnType<typeof setTimeout> | undefined;
  /** Set while a failed write waits to be tried again. */
  private flushRetrying: boolean;
  private creating: Promise<void> | undefined;
  private subscriptionsReconciled: boolean;
  private settingsReconciled: boolean;
  private stopped: boolean;
  private outstanding: number;
  private merging: boolean;
  /** Whether the pass applying a retention to the account's entries is running. */
  private restamping: boolean;
  /** What is failing now: writes, reads, or listeners waiting to be started again. */
  private readonly health = new Health();
  /** Resolves once the subscriptions are known from the server, so no entry of a removed feed is uploaded. */
  private readonly subscriptionsReady = Promise.withResolvers<void>();
  /** Whether entries older than the recent window are still loading. */
  loadingOlder = false;

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
    this.restamping = false;
    this.flushRetrying = false;
  }

  /** Whether something failed: a write, a read, or a listener that has not come back yet. */
  get failed(): boolean {
    return this.health.failed;
  }

  /** Whether the account's feeds have come from the server in this session. */
  get feedsKnown(): boolean {
    return this.subscriptionsReconciled;
  }

  /** How long the account keeps entries, as known here. */
  get retention(): Retention {
    return retentionFromDays(this.state.account.retentionDays);
  }

  /** Whether anything is being read or written. */
  get busy(): boolean {
    return (
      this.merging ||
      this.restamping ||
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
    this.mergeAll().catch((error: unknown) => this.failRead(error));
    this.retryCreates();
    this.resumeRestamp();
  }

  /**
   * Choose how long the account keeps entries: saved to the account for every
   * device, then applied to the entries already there by a pass this device runs.
   */
  setRetention(retention: Retention): void {
    const days = retentionDays(retention);
    if (days !== this.state.account.retentionDays && !this.stopped) {
      this.state.account = {
        ...this.state.account,
        retentionDays: days,
        updatedAt: Date.now(),
      };
      this.state.restamp = { retentionDays: days, after: "" };
      this.saveState();
      this.writeAccount();
      this.resumeRestamp();
      this.onStatus();
    }
  }

  private writeAccount(): void {
    const settings = collection(this.db, "users", this.uid, "settings");
    this.commit([
      {
        ref: doc(settings, ACCOUNT_SETTINGS_ID),
        fields: { ...this.state.account },
        merge: false,
      },
    ]).catch((error: unknown) => this.fail(error));
  }

  /** Take up the account's retention when it changed elsewhere, or send this device's newer one. */
  private onAccount(snapshot: QuerySnapshot): void {
    const accountDoc = snapshot.docs.find(
      (snapshotDoc) => snapshotDoc.id === ACCOUNT_SETTINGS_ID,
    );
    // a missing document means something only once the server has answered
    const known = accountDoc !== undefined || !snapshot.metadata.fromCache;
    if (known && accountDoc?.metadata.hasPendingWrites !== true) {
      const remote =
        accountDoc === undefined ? undefined : parseAccount(accountDoc.data());
      const winner = accountWinner(this.state.account, remote);
      if (winner === "local") {
        this.writeAccount();
      } else if (remote !== undefined) {
        // the same choice may carry a newer record of what was stamped
        this.state.account = remote;
        this.saveState();
        this.onStatus();
      }
      this.restampIfBehind();
    }
  }

  /**
   * Start a pass when the account's entries were last stamped with another
   * retention than its own, as when the device that chose it stopped before
   * finishing; a pass already under way here goes on instead.
   */
  private restampIfBehind(): void {
    const { account } = this.state;
    if (this.state.restamp === undefined && needsRestamp(account)) {
      this.state.restamp = { retentionDays: account.retentionDays, after: "" };
      this.saveState();
      this.resumeRestamp();
    }
  }

  /** Record in the account that its entries now all carry a retention, so no device runs the pass again. */
  private markStamped(days: number): void {
    if (this.state.account.retentionDays === days) {
      this.state.account = { ...this.state.account, stampedDays: days };
      this.saveState();
      const settings = collection(this.db, "users", this.uid, "settings");
      this.commit([
        {
          ref: doc(settings, ACCOUNT_SETTINGS_ID),
          fields: { stampedDays: days },
          merge: true,
        },
      ]).catch((error: unknown) => this.fail(error));
    }
  }

  private resumeRestamp(): void {
    if (
      !(this.restamping || this.stopped) &&
      this.state.restamp !== undefined
    ) {
      this.restamping = true;
      this.onStatus();
      this.retrying(() => this.restampAll())
        .catch((error: unknown) => this.failRead(error))
        .finally(() => {
          this.restamping = false;
          this.onStatus();
        });
    }
  }

  /**
   * Set the expiry of each of the account's entries to the retention chosen
   * here, a page at a time in id order, carrying on from where the pass
   * stopped. Each entry is read once; only those that change are written.
   */
  private async restampAll(): Promise<void> {
    let { restamp } = this.state;
    while (restamp !== undefined && !this.stopped) {
      // biome-ignore lint/performance/noAwaitInLoops: one page at a time, each picking up where the last stopped
      const docs = await this.restampPage(restamp);
      this.health.readWorked();
      // a newer choice made meanwhile starts its own pass over
      if (this.state.restamp === restamp) {
        const last = docs.at(-1);
        const finished = last === undefined || docs.length < BATCH_LIMIT;
        this.state.restamp = finished
          ? undefined
          : { ...restamp, after: last.id };
        this.saveState();
        if (finished) {
          this.markStamped(restamp.retentionDays);
        }
      }
      ({ restamp } = this.state);
    }
  }

  /** Re-stamp the page of entries after the pass's last one; the page read. */
  private async restampPage(
    restamp: Restamp,
  ): Promise<QueryDocumentSnapshot[]> {
    const after = restamp.after === "" ? [] : [startAfter(restamp.after)];
    const page = await getDocsFromServer(
      query(
        this.entriesCollection(),
        orderBy(documentId()),
        ...after,
        limit(BATCH_LIMIT),
      ),
    );
    const retention = retentionFromDays(restamp.retentionDays);
    const batch = writeBatch(this.db);
    let changed = 0;
    for (const snapshotDoc of page.docs) {
      const change = expiryChange(snapshotDoc, retention);
      if (change !== undefined) {
        batch.update(snapshotDoc.ref, { expireAt: change });
        changed += 1;
      }
    }
    if (changed > 0) {
      await batch.commit();
    }
    return page.docs;
  }

  /**
   * Stop listening. With `forget`, as at sign-out, this account's progress on
   * this device goes too, all but an unfinished retention pass, which the
   * next session for the account finishes.
   */
  stop(forget: boolean): void {
    this.stopped = true;
    for (const stop of this.stops) {
      stop();
    }
    clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    // a first pass still waiting on the subscriptions ends now rather than holding this session
    this.subscriptionsReady.resolve();
    if (forget) {
      const kept = stateAfterStop(this.state);
      try {
        if (kept === undefined) {
          localStorage.removeItem(stateKey(this.uid));
        } else {
          localStorage.setItem(stateKey(this.uid), JSON.stringify(kept));
        }
      } catch {
        // storage can be blocked; nothing was saved then
      }
    }
  }

  /**
   * Write queued changes now rather than after the usual wait, as when the
   * page is hidden or at sign-out, including ones a failed write put back.
   */
  flushNow(): void {
    clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    if (this.outbox.hasQueued) {
      this.flush();
    }
  }

  /** Take local changes; writes go out shortly in batches. */
  queue(change: LocalChange): void {
    this.outbox.add(change);
    const added = this.addPending(change.added ?? []);
    if (added || this.outbox.hasQueued) {
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

  /** Try again to upload entries found here, as when back online; they stay pending until their write succeeds. */
  retryCreates(): void {
    if (
      this.creating === undefined &&
      this.state.pending.length > 0 &&
      !this.stopped
    ) {
      this.creating = this.createPending()
        .catch((error: unknown) => {
          console.debug("Reader sync: upload waits for the next try", error);
          this.retryCreatesLater();
        })
        .finally(() => {
          this.creating = undefined;
          this.onStatus();
        });
    }
  }

  private retryCreatesLater(): void {
    retryLater()
      .then(() => this.retryCreates())
      .catch(() => undefined);
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

  /** Report a failed write; it clears with the next write that goes through. */
  private fail(error: unknown): void {
    if (!this.stopped) {
      console.error("Reader sync failed:", errorSummary(error));
      this.health.writeFailed();
      this.onStatus();
    }
  }

  /** Report a failed read, or a snapshot that could not be stored; it clears with the next read that works. */
  private failRead(error: unknown): void {
    if (!this.stopped) {
      console.error("Reader sync failed to read:", errorSummary(error));
      this.health.readFailed();
      this.onStatus();
    }
  }

  /** Handle a snapshot after the ones before it; a failure is reported and the rest still run. */
  private serially(task: () => Promise<void>): void {
    this.handling = this.handling
      .then(task)
      .catch((error: unknown) => this.failRead(error));
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
          if (!this.stopped) {
            console.error(
              `Reader sync: ${name} listener stopped:`,
              errorSummary(error),
            );
            this.health.listenerFailed(name);
            this.onStatus();
          }
          setTimeout(() => this.listen(name, subscribe), RETRY_MS);
        }),
      );
    }
  }

  /** Note that a listener delivered, so it and reads are working again. */
  private heard(name: string): void {
    if (this.health.heard(name)) {
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
    if (!this.subscriptionsReconciled && present !== undefined) {
      this.subscriptionsReconciled = true;
      this.onStatus();
    }
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
        const parsed =
          snapshotDoc.id === ACCOUNT_SETTINGS_ID
            ? undefined
            : parseSettings(snapshotDoc.data());
        return parsed && [parsed.key, parsed];
      },
    );
    this.onAccount(snapshot);
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

  /**
   * Listen for entry changes from the saved cursor. A snapshot that cannot be
   * stored stops the listener like an error, so it starts again from the
   * cursor, which did not move, and the changes come again.
   */
  private listenEntries(): void {
    this.listen("entries", (onError) => {
      let stop: Unsubscribe = () => undefined;
      let stopped = false;
      function end(): void {
        stopped = true;
        stop();
      }
      this.cursorHeld = false;
      this.entriesStart()
        .then((since) => {
          if (!stopped) {
            stop = this.watchEntries(since, (error) => {
              end();
              onError(error);
            });
          }
        })
        .catch(onError);
      return end;
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
            if (!this.cursorHeld) {
              this.cursorHeld = true;
              onError(
                error instanceof Error ? error : new Error(errorSummary(error)),
              );
            }
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
    try {
      if (!this.state.merged) {
        await this.subscriptionsReady.promise;
      }
      while (!(this.state.merged || this.stopped)) {
        const from = upper;
        // biome-ignore lint/performance/noAwaitInLoops: pages are read one after another, each after the last
        upper = (await this.retrying(() => this.mergePage(seen, from))) ?? from;
        if (!this.state.merged && upper < cutoff) {
          this.loadingOlder = true;
          this.onStatus();
          await sleep(OLDER_PAUSE_MS);
        }
      }
    } finally {
      this.merging = false;
      this.loadingOlder = false;
      this.onStatus();
    }
  }

  /**
   * Read, store and upload one page of the first pass, then record where it
   * stopped; the new upper end of what is left. Retried whole when storing or
   * uploading fails.
   */
  private async mergePage(seen: Set<string>, upper: number): Promise<number> {
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
    await this.create(unlisted(this.host.local().entries, seen, range));
    const last = page.at(-1);
    let next = upper;
    if (this.stopped) {
      // the page may not be stored or its entries uploaded; nothing is recorded
    } else if (range.low === Number.NEGATIVE_INFINITY || last === undefined) {
      this.state.merged = true;
      this.state.page = undefined;
    } else {
      this.state.page = { published: last[1].published, id: last[0] };
      next = range.low;
    }
    this.saveState();
    return next;
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
        this.health.readWorked();
        page = [];
        for (const snapshotDoc of snapshot.docs) {
          const parsed = parseEntry(snapshotDoc.data());
          if (parsed !== undefined) {
            page.push([snapshotDoc.id, parsed]);
          }
        }
      } catch (error) {
        console.debug("Reader sync: page read failed, retrying", error);
        await retryLater();
      }
    }
    return page ?? [];
  }

  /** Run a task until it succeeds or sync stops, waiting between tries. */
  private async retrying<Result>(
    task: () => Promise<Result>,
  ): Promise<Result | undefined> {
    let done = false;
    let result: Result | undefined;
    while (!(done || this.stopped)) {
      try {
        // biome-ignore lint/performance/noAwaitInLoops: a failed try is repeated after a wait
        result = await task();
        done = true;
      } catch (error) {
        console.debug("Reader sync: retrying after", error);
        await retryLater();
      }
    }
    return result;
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
      await this.commit(
        group.map((entry) => ({
          ref: doc(this.entriesCollection(), entry.id),
          fields: entryFields(entryDocument(entry, this.retention), false),
          merge: false,
        })),
      );
      for (const entry of group) {
        this.shareBody(entry);
      }
    }
  }

  /** Upload an entry's body unless the account already has it; one at a time. */
  private shareBody(entry: Entry): void {
    this.uploadIfMissing(entry.bodyHash, () => this.host.body(entry.id));
  }

  /**
   * Upload a body this device holds when the account lacks it, as after it
   * expired there; one read, and a write only when it is missing. Checked
   * once a session per hash.
   */
  restoreBody(hash: string, text: string): void {
    this.uploadIfMissing(hash, () => Promise.resolve(text));
  }

  /** Note a body just downloaded, so it is not looked up again this session. */
  bodyPresent(hash: string): void {
    this.checkedBodies.add(hash);
  }

  /** Look a body up by hash and upload this device's text when the account lacks it; one at a time. */
  private uploadIfMissing(hash: string, text: () => Promise<string>): void {
    if (hash !== "" && !this.checkedBodies.has(hash)) {
      this.checkedBodies.add(hash);
      this.bodyQueue = this.bodyQueue
        .then(async () => {
          const ref = doc(this.db, BODIES, hash);
          const existing = await getDocFromServer(ref);
          if (!existing.exists()) {
            const body = await text();
            if (shareable(body) && (await matches(hash, body))) {
              const expireAt = bodyExpiry(this.retention, Date.now());
              await setDoc(
                ref,
                expireAt === undefined
                  ? { text: body }
                  : { text: body, expireAt: Timestamp.fromMillis(expireAt) },
              );
            }
          }
        })
        .catch((error: unknown) => {
          this.checkedBodies.delete(hash);
          console.debug("Reader sync: body upload failed", error);
        });
    }
  }

  /**
   * Write everything queued, in batches; never waits for the server. A failed
   * write puts its changes back and is tried again later.
   */
  private flush(): void {
    if (this.stopped) {
      return;
    }
    // an entry still waiting to be uploaded keeps its mark change for a later flush
    const taken = this.outbox.take(new Set(this.state.pending));
    const writes: Write[] = [];
    const subscriptions = collection(
      this.db,
      "users",
      this.uid,
      "subscriptions",
    );
    const settings = collection(this.db, "users", this.uid, "settings");
    for (const feed of [...taken.feeds.values()].filter(storableFeed)) {
      writes.push({
        ref: doc(subscriptions, feed.id),
        fields: { ...subscriptionDocument(feed) },
        merge: false,
      });
    }
    for (const [id, updatedAt] of taken.removedFeeds) {
      writes.push({
        ref: doc(subscriptions, id),
        fields: { ...subscriptionTombstone(updatedAt) },
        merge: false,
      });
    }
    for (const [key, saved] of [...taken.settings].filter(([viewKey]) =>
      storableKey(viewKey),
    )) {
      writes.push({
        ref: doc(settings, settingsDocumentId(key)),
        fields: { ...settingsDocument(key, saved, saved.updatedAt) },
        merge: false,
      });
    }
    for (const [key, updatedAt] of [...taken.removedSettings].filter(
      ([viewKey]) => storableKey(viewKey),
    )) {
      writes.push({
        ref: doc(settings, settingsDocumentId(key)),
        fields: { ...settingsDocument(key, undefined, updatedAt) },
        merge: false,
      });
    }
    for (const entry of [...taken.entries.values()].filter(storableEntry)) {
      writes.push({
        ref: doc(this.entriesCollection(), entry.id),
        fields: entryFields(entryDocument(entry, this.retention), true),
        merge: true,
      });
    }
    this.commit(writes).then(
      () => {
        for (const id of taken.removedFeeds.keys()) {
          this.dropRemoteEntries(id);
        }
      },
      (error: unknown) => {
        this.outbox.restore(taken);
        this.fail(error);
        this.retryFlush();
      },
    );
    this.onStatus();
  }

  /** Write what a failed flush put back once the browser is online again or after a wait. */
  private retryFlush(): void {
    if (!(this.flushRetrying || this.stopped)) {
      this.flushRetrying = true;
      retryLater()
        .then(() => {
          this.flushRetrying = false;
          if (this.outbox.hasQueued) {
            this.scheduleFlush();
          }
        })
        .catch((error: unknown) => this.fail(error));
    }
  }

  /** Commit writes in batches of at most 500, each stamped with the server time. */
  private async commit(writes: readonly Write[]): Promise<void> {
    await Promise.all(
      chunks(writes, BATCH_LIMIT).map((group) => this.commitGroup(group)),
    );
  }

  /**
   * Commit one batch. When the rules or the data refuse it, which no retry
   * fixes, each write is tried on its own and the refused ones are dropped,
   * so the valid changes still land.
   */
  private async commitGroup(group: readonly Write[]): Promise<void> {
    this.outstanding += 1;
    this.onStatus();
    try {
      try {
        await this.batchOf(group).commit();
      } catch (error) {
        if (!isRefused(error)) {
          throw error;
        }
        await this.commitEach(group, error);
      }
      this.health.writeWorked();
    } finally {
      this.outstanding -= 1;
      this.onStatus();
    }
  }

  /** Commit refused writes one at a time, dropping each the rules or the data refuse. */
  private async commitEach(
    group: readonly Write[],
    refusal: unknown,
  ): Promise<void> {
    if (group.length === 1) {
      console.error(
        "Reader sync: write refused, dropped:",
        errorSummary(refusal),
      );
    } else {
      await Promise.all(
        group.map(async (write) => {
          try {
            await this.batchOf([write]).commit();
          } catch (error) {
            if (!isRefused(error)) {
              throw error;
            }
            console.error(
              "Reader sync: write refused, dropped:",
              errorSummary(error),
            );
          }
        }),
      );
    }
  }

  /** A batch of writes, each stamped with the server time. */
  private batchOf(group: readonly Write[]): WriteBatch {
    const batch = writeBatch(this.db);
    for (const write of group) {
      batch.set(
        write.ref,
        { ...write.fields, syncedAt: serverTimestamp() },
        { merge: write.merge },
      );
    }
    return batch;
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

/** Delete an account's documents, once every write still on its way has landed, so none is left behind. */
async function deleteAccountData(db: Firestore, uid: string): Promise<void> {
  await waitForPendingWrites(db);
  for (const name of ACCOUNT_COLLECTIONS) {
    // biome-ignore lint/performance/noAwaitInLoops: collections go one after another
    await deleteCollection(db, collection(db, "users", uid, name));
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

/** Sign in with Google in a pop-up window; closing it is not an error. */
export async function signInWithGoogle(auth: Auth): Promise<SignInResult> {
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
}

/** What the caller of `deleteAccountOf` does at each step. */
export interface DeleteSteps {
  /** After signing in again, before anything is deleted: stop writing to the account. */
  stopping?: () => void;
  /** Removing the documents failed, so the account is as it was: sync may carry on. */
  kept?: () => void;
  /** The documents are gone; only the sign-in is left to remove. */
  emptied?: () => void;
}

/**
 * Delete a signed-in account, the one way both the app and the delete page
 * use. Signing in again opens straight from the click, before anything is
 * deleted, so Firebase accepts removing the sign-in; closing it changes
 * nothing. Then, once writes on their way have landed, the account's
 * documents go, with this device's sync progress for it, then the sign-in
 * itself. It always ends signed out once the documents are gone, even when
 * removing the sign-in fails, so nothing is uploaded into the emptied account.
 */
export async function deleteAccountOf(
  firebase: Services,
  current: User,
  steps: DeleteSteps = {},
): Promise<void> {
  await reauthenticateWithPopup(current, new GoogleAuthProvider());
  steps.stopping?.();
  try {
    await deleteAccountData(firebase.db, current.uid);
  } catch (error) {
    steps.kept?.();
    throw error;
  }
  steps.emptied?.();
  forgetProgress(current.uid);
  if (lastAccount() === current.uid) {
    forgetAccount();
  }
  try {
    await deleteUser(current);
  } finally {
    await signOut(firebase.auth);
  }
}

/** The delete page's way into Firebase: sign-in and the app's own account deletion, with no reader behind it. */
export function deletionBackend(settings: FirebaseSettings): DeletionBackend {
  const firebase = firebaseServices(settings);
  return {
    watch(onUser): void {
      onAuthStateChanged(firebase.auth, (user) => {
        onUser(user?.email ?? undefined);
      });
    },
    signIn: () => signInWithGoogle(firebase.auth),
    signOut: () => signOut(firebase.auth),
    async deleteAccount(): Promise<void> {
      const current = firebase.auth.currentUser;
      if (current === null) {
        throw new Error("no account is signed in");
      }
      await deleteAccountOf(firebase, current);
    },
  };
}

/** Firebase's sign-in and database for the page. */
export interface Services {
  /** Google sign-in. */
  auth: Auth;
  /** The account's documents. */
  db: Firestore;
}

// kept across a hot reload of this module in development, since Firebase keeps its instances
let services: Services | undefined = import.meta.hot?.data["services"];

/**
 * Firebase's sign-in and database, set up on the first call and the same
 * after: Firestore refuses a second setup with its options, so starting sync
 * again (after a failed start, or a new app state on a hot reload) reuses them.
 */
export function firebaseServices(settings: FirebaseSettings): Services {
  if (services === undefined) {
    const app = initializeApp(settings);
    services = {
      auth: initializeAuth(app, {
        persistence: [indexedDBLocalPersistence, browserLocalPersistence],
        popupRedirectResolver: browserPopupRedirectResolver,
      }),
      db: initializeFirestore(app, {
        localCache: persistentLocalCache({
          tabManager: persistentMultipleTabManager(),
        }),
      }),
    };
    if (import.meta.hot !== undefined) {
      import.meta.hot.data["services"] = services;
    }
  }
  return services;
}

/** Start Firebase and follow the signed-in account, reporting status as it changes. */
export function startFirebase(
  settings: FirebaseSettings,
  host: SyncHost,
  report: (update: SyncReport) => void,
): Engine {
  const { auth, db } = firebaseServices(settings);
  let session: Session | undefined;
  let user: User | null = null;
  let known = false;
  // a different account than last time, waiting for the person's choice
  let waiting = false;
  // changes made before the first answer about who is signed in
  let early: LocalChange[] = [];
  // changes made while the account is being deleted, sent if that fails
  let heldForDelete: LocalChange[] | undefined;
  let replacing = false;

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
      photoUrl: user?.photoURL ?? "",
      loadingOlder: session?.loadingOlder ?? false,
      retention: session?.retention ?? "forever",
      feedsKnown: session?.feedsKnown ?? false,
    });
  }

  function onOnline(): void {
    session?.retryCreates();
    update();
  }
  // queued changes would otherwise wait out the flush delay and be lost with the page
  function onPageHide(): void {
    session?.flushNow();
  }
  function onVisibility(): void {
    if (document.visibilityState === "hidden") {
      session?.flushNow();
    }
  }
  globalThis.addEventListener("online", onOnline);
  globalThis.addEventListener("offline", update);
  globalThis.addEventListener("pagehide", onPageHide);
  document.addEventListener("visibilitychange", onVisibility);

  function begin(uid: string, changes: readonly LocalChange[]): void {
    rememberAccount(uid);
    session = new Session(db, uid, host, update);
    session.start();
    for (const change of changes) {
      session.queue(change);
    }
  }

  /** Carry on syncing an account whose deletion failed, with the changes made meanwhile, so the status stays true. */
  function resumeAfterFailedDelete(uid: string, wasWaiting: boolean): void {
    const held = heldForDelete ?? [];
    heldForDelete = undefined;
    if (user?.uid === uid && session === undefined) {
      waiting = wasWaiting;
      if (!waiting) {
        begin(uid, held);
      }
    }
    update();
  }

  const stopAuth = onAuthStateChanged(auth, (next) => {
    known = true;
    if (next?.uid !== user?.uid) {
      session?.stop(true);
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
    signIn(): Promise<SignInResult> {
      return signInWithGoogle(auth);
    },
    async replaceLocal(): Promise<void> {
      if (waiting && !replacing && user !== null) {
        const { uid } = user;
        replacing = true;
        try {
          await host.clear();
          waiting = false;
          // this account's saved progress belonged to data that is gone now
          forgetProgress(uid);
          // the account may have signed out meanwhile
          if (user?.uid === uid && session === undefined) {
            begin(uid, []);
          }
        } finally {
          replacing = false;
          update();
        }
      }
    },
    async deleteAccount(): Promise<void> {
      const current = auth.currentUser;
      if (current !== null) {
        const wasWaiting = waiting;
        await deleteAccountOf({ auth, db }, current, {
          stopping(): void {
            waiting = false;
            // nothing may be written back while the account is emptied
            session?.stop(true);
            session = undefined;
            heldForDelete = [];
          },
          kept(): void {
            resumeAfterFailedDelete(current.uid, wasWaiting);
          },
          emptied(): void {
            heldForDelete = undefined;
          },
        });
      }
    },
    async signOut(): Promise<void> {
      waiting = false;
      session?.flushNow();
      // writes reach the account only while signed in
      if (navigator.onLine) {
        await within(waitForPendingWrites(db), SIGN_OUT_WAIT_MS);
      }
      session?.stop(true);
      session = undefined;
      await signOut(auth);
    },
    queue(change: LocalChange): void {
      if (!known) {
        early.push(change);
      } else if (heldForDelete === undefined) {
        session?.queue(change);
      } else {
        heldForDelete.push(change);
      }
    },
    setRetention(retention: Retention): void {
      session?.setRetention(retention);
      update();
    },
    restoreBody(hash: string, text: string): void {
      session?.restoreBody(hash, text);
    },
    dispose(): void {
      stopAuth();
      globalThis.removeEventListener("online", onOnline);
      globalThis.removeEventListener("offline", update);
      globalThis.removeEventListener("pagehide", onPageHide);
      document.removeEventListener("visibilitychange", onVisibility);
      session?.stop(false);
      session = undefined;
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
          console.debug("Reader sync: body download failed", error);
          text = "";
        }
      }
      const found = await matches(hash, text);
      if (found) {
        session?.bodyPresent(hash);
      }
      return found ? text : "";
    },
  };
}
