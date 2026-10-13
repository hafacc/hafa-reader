import type { Entry } from "./types.ts";

/** Compare text by UTF-16 code units, which, unlike the locale's collation, every device agrees on. */
export function byCodeUnits(first: string, second: string): number {
  let order = 0;
  if (first < second) {
    order = -1;
  } else if (first > second) {
    order = 1;
  }
  return order;
}

/**
 * Order two entries newest or oldest first: by date (the first sighting for
 * undated ones), then by when this device first saw them, then by place in
 * the feed, whose top is newest, then by guid and address, which every
 * device has (place in the feed is not synced), then by id. Oldest first is
 * the exact reverse, so the order never depends on how entries were loaded.
 */
export function compareEntries(
  first: Entry,
  second: Entry,
  sort: "newest" | "oldest",
): number {
  const newestFirst =
    second.published - first.published ||
    second.addedAt - first.addedAt ||
    first.position - second.position ||
    byCodeUnits(first.guid, second.guid) ||
    byCodeUnits(first.url, second.url) ||
    byCodeUnits(first.id, second.id);
  return sort === "newest" ? newestFirst : -newestFirst;
}
