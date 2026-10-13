import type { Entry, Feed, SavedSettings } from "../types.ts";
import type { Retention } from "./documents.ts";

/** How sync is doing, for the status shown in the app. */
export type SyncStatus =
  | "off"
  | "connecting"
  | "syncing"
  | "synced"
  | "offline"
  | "error"
  // a different account than last time signed in; waits for the person to choose
  | "waiting";

/** What the sync engine tells the app about itself. */
export interface SyncReport {
  status: SyncStatus;
  /** The signed-in account's email; empty when signed out. */
  email: string;
  /** The signed-in account's picture as the sign-in gives it; empty when none. */
  photoUrl: string;
  /** Whether entries older than the recent window are still being loaded. */
  loadingOlder: boolean;
  /** How long the account keeps entries. */
  retention: Retention;
  /** Whether the account's feeds have come from the server since signing in; false when signed out. */
  feedsKnown: boolean;
}

/** Everything stored on this device, as sync reads it. */
export interface LocalData {
  /** Subscribed feeds. */
  feeds: readonly Feed[];
  /** Stored articles. */
  entries: readonly Entry[];
  /** View settings by view key. */
  settings: Readonly<Record<string, SavedSettings>>;
  /** Removal times, by tombstone key (`feed:{id}` or `settings:{viewKey}`). */
  tombstones: ReadonlyMap<string, number>;
}

/** Changes from the account for the app to store; none of them are sent back. */
export interface RemoteChange {
  /** Feeds to add or update, with this device's own fields kept. */
  feeds: Feed[];
  /** Feeds unsubscribed elsewhere, with when; their entries are dropped. */
  removedFeeds: Map<string, number>;
  /** Entries to add, or to update in place. */
  entries: Entry[];
  /** View settings to save, by view key. */
  settings: Map<string, SavedSettings>;
  /** View settings removed elsewhere, with when. */
  removedSettings: Map<string, number>;
}

/** Changes made on this device for sync to send. */
export interface LocalChange {
  /** Feeds added or edited. */
  feeds?: readonly Feed[];
  /** Feeds unsubscribed, with when. */
  removedFeeds?: ReadonlyMap<string, number>;
  /** Entries whose read or starred mark changed. */
  entries?: readonly Entry[];
  /** Entries this device just found; uploaded unless the account has them. */
  added?: readonly Entry[];
  /** View settings saved, by view key. */
  settings?: ReadonlyMap<string, SavedSettings>;
  /** View settings removed, with when. */
  removedSettings?: ReadonlyMap<string, number>;
}

/** What the sync engine needs from the app. */
export interface SyncHost {
  /** What is stored on this device now. */
  local: () => LocalData;
  /** Store changes from the account, here and in the local database. */
  apply: (change: RemoteChange) => Promise<void>;
  /** An entry's stored body; empty when there is none. */
  body: (entryId: string) => Promise<string>;
  /** Remove everything stored on this device, before another account's data comes in. */
  clear: () => Promise<void>;
}

/**
 * Whether this device holds anything of an account's: feeds, entries,
 * settings, or removals, which would otherwise be sent to another account.
 */
export function hasLocalData(local: LocalData): boolean {
  return (
    local.feeds.length > 0 ||
    local.entries.length > 0 ||
    Object.keys(local.settings).length > 0 ||
    local.tombstones.size > 0
  );
}

/** A change with nothing in it. */
export function emptyChange(): RemoteChange {
  return {
    feeds: [],
    removedFeeds: new Map(),
    entries: [],
    settings: new Map(),
    removedSettings: new Map(),
  };
}

/** Whether a change has anything to store. */
export function hasChanges(change: RemoteChange): boolean {
  return (
    change.feeds.length > 0 ||
    change.removedFeeds.size > 0 ||
    change.entries.length > 0 ||
    change.settings.size > 0 ||
    change.removedSettings.size > 0
  );
}
