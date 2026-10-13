import { beforeEach, describe, expect, test } from "bun:test";
import type { ParsedFeed } from "$shared/feed.ts";
import { DEFAULT_SETTINGS } from "$shared/filter.ts";
import type { ExtensionResponse } from "$shared/protocol.ts";
import { Reader } from "./reader.svelte.ts";
import { sampleEntry, sampleFeed } from "./sync/fixtures.ts";
import type { LocalChange } from "./sync/host.ts";
import type { Entry, SavedSettings } from "./types.ts";

function settings(change: Partial<SavedSettings>): SavedSettings {
  return { ...DEFAULT_SETTINGS, updatedAt: 1, ...change };
}

function article(id: string, change: Partial<Entry> = {}): Entry {
  return sampleEntry({ id, guid: id, title: `Story ${id}`, ...change });
}

/** A reader with no database or extension, holding two feeds in a group and one on its own. */
function makeReader(entries: Entry[]): Reader {
  const reader = new Reader();
  reader.feeds = [
    sampleFeed({ id: "tech", title: "Tech", folder: "News" }),
    sampleFeed({ id: "world", title: "World", folder: "News" }),
    sampleFeed({ id: "comics", title: "Comics" }),
  ];
  reader.entries = entries;
  reader.loaded = true;
  return reader;
}

beforeEach(() => {
  localStorage.clear();
});

describe("unread counts", () => {
  test("count only what each list would show", () => {
    const reader = makeReader([
      article("a", { feedId: "tech", title: "Rust release" }),
      article("b", { feedId: "tech", title: "Crypto news" }),
      article("c", { feedId: "world", title: "Crypto markets" }),
      article("d", { feedId: "world", title: "Elections", read: true }),
      article("e", { feedId: "comics", categories: ["Spoilers"] }),
    ]);
    reader.settings = {
      "feed:tech": settings({ hideWords: ["crypto"] }),
      "folder:News": settings({ hideWords: ["markets"] }),
      "feed:comics": settings({ offTopics: ["spoilers"] }),
    };
    expect(reader.unreadByFeed.get("tech")).toBe(1);
    expect(reader.unreadByFeed.get("world")).toBe(1);
    expect(reader.unreadByFeed.get("comics")).toBeUndefined();
    const group = reader.tree.find((item) => item.kind === "folder");
    expect(group?.unread).toBe(1);
    expect(reader.unreadTotal).toBe(2);
  });

  test("a feed whose unread articles are all hidden is not listed", () => {
    const reader = makeReader([
      article("e", { feedId: "comics", categories: ["Spoilers"] }),
    ]);
    reader.settings = { "feed:comics": settings({ offTopics: ["Spoilers"] }) };
    const [comics] = reader.feeds.filter((feed) => feed.id === "comics");
    expect(comics && reader.listed(comics)).toBe(false);
    reader.select({ kind: "feed", id: "comics" });
    expect(comics && reader.listed(comics)).toBe(true);
    expect(reader.visible).toEqual([]);
  });

  test("mark all read clears what the count shows", async () => {
    const reader = makeReader([
      article("a", { feedId: "tech" }),
      article("b", { feedId: "world" }),
      article("c", { feedId: "comics" }),
    ]);
    reader.select({ kind: "folder", name: "News" });
    await reader.markAllRead();
    expect(reader.tree.find((item) => item.kind === "folder")?.unread).toBe(0);
    expect(reader.unreadTotal).toBe(1);
  });
});

