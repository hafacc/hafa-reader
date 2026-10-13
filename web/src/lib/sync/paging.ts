import type { Entry } from "../types.ts";

const DAY_MS = 24 * 60 * 60 * 1000;

/** Days of entries a new device loads before anything older. */
export const RECENT_DAYS = 90;
/** Entries read from the account per page. */
export const PAGE_SIZE = 500;
/** Most ids one Firestore `in` query takes. */
export const IN_LIMIT = 30;
/** Most writes in one Firestore batch. */
export const BATCH_LIMIT = 500;

/** Where a page of entries, newest first, ended. */
export interface PageCursor {
  /** Date of the last entry read. */
  published: number;
  /** Id of the last entry read. */
  id: string;
}

/** Earliest publish time that still counts as recent. */
export function recentCutoff(now: number): number {
  return now - RECENT_DAYS * DAY_MS;
}

/**
 * Publish times a page settles, newest first: `upper` down to just above the
 * last item's time, or everything when the page was the last. Items sharing
 * the last time may run onto the next page, so that time stays open.
 */
export function settledRange(
  upper: number,
  page: readonly { published: number }[],
  pageSize: number,
): { low: number; high: number } {
  const last = page.at(-1);
  const low =
    page.length < pageSize || last === undefined
      ? Number.NEGATIVE_INFINITY
      : last.published;
  return { low, high: upper };
}

/** Local entries in a settled range that the account did not list. */
export function unlisted(
  local: Iterable<Entry>,
  seen: ReadonlySet<string>,
  range: { low: number; high: number },
): Entry[] {
  const missing: Entry[] = [];
  for (const entry of local) {
    if (
      entry.published > range.low &&
      entry.published <= range.high &&
      !seen.has(entry.id)
    ) {
      missing.push(entry);
    }
  }
  return missing;
}

/** Split items into runs of at most `size`. */
export function chunks<Item>(items: readonly Item[], size: number): Item[][] {
  const runs: Item[][] = [];
  for (let start = 0; start < items.length; start += size) {
    runs.push(items.slice(start, start + size));
  }
  return runs;
}
