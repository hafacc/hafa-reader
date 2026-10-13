import type { Entry, Feed, SavedSettings } from "../types.ts";
import type { LocalChange } from "./host.ts";

/** Changes taken from the outbox for one write. */
export interface OutboxBatch {
  /** Feeds added or edited. */
  feeds: Map<string, Feed>;
  /** Feeds unsubscribed, with when. */
  removedFeeds: Map<string, number>;
  /** Entries whose read or starred mark changed. */
  entries: Map<string, Entry>;
  /** View settings saved, by view key. */
  settings: Map<string, SavedSettings>;
  /** View settings removed, with when. */
  removedSettings: Map<string, number>;
}

function emptyBatch(): OutboxBatch {
  return {
    feeds: new Map(),
    removedFeeds: new Map(),
    entries: new Map(),
    settings: new Map(),
    removedSettings: new Map(),
  };
}

/** Put taken changes back in `queued`, except where `queued` or its opposite has a newer change for the same id. */
function putBack<Value, Opposite>(
  queued: Map<string, Value>,
  opposite: ReadonlyMap<string, Opposite>,
  taken: ReadonlyMap<string, Value>,
): void {
  for (const [id, value] of taken) {
    if (!(queued.has(id) || opposite.has(id))) {
      queued.set(id, value);
    }
  }
}

/**
 * Local changes waiting to be written to the account, the newest per feed,
 * entry or view. Changes are taken out for a write and put back if it fails,
 * unless a newer change for the same thing came in meanwhile.
 */
export class Outbox {
  private queued: OutboxBatch = emptyBatch();

  /** Whether anything waits to be written. */
  get hasQueued(): boolean {
    const { feeds, removedFeeds, entries, settings, removedSettings } =
      this.queued;
    return (
      feeds.size > 0 ||
      removedFeeds.size > 0 ||
      entries.size > 0 ||
      settings.size > 0 ||
      removedSettings.size > 0
    );
  }

  /** Take a change, replacing any older one for the same feed, entry or view. */
  add(change: LocalChange): void {
    const { feeds, removedFeeds, entries, settings, removedSettings } =
      this.queued;
    for (const feed of change.feeds ?? []) {
      removedFeeds.delete(feed.id);
      feeds.set(feed.id, feed);
    }
    for (const [id, updatedAt] of change.removedFeeds ?? []) {
      feeds.delete(id);
      removedFeeds.set(id, updatedAt);
    }
    for (const entry of change.entries ?? []) {
      entries.set(entry.id, entry);
    }
    for (const [key, saved] of change.settings ?? []) {
      removedSettings.delete(key);
      settings.set(key, saved);
    }
    for (const [key, updatedAt] of change.removedSettings ?? []) {
      settings.delete(key);
      removedSettings.set(key, updatedAt);
    }
  }

  /** Take everything queued out for a write, except entries in `held`, which stay. */
  take(held: ReadonlySet<string>): OutboxBatch {
    const taken = this.queued;
    this.queued = emptyBatch();
    for (const [id, entry] of taken.entries) {
      if (held.has(id)) {
        this.queued.entries.set(id, entry);
        taken.entries.delete(id);
      }
    }
    return taken;
  }

  /** Put back a batch whose write failed, skipping anything changed again since. */
  restore(batch: OutboxBatch): void {
    const { feeds, removedFeeds, entries, settings, removedSettings } =
      this.queued;
    putBack(feeds, removedFeeds, batch.feeds);
    putBack(removedFeeds, feeds, batch.removedFeeds);
    putBack(entries, new Map(), batch.entries);
    putBack(settings, removedSettings, batch.settings);
    putBack(removedSettings, settings, batch.removedSettings);
  }
}