describe("reading", () => {
  test("leaving an article marks it read, and it stays listed until the view changes", async () => {
    const reader = makeReader([
      article("a", { published: 3 }),
      article("b", { published: 2 }),
    ]);
    await reader.open("a");
    expect(reader.current?.read).toBe(false);
    await reader.open("b");
    expect(reader.entries.find((entry) => entry.id === "a")?.read).toBe(true);
    expect(reader.visible.map((entry) => entry.id)).toEqual(["a", "b"]);
    // changing view closes b, which marks it read too
    reader.select({ kind: "starred" });
    reader.select({ kind: "all" });
    await Promise.resolve();
    expect(reader.visible).toEqual([]);
  });

  test("a refresh drops read articles kept in the list as soon as it starts", async () => {
    const reader = makeReader([
      article("a", { published: 3 }),
      article("b", { published: 2 }),
    ]);
    await reader.open("a");
    await reader.open("b");
    expect(reader.visible.map((entry) => entry.id)).toEqual(["a", "b"]);
    reader.extension.version = "test";
    const refreshing = reader.refresh();
    expect(reader.visible.map((entry) => entry.id)).toEqual(["b"]);
    await refreshing;
  });

  test("stepping past either end closes the article", async () => {
    const reader = makeReader([
      article("a", { published: 2 }),
      article("b", { published: 1 }),
    ]);
    expect(await reader.step(1)).toBe(true);
    expect(reader.currentId).toBe("a");
    expect(await reader.step(1)).toBe(true);
    expect(await reader.step(1)).toBe(false);
    expect(reader.currentId).toBe("");
  });

  test("marking the open article unread from the list sticks after leaving", async () => {
    const reader = makeReader([article("a", { read: true })]);
    reader.setUnreadOnly(false);
    await reader.open("a");
    await reader.toggleReadFor("a");
    await reader.close();
    expect(reader.entries[0]?.read).toBe(false);
  });

  test("turning unread only on closes a read article", async () => {
    const reader = makeReader([article("a", { read: true })]);
    reader.setUnreadOnly(false);
    await reader.open("a");
    reader.setUnreadOnly(true);
    expect(reader.currentId).toBe("");
  });

  test("an article without a body shows its summary as text", async () => {
    const reader = makeReader([article("a", { summary: "Short <note>" })]);
    await reader.open("a");
    expect(reader.body).toBe("<p>Short &lt;note&gt;</p>");
  });
});

describe("topics", () => {
  test("a topic turned off from an article keeps the list until the view changes", async () => {
    const reader = makeReader([
      article("a", { categories: ["Rust"] }),
      article("b", { categories: ["Go"] }),
    ]);
    reader.feeds = [...reader.feeds, sampleFeed()];
    reader.select({ kind: "feed", id: "feed-1" });
    await reader.toggleTopic("Rust", true);
    expect(reader.topicOff("rust")).toBe(true);
    expect(reader.visible.map((entry) => entry.id).sort()).toEqual(["a", "b"]);
    reader.select({ kind: "all" });
    reader.select({ kind: "feed", id: "feed-1" });
    expect(reader.visible.map((entry) => entry.id)).toEqual(["b"]);
  });

  test("a topic turned off in the settings panel applies at once", async () => {
    const reader = makeReader([
      article("a", { categories: ["Rust"] }),
      article("b", { categories: ["Go"] }),
    ]);
    await reader.toggleTopic("Rust", false);
    expect(reader.visible.map((entry) => entry.id)).toEqual(["b"]);
  });
});

test("an OPML import keeps titles but lets the feed's own title take over", async () => {
  const reader = makeReader([]);
  const result = await reader.importOpml(
    '<opml><body><outline text="My name" xmlUrl="https://new.example/feed"/><outline text="Dup" xmlUrl="https://example.com/feed"/></body></opml>',
  );
  expect(result.found).toBe(2);
  const imported = reader.feeds.find(
    (feed) => feed.url === "https://new.example/feed",
  );
  expect(imported?.title).toBe("My name");
  expect(imported?.renamed).toBe(false);
});

