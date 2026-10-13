import { describe, expect, test } from "bun:test";
import { sampleEntry, sampleFeed } from "./fixtures.ts";
import { Outbox } from "./outbox.ts";

const SETTINGS = {
  sort: "newest" as const,
  hideWords: [],
  onlyWords: [],
  offTopics: [],
  updatedAt: 1,
};

describe("Outbox", () => {
  test("a failed write puts every change back", () => {
    const outbox = new Outbox();
    const read = sampleEntry({ read: true, updatedAt: 5 });
    outbox.add({
      feeds: [sampleFeed()],
      removedFeeds: new Map([["gone", 3]]),
      entries: [read],
      settings: new Map([["all", SETTINGS]]),
      removedSettings: new Map([["starred", 4]]),
    });
    const taken = outbox.take(new Set());
    expect(outbox.hasQueued).toBe(false);
    outbox.restore(taken);
    expect(outbox.take(new Set())).toEqual(taken);
  });

  test("a change made during a failed write is kept over the older one", () => {
    const outbox = new Outbox();
    outbox.add({ entries: [sampleEntry({ read: true, updatedAt: 5 })] });
    const taken = outbox.take(new Set());
    const unread = sampleEntry({ read: false, updatedAt: 6 });
    outbox.add({ entries: [unread], removedFeeds: new Map([["feed-1", 7]]) });
    outbox.restore({ ...taken, feeds: new Map([["feed-1", sampleFeed()]]) });
    const again = outbox.take(new Set());
    expect([...again.entries.values()]).toEqual([unread]);
    expect(again.feeds.size).toBe(0);
    expect(again.removedFeeds).toEqual(new Map([["feed-1", 7]]));
  });

  test("an entry still waiting to be uploaded stays queued", () => {
    const outbox = new Outbox();
    outbox.add({ entries: [sampleEntry()] });
    expect(outbox.take(new Set(["entry-1"])).entries.size).toBe(0);
    expect(outbox.take(new Set()).entries.size).toBe(1);
  });
});
