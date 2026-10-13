import type { PageCursor } from "./paging.ts";

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
}

/** The state before an account's first sync on this device. */
export function freshState(): SyncState {
  return { cursor: 0, merged: false, page: undefined, pending: [] };
}

/** Browser storage key of an account's sync state. */
export function stateKey(uid: string): string {
  return `h-reader:sync:${uid}`;
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
