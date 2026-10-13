import type { Entry } from "../types.ts";
import { type EntryDocument, entryFromDocument } from "./documents.ts";

/** A record's version: when it last changed and whether that change removed it. */
export interface Version {
  /** When it last changed, by the changing device's clock. */
  updatedAt: number;
  /** Whether that change removed it. */
  deleted: boolean;
}

/** What to do with one record: take the account's copy, send this device's, or nothing. */
export type Decision = "pull" | "push" | "keep";

/** Prefix of a tombstone key for an unsubscribed feed. */
export const FEED_TOMBSTONE = "feed:";
/** Prefix of a tombstone key for removed view settings. */
export const SETTINGS_TOMBSTONE = "settings:";

/**
 * Last write wins by `updatedAt`. A removal nobody else knows about is not
 * sent, and a removal this device never had is not taken; equal times keep
 * what is here, so a change that came from the account is never sent back.
 */
export function decide(
  local: Version | undefined,
  remote: Version | undefined,
): Decision {
  let decision: Decision = "keep";
  if (local === undefined) {
    decision = remote === undefined || remote.deleted ? "keep" : "pull";
  } else if (remote === undefined) {
    decision = local.deleted ? "keep" : "push";
  } else if (local.updatedAt > remote.updatedAt) {
    decision = "push";
  } else if (local.updatedAt < remote.updatedAt) {
    decision = "pull";
  } else {
    decision = "keep";
  }
  return decision;
}

/** The version of a local record, from the record or its tombstone time. */
export function localVersion(
  record: { updatedAt: number } | undefined,
  tombstone: number | undefined,
): Version | undefined {
  let version: Version | undefined;
  if (record !== undefined) {
    version = { updatedAt: record.updatedAt, deleted: false };
  } else if (tombstone !== undefined) {
    version = { updatedAt: tombstone, deleted: true };
  }
  return version;
}

/** Entries to store here and entries to send, from a set of account documents. */
export interface EntryMerge {
  /** New entries, or local ones with the account's newer marks applied. */
  pull: Entry[];
  /** Local entries whose marks are newer than the account's. */
  push: Entry[];
}

/**
 * Merge account entries into local ones by last write wins on the read and
 * starred marks. Entries of feeds this device has unsubscribed from are
 * skipped. A local entry keeps its body hash when the account has none.
 */
export function mergeEntries(
  local: ReadonlyMap<string, Entry>,
  remote: Iterable<[string, EntryDocument]>,
  droppedFeeds: ReadonlySet<string>,
): EntryMerge {
  const pull: Entry[] = [];
  const push: Entry[] = [];
  for (const [id, stored] of remote) {
    const mine = local.get(id);
    const decision = decide(
      mine && { updatedAt: mine.updatedAt, deleted: false },
      { updatedAt: stored.updatedAt, deleted: false },
    );
    if (decision === "pull" && mine !== undefined) {
      pull.push({
        ...mine,
        // a plain copy: the local array may be a reactive proxy, which IndexedDB cannot store
        categories: [...mine.categories],
        read: stored.read,
        starred: stored.starred,
        updatedAt: stored.updatedAt,
        bodyHash: mine.bodyHash || stored.bodyHash,
      });
    } else if (decision === "pull" && !droppedFeeds.has(stored.feedId)) {
      pull.push(entryFromDocument(id, stored));
    } else if (decision === "push" && mine !== undefined) {
      push.push(mine);
    }
  }
  return { pull, push };
}

/**
 * The entries whose feed is still subscribed here and not removed, the only
 * ones worth uploading.
 */
export function subscribedEntries(
  entries: readonly Entry[],
  local: {
    feeds: readonly { id: string }[];
    tombstones: ReadonlyMap<string, number>;
  },
): Entry[] {
  const live = new Set(local.feeds.map((feed) => feed.id));
  const dropped = droppedFeedIds(local.tombstones.keys());
  return entries.filter(
    (entry) => live.has(entry.feedId) && !dropped.has(entry.feedId),
  );
}

/** Feed ids with a tombstone, from tombstone keys. */
export function droppedFeedIds(tombstones: Iterable<string>): Set<string> {
  const ids = new Set<string>();
  for (const key of tombstones) {
    if (key.startsWith(FEED_TOMBSTONE)) {
      ids.add(key.slice(FEED_TOMBSTONE.length));
    }
  }
  return ids;
}

/**
 * Account removals this device has no record of at all, with when. A device
 * that never had a feed still keeps its tombstone, so the feed's entries are
 * not pulled from the account later.
 */
export function unknownRemovals<Remote extends Version>(
  localKeys: ReadonlySet<string>,
  tombstones: ReadonlyMap<string, number>,
  remote: Iterable<[string, Remote]>,
): Map<string, number> {
  const removals = new Map<string, number>();
  for (const [key, copy] of remote) {
    if (copy.deleted && !localKeys.has(key) && !tombstones.has(key)) {
      removals.set(key, copy.updatedAt);
    }
  }
  return removals;
}

/** What to do with records of one kind, from the account's copies of them. */
export interface RecordMerge<Value, Remote> {
  /** Account copies to store here, live or removed. */
  pull: [string, Remote][];
  /** Local records to send. */
  push: [string, Value][];
  /** Local removals to send, with when. */
  pushRemoved: [string, number][];
}

/**
 * Merge records by last write wins. `remote` holds the account copies seen;
 * when `present` is given it names every record the account has, so local
 * records missing from it are sent too.
 */
export function mergeRecords<
  Value extends { updatedAt: number },
  Remote extends Version,
>(
  local: ReadonlyMap<string, Value>,
  tombstones: ReadonlyMap<string, number>,
  remote: Iterable<[string, Remote]>,
  present?: ReadonlySet<string>,
): RecordMerge<Value, Remote> {
  const result: RecordMerge<Value, Remote> = {
    pull: [],
    push: [],
    pushRemoved: [],
  };
  for (const [key, copy] of remote) {
    const mine = local.get(key);
    const tombstone = tombstones.get(key);
    const decision = decide(localVersion(mine, tombstone), copy);
    if (decision === "pull") {
      result.pull.push([key, copy]);
    } else if (decision === "push" && mine !== undefined) {
      result.push.push([key, mine]);
    } else if (decision === "push" && tombstone !== undefined) {
      result.pushRemoved.push([key, tombstone]);
    }
  }
  if (present !== undefined) {
    for (const [key, mine] of local) {
      if (!present.has(key)) {
        result.push.push([key, mine]);
      }
    }
  }
  return result;
}

/** Tombstones with a prefix, keyed by what follows it. */
export function tombstonesOf(
  tombstones: ReadonlyMap<string, number>,
  prefix: string,
): Map<string, number> {
  const found = new Map<string, number>();
  for (const [key, when] of tombstones) {
    if (key.startsWith(prefix)) {
      found.set(key.slice(prefix.length), when);
    }
  }
  return found;
}
