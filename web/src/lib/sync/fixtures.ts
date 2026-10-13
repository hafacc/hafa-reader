import type { Entry, Feed } from "../types.ts";

/** A feed for tests, with any fields replaced. */
export function sampleFeed(change: Partial<Feed> = {}): Feed {
  return {
    id: "feed-1",
    url: "https://example.com/feed",
    title: "Example",
    siteUrl: "https://example.com/",
    icon: "",
    renamed: false,
    folder: "",
    lastFetched: 5,
    error: "",
    updatedAt: 100,
    ...change,
  };
}

/** An entry for tests, with any fields replaced. */
export function sampleEntry(change: Partial<Entry> = {}): Entry {
  return {
    id: "entry-1",
    feedId: "feed-1",
    guid: "guid-1",
    title: "Title",
    url: "https://example.com/1",
    published: 1000,
    dated: true,
    author: "",
    imageUrl: "",
    excerpt: "Start",
    summary: "",
    categories: ["News"],
    read: false,
    starred: false,
    addedAt: 2000,
    position: 0,
    bodyHash: "",
    updatedAt: 2000,
    ...change,
  };
}
