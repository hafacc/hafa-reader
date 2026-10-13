import { describe, expect, test } from "bun:test";
import {
  cutText,
  entryDocument,
  entryFromDocument,
  FIELD_LIMITS,
  feedFromDocument,
  parseEntry,
  parseSettings,
  parseSubscription,
  settingsDocument,
  settingsDocumentId,
  settingsFromDocument,
  storableEntry,
  storableKey,
  subscriptionDocument,
  subscriptionTombstone,
} from "./documents.ts";
import { sampleEntry, sampleFeed } from "./fixtures.ts";

describe("subscriptions", () => {
  test("round trip keeps this device's own fields", () => {
    const feed = sampleFeed({ folder: "News", renamed: true });
    const document = subscriptionDocument(feed);
    expect(document.deleted).toBe(false);
    const back = feedFromDocument(feed.id, document, {
      ...feed,
      lastFetched: 9,
      error: "broken",
    });
    expect(back).toEqual({ ...feed, lastFetched: 9, error: "broken" });
  });

  test("another device's change keeps this device's own name, site and picture", () => {
    const local = sampleFeed({
      title: "New name",
      siteUrl: "https://a.example/",
      icon: "https://a.example/icon.png",
    });
    const older = subscriptionDocument(
      sampleFeed({ title: "Old name", siteUrl: "", icon: "", folder: "News" }),
    );
    const back = feedFromDocument(local.id, older, local);
    expect(back.title).toBe("New name");
    expect(back.siteUrl).toBe("https://a.example/");
    expect(back.icon).toBe("https://a.example/icon.png");
    expect(back.folder).toBe("News");
  });

  test("a name given by hand on another device is taken", () => {
    const local = sampleFeed({ title: "Feed's own name" });
    const named = subscriptionDocument(
      sampleFeed({ title: "My name", renamed: true }),
    );
    const back = feedFromDocument(local.id, named, local);
    expect(back.title).toBe("My name");
    expect(back.renamed).toBe(true);
  });

  test("a feed new to this device has never been fetched", () => {
    const back = feedFromDocument("feed-1", subscriptionDocument(sampleFeed()));
    expect(back.lastFetched).toBe(0);
    expect(back.error).toBe("");
  });

  test("tombstones parse and carry no feed", () => {
    const parsed = parseSubscription({ ...subscriptionTombstone(7) });
    expect(parsed).toEqual({ ...subscriptionTombstone(7) });
  });

  test("documents without a time or address are rejected", () => {
    expect(parseSubscription({ url: "https://a" })).toBeUndefined();
    expect(parseSubscription({ updatedAt: 1, url: "" })).toBeUndefined();
  });
});

describe("entries", () => {
  test("round trip", () => {
    const entry = sampleEntry({ bodyHash: "abc", starred: true });
    const document = entryDocument(entry);
    expect(document.expireAt).toBeUndefined();
    expect(entryFromDocument(entry.id, document)).toEqual(entry);
    expect(parseEntry({ ...document })).toEqual(document);
  });

  test("a finite retention sets the expiry from when it was added", () => {
    const document = entryDocument(sampleEntry({ addedAt: 0 }), 30);
    expect(document.expireAt).toBe(30 * 24 * 60 * 60 * 1000);
  });

  test("bad fields fall back to defaults", () => {
    const parsed = parseEntry({
      feedId: "feed-1",
      updatedAt: 3,
      categories: ["ok", 4],
      read: "yes",
      published: Number.NaN,
    });
    expect(parsed?.categories).toEqual(["ok"]);
    expect(parsed?.read).toBe(false);
    expect(parsed?.published).toBe(0);
    expect(parsed?.dated).toBe(true);
  });

  test("an entry without a feed is rejected", () => {
    expect(parseEntry({ updatedAt: 3 })).toBeUndefined();
  });
});

describe("settings", () => {
  test("round trip", () => {
    const saved = {
      sort: "oldest" as const,
      hideWords: ["a"],
      onlyWords: [],
      offTopics: ["b"],
      updatedAt: 4,
    };
    const document = settingsDocument("folder:News", saved, 4);
    expect(parseSettings({ ...document })).toEqual(document);
    expect(settingsFromDocument(document)).toEqual(saved);
  });

  test("tombstones", () => {
    const document = settingsDocument("all", undefined, 8);
    expect(document.deleted).toBe(true);
    expect(document.updatedAt).toBe(8);
  });

  test("document ids hold no slash", () => {
    expect(settingsDocumentId("folder:A/B")).toBe("folder%3AA%2FB");
  });
});

describe("https only", () => {
  test("account feeds and entries have plain http upgraded", () => {
    const feed = parseSubscription({
      ...subscriptionDocument(
        sampleFeed({
          url: "http://old.example/rss",
          siteUrl: "http://old.example/",
          icon: "http://old.example/icon.png",
        }),
      ),
    });
    expect(feed?.url).toBe("https://old.example/rss");
    expect(feed?.siteUrl).toBe("https://old.example/");
    expect(feed?.icon).toBe("https://old.example/icon.png");
    const entry = parseEntry({
      ...entryDocument(
        sampleEntry({
          url: "http://old.example/1",
          imageUrl: "http://old.example/1.png",
        }),
      ),
    });
    expect(entry?.url).toBe("https://old.example/1");
    expect(entry?.imageUrl).toBe("https://old.example/1.png");
  });

  test("an account feed with no web address is dropped", () => {
    expect(
      parseSubscription({
        ...subscriptionDocument(sampleFeed({ url: "ftp://old.example/rss" })),
      }),
    ).toBeUndefined();
  });
});

describe("limits the rules set", () => {
  test("long text is cut to fit, and long addresses are dropped", () => {
    const entry = sampleEntry({
      summary: "s".repeat(FIELD_LIMITS.summary + 5),
      imageUrl: `https://example.com/${"a".repeat(FIELD_LIMITS.address)}`,
      categories: Array.from({ length: 150 }, (_, index) => `topic ${index}`),
    });
    const document = entryDocument(entry);
    expect(document.summary).toHaveLength(FIELD_LIMITS.summary);
    expect(document.imageUrl).toBe("");
    expect(document.categories).toHaveLength(FIELD_LIMITS.categories);
  });

  test("text is never cut inside a character", () => {
    expect(cutText("ab😀", 3)).toBe("ab");
    expect(cutText("ab😀", 4)).toBe("ab😀");
  });

  test("an entry whose guid is too long stays on this device", () => {
    expect(storableEntry(sampleEntry())).toBe(true);
    expect(
      storableEntry(sampleEntry({ guid: "g".repeat(FIELD_LIMITS.guid + 1) })),
    ).toBe(false);
  });
});

describe("view keys", () => {
  test("a key is kept only while its encoded document id fits Firestore's limit", () => {
    expect(storableKey("folder:News")).toBe(true);
    // 400 characters, but each is four bytes encoded as twelve
    const wide = `folder:${"\u{1F600}".repeat(200)}`;
    expect(wide.length).toBeLessThanOrEqual(FIELD_LIMITS.key);
    expect(storableKey(wide)).toBe(false);
  });
});
