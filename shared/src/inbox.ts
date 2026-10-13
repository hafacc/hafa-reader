import type { PulledFeed } from "./protocol.ts";

/** One feed's contents waiting for the web app, numbered in the order they changed. */
export interface InboxRecord {
  /** Number of the change; later changes have larger numbers. */
  seq: number;
  /** The feed's contents waiting to be taken. */
  pulled: PulledFeed;
}

/** Most entries one waiting feed keeps; the newest are kept. */
export const INBOX_ENTRY_LIMIT = 1000;

/**
 * Fold a newly pulled feed into the one already waiting for the web app: the
 * new details and entries, then the waiting entries the new pull no longer
 * shows, up to `INBOX_ENTRY_LIMIT` entries and, by dropping the oldest of those
 * carried over, `maxCharacters` of JSON; size never drops an entry of the new pull.
 */
export function mergePulled(
  waiting: PulledFeed | undefined,
  pulled: PulledFeed,
  maxCharacters = Number.POSITIVE_INFINITY,
): PulledFeed {
  let merged = pulled;
  if (waiting !== undefined) {
    const shown = new Set(pulled.feed.entries.map((entry) => entry.guid));
    const dropped = waiting.feed.entries
      .filter((entry) => !shown.has(entry.guid))
      .slice(0, Math.max(0, INBOX_ENTRY_LIMIT - pulled.feed.entries.length));
    let size = JSON.stringify(pulled).length;
    let kept = 0;
    for (const entry of dropped) {
      // One more entry adds its JSON and a comma.
      const entrySize = JSON.stringify(entry).length + 1;
      if (size + entrySize > maxCharacters) {
        break;
      }
      size += entrySize;
      kept += 1;
    }
    merged = {
      ...pulled,
      feed: {
        ...pulled.feed,
        entries: [...pulled.feed.entries, ...dropped.slice(0, kept)].slice(
          0,
          INBOX_ENTRY_LIMIT,
        ),
      },
    };
  }
  return merged;
}

/** What one drain hands over. */
export interface InboxPage {
  /** Oldest records first. */
  records: InboxRecord[];
  /** Records were left for a later drain. */
  more: boolean;
}

/**
 * The oldest records whose JSON adds up to at most `budget` characters, and
 * always the oldest one, so a single large feed still gets through. Records
 * are loaded one at a time with `load`, oldest `seqs` first, so a drain reads
 * only what it hands over and one more.
 */
export async function drainPage(
  seqs: Record<string, number>,
  load: (feedId: string) => Promise<InboxRecord | undefined>,
  budget: number,
): Promise<InboxPage> {
  const ordered = Object.entries(seqs).sort(
    ([, first], [, second]) => first - second,
  );
  const taken: InboxRecord[] = [];
  let used = 0;
  let more = false;
  for (const [feedId] of ordered) {
    // biome-ignore lint/performance/noAwaitInLoops: records are read one at a time so a drain stops at its budget
    const record = await load(feedId);
    if (record !== undefined) {
      const size = JSON.stringify(record).length;
      if (taken.length > 0 && used + size > budget) {
        more = true;
        break;
      }
      taken.push(record);
      used += size;
    }
  }
  return { records: taken, more };
}