describe("more counts and views", () => {
  test("All items' own words lower the All count", () => {
    const reader = makeReader([
      article("a", { feedId: "tech", title: "Rust" }),
      article("b", { feedId: "comics", title: "Sponsored post" }),
    ]);
    reader.settings = { all: settings({ hideWords: ["sponsored"] }) };
    expect(reader.unreadTotal).toBe(1);
    expect(reader.unreadByFeed.get("comics")).toBe(1);
  });

  test("Starred counts and marks every starred article, whatever the filters", async () => {
    const reader = makeReader([
      article("a", { feedId: "tech", title: "Crypto", starred: true }),
    ]);
    reader.settings = { "feed:tech": settings({ hideWords: ["crypto"] }) };
    reader.select({ kind: "starred" });
    expect(reader.filtersApply).toBe(false);
    expect(reader.viewUnread).toBe(1);
    await reader.markAllRead();
    expect(reader.entries[0]?.read).toBe(true);
  });

  test("a renamed group keeps its place in the sidebar", async () => {
    const reader = makeReader([article("a", { feedId: "comics" })]);
    reader.freezeOrder();
    const before = reader.tree.map((item) => item.name);
    await reader.renameFolder("News", "Zeta");
    const after = reader.tree.map((item) => item.name);
    expect(after.indexOf("Zeta")).toBe(before.indexOf("News"));
  });
});

