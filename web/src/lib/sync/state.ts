import type { AccountDocument } from "./documents.ts";
import type { PageCursor } from "./paging.ts";

/** A pass setting the expiry of the account's entries to a retention, in document id order. */
export interface Restamp {
  /** The retention being applied, in days; 0 for forever. */
  retentionDays: number;
  /** Id of the last entry done; empty before the first page. */
  after: string;
}

/** What sync remembers on this device for one account. */
export interface SyncState {
  /** Newest server write time the entry listener has seen, in milliseconds. */
  cursor: number;
  /** Whether the first pass over the account's entries has finished. */
  merged: boolean;
  /** Where that pass stopped; undefined before its first page. */
  page: PageCursor | undefined;
  /** Ids of entries found here that still have to be uploaded. */
  pending: string[];
  /** The account's retention as last known here. */
  account: AccountDocument;
  /** An unfinished pass applying a retention this device chose; undefined when none. */
  restamp: Restamp | undefined;
}

/** The state before an account's first sync on this device. */
export function freshState(): SyncState {
  return {
    cursor: 0,
    merged: false,
    page: undefined,
    pending: [],
    account: { retentionDays: 0, updatedAt: 0, stampedDays: 0 },
    restamp: undefined,
  };
}

/**
 * What stays saved when sync stops for an account, as at sign-out: nothing,
 * except an unfinished retention pass, which must still finish or entries
 * keep an expiry the account no longer wants.
 */
export function stateAfterStop(state: SyncState): SyncState | undefined {
  return state.restamp === undefined
    ? undefined
    : { ...freshState(), account: state.account, restamp: state.restamp };
}

/** Browser storage key of an account's sync state. */
export function stateKey(uid: string): string {
  return `h-reader:sync:${uid}`;
}

/** Forget every account's sync state on this device, as when its data is gone, so each account syncs again from the start. */
export function forgetAllStates(): void {
  try {
    const keys = Array.from({ length: localStorage.length }, (_slot, index) =>
      localStorage.key(index),
    );
    for (const key of keys) {
      if (key?.startsWith(stateKey("")) === true) {
        localStorage.removeItem(key);
      }
    }
  } catch {
    // storage can be blocked; nothing was saved then
  }
}

function readPage(value: unknown): PageCursor | undefined {
  let page: PageCursor | undefined;
  if (
    typeof value === "object" &&
    value !== null &&
    "published" in value &&
    "id" in value &&
    typeof value.published === "number" &&
    typeof value.id === "string"
  ) {
    page = { published: value.published, id: value.id };
  }
  return page;
}

function readAccount(value: unknown): AccountDocument | undefined {
  let account: AccountDocument | undefined;
  if (
    typeof value === "object" &&
    value !== null &&
    "retentionDays" in value &&
    "updatedAt" in value &&
    typeof value.retentionDays === "number" &&
    typeof value.updatedAt === "number"
  ) {
    account = {
      retentionDays: value.retentionDays,
      updatedAt: value.updatedAt,
      stampedDays:
        "stampedDays" in value && typeof value.stampedDays === "number"
          ? value.stampedDays
          : 0,
    };
  }
  return account;
}

function readRestamp(value: unknown): Restamp | undefined {
  let restamp: Restamp | undefined;
  if (
    typeof value === "object" &&
    value !== null &&
    "retentionDays" in value &&
    "after" in value &&
    typeof value.retentionDays === "number" &&
    typeof value.after === "string"
  ) {
    restamp = { retentionDays: value.retentionDays, after: value.after };
  }
  return restamp;
}

/** Read the saved retention and any unfinished pass applying one into `state`. */
function readRetention(value: object, state: SyncState): void {
  if ("account" in value) {
    state.account = readAccount(value.account) ?? state.account;
  }
  if ("restamp" in value) {
    state.restamp = readRestamp(value.restamp);
  }
}

/** Read saved state; anything unreadable starts over. */
export function parseState(raw: string | null): SyncState {
  const state = freshState();
  try {
    const value: unknown = raw === null ? undefined : JSON.parse(raw);
    if (typeof value === "object" && value !== null) {
      if ("cursor" in value && typeof value.cursor === "number") {
        state.cursor = value.cursor;
      }
      if ("merged" in value && typeof value.merged === "boolean") {
        state.merged = value.merged;
      }
      if ("page" in value) {
        state.page = readPage(value.page);
      }
      readRetention(value, state);
      if ("pending" in value && Array.isArray(value.pending)) {
        state.pending = value.pending.filter(
          (item): item is string => typeof item === "string",
        );
      }
    }
  } catch {
    return freshState();
  }
  return state;
}

/** When the entry listener starts: the cursor less an overlap for clock skew; 0 for no cursor. */
export function listenFrom(cursor: number, overlap: number): number {
  return cursor > 0 ? Math.max(0, cursor - overlap) : 0;
}

/** Browser storage key of the account this device last synced with. */
export const ACCOUNT_KEY = "h-reader:sync-account";

/**
 * Whether signing in must wait for the person to choose: a different
 * account than the one this device last synced with, while the device still
 * holds data. One account's data is never uploaded into another.
 */
export function needsChoice(
  lastUid: string,
  uid: string,
  hasData: boolean,
): boolean {
  return lastUid !== "" && lastUid !== uid && hasData;
}
