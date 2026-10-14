import { beforeEach, describe, expect, test } from "bun:test";
import type { ParsedFeed } from "$shared/feed.ts";
import { DEFAULT_SETTINGS } from "$shared/filter.ts";
import { UNSETTLED_TRIES } from "$shared/https.ts";
import { entryId, feedId } from "$shared/id.ts";
import type { ExtensionResponse } from "$shared/protocol.ts";
import { OVERDUE_MS } from "$shared/schedule.ts";
import { copy } from "./copy.ts";
import { GATE_TIMING } from "./load-gate.ts";
import { Reader } from "./reader.svelte.ts";
import { sampleEntry, sampleFeed } from "./sync/fixtures.ts";
import { emptyChange, type LocalChange } from "./sync/host.ts";
import { TRACK_BUDGET } from "./track.ts";
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

describe("starting", () => {
  test("with no extension, checking ends and nothing is shown", async () => {
    const reader = new Reader();
    await reader.init();
    expect(reader.checked).toBe(true);
    expect(reader.loaded).toBe(true);
    expect(reader.extension.version).toBe("");
    expect(reader.feeds).toEqual([]);
  });

  test("with the extension and nothing stored, the reader opens empty", async () => {
    const reader = new Reader();
    const replies: Partial<Record<string, ExtensionResponse>> = {
      ping: { type: "pong", version: "1.0.0" },
      drain: { type: "drained", seq: 0, more: false, status: [], pulled: [] },
    };
    reader.extension.send = (request): Promise<ExtensionResponse | undefined> =>
      Promise.resolve(replies[request.type]);
    await reader.init();
    expect(reader.checked).toBe(true);
    expect(reader.extension.version).toBe("1.0.0");
    expect(reader.feeds).toEqual([]);
    expect(reader.entries).toEqual([]);
  });

  test("opening asks the extension to check due feeds, not every feed, and shows no refresh", async () => {
    const reader = new Reader();
    const sent: string[] = [];
    const replies: Partial<Record<string, ExtensionResponse>> = {
      ping: { type: "pong", version: "1.0.0" },
      track: { type: "tracked" },
      checkDue: { type: "queued" },
      drain: { type: "drained", seq: 0, more: false, status: [], pulled: [] },
    };
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> => {
      sent.push(request.type);
      return Promise.resolve(replies[request.type]);
    };
    const opening = reader.init();
    expect(reader.refreshing).toBe(false);
    await opening;
    expect(sent).toContain("checkDue");
    expect(sent).toContain("drain");
    expect(sent).not.toContain("refresh");
    expect(reader.refreshing).toBe(false);
  });

  test("an extension too old to check due feeds still opens and drains", async () => {
    const reader = new Reader();
    const sent: string[] = [];
    const replies: Partial<Record<string, ExtensionResponse>> = {
      ping: { type: "pong", version: "1.0.0" },
      checkDue: { type: "error", message: "Unknown request." },
      drain: { type: "drained", seq: 0, more: false, status: [], pulled: [] },
    };
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> => {
      sent.push(request.type);
      return Promise.resolve(replies[request.type]);
    };
    await reader.init();
    expect(reader.checked).toBe(true);
    expect(sent).toContain("drain");
  });

  test("an extension that stops answering is gone only after a few checks", async () => {
    const reader = makeReader([]);
    reader.extension.version = "1.0.0";
    reader.extension.send = (): Promise<ExtensionResponse | undefined> =>
      Promise.resolve(undefined);
    await reader.collect();
    await reader.collect();
    expect(reader.extension.version).toBe("1.0.0");
    await reader.collect();
    expect(reader.extension.version).toBe("");
  });

  test("a notice shows its text", () => {
    const reader = makeReader([]);
    reader.notify("Saved");
    expect(reader.notice).toBe("Saved");
  });
});