describe("talking to the extension", () => {
  function feedWith(guids: string[]): ParsedFeed {
    return {
      title: "Feed",
      siteUrl: "",
      iconUrl: "",
      entries: guids.map((guid) => ({
        guid,
        title: guid,
        url: `http://site.example/${guid}`,
        published: 0,
        author: "",
        imageUrl: "",
        summary: "",
        html: "",
        categories: [],
      })),
    };
  }

  test("one drain adds every pulled feed's new entries, upgraded to https", async () => {
    const reader = makeReader([]);
    reader.extension.version = "test";
    reader.extension.send = (request): Promise<ExtensionResponse | undefined> =>
      Promise.resolve(
        request.type === "drain"
          ? {
              type: "drained",
              seq: 0,
              more: false,
              status: [],
              pulled: [
                {
                  feedId: "tech",
                  feed: feedWith(["one", "two"]),
                  etag: "",
                  lastModified: "",
                },
                {
                  feedId: "world",
                  feed: feedWith(["three"]),
                  etag: "",
                  lastModified: "",
                },
                {
                  feedId: "gone",
                  feed: feedWith(["four"]),
                  etag: "",
                  lastModified: "",
                },
              ],
            }
          : undefined,
      );
    await reader.collect().catch(() => undefined);
    reader.extension.version = "test";
    await reader["drainExtension"]();
    expect(reader.entries.map((entry) => entry.guid).sort()).toEqual([
      "one",
      "three",
      "two",
    ]);
    expect(reader.entries[0]?.url.startsWith("https://")).toBe(true);
  });

  test("a feed's own new name and picture stay on this device and keep its change time", async () => {
    const reader = makeReader([]);
    const queued: LocalChange[] = [];
    reader.sync.queue = (change): void => {
      queued.push(change);
    };
    reader.extension.version = "test";
    reader.extension.send = (request): Promise<ExtensionResponse | undefined> =>
      Promise.resolve(
        request.type === "drain"
          ? {
              type: "drained",
              seq: 0,
              more: false,
              status: [],
              pulled: [
                {
                  feedId: "comics",
                  feed: {
                    ...feedWith([]),
                    title: "Comics Weekly",
                    iconUrl: "https://comics.example/icon.png",
                  },
                  etag: "",
                  lastModified: "",
                },
              ],
            }
          : undefined,
      );
    const before = reader.feeds.find((feed) => feed.id === "comics");
    const updatedAt = before?.updatedAt;
    await reader["drainExtension"]();
    const after = reader.feeds.find((feed) => feed.id === "comics");
    expect(after?.title).toBe("Comics Weekly");
    expect(after?.icon).toBe("https://comics.example/icon.png");
    expect(after?.updatedAt).toBe(updatedAt);
    expect(queued.some((change) => (change.feeds ?? []).length > 0)).toBe(
      false,
    );
  });

  test("a picture that fails is kept when the site's page cannot be read", async () => {
    const reader = makeReader([]);
    reader.feeds = [sampleFeed({ icon: "https://example.com/own.png" })];
    reader.extension.send = (): Promise<ExtensionResponse | undefined> =>
      Promise.resolve({
        type: "fetched",
        status: 404,
        url: "",
        etag: "",
        lastModified: "",
        hint: 0,
        insecure: false,
        text: "",
      });
    await reader.refreshIcon("feed-1");
    expect(reader.feeds[0]?.icon).toBe("https://example.com/own.png");
  });

  test("a picture that fails is replaced by the one the site's page links to", async () => {
    const reader = makeReader([]);
    reader.feeds = [sampleFeed({ icon: "https://example.com/own.png" })];
    reader.extension.send = (): Promise<ExtensionResponse | undefined> =>
      Promise.resolve({
        type: "fetched",
        status: 200,
        url: "",
        etag: "",
        lastModified: "",
        hint: 0,
        insecure: false,
        text: '<html><head><link rel="icon" href="/site.png"></head></html>',
      });
    await reader.refreshIcon("feed-1");
    expect(reader.feeds[0]?.icon).toBe("https://example.com/site.png");
  });

  test("a refresh drains again after a drain that was already running", async () => {
    const reader = makeReader([]);
    reader.extension.version = "test";
    const sent: string[] = [];
    const { promise: firstDrain, resolve: answerFirst } = Promise.withResolvers<
      ExtensionResponse | undefined
    >();
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> => {
      sent.push(request.type);
      const drains = sent.filter((type) => type === "drain").length;
      let response: Promise<ExtensionResponse | undefined> =
        Promise.resolve(undefined);
      if (request.type === "drain" && drains === 1) {
        response = firstDrain;
      } else if (request.type === "drain") {
        response = Promise.resolve({
          type: "drained",
          seq: 0,
          more: false,
          status: [],
          pulled: [
            {
              feedId: "tech",
              feed: feedWith(["fresh"]),
              etag: "",
              lastModified: "",
            },
          ],
        });
      }
      return response;
    };
    const running = reader["drainExtension"]();
    const refreshing = reader.refresh(["tech"]);
    await Promise.resolve();
    answerFirst({
      type: "drained",
      seq: 0,
      more: false,
      status: [],
      pulled: [],
    });
    await running;
    await refreshing;
    expect(sent.filter((type) => type === "drain")).toHaveLength(2);
    expect(reader.entries.map((entry) => entry.guid)).toEqual(["fresh"]);
  });

  test("a mark change is sent even when this device cannot keep it", async () => {
    const reader = makeReader([article("a", { feedId: "tech" })]);
    const queued: LocalChange[] = [];
    reader.sync.queue = (change): void => {
      queued.push(change);
    };
    Reflect.set(reader, "db", {
      updateEntries: () => Promise.reject(new Error("full")),
    });
    await expect(reader.toggleStarFor("a")).rejects.toThrow("full");
    expect(queued.map((change) => change.entries?.[0]?.starred)).toEqual([
      true,
    ]);
  });

  test("search results are upgraded, de-duplicated and need an address", async () => {
    const reader = makeReader([]);
    reader.extension.send = (): Promise<ExtensionResponse | undefined> =>
      Promise.resolve({
        type: "fetched",
        status: 200,
        url: "",
        etag: "",
        lastModified: "",
        hint: 0,
        insecure: false,
        text: JSON.stringify({
          results: [
            { feedId: "feed/http://xkcd.com/atom.xml", title: "xkcd" },
            { feedId: "feed/https://xkcd.com/atom.xml", title: "xkcd again" },
            { feedId: "topic/comics", title: "Not a feed" },
          ],
        }),
      });
    const hits = await reader.searchFeeds("comics");
    expect(hits.map((hit) => hit.url)).toEqual(["https://xkcd.com/atom.xml"]);
  });
});
