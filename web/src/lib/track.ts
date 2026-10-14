import type { FetchOutcome, TrackedFeed } from "$shared/protocol.ts";

/** Characters of JSON the answers in one `track` message may hold, well under Chrome's 64 MiB message limit. */
export const TRACK_BUDGET = 8 * 1024 * 1024;

/**
 * The `track` messages that hand the extension `feeds` with the answers in
 * `firstChecks` (by feed id) as their first checks. Every message lists every
 * feed, since each one replaces the list the extension tracks; the answers
 * are spread over them, as many in each as fit in `budget` characters of JSON
 * and at least one. Without answers it is one message.
 */
export function trackMessages(
  feeds: readonly TrackedFeed[],
  firstChecks: ReadonlyMap<string, FetchOutcome>,
  budget = TRACK_BUDGET,
): TrackedFeed[][] {
  const groups: Map<string, FetchOutcome>[] = [];
  let group = new Map<string, FetchOutcome>();
  let used = 0;
  for (const feed of feeds) {
    const first = firstChecks.get(feed.id);
    if (first !== undefined) {
      const size = JSON.stringify(first).length;
      if (group.size > 0 && used + size > budget) {
        groups.push(group);
        group = new Map();
        used = 0;
      }
      group.set(feed.id, first);
      used += size;
    }
  }
  if (group.size > 0 || groups.length === 0) {
    groups.push(group);
  }
  return groups.map((answers) =>
    feeds.map((feed) => {
      const first = answers.get(feed.id);
      return first === undefined ? feed : { ...feed, first };
    }),
  );
}