describe("loading", () => {
  /** A reader past its start: stored data read and the extension found. */
  function started(entries: Entry[]): Reader {
    const reader = makeReader(entries);
    reader.checked = true;
    reader.connected = true;
    reader.extension.version = "1.0.0";
    return reader;
  }

  test("holds the person until what is stored and found has loaded", () => {
    const reader = new Reader();
    expect(reader.loadingBusy).toBe(true);
    expect(reader.gateBlocks).toBe(true);
    reader.checked = true;
    reader.extension.version = "1.0.0";
    reader.loaded = true;
    expect(reader.loadingBusy).toBe(true);
    reader.connected = true;
    expect(reader.loadingBusy).toBe(false);
  });

  test("an instant load lets the person in without the logo and settles the lists", () => {
    const reader = started([article("a", { feedId: "tech" })]);
    reader.updateGate(false, reader.gate.busySince ?? 0);
    expect(reader.gate.phase).toBe("open");
    expect(reader.heldEntries?.has("a")).toBe(true);
    expect(reader.heldFeeds?.has("comics")).toBe(true);
  });

  test("a slow load shows the logo, and one that never ends lets the person in", () => {
    const reader = started([]);
    const start = reader.gate.busySince ?? 0;
    reader.updateGate(true, start + GATE_TIMING.delayMs);
    expect(reader.gate.phase).toBe("shown");
    reader.updateGate(true, start + GATE_TIMING.timeoutMs);
    expect(reader.gateBlocks).toBe(false);
    expect(reader.sync.awaited).toBe(false);
  });

  test("the account holds the person while it arrives, but not offline or failing", () => {
    const reader = started([]);
    reader.sync.email = "reader@example.com";
    reader.sync.status = "syncing";
    expect(reader.loadingBusy).toBe(true);
    reader.sync.status = "offline";
    expect(reader.loadingBusy).toBe(false);
    reader.sync.status = "error";
    expect(reader.loadingBusy).toBe(false);
    reader.sync.status = "synced";
    reader.sync.caughtUp = true;
    expect(reader.loadingBusy).toBe(false);
    reader.sync.caughtUp = false;
    reader.sync.awaited = false;
    expect(reader.loadingBusy).toBe(false);
  });

  test("an import holds the person until its feeds' first check", () => {
    const reader = started([]);
    reader.importing = 1;
    expect(reader.loadingBusy).toBe(true);
  });

  test("articles arriving later wait until the list is next settled", async () => {
    const reader = started([article("a", { feedId: "tech", published: 1 })]);
    reader.settle();
    await reader["applyRemote"]({
      ...emptyChange(),
      entries: [article("b", { feedId: "tech", published: 2 })],
    });
    expect(reader.visible.map((entry) => entry.id)).toEqual(["a"]);
    expect(reader.unreadTotal).toBe(2);
    reader.select({ kind: "feed", id: "tech" });
    expect(reader.visible.map((entry) => entry.id)).toEqual(["b", "a"]);
  });

  test("an empty list takes in what arrives, since no row can move", async () => {
    const reader = started([]);
    reader.settle();
    await reader["applyRemote"]({
      ...emptyChange(),
      entries: [article("b", { feedId: "tech" })],
    });
    expect(reader.visible.map((entry) => entry.id)).toEqual(["b"]);
  });

  test("an article read elsewhere stays listed until the view changes", async () => {
    const reader = started([article("a", { feedId: "tech" })]);
    reader.settle();
    await reader["applyRemote"]({
      ...emptyChange(),
      entries: [article("a", { feedId: "tech", read: true, updatedAt: 9 })],
    });
    expect(reader.visible.map((entry) => entry.id)).toEqual(["a"]);
    reader.select({ kind: "starred" });
    reader.select({ kind: "all" });
    expect(reader.visible).toEqual([]);
  });

  test("a feed with nothing unread stays out of the sidebar when articles arrive", async () => {
    const reader = started([article("a", { feedId: "tech" })]);
    reader.settle();
    const [, , comics] = reader.feeds;
    expect(comics && reader.listed(comics)).toBe(false);
    await reader["applyRemote"]({
      ...emptyChange(),
      entries: [article("c", { feedId: "comics" })],
    });
    expect(comics && reader.listed(comics)).toBe(false);
    reader.settle();
    expect(comics && reader.listed(comics)).toBe(true);
  });
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

describe("unread counts as marks change", () => {
  test("marking one article read updates every count, and a mark changes a copy of the entry", async () => {
    const reader = makeReader([
      article("a", { feedId: "tech", title: "Rust release" }),
      article("b", { feedId: "tech", title: "Crypto news" }),
      article("c", { feedId: "world", title: "Markets" }),
    ]);
    reader.settings = { "feed:tech": settings({ hideWords: ["crypto"] }) };
    const before = reader.entries.find((entry) => entry.id === "a");
    expect(reader.unreadByFeed.get("tech")).toBe(1);
    expect(reader.unreadTotal).toBe(2);
    await reader.toggleReadFor("a");
    expect(reader.unreadByFeed.get("tech")).toBeUndefined();
    expect(reader.tree.find((item) => item.kind === "folder")?.unread).toBe(1);
    expect(reader.unreadTotal).toBe(1);
    const after = reader.entries.find((entry) => entry.id === "a");
    expect(after?.read).toBe(true);
    expect(before?.read).toBe(false);
  });

  test("the open view's count and list follow its own filters and held topics", async () => {
    const reader = makeReader([
      article("a", { feedId: "tech", categories: ["Rust"] }),
      article("b", { feedId: "tech", categories: ["Go"] }),
    ]);
    reader.select({ kind: "feed", id: "tech" });
    reader.settings = { "feed:tech": settings({ offTopics: ["go"] }) };
    expect(reader.viewUnread).toBe(1);
    expect(reader.visible.map((entry) => entry.id)).toEqual(["a"]);
    await reader.toggleStarFor("a");
    expect(reader.entries.find((entry) => entry.id === "a")?.starred).toBe(
      true,
    );
    expect(reader.viewUnread).toBe(1);
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
    await reader.open("a");
    expect(await reader.step(-1)).toBe(false);
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

describe("reading ahead", () => {
  /** Three articles, newest first, whose bodies are only in the account; `fetched` lists each body read. */
  function aheadReader(signedIn: boolean): {
    reader: Reader;
    fetched: string[];
  } {
    const reader = makeReader([
      article("a", { published: 3, bodyHash: "ha" }),
      article("b", { published: 2, bodyHash: "hb" }),
      article("c", { published: 1, bodyHash: "hc" }),
    ]);
    const fetched: string[] = [];
    reader.sync.email = signedIn ? "me@example.com" : "";
    reader.sync.fetchBody = async (hash: string): Promise<string> => {
      fetched.push(hash);
      await new Promise((resolve) => {
        setTimeout(resolve, 1);
      });
      return `<p>${hash}</p>`;
    };
    return { reader, fetched };
  }

  function idle(): Promise<void> {
    return new Promise((resolve) => {
      setTimeout(resolve, 20);
    });
  }

  test("signed in, the next article's body is read once, in the way the reader moves", async () => {
    const { reader, fetched } = aheadReader(true);
    await reader.open("b");
    await idle();
    expect(fetched).toEqual(["hb", "hc"]);
    expect(await reader.step(-1)).toBe(true);
    await idle();
    expect(fetched).toEqual(["hb", "hc", "ha"]);
    expect(await reader.step(1)).toBe(true);
    await idle();
    expect(fetched).toEqual(["hb", "hc", "ha"]);
  });

  test("signed out, nothing is read ahead", async () => {
    const { reader, fetched } = aheadReader(false);
    await reader.open("b");
    await idle();
    expect(fetched).toEqual(["hb"]);
  });

  test("opening the article ahead while it downloads reads it once", async () => {
    const { reader, fetched } = aheadReader(true);
    await reader.open("b");
    await reader.open("c");
    await idle();
    expect(fetched).toEqual(["hb", "hc"]);
    expect(reader.body).toContain("hc");
  });
});

describe("bodies gone from the account", () => {
  const YearAndWeek = (365 + 7) * 24 * 60 * 60 * 1000;

  /** Open an article whose body is on this device, added `age` ago; what is offered back to the account. */
  async function offeredAfterOpening(age: number): Promise<[string, string][]> {
    const reader = makeReader([
      article("a", { addedAt: Date.now() - age, bodyHash: "ha" }),
    ]);
    reader["memoryBodies"].set("a", "<p>kept here</p>");
    const offered: [string, string][] = [];
    reader.sync.restoreBody = (hash: string, text: string): void => {
      offered.push([hash, text]);
    };
    await reader.open("a");
    return offered;
  }

  test("opening an article older than a body can last offers this device's copy", async () => {
    expect(await offeredAfterOpening(YearAndWeek + 60_000)).toEqual([
      ["ha", "<p>kept here</p>"],
    ]);
  });

  test("a newer article's body cannot have expired, so nothing is looked up", async () => {
    expect(await offeredAfterOpening(YearAndWeek - 60_000)).toEqual([]);
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

  test("a refresh is suggested only while a feed is overdue and nothing is being checked", async () => {
    const reader = makeReader([]);
    reader.extension.version = "test";
    const now = Date.now();
    let checking = false;
    let techNext = now - OVERDUE_MS - 1;
    reader.extension.send = (request): Promise<ExtensionResponse | undefined> =>
      Promise.resolve(
        request.type === "drain"
          ? {
              type: "drained",
              seq: 0,
              more: false,
              pulled: [],
              checking,
              status: [
                {
                  feedId: "tech",
                  lastChecked: 1,
                  error: "",
                  nextCheck: techNext,
                },
                { feedId: "world", lastChecked: 1, error: "", nextCheck: now },
                // an older extension sends no next check
                { feedId: "comics", lastChecked: 1, error: "" },
                { feedId: "gone", lastChecked: 1, error: "", nextCheck: 0 },
              ],
            }
          : undefined,
      );
    await reader["drainExtension"]();
    expect(reader.refreshSuggested).toBe(true);
    checking = true;
    await reader["drainExtension"]();
    expect(reader.refreshSuggested).toBe(false);
    checking = false;
    techNext = now + 60_000;
    await reader["drainExtension"]();
    expect(reader.overdue).toBe(false);
    expect(reader.refreshSuggested).toBe(false);
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

describe("plain http feeds", () => {
  /** An RSS document with this title, site and guids. */
  function rssOf(title: string, site: string, guids: string[] = []): string {
    const items = guids
      .map((guid) => `<item><title>${guid}</title><guid>${guid}</guid></item>`)
      .join("");
    return `<rss><channel><title>${title}</title><link>${site}</link>${items}</channel></rss>`;
  }

  function fetched(url: string, text: string | undefined): ExtensionResponse {
    return {
      type: "fetched",
      status: text === undefined ? 404 : 200,
      url,
      text: text ?? "",
      etag: "",
      lastModified: "",
      hint: 0,
      insecure: false,
    };
  }

  /** An extension that answers fetches from `pages` by address (404 for others) and records them in `asked`. */
  function serve(
    reader: Reader,
    pages: Record<string, string>,
    asked: string[] = [],
  ): ExtensionResponse[] {
    const tracks: ExtensionResponse[] = [];
    reader.extension.version = "test";
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> => {
      let response: ExtensionResponse | undefined;
      if (request.type === "fetch") {
        asked.push(request.url);
        response = fetched(request.url, pages[request.url]);
      } else if (request.type === "drain") {
        response = {
          type: "drained",
          seq: 0,
          more: false,
          status: [],
          pulled: [],
        };
      } else if (request.type === "track") {
        tracks.push({ type: "error", message: JSON.stringify(request.feeds) });
      }
      return Promise.resolve(response);
    };
    return tracks;
  }

  test("a typed http address is refused without fetching anything", async () => {
    const reader = makeReader([]);
    const asked: string[] = [];
    serve(reader, {}, asked);
    await expect(reader.addFeed("http://blog.example/rss")).rejects.toThrow(
      copy.httpRefused,
    );
    expect(asked).toEqual([]);
  });

  test("an import moves http feeds to https when it serves them, and counts the rest", async () => {
    const reader = makeReader([]);
    serve(reader, {
      "https://good.example/rss": rssOf("Good", "https://good.example/"),
    });
    const result = await reader.importOpml(
      `<opml><body><outline text="Mine">
        <outline text="Good" xmlUrl="http://good.example/rss"/>
        <outline text="Gone" xmlUrl="http://gone.example/rss"/>
        <outline text="Safe" xmlUrl="https://safe.example/rss"/>
      </outline></body></opml>`,
    );
    expect(result).toEqual({ found: 3, added: 2, skipped: 1 });
    expect(
      reader.feeds
        .filter((feed) => feed.folder === "Mine")
        .map((feed) => feed.url)
        .sort(),
    ).toEqual(["https://good.example/rss", "https://safe.example/rss"]);
  });

  test("an import goes into the chosen group, or none, or the file's folders", async () => {
    const opml =
      '<opml><body><outline text="Theirs"><outline text="A" xmlUrl="https://a.example/rss"/></outline></body></opml>';
    const grouped = makeReader([]);
    await grouped.importOpml(opml, "News");
    expect(grouped.feeds.find((feed) => feed.title === "A")?.folder).toBe(
      "News",
    );
    const loose = makeReader([]);
    await loose.importOpml(opml, "");
    expect(loose.feeds.find((feed) => feed.title === "A")?.folder).toBe("");
    const kept = makeReader([]);
    await kept.importOpml(opml);
    expect(kept.feeds.find((feed) => feed.title === "A")?.folder).toBe(
      "Theirs",
    );
  });

  test("an import does not add the https copy of a feed already followed over http", async () => {
    const reader = makeReader([]);
    reader.feeds = [sampleFeed({ id: "old", url: "http://blog.example/rss" })];
    serve(reader, {});
    const result = await reader.importOpml(
      '<opml><body><outline text="Blog" xmlUrl="http://blog.example/rss"/></body></opml>',
    );
    expect(result).toEqual({ found: 1, added: 0, skipped: 0 });
    expect(reader.feeds.map((feed) => feed.id)).toEqual(["old"]);
  });

  test("search results on http are shown at https when it serves the same feed, else dropped", async () => {
    const reader = makeReader([]);
    const asked: string[] = [];
    serve(
      reader,
      {
        "https://good.example/rss": rssOf("Good", "https://good.example/"),
        "https://other.example/rss": rssOf(
          "Parked",
          "https://parking.example/",
        ),
      },
      asked,
    );
    const answer = reader.extension.send;
    const search = JSON.stringify({
      results: [
        { feedId: "feed/http://good.example/rss", title: "Good" },
        { feedId: "feed/http://other.example/rss", title: "Other" },
        { feedId: "feed/http://gone.example/rss", title: "Gone" },
      ],
    });
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> =>
      request.type === "fetch" &&
      request.url.startsWith("https://cloud.feedly.com/")
        ? Promise.resolve(fetched(request.url, search))
        : answer(request);
    const hits = await reader.searchFeeds("good");
    expect(hits.map((hit) => hit.url)).toEqual(["https://good.example/rss"]);
    expect(
      asked.toSorted((first, second) => first.localeCompare(second)),
    ).toEqual([
      "https://gone.example/rss",
      "https://good.example/rss",
      "https://other.example/rss",
    ]);
  });

  test("a followed http feed moves to https once, keeping its id, articles and group", async () => {
    const reader = makeReader([
      article(await entryId("old", "post-1"), {
        feedId: "old",
        guid: "post-1",
        starred: true,
      }),
    ]);
    reader.feeds = [
      sampleFeed({
        id: "old",
        url: "http://blog.example/rss",
        title: "Renamed",
        folder: "News",
        error: copy.httpFeed,
        updatedAt: 1,
      }),
      sampleFeed({ id: "dead", url: "http://dead.example/rss" }),
    ];
    const queued: LocalChange[] = [];
    reader.sync.queue = (change): void => {
      queued.push(change);
    };
    const asked: string[] = [];
    const tracks = serve(
      reader,
      {
        "https://blog.example/rss": rssOf("Blog", "https://other.example/", [
          "post-1",
          "post-2",
        ]),
      },
      asked,
    );
    await reader["upgradeHttpFeeds"]();
    const moved = reader.feeds.find((feed) => feed.id === "old");
    expect(moved?.url).toBe("https://blog.example/rss");
    expect(moved?.folder).toBe("News");
    expect(moved?.error).toBe("");
    expect(moved?.updatedAt).toBeGreaterThan(1);
    expect(
      queued.flatMap((change) => change.feeds ?? []).map((feed) => feed.id),
    ).toEqual(["old"]);
    expect(
      reader.entries
        .filter((entry) => entry.feedId === "old")
        .map((entry) => [entry.guid, entry.starred]),
    ).toEqual([
      ["post-1", true],
      ["post-2", false],
    ]);
    const lastTrack = tracks.at(-1);
    const tracked: unknown = JSON.parse(
      lastTrack?.type === "error" ? lastTrack.message : "[]",
    );
    expect(tracked).toEqual([
      {
        id: "old",
        url: "https://blog.example/rss",
        empty: false,
        first: expect.objectContaining({
          status: 200,
          url: "https://blog.example/rss",
        }),
      },
    ]);
    expect(moved?.lastFetched).toBeGreaterThan(0);
    await reader["upgradeHttpFeeds"]();
    expect(
      asked.toSorted((first, second) => first.localeCompare(second)),
    ).toEqual(["https://blog.example/rss", "https://dead.example/rss"]);
    await reader["markHttpFeeds"]();
    expect(reader.feeds.find((feed) => feed.id === "dead")?.error).toBe(
      copy.httpFeed,
    );
  });

  test("an http feed that got no definite answer is tried again on the next refresh", async () => {
    const reader = makeReader([]);
    reader.feeds = [
      sampleFeed({ id: "busy", url: "http://busy.example/rss" }),
      sampleFeed({ id: "offline", url: "http://offline.example/rss" }),
    ];
    const asked: string[] = [];
    reader.extension.version = "test";
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> => {
      let response: ExtensionResponse | undefined;
      if (request.type === "fetch") {
        asked.push(request.url);
        // a failing server answers; the other fetch could not be made at all
        const failing = fetched(request.url, undefined);
        response =
          request.url.includes("busy") && failing.type === "fetched"
            ? { ...failing, status: 503 }
            : undefined;
      }
      return Promise.resolve(response);
    };
    await reader["upgradeHttpFeeds"]();
    await reader["upgradeHttpFeeds"]();
    expect(
      asked.toSorted((first, second) => first.localeCompare(second)),
    ).toEqual([
      "https://busy.example/rss",
      "https://busy.example/rss",
      "https://offline.example/rss",
      "https://offline.example/rss",
    ]);
    expect(reader.feeds.map((feed) => feed.url)).toEqual([
      "http://busy.example/rss",
      "http://offline.example/rss",
    ]);
  });

  test("a feed's https address is matched by its own name, not one the person gave it", async () => {
    const reader = makeReader([]);
    reader.feeds = [
      sampleFeed({
        id: "old",
        url: "http://feeds.host.example/a",
        title: "Parked",
        renamed: true,
      }),
    ];
    serve(reader, {
      "https://feeds.host.example/a": rssOf(
        "Parked",
        "https://parking.example/",
      ),
    });
    await reader["upgradeHttpFeeds"]();
    expect(reader.feeds[0]?.url).toBe("http://feeds.host.example/a");
  });

  test("adding the https address of a feed followed over http moves that feed", async () => {
    const reader = makeReader([]);
    reader.feeds = [
      sampleFeed({ id: "old", url: "http://blog.example/rss", title: "Blog" }),
    ];
    const tracks = serve(reader, {
      "https://blog.example/rss": rssOf("Blog", "https://blog.example/"),
    });
    await reader.addFeed("https://blog.example/rss");
    expect(reader.feeds.map((feed) => [feed.id, feed.url])).toEqual([
      ["old", "https://blog.example/rss"],
    ]);
    expect(reader.selection).toEqual({ kind: "feed", id: "old" });
    const lastTrack = tracks.at(-1);
    const tracked: { id: string; first?: { status: number } }[] = JSON.parse(
      lastTrack?.type === "error" ? lastTrack.message : "[]",
    );
    expect(tracked.find((feed) => feed.id === "old")?.first?.status).toBe(200);
  });

  test("adding the https address of a different feed followed over http adds it beside the one followed", async () => {
    const reader = makeReader([]);
    reader.feeds = [
      sampleFeed({ id: "old", url: "http://blog.example/rss", title: "Blog" }),
    ];
    serve(reader, {
      "https://blog.example/rss": rssOf("Parked", "https://parking.example/"),
    });
    await reader.addFeed("https://blog.example/rss");
    const id = await feedId("https://blog.example/rss");
    expect(reader.feeds.map((feed) => [feed.id, feed.url])).toEqual([
      ["old", "http://blog.example/rss"],
      [id, "https://blog.example/rss"],
    ]);
    expect(reader.selection).toEqual({ kind: "feed", id });
    await reader.addFeed("https://blog.example/rss");
    expect(reader.feeds).toHaveLength(2);
    expect(reader.selection).toEqual({ kind: "feed", id });
  });

  test("an http feed with no definite answer is tried a few times a session, then left", async () => {
    const reader = makeReader([]);
    reader.feeds = [sampleFeed({ id: "loop", url: "http://loop.example/rss" })];
    const asked: string[] = [];
    reader.extension.version = "test";
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> => {
      if (request.type === "fetch") {
        asked.push(request.url);
      }
      return Promise.resolve(undefined);
    };
    for (let round = 0; round < UNSETTLED_TRIES + 2; round += 1) {
      // biome-ignore lint/performance/noAwaitInLoops: one refresh after another
      await reader["upgradeHttpFeeds"]();
    }
    expect(asked).toHaveLength(UNSETTLED_TRIES);
  });

  test("an http feed whose https address redirects off https is settled at once", async () => {
    const reader = makeReader([]);
    reader.feeds = [sampleFeed({ id: "loop", url: "http://loop.example/rss" })];
    const asked: string[] = [];
    serve(reader, {}, asked);
    const answer = reader.extension.send;
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> =>
      request.type === "fetch"
        ? answer(request).then(() => ({
            type: "fetched",
            status: 0,
            url: "",
            text: "",
            etag: "",
            lastModified: "",
            hint: 0,
            insecure: true,
          }))
        : answer(request);
    await reader["upgradeHttpFeeds"]();
    await reader["upgradeHttpFeeds"]();
    expect(asked).toEqual(["https://loop.example/rss"]);
    expect(reader.feeds[0]?.url).toBe("http://loop.example/rss");
  });

  test("a typed https address that redirects off https says so", async () => {
    const reader = makeReader([]);
    reader.extension.version = "test";
    reader.extension.send = (): Promise<ExtensionResponse | undefined> =>
      Promise.resolve({
        type: "fetched",
        status: 0,
        url: "",
        text: "",
        etag: "",
        lastModified: "",
        hint: 0,
        insecure: true,
      });
    await expect(reader.addFeed("https://loop.example/rss")).rejects.toThrow(
      copy.notHttps,
    );
  });

  test("http search results and feeds on local or private hosts are never tried", async () => {
    const reader = makeReader([]);
    reader.feeds = [sampleFeed({ id: "nas", url: "http://nas.local/rss" })];
    const asked: string[] = [];
    serve(reader, {}, asked);
    const answer = reader.extension.send;
    const search = JSON.stringify({
      results: [
        { feedId: "feed/http://192.168.1.1/rss", title: "Router" },
        { feedId: "feed/http://localhost:8080/rss", title: "Local" },
        { feedId: "feed/http://169.254.169.254/latest", title: "Metadata" },
      ],
    });
    reader.extension.send = (
      request,
    ): Promise<ExtensionResponse | undefined> =>
      request.type === "fetch" &&
      request.url.startsWith("https://cloud.feedly.com/")
        ? Promise.resolve(fetched(request.url, search))
        : answer(request);
    expect(await reader.searchFeeds("router")).toEqual([]);
    await reader["upgradeHttpFeeds"]();
    await reader["upgradeHttpFeeds"]();
    expect(asked).toEqual([]);
  });

  test("feeds moved to https hand their answers on in messages of a bounded size", async () => {
    const reader = makeReader([]);
    const big = "x".repeat(TRACK_BUDGET / 2);
    reader.feeds = ["a", "b", "c"].map((name) =>
      sampleFeed({ id: name, url: `http://${name}.example/rss`, title: name }),
    );
    const tracks = serve(
      reader,
      Object.fromEntries(
        ["a", "b", "c"].map((name) => [
          `https://${name}.example/rss`,
          rssOf(name, `https://${name}.example/`).replace(
            "</channel>",
            `<description>${big}</description></channel>`,
          ),
        ]),
      ),
    );
    await reader["upgradeHttpFeeds"]();
    const sent = tracks.map((track) => {
      const feeds: { id: string; first?: unknown }[] = JSON.parse(
        track.type === "error" ? track.message : "[]",
      );
      return feeds;
    });
    expect(sent.length).toBeGreaterThan(1);
    for (const feeds of sent) {
      expect(feeds.map((feed) => feed.id)).toEqual(["a", "b", "c"]);
    }
    expect(
      sent
        .flatMap((feeds) =>
          feeds.filter((feed) => feed.first).map((feed) => feed.id),
        )
        .sort(),
    ).toEqual(["a", "b", "c"]);
  });

  test("an added feed's answer is handed on as its first check", async () => {
    const reader = makeReader([]);
    const tracks = serve(reader, {
      "https://new.example/rss": rssOf("New", "https://new.example/"),
    });
    await reader.addFeed("https://new.example/rss");
    const lastTrack = tracks.at(-1);
    const tracked: { url: string; first?: { status: number } }[] = JSON.parse(
      lastTrack?.type === "error" ? lastTrack.message : "[]",
    );
    expect(
      tracked.find((feed) => feed.url === "https://new.example/rss")?.first
        ?.status,
    ).toBe(200);
  });

  test("an http feed whose https address is already followed stays as it is", async () => {
    const reader = makeReader([]);
    reader.feeds = [
      sampleFeed({ id: "old", url: "http://blog.example/rss", title: "Blog" }),
      sampleFeed({ id: "new", url: "https://blog.example/rss", title: "Blog" }),
    ];
    serve(reader, {
      "https://blog.example/rss": rssOf("Blog", "https://blog.example/"),
    });
    await reader["upgradeHttpFeeds"]();
    expect(reader.feeds.map((feed) => feed.url)).toEqual([
      "http://blog.example/rss",
      "https://blog.example/rss",
    ]);
  });
});
