import { upgradedAddress } from "$shared/address.ts";
import { statusProblem } from "$shared/check.ts";
import { type ParsedFeed, parseFeed } from "$shared/feed.ts";
import {
  DEFAULT_SETTINGS,
  hasWord,
  passes,
  topicCounts,
  type ViewSettings,
} from "$shared/filter.ts";
import { entryId, feedId } from "$shared/id.ts";
import type { FeedStatus, PulledFeed, TrackedFeed } from "$shared/protocol.ts";
import { discoverIconLinks, feedCandidates } from "$shared/resolve.ts";
import { copy } from "./copy.ts";
import { Database } from "./db.ts";
import { ExtensionClient } from "./extension.svelte.ts";
import { parseOpml } from "./opml.ts";
import { compareEntries } from "./order.ts";
import { excerpt, plainBody, sanitize } from "./sanitize.ts";
import { bodyHash } from "./sync/bodies.ts";
import type { LocalData, RemoteChange } from "./sync/host.ts";
import { FEED_TOMBSTONE, SETTINGS_TOMBSTONE } from "./sync/merge.ts";
import { Sync } from "./sync.svelte.ts";
import type { Entry, Feed, SavedSettings, Selection } from "./types.ts";
import { isWebAddress, normalizeUrl } from "./urls.ts";

const UNREAD_KEY = "h-reader:unread-only";
// shortest time the refreshing marks stay on, so a quick check is still seen
const MIN_REFRESH_MS = 700;
// most addresses tried while looking for a feed
const MAX_DISCOVERY_TRIES = 12;
// most drains in a row while the extension says more is waiting; the next tick takes the rest
const MAX_DRAINS = 20;
const VIEW_KEY = "h-reader:view";
// Feedly's public feed search, reached through the extension
const SEARCH_ADDRESS = "https://cloud.feedly.com/v3/search/feeds";
// Feedly writes a feed's id as "feed/" and its address
const FEED_PREFIX = /^feed\//u;

/** New entries with their bodies, waiting to be added. */
interface PreparedEntries {
  /** The new entries. */
  entries: Entry[];
  /** Article bodies by entry id. */
  bodies: Map<string, string>;
}

/**
 * A feed's entries not in `known`, with their bodies and body hashes,
 * ready for `commitEntries`; their ids are added to `known`.
 */
async function prepareEntries(
  feed: Feed,
  parsed: ParsedFeed,
  known: Set<string>,
): Promise<PreparedEntries> {
  const now = Date.now();
  const entries: Entry[] = [];
  const bodies = new Map<string, string>();
  const ids = await Promise.all(
    parsed.entries.map((item) => entryId(feed.id, item.guid)),
  );
  for (const [index, item] of parsed.entries.entries()) {
    const id = ids[index];
    if (id && !known.has(id)) {
      known.add(id);
      entries.push({
        id,
        feedId: feed.id,
        guid: item.guid,
        title: item.title,
        url: upgradedAddress(item.url),
        published: item.published || now,
        dated: item.published !== 0,
        author: item.author,
        imageUrl: upgradedAddress(item.imageUrl),
        excerpt: item.summary || excerpt(item.html),
        summary: item.summary,
        categories: item.categories,
        read: false,
        starred: false,
        addedAt: now,
        position: index,
        bodyHash: "",
        // marks never set here, so any the account already has win
        updatedAt: 0,
      });
      bodies.set(id, item.html);
    }
  }
  const hashes = await Promise.all(
    entries.map((entry) => bodyHash(bodies.get(entry.id) ?? "")),
  );
  for (const [index, entry] of entries.entries()) {
    entry.bodyHash = hashes[index] ?? "";
  }
  return { entries, bodies };
}

/**
 * Ask the browser to keep this site's storage when space runs low, as PLAN
 * promises; unsupported or refused, the app works the same.
 */
function askToPersist(): void {
  if (typeof navigator !== "undefined" && navigator.storage?.persist) {
    navigator.storage.persist().catch(() => false);
  }
}

/** A feed in the sidebar, with what it is ordered by. */
export interface FeedRow {
  /** The feed. */
  feed: Feed;
  /** Its unread count. */
  unread: number;
  /** Whether its last checks failed. */
  failing: boolean;
}

/** A top-level sidebar item: a group with its feeds, or a feed on its own. */
export type TreeRow =
  | {
      kind: "folder";
      /** Group name. */
      name: string;
      /** Unread count of the group view. */
      unread: number;
      /** Whether every feed in it is failing. */
      failing: boolean;
      /** Its feeds, in sidebar order. */
      feeds: FeedRow[];
    }
  | {
      kind: "feed";
      /** The feed's row. */
      row: FeedRow;
      /** Its unread count. */
      unread: number;
      /** Whether it is failing. */
      failing: boolean;
      /** Its name. */
      name: string;
    };

function sortRows(rows: FeedRow[]): FeedRow[] {
  return rows.sort((first, second) =>
    byUnread(first, second, first.feed.title, second.feed.title),
  );
}

/** Most unread first, then working before failing, then by name. */
function byUnread(
  first: { unread: number; failing: boolean },
  second: { unread: number; failing: boolean },
  firstName: string,
  secondName: string,
): number {
  return (
    second.unread - first.unread ||
    Number(first.failing) - Number(second.failing) ||
    firstName.localeCompare(secondName)
  );
}

/** Whether saved settings keep an entry; no settings keep everything. */
function settingsKeep(
  settings: ViewSettings | undefined,
  entry: Entry,
): boolean {
  return settings === undefined || passes(settings, entry);
}

/** A feed found by searching, ready to subscribe to. */
export interface FeedHit {
  /** Feed address. */
  url: string;
  /** Feed name. */
  title: string;
  /** What the feed says it is about. */
  description: string;
  /** Picture address; empty for none. */
  icon: string;
  /** Feedly's subscriber count. */
  subscribers: number;
}

function siteAddress(address: string): string {
  let site = address;
  try {
    site = `${new URL(address).origin}/`;
  } catch {
    site = address;
  }
  return site;
}

/** The example data module, loaded only for the demo. */
type DemoData = typeof import("./demo.ts");

function demoEntries(data: DemoData, mode: string): Entry[] {
  return mode === "big" ? data.bigEntries(20_000) : data.DEMO_ENTRIES;
}

/** Run a task without waiting for it, logging a failure rather than leaving it unhandled. */
export function logFailure(task: Promise<unknown>): void {
  task.catch((error: unknown) => {
    console.error("H Reader:", error);
  });
}

/** The address a site usually serves its small picture at. */
export function faviconFor(address: string): string {
  let icon = "";
  try {
    icon = `${new URL(address).origin}/favicon.ico`;
  } catch {
    icon = "";
  }
  return icon;
}

function isHit(value: unknown): value is {
  feedId: string;
  title?: string;
  description?: string;
  iconUrl?: string;
  subscribers?: number;
} {
  return (
    typeof value === "object" &&
    value !== null &&
    "feedId" in value &&
    typeof value.feedId === "string"
  );
}

function toSelection(value: unknown): Selection {
  let selection: Selection = { kind: "all" };
  if (typeof value === "object" && value !== null && "kind" in value) {
    if (value.kind === "starred") {
      selection = { kind: "starred" };
    } else if (
      value.kind === "folder" &&
      "name" in value &&
      typeof value.name === "string"
    ) {
      selection = { kind: "folder", name: value.name };
    } else if (
      value.kind === "feed" &&
      "id" in value &&
      typeof value.id === "string"
    ) {
      selection = { kind: "feed", id: value.id };
    }
  }
  return selection;
}

/** Key under which a view's settings are saved. */
export function viewKey(selection: Selection): string {
  let key: string;
  if (selection.kind === "folder") {
    key = `folder:${selection.name}`;
  } else if (selection.kind === "feed") {
    key = `feed:${selection.id}`;
  } else {
    key = selection.kind;
  }
  return key;
}

/** Order items by a saved position, keeping the given order for unsaved ones, which go last. */
function holdOrder<Item>(
  items: Item[],
  keyOf: (item: Item) => string,
  rank: Map<string, number> | undefined,
): Item[] {
  let result = items;
  if (rank !== undefined) {
    result = items
      .map((item, index) => ({ item, index }))
      .sort(
        (first, second) =>
          (rank.get(keyOf(first.item)) ?? Number.POSITIVE_INFINITY) -
            (rank.get(keyOf(second.item)) ?? Number.POSITIVE_INFINITY) ||
          first.index - second.index,
      )
      .map(({ item }) => item);
  }
  return result;
}

/** Everything the interface shows, and the actions that change it. */
/** How long a notice stays up, in milliseconds. */
const NOTICE_MS = 4000;

export class Reader {
  /** Subscribed feeds. */
  feeds = $state<Feed[]>([]);
  /** Every stored article, without bodies. */
  entries = $state<Entry[]>([]);
  /** The view the list shows. */
  selection = $state<Selection>({ kind: "all" });
  /** Saved settings by view key; see `viewKey`. */
  settings = $state<Record<string, SavedSettings>>({});
  /** Entries read in this view; they stay listed until the view changes. */
  pinned = $state<Record<string, true>>({});
  /**
   * Topics each view filtered on before a tag was toggled from an article, by
   * settings key; the list keeps using them until the next refresh or view change.
   */
  heldTopics = $state<Record<string, string[]>>({});
  /** Only list articles not yet read, and feeds that have any; one switch for every view. */
  unreadOnly = $state(true);
  /** Id of the open entry; empty when none is open. */
  currentId = $state("");
  /** Sanitized HTML of the open entry. */
  body = $state("");
  /** Whether stored data has been read. */
  loaded = $state(false);
  /** Whether the extension has been looked for at least once. */
  checked = $state(false);
  /** Whether feeds are being checked. */
  refreshing = $state(false);
  /** Whether a feed is being searched for, added or imported. */
  adding = $state(false);
  /** A short message shown above the bottom of the screen; empty when none is up. */
  notice = $state("");
  /** The browser extension that fetches feeds. */
  readonly extension = new ExtensionClient();
  /** Optional sign-in and sync. */
  readonly sync = new Sync({
    local: (): LocalData => ({
      feeds: this.feeds,
      entries: this.entries,
      settings: this.settings,
      tombstones: this.tombstones,
    }),
    apply: (change): Promise<void> => this.applyRemote(change),
    body: (id): Promise<string> => this.storedBody(id),
    saveHashes: (hashes): Promise<void> => this.saveHashes(hashes),
    clear: (): Promise<void> => this.clearLocal(),
  });

  private db: Database | undefined;
  private noticeTimer: ReturnType<typeof setTimeout> | undefined;
  /** Example data while the demo runs. */
  private demoData: DemoData | undefined;
  /** Bodies kept in memory when there is no database. */
  private readonly memoryBodies = new Map<string, string>();
  /** Refreshes under way; they can overlap. */
  private refreshCount = 0;
  /** When each removed feed or setting was removed, by tombstone key. */
  private tombstones = new Map<string, number>();
  private draining: Promise<void> | undefined;
  private readonly manual = new Set<string>();
  private readonly iconsTried = new Set<string>();

  /** Whether the current view has words and topics; Starred shows every starred article and has only a sort. */
  filtersApply = $derived(this.selection.kind !== "starred");

  /** Settings of the current view. */
  viewSettings = $derived(
    this.settings[viewKey(this.selection)] ?? DEFAULT_SETTINGS,
  );

  /** Entries that belong to the current view, before any rules. */
  baseEntries = $derived.by(() => {
    const { selection } = this;
    const feedFolders = new Map(
      this.feeds.map((feed) => [feed.id, feed.folder]),
    );
    return this.entries.filter((entry) => {
      let inView: boolean;
      if (selection.kind === "all") {
        inView = true;
      } else if (selection.kind === "starred") {
        inView = entry.starred;
      } else if (selection.kind === "folder") {
        inView = feedFolders.get(entry.feedId) === selection.name;
      } else {
        inView = entry.feedId === selection.id;
      }
      return inView;
    });
  });

  /** The current view in the chosen order, before any filter. */
  ordered = $derived.by(() => {
    const { sort } = this.viewSettings;
    return [...this.baseEntries].sort((first, second) =>
      compareEntries(first, second, sort),
    );
  });

  /** Ids of the entries the current view's words and topics keep. */
  kept = $derived.by(() => {
    const ids = new Set<string>();
    for (const entry of this.baseEntries) {
      if (this.keeps(entry)) {
        ids.add(entry.id);
      }
    }
    return ids;
  });

  /** Entries in the current view, in the chosen order. */
  visible = $derived.by(() => {
    const unreadOnly = this.unreadOnly && this.selection.kind !== "starred";
    const { kept, pinned, currentId } = this;
    return this.ordered.filter(
      (entry) =>
        entry.id === currentId ||
        (kept.has(entry.id) &&
          !(unreadOnly && entry.read && !pinned[entry.id])),
    );
  });

  /** The topics in the current view, commonest first. */
  topics = $derived(topicCounts(this.baseEntries));

  /** Name of the current view. */
  viewTitle = $derived.by(() => {
    const { selection } = this;
    let title: string = copy.allItems;
    if (selection.kind === "starred") {
      title = copy.starred;
    } else if (selection.kind === "folder") {
      title = selection.name;
    } else if (selection.kind === "feed") {
      title =
        this.feeds.find((feed) => feed.id === selection.id)?.title ?? title;
    }
    return title;
  });

  /** Feeds in the current view; empty means every feed. */
  viewFeedIds = $derived.by(() => {
    const { selection } = this;
    let ids: string[] = [];
    if (selection.kind === "folder") {
      ids = this.feeds
        .filter((feed) => feed.folder === selection.name)
        .map((feed) => feed.id);
    } else if (selection.kind === "feed") {
      ids = [selection.id];
    }
    return ids;
  });

  /** The open entry, if any. */
  current = $derived(this.entries.find((entry) => entry.id === this.currentId));

  /**
   * Unread entries the lists would show: per feed those its own words and
   * topics keep, per group and in All items those that view's keep as well.
   * Worked out again only when entries, feeds or settings change.
   */
  unreadCounts = $derived.by(() => {
    const byFeed = new Map<string, number>();
    const byFolder = new Map<string, number>();
    let total = 0;
    const folderOf = new Map(this.feeds.map((feed) => [feed.id, feed.folder]));
    const { settings } = this;
    for (const entry of this.entries) {
      if (
        !entry.read &&
        settingsKeep(settings[`feed:${entry.feedId}`], entry)
      ) {
        byFeed.set(entry.feedId, (byFeed.get(entry.feedId) ?? 0) + 1);
        const folder = folderOf.get(entry.feedId) ?? "";
        if (
          folder !== "" &&
          settingsKeep(settings[`folder:${folder}`], entry)
        ) {
          byFolder.set(folder, (byFolder.get(folder) ?? 0) + 1);
        }
        if (settingsKeep(settings["all"], entry)) {
          total += 1;
        }
      }
    }
    return { byFeed, byFolder, total };
  });

  /** Unread entries per feed id that the feed's own words and topics keep. */
  unreadByFeed = $derived(this.unreadCounts.byFeed);

  /** Unread entries in the current view that its filters keep. */
  viewUnread = $derived(
    this.baseEntries.filter((entry) => !entry.read && this.counted(entry))
      .length,
  );

  /** Sidebar positions fixed at load and at each refresh; rows added later follow them. */
  frozenRank = $state<Map<string, number> | undefined>();

  /** The sidebar's groups, then its loose feeds, each most unread first, then working before failing, then by name; the order holds until the feeds are next refreshed. */
  tree = $derived.by((): TreeRow[] => {
    const rank = this.frozenRank;
    const rowOf = (feed: Feed): FeedRow => ({
      feed,
      unread: this.unreadByFeed.get(feed.id) ?? 0,
      failing: feed.error !== "",
    });
    const groups: TreeRow[] = this.folders.map((name): TreeRow => {
      const feeds = holdOrder(
        sortRows(this.feeds.filter((feed) => feed.folder === name).map(rowOf)),
        (row) => `feed:${row.feed.id}`,
        rank,
      );
      return {
        kind: "folder",
        name,
        feeds,
        unread: this.unreadCounts.byFolder.get(name) ?? 0,
        failing: feeds.every((row) => row.failing),
      };
    });
    groups.sort((first, second) =>
      byUnread(
        first,
        second,
        first.kind === "folder" ? first.name : "",
        second.kind === "folder" ? second.name : "",
      ),
    );
    const loose: TreeRow[] = holdOrder(
      sortRows(this.feeds.filter((feed) => !feed.folder).map(rowOf)),
      (row) => `feed:${row.feed.id}`,
      rank,
    ).map(
      (row): TreeRow => ({
        kind: "feed",
        row,
        unread: row.unread,
        failing: row.failing,
        name: row.feed.title,
      }),
    );
    return [
      ...holdOrder(groups, (group) => `folder:${group.name}`, rank),
      ...loose,
    ];
  });

  /** Fix the sidebar order as the counts stand now. */
  freezeOrder(): void {
    this.frozenRank = undefined;
    const rank = new Map<string, number>();
    for (const item of this.tree) {
      if (item.kind === "folder") {
        rank.set(`folder:${item.name}`, rank.size);
        for (const row of item.feeds) {
          rank.set(`feed:${row.feed.id}`, rank.size);
        }
      } else {
        rank.set(`feed:${item.row.feed.id}`, rank.size);
      }
    }
    this.frozenRank = rank;
  }

  /** Every view the sidebar lists, in its order. */
  viewOrder = $derived.by((): Selection[] => {
    const order: Selection[] = [{ kind: "all" }, { kind: "starred" }];
    for (const item of this.tree) {
      order.push(...this.listedViews(item));
    }
    return order;
  });

  /** The views a sidebar item lists: a group then its listed feeds, or a feed. */
  private listedViews(item: TreeRow): Selection[] {
    const views: Selection[] = [];
    if (item.kind === "folder") {
      if (this.folderListed(item.name, item.feeds)) {
        views.push({ kind: "folder", name: item.name });
        for (const row of item.feeds) {
          if (this.listed(row.feed)) {
            views.push({ kind: "feed", id: row.feed.id });
          }
        }
      }
    } else if (this.listed(item.row.feed)) {
      views.push({ kind: "feed", id: item.row.feed.id });
    }
    return views;
  }

  /** Whether the sidebar lists a feed: it has something unread, it is the one open, or unread only is off. */
  listed(feed: Feed): boolean {
    const { selection } = this;
    return (
      !this.unreadOnly ||
      (this.unreadByFeed.get(feed.id) ?? 0) > 0 ||
      (selection.kind === "feed" && selection.id === feed.id)
    );
  }

  /** Whether the sidebar lists a group: it has a listed feed, or it is the one open. */
  folderListed(name: string, feeds: readonly FeedRow[]): boolean {
    const { selection } = this;
    return (
      feeds.some((row) => this.listed(row.feed)) ||
      (selection.kind === "folder" && selection.name === name)
    );
  }

  /** Unread entries All items would show. */
  unreadTotal = $derived(this.unreadCounts.total);

  /** Folder names in use, sorted. */
  folders = $derived(
    [...new Set(this.feeds.map((feed) => feed.folder).filter(Boolean))].sort(),
  );

  /** Open the database, show what is stored, then pull anything new. */
  async init(): Promise<void> {
    askToPersist();
    try {
      this.db = await Database.open();
      const stored = await this.db.loadAll();
      this.feeds = stored.feeds;
      this.entries = stored.entries;
      const saved = await this.db.loadSettings();
      this.settings = Object.fromEntries(
        Object.entries(saved).map(([key, value]) => [
          key,
          { ...DEFAULT_SETTINGS, ...value },
        ]),
      );
      this.tombstones = await this.db.loadTombstones();
    } catch {
      this.db = undefined;
    }
    this.restoreView();
    this.restoreUnreadOnly();
    this.freezeOrder();
    this.loaded = true;
    if (this.db) {
      this.sync.resume();
    }
    await this.connect();
  }

  /** Show `text` as a notice for a few seconds. */
  notify(text: string): void {
    clearTimeout(this.noticeTimer);
    this.notice = text;
    this.noticeTimer = setTimeout(() => {
      this.notice = "";
    }, NOTICE_MS);
  }

  /** Look for the extension and, once found, hand it the feeds and check them. */
  async connect(): Promise<void> {
    const found = await this.extension.detect();
    this.checked = true;
    if (found) {
      await this.drainExtension();
      await this.trackFeeds();
      await this.refresh();
    }
  }

  /**
   * Subscribe to a feed by address. The address may also be a web page or a
   * profile on a service with feeds; its feed is found and used. Throws with a
   * reason on failure.
   */
  async addFeed(input: string, folder = ""): Promise<void> {
    const address = normalizeUrl(input);
    if (!isWebAddress(address)) {
      throw new Error("Only https addresses can be added.");
    }
    const found = await this.discover(address);
    const id = await feedId(found.url);
    if (this.feeds.some((feed) => feed.id === id)) {
      this.select({ kind: "feed", id });
    } else {
      const feed: Feed = {
        id,
        url: found.url,
        title: found.parsed.title || new URL(found.url).hostname,
        siteUrl: upgradedAddress(found.parsed.siteUrl),
        icon:
          upgradedAddress(found.parsed.iconUrl) ||
          (await this.findIcon(
            upgradedAddress(found.parsed.siteUrl) || found.url,
          )),
        renamed: false,
        folder,
        lastFetched: Date.now(),
        error: "",
        updatedAt: Date.now(),
      };
      this.feeds = [...this.feeds, feed];
      await this.db?.putFeed($state.snapshot(feed));
      await this.forget([`${FEED_TOMBSTONE}${id}`]);
      this.sync.queue({ feeds: [feed] });
      await this.ingest(feed, found.parsed);
      this.select({ kind: "feed", id });
      // the answer just fetched is the first check; the extension takes over from here
      await this.trackFeeds();
    }
  }

  /**
   * Subscribe to every feed in an OPML file. Returns how many feeds the file
   * lists and how many of them were new.
   */
  async importOpml(source: string): Promise<{ found: number; added: number }> {
    const items = parseOpml(source)
      .map((item) => ({ ...item, url: normalizeUrl(item.url) }))
      .filter((item) => isWebAddress(item.url));
    const ids = await Promise.all(items.map((item) => feedId(item.url)));
    const added: Feed[] = [];
    for (const [index, item] of items.entries()) {
      const id = ids[index];
      if (
        id &&
        !this.feeds.some((feed) => feed.id === id) &&
        !added.some((feed) => feed.id === id)
      ) {
        added.push({
          id,
          url: item.url,
          title: item.title || new URL(item.url).hostname,
          siteUrl: "",
          icon: faviconFor(item.url),
          renamed: false,
          folder: item.folder.trim(),
          lastFetched: 0,
          error: "",
          updatedAt: Date.now(),
        });
      }
    }
    this.feeds = [...this.feeds, ...added];
    await Promise.all(
      added.map((feed) => this.db?.putFeed($state.snapshot(feed))),
    );
    await this.forget(added.map((feed) => `${FEED_TOMBSTONE}${feed.id}`));
    this.sync.queue({ feeds: added });
    await this.trackFeeds();
    this.refresh().catch(() => undefined);
    return { found: items.length, added: added.length };
  }

  /** Search for feeds by what they are about; empty when nothing is found. */
  async searchFeeds(query: string): Promise<FeedHit[]> {
    const response = this.demoData
      ? undefined
      : await this.extension.send({
          type: "fetch",
          url: `${SEARCH_ADDRESS}?count=8&query=${encodeURIComponent(query.trim())}`,
          etag: "",
          lastModified: "",
        });
    let hits: FeedHit[] = this.demoData?.DEMO_HITS ?? [];
    if (response?.type === "fetched" && response.status === 200) {
      try {
        const parsed: unknown = JSON.parse(response.text);
        const results =
          typeof parsed === "object" && parsed !== null && "results" in parsed
            ? parsed.results
            : [];
        hits = (Array.isArray(results) ? results : [])
          .filter(isHit)
          .map((hit) => ({
            // a feed's id is "feed/" and its address; other ids are not feeds
            url: FEED_PREFIX.test(hit.feedId)
              ? upgradedAddress(hit.feedId.replace(FEED_PREFIX, ""))
              : "",
            title: hit.title ?? hit.feedId,
            description: hit.description ?? "",
            icon: upgradedAddress(hit.iconUrl ?? ""),
            subscribers: hit.subscribers ?? 0,
          }))
          .filter(
            (hit, index, all) =>
              isWebAddress(hit.url) &&
              all.findIndex((other) => other.url === hit.url) === index,
          );
      } catch {
        hits = [];
      }
    }
    return hits;
  }

  /**
   * Check feeds now whatever their schedule says. With no ids, every feed.
   * Requests are still conditional, so unchanged feeds cost little.
   */
  async refresh(feedIds: string[] = []): Promise<void> {
    if (this.extension.version) {
      this.refreshCount += 1;
      this.refreshing = true;
      // read articles kept in the list go as soon as a refresh is asked for
      this.pinned = {};
      this.heldTopics = {};
      const started = Date.now();
      try {
        if (
          this.unreadOnly &&
          this.selection.kind !== "starred" &&
          this.current?.read
        ) {
          await this.close();
        }
        const wanted =
          feedIds.length > 0 ? feedIds : this.feeds.map((feed) => feed.id);
        await this.extension.send({ type: "refresh", feedIds: wanted });
        // a drain already running may have asked before this refresh's results were saved
        await this.draining?.catch(() => undefined);
        await this.drainExtension();
        this.freezeOrder();
      } finally {
        const remaining = MIN_REFRESH_MS - (Date.now() - started);
        if (remaining > 0) {
          await new Promise((resolve) => setTimeout(resolve, remaining));
        }
        this.refreshCount -= 1;
        this.refreshing = this.refreshCount > 0;
      }
    }
  }

  /** Check the feeds in the current view now. */
  async refreshView(): Promise<void> {
    await this.refresh(this.viewFeedIds);
  }

  /**
   * Fill the app with example data and no storage; development only. A
   * `syncMode` shows a pretend sign-in in that state (see `Sync.demo`).
   */
  async demo(mode = "", syncMode = ""): Promise<void> {
    if (!import.meta.env.DEV) {
      return;
    }
    const data = await import("./demo.ts");
    this.demoData = data;
    const empty = mode === "empty" || mode === "gate";
    this.feeds = empty ? [] : data.DEMO_FEEDS;
    this.entries = empty ? [] : demoEntries(data, mode);
    this.extension.version = mode === "gate" ? "" : "demo";
    if (syncMode !== "") {
      await this.sync.demo(syncMode);
    }
    this.restoreUnreadOnly();
    this.freezeOrder();
    this.loaded = true;
    this.checked = true;
  }

  /** Pick up what the extension has found; notices if the extension has gone. */
  async collect(): Promise<void> {
    if (await this.extension.detect()) {
      await this.drainExtension();
    }
  }

  /** Change the current view's settings and save them. */
  async changeSettings(change: Partial<ViewSettings>): Promise<void> {
    const key = viewKey(this.selection);
    const next: SavedSettings = {
      ...this.viewSettings,
      ...change,
      updatedAt: Date.now(),
    };
    this.settings[key] = next;
    await this.db?.putSettings(key, $state.snapshot(next));
    await this.forget([`${SETTINGS_TOMBSTONE}${key}`]);
    this.sync.queue({ settings: new Map([[key, next]]) });
  }

  /** Whether the current view has a topic turned off. */
  topicOff(topic: string): boolean {
    const key = topic.toLowerCase();
    return this.viewSettings.offTopics.some(
      (known) => known.toLowerCase() === key,
    );
  }

  /**
   * Turn a topic off or back on in the current view. From an article the list
   * keeps its rows until the next refresh or view change; from the settings
   * panel everything takes effect at once.
   */
  async toggleTopic(topic: string, fromArticle: boolean): Promise<void> {
    const key = viewKey(this.selection);
    const wasOff = this.topicOff(topic);
    const lower = topic.toLowerCase();
    const offTopics = wasOff
      ? this.viewSettings.offTopics.filter(
          (known) => known.toLowerCase() !== lower,
        )
      : [...this.viewSettings.offTopics, topic];
    if (fromArticle) {
      this.heldTopics[key] ??= [...this.viewSettings.offTopics];
    } else {
      this.heldTopics = {};
    }
    await this.changeSettings({ offTopics });
  }

  /** How many articles in the current view have a word in the title or summary. */
  wordCount(word: string): number {
    return this.baseEntries.filter((entry) => hasWord([word], entry)).length;
  }

  /** Turn the unread-only switch on or off, and remember it. */
  setUnreadOnly(next: boolean): void {
    this.unreadOnly = next;
    this.pinned = {};
    this.heldTopics = {};
    if (next && this.current?.read) {
      this.close().catch(() => undefined);
    }
    try {
      localStorage.setItem(UNREAD_KEY, next ? "1" : "0");
    } catch {
      // storage can be blocked; the choice just does not persist
    }
  }

  /** Show a feed, a group or one of the main lists, and remember the choice. */
  select(selection: Selection): void {
    const before = viewKey(this.selection);
    this.selection = this.valid(selection) ? selection : { kind: "all" };
    if (viewKey(this.selection) !== before) {
      if (this.currentId !== "") {
        this.close().catch(() => undefined);
      }
      this.pinned = {};
      this.heldTopics = {};
    }
    try {
      localStorage.setItem(VIEW_KEY, JSON.stringify(this.selection));
    } catch {
      // storage can be blocked; the choice just does not persist
    }
  }

  /** Show the view before or after the current one in the sidebar's order. */
  moveView(delta: number): void {
    const keys = this.viewOrder.map(viewKey);
    const index = keys.indexOf(viewKey(this.selection));
    const next =
      this.viewOrder[Math.max(0, Math.min(keys.length - 1, index + delta))];
    if (next) {
      this.select(next);
    }
  }

  /** Give a feed its own name; a blank name is ignored. */
  async renameFeed(id: string, name: string): Promise<void> {
    const feed = this.feeds.find((candidate) => candidate.id === id);
    const title = name.trim();
    if (feed && title !== "") {
      feed.title = title;
      feed.renamed = true;
      await this.saveFeeds([feed]);
    }
  }

  /** Rename a group; if the name is taken, the two groups become one. */
  async renameFolder(from: string, name: string): Promise<void> {
    const to = name.trim();
    if (to !== "" && to !== from) {
      // the renamed group keeps its place in the sidebar until the next refresh
      const rank = this.frozenRank;
      const place = rank?.get(`folder:${from}`);
      if (rank && place !== undefined && !rank.has(`folder:${to}`)) {
        this.frozenRank = new Map(rank).set(`folder:${to}`, place);
      }
      const moved = this.feeds.filter((feed) => feed.folder === from);
      for (const feed of moved) {
        feed.folder = to;
      }
      await this.saveFeeds(moved);
      const old = this.settings[`folder:${from}`];
      if (old && !this.settings[`folder:${to}`]) {
        const copied: SavedSettings = { ...old, updatedAt: Date.now() };
        this.settings[`folder:${to}`] = copied;
        await this.db?.putSettings(`folder:${to}`, $state.snapshot(copied));
        await this.forget([`${SETTINGS_TOMBSTONE}folder:${to}`]);
        this.sync.queue({ settings: new Map([[`folder:${to}`, copied]]) });
      }
      await this.removeSettings(`folder:${from}`);
      this.select({ kind: "folder", name: to });
    }
  }

  /** Delete a group: its feeds stay, but belong to no group. */
  async deleteFolder(name: string): Promise<void> {
    const moved = this.feeds.filter((feed) => feed.folder === name);
    for (const feed of moved) {
      feed.folder = "";
    }
    await this.saveFeeds(moved);
    await this.removeSettings(`folder:${name}`);
    this.select({ kind: "all" });
  }

  /** A feed's picture would not load: look for the site's own once, and use it. */
  async refreshIcon(id: string): Promise<void> {
    const feed = this.feeds.find((candidate) => candidate.id === id);
    if (feed && !this.iconsTried.has(id)) {
      this.iconsTried.add(id);
      const icon = await this.findIcon(feed.siteUrl || feed.url);
      if (icon !== "" && icon !== feed.icon) {
        feed.icon = icon;
        await this.db?.putFeed($state.snapshot(feed));
      }
    }
  }

  /** Move a feed into a group; an empty name takes it out of any group. */
  async setFolder(id: string, folder: string): Promise<void> {
    const feed = this.feeds.find((candidate) => candidate.id === id);
    if (feed) {
      feed.folder = folder.trim();
      await this.saveFeeds([feed]);
      this.select(this.selection);
    }
  }

  /**
   * Open an entry. The one that was open is marked read now, since it has been
   * left; the new one is marked only when it is left in turn, so an article
   * that fails to show is never counted as read.
   */
  async open(id: string): Promise<void> {
    const previous = this.currentId === id ? undefined : this.current;
    this.currentId = id;
    this.body = "";
    if (previous) {
      await this.leave(previous);
    }
    const entry = this.entries.find((candidate) => candidate.id === id);
    if (entry) {
      const html = await this.bodyOf(entry);
      if (this.currentId === id) {
        const feedUrl =
          this.feeds.find((feed) => feed.id === entry.feedId)?.url ?? "";
        this.body =
          html === ""
            ? plainBody(entry.summary || entry.excerpt)
            : sanitize(html, entry.url || feedUrl);
      }
    }
  }

  /** An entry's body from this device, else downloaded and kept; empty when there is none. */
  private async bodyOf(entry: Entry): Promise<string> {
    let html = this.demoData
      ? this.demoData.DEMO_BODY
      : await this.storedBody(entry.id);
    if (html === "" && entry.bodyHash !== "") {
      html = await this.sync.fetchBody(entry.bodyHash);
      if (html !== "") {
        await this.keepBody(entry.id, html);
      }
    }
    return html;
  }

  /** Close the open entry, marking it read. */
  async close(): Promise<void> {
    const previous = this.current;
    this.currentId = "";
    this.body = "";
    if (previous) {
      await this.leave(previous);
    }
  }

  /**
   * Move the open entry by a number of rows in the current view. Stepping off
   * either end closes it. Returns whether an entry is open afterwards.
   */
  async step(delta: number): Promise<boolean> {
    const rows = this.visible;
    const index = rows.findIndex((entry) => entry.id === this.currentId);
    const target = index + delta;
    let next: Entry | undefined;
    if (index === -1) {
      [next] = rows;
    } else if (target >= 0 && target < rows.length) {
      next = rows[target];
    }
    if (next) {
      await this.open(next.id);
    } else if (index !== -1) {
      await this.close();
    }
    return next !== undefined;
  }

  /** Flip the starred mark on the open entry. */
  async toggleStar(): Promise<void> {
    const entry = this.current;
    if (entry) {
      entry.starred = !entry.starred;
      await this.saveMarks([entry]);
    }
  }

  /** Flip the read mark on the open entry. */
  async toggleRead(): Promise<void> {
    const entry = this.current;
    if (entry) {
      this.manual.add(entry.id);
      await this.setRead([entry], !entry.read);
    }
  }

  /** Flip the starred mark on one entry. */
  async toggleStarFor(id: string): Promise<void> {
    const entry = this.entries.find((candidate) => candidate.id === id);
    if (entry) {
      entry.starred = !entry.starred;
      await this.saveMarks([entry]);
    }
  }

  /** Flip the read mark on one entry. */
  async toggleReadFor(id: string): Promise<void> {
    const entry = this.entries.find((candidate) => candidate.id === id);
    if (entry) {
      if (id === this.currentId) {
        this.manual.add(id);
      }
      await this.setRead([entry], !entry.read);
    }
  }

  /** Mark everything in the current view as read: what it lists and what its unread counts include. */
  async markAllRead(): Promise<void> {
    const shown = new Set(this.visible.map((entry) => entry.id));
    await this.setRead(
      this.baseEntries.filter(
        (entry) => !entry.read && (shown.has(entry.id) || this.counted(entry)),
      ),
      true,
    );
  }

  /** Whether the current view's unread count includes an entry, read or not. */
  private counted(entry: Entry): boolean {
    const { selection } = this;
    let counts = true;
    if (selection.kind !== "starred") {
      const own = settingsKeep(this.settings[`feed:${entry.feedId}`], entry);
      counts =
        selection.kind === "feed"
          ? own
          : own && settingsKeep(this.settings[viewKey(selection)], entry);
    }
    return counts;
  }

  /** Remove a feed and everything stored for it. */
  async removeFeed(id: string): Promise<void> {
    const now = Date.now();
    const hadSettings = this.settings[`feed:${id}`] !== undefined;
    await this.dropFeed(id, now);
    const removedSettings = new Map<string, number>();
    if (hadSettings) {
      await this.remember(new Map([[`${SETTINGS_TOMBSTONE}feed:${id}`, now]]));
      removedSettings.set(`feed:${id}`, now);
    }
    this.sync.queue({ removedFeeds: new Map([[id, now]]), removedSettings });
  }

  /** Remove a feed, its entries and its settings here, leaving a tombstone. */
  private async dropFeed(id: string, when: number): Promise<void> {
    await this.db?.deleteFeed(id);
    delete this.settings[`feed:${id}`];
    this.feeds = this.feeds.filter((feed) => feed.id !== id);
    this.entries = this.entries.filter((entry) => entry.feedId !== id);
    await this.remember(new Map([[`${FEED_TOMBSTONE}${id}`, when]]));
    this.select(this.selection);
    await this.trackFeeds();
  }

  /** Store changes from the account; nothing here is sent back. */
  private async applyRemote(change: RemoteChange): Promise<void> {
    for (const [id, when] of change.removedFeeds) {
      // biome-ignore lint/performance/noAwaitInLoops: removals are rare and each touches several stores
      await this.dropFeed(id, when);
    }
    const forgotten: string[] = [];
    if (change.feeds.length > 0) {
      const byId = new Map(change.feeds.map((feed) => [feed.id, feed]));
      const kept = this.feeds.map((feed) => byId.get(feed.id) ?? feed);
      const known = new Set(this.feeds.map((feed) => feed.id));
      const added = change.feeds.filter((feed) => !known.has(feed.id));
      this.feeds = [...kept, ...added];
      await Promise.all(change.feeds.map((feed) => this.db?.putFeed(feed)));
      forgotten.push(
        ...change.feeds.map((feed) => `${FEED_TOMBSTONE}${feed.id}`),
      );
    }
    if (change.entries.length > 0) {
      const byId = new Map(change.entries.map((entry) => [entry.id, entry]));
      const known = new Set(this.entries.map((entry) => entry.id));
      this.entries = [
        ...this.entries.map((entry) => byId.get(entry.id) ?? entry),
        ...change.entries.filter((entry) => !known.has(entry.id)),
      ];
      await this.db?.updateEntries(change.entries);
    }
    for (const [key, saved] of change.settings) {
      this.settings[key] = { ...DEFAULT_SETTINGS, ...saved };
      // biome-ignore lint/performance/noAwaitInLoops: settings are few
      await this.db?.putSettings(key, saved);
      forgotten.push(`${SETTINGS_TOMBSTONE}${key}`);
    }
    for (const [key, when] of change.removedSettings) {
      delete this.settings[key];
      // biome-ignore lint/performance/noAwaitInLoops: settings are few
      await this.db?.deleteSettings(key);
      await this.remember(new Map([[`${SETTINGS_TOMBSTONE}${key}`, when]]));
    }
    await this.forget(forgotten);
    if (change.feeds.length > 0) {
      this.select(this.selection);
      await this.trackFeeds();
    }
  }

  /** Remove every feed, entry, body, setting and tombstone on this device. */
  private async clearLocal(): Promise<void> {
    await this.db?.clear();
    this.memoryBodies.clear();
    this.manual.clear();
    this.currentId = "";
    this.body = "";
    this.feeds = [];
    this.entries = [];
    this.settings = {};
    this.tombstones = new Map();
    this.pinned = {};
    this.heldTopics = {};
    this.select({ kind: "all" });
    this.freezeOrder();
    await this.trackFeeds();
  }

  private async saveHashes(hashes: ReadonlyMap<string, string>): Promise<void> {
    const changed: Entry[] = [];
    for (const entry of this.entries) {
      const hash = hashes.get(entry.id);
      if (hash !== undefined && entry.bodyHash === "") {
        entry.bodyHash = hash;
        changed.push($state.snapshot(entry));
      }
    }
    await this.db?.updateEntries(changed);
  }

  /** Save feeds whose synced fields changed, and send them. */
  private async saveFeeds(feeds: Feed[]): Promise<void> {
    const now = Date.now();
    for (const feed of feeds) {
      feed.updatedAt = now;
    }
    await Promise.all(
      feeds.map((feed) => this.db?.putFeed($state.snapshot(feed))),
    );
    this.sync.queue({ feeds });
  }

  /** Save entries whose read or starred mark changed, and send them. */
  private async saveMarks(entries: Entry[]): Promise<void> {
    const now = Date.now();
    for (const entry of entries) {
      entry.updatedAt = now;
    }
    try {
      await this.db?.updateEntries(
        entries.map((entry) => $state.snapshot(entry)),
      );
    } finally {
      // the marks are already shown, so the account gets them even if this device could not keep them
      this.sync.queue({ entries });
    }
  }

  /** Remove a view's settings, leaving a tombstone so the removal syncs. */
  private async removeSettings(key: string): Promise<void> {
    if (this.settings[key] !== undefined) {
      const now = Date.now();
      delete this.settings[key];
      await this.db?.deleteSettings(key);
      await this.remember(new Map([[`${SETTINGS_TOMBSTONE}${key}`, now]]));
      this.sync.queue({ removedSettings: new Map([[key, now]]) });
    }
  }

  private async remember(tombstones: Map<string, number>): Promise<void> {
    for (const [key, when] of tombstones) {
      this.tombstones.set(key, when);
    }
    await this.db?.putTombstones(tombstones);
  }

  private async forget(keys: string[]): Promise<void> {
    const present = keys.filter((key) => this.tombstones.has(key));
    for (const key of present) {
      this.tombstones.delete(key);
    }
    if (present.length > 0) {
      await this.db?.putTombstones(
        new Map(present.map((key) => [key, undefined])),
      );
    }
  }

  private holding(key: string, settings: ViewSettings): ViewSettings {
    const held = this.heldTopics[key];
    return held === undefined ? settings : { ...settings, offTopics: held };
  }

  private keeps(entry: Entry): boolean {
    let kept = true;
    if (this.selection.kind !== "starred") {
      const ownKey = `feed:${entry.feedId}`;
      const own = this.holding(
        ownKey,
        this.settings[ownKey] ?? DEFAULT_SETTINGS,
      );
      const view =
        this.selection.kind === "feed"
          ? DEFAULT_SETTINGS
          : this.holding(viewKey(this.selection), this.viewSettings);
      kept = passes(own, entry) && passes(view, entry);
    }
    return kept;
  }

  private valid(selection: Selection): boolean {
    let ok = true;
    if (selection.kind === "feed") {
      ok = this.feeds.some((feed) => feed.id === selection.id);
    } else if (selection.kind === "folder") {
      ok = this.folders.includes(selection.name);
    }
    return ok;
  }

  private restoreUnreadOnly(): void {
    try {
      this.unreadOnly = localStorage.getItem(UNREAD_KEY) !== "0";
    } catch {
      this.unreadOnly = true;
    }
  }

  private restoreView(): void {
    let saved: Selection = { kind: "all" };
    try {
      const raw = localStorage.getItem(VIEW_KEY);
      if (raw) {
        saved = toSelection(JSON.parse(raw));
      }
    } catch {
      saved = { kind: "all" };
    }
    this.select(saved);
  }

  private async discover(
    start: string,
  ): Promise<{ url: string; parsed: ParsedFeed }> {
    const queue = [start];
    const tried = new Set<string>();
    // what went wrong with the address as given; the usual feed paths tried after it mostly answer 404, which says less
    let failure = "The site did not answer.";
    while (queue.length > 0 && tried.size < MAX_DISCOVERY_TRIES) {
      const url = queue.shift() ?? start;
      if (!tried.has(url)) {
        tried.add(url);
        // biome-ignore lint/performance/noAwaitInLoops: addresses are tried one at a time, best first
        const attempt = await this.tryAddress(url);
        if (attempt.parsed) {
          return { url, parsed: attempt.parsed };
        }
        if (tried.size === 1) {
          failure = attempt.failure || failure;
          queue.push(
            ...feedCandidates(attempt.base, attempt.html)
              .map((candidate) => upgradedAddress(candidate))
              .filter((candidate) => candidate !== ""),
          );
        }
      }
    }
    throw new Error(failure);
  }

  /** Fetch an address; `base` is where the answer came from after redirects, which relative links in it point from. */
  private async tryAddress(url: string): Promise<{
    parsed: ParsedFeed | undefined;
    failure: string;
    html: string;
    base: string;
  }> {
    const response = await this.extension.send({
      type: "fetch",
      url,
      etag: "",
      lastModified: "",
    });
    if (response?.type === "error") {
      throw new Error(response.message);
    } else if (response?.type !== "fetched") {
      throw new Error("The H Reader extension is not installed.");
    }
    // extensions before redirects were reported send no address
    const base = upgradedAddress(response.url) || url;
    let parsed: ParsedFeed | undefined;
    let failure = "";
    if (response.status === 200) {
      try {
        parsed = parseFeed(response.text, base);
      } catch {
        failure = "That address is not a feed and does not point to one.";
      }
    } else if (response.insecure === true) {
      failure = "The feed is not on https.";
    } else if (response.status !== 0) {
      failure = statusProblem(response.status);
    }
    return {
      parsed,
      failure,
      html: response.status === 200 ? response.text : "",
      base,
    };
  }

  private async leave(entry: Entry): Promise<void> {
    if (!(entry.read || this.manual.has(entry.id))) {
      await this.setRead([entry], true);
    }
    this.manual.delete(entry.id);
  }

  /** The picture a site's page links to; empty when the page could not be read or links none. */
  private async findIcon(address: string): Promise<string> {
    const response = await this.extension.send({
      type: "fetch",
      url: siteAddress(address),
      etag: "",
      lastModified: "",
    });
    let icon = "";
    if (response?.type === "fetched" && response.status === 200) {
      const [best = ""] = discoverIconLinks(response.text, address);
      icon = upgradedAddress(best);
    }
    return icon;
  }

  private async setRead(entries: Entry[], read: boolean): Promise<void> {
    for (const entry of entries) {
      entry.read = read;
      if (read) {
        this.pinned[entry.id] = true;
      }
    }
    await this.saveMarks(entries);
  }

  /** Store a feed's new entries; how many were new. */
  private async ingest(feed: Feed, parsed: ParsedFeed): Promise<number> {
    const found = await prepareEntries(
      feed,
      parsed,
      new Set(this.entries.map((entry) => entry.id)),
    );
    return await this.commitEntries(found);
  }

  /**
   * Add prepared entries in one step, so the lists are worked out once, and
   * save them; how many were added.
   */
  private async commitEntries(found: PreparedEntries): Promise<number> {
    // sync or an unsubscribe may have changed things while preparing
    const present = new Set(this.entries.map((entry) => entry.id));
    const subscribed = new Set(this.feeds.map((feed) => feed.id));
    const added = found.entries.filter(
      (entry) => subscribed.has(entry.feedId) && !present.has(entry.id),
    );
    if (added.length > 0) {
      this.entries = [...this.entries, ...added];
      try {
        await this.saveNew(added, found.bodies);
      } catch (error) {
        // unsaved entries must not look known, or the next drain skips them for good
        const unsaved = new Set(added.map((entry) => entry.id));
        this.entries = this.entries.filter((entry) => !unsaved.has(entry.id));
        throw error;
      }
      this.sync.queue({ added });
    }
    return added.length;
  }

  /** Save new entries and their bodies, in memory when there is no database. */
  private async saveNew(
    entries: Entry[],
    bodies: ReadonlyMap<string, string>,
  ): Promise<void> {
    if (this.db) {
      await this.db.putEntries(entries, bodies);
    } else {
      for (const entry of entries) {
        this.memoryBodies.set(entry.id, bodies.get(entry.id) ?? "");
      }
    }
  }

  /** An entry's stored body; empty when there is none. */
  private storedBody(id: string): Promise<string> {
    return (
      this.db?.getBody(id) ?? Promise.resolve(this.memoryBodies.get(id) ?? "")
    );
  }

  /** Keep a downloaded body. */
  private async keepBody(id: string, body: string): Promise<void> {
    if (this.db) {
      await this.db.putBody(id, body);
    } else {
      this.memoryBodies.set(id, body);
    }
  }

  private drainExtension(): Promise<void> {
    this.draining ??= this.drainOnce().finally(() => {
      this.draining = undefined;
    });
    return this.draining;
  }

  /** Take what the extension has waiting, draining again while it says more is left. */
  private async drainOnce(): Promise<void> {
    let more = true;
    for (let round = 0; more && round < MAX_DRAINS; round += 1) {
      // biome-ignore lint/performance/noAwaitInLoops: each drain follows the acknowledgment of the last
      more = await this.drainRound();
    }
  }

  /** One drain and its acknowledgment; whether the extension has more waiting. */
  private async drainRound(): Promise<boolean> {
    const response = this.extension.version
      ? await this.extension.send({ type: "drain" })
      : undefined;
    let more = false;
    if (response?.type === "drained") {
      const known = new Set(this.entries.map((entry) => entry.id));
      const found: PreparedEntries = { entries: [], bodies: new Map() };
      for (const pulled of response.pulled) {
        // biome-ignore lint/performance/noAwaitInLoops: one feed at a time so no entry is added twice
        const prepared = await this.takePulled(pulled, known);
        found.entries.push(...prepared.entries);
        for (const [id, body] of prepared.bodies) {
          found.bodies.set(id, body);
        }
      }
      await this.commitEntries(found);
      await this.applyStatus(response.status);
      await this.extension.send({ type: "ack", upTo: response.seq });
      // extensions before paged drains send no flag
      more = response.more === true && response.seq > 0;
    }
    return more;
  }

  /**
   * A pulled feed's new entries, prepared; the feed's own name, site and
   * picture are updated now. Those updates stay on this device: they keep
   * `updatedAt` and are not sent, so they never outweigh a person's change on
   * another device, and every device reads them from the feed itself.
   */
  private async takePulled(
    pulled: PulledFeed,
    known: Set<string>,
  ): Promise<PreparedEntries> {
    const feed = this.feeds.find((candidate) => candidate.id === pulled.feedId);
    let prepared: PreparedEntries = { entries: [], bodies: new Map() };
    if (feed) {
      prepared = await prepareEntries(feed, pulled.feed, known);
      const before = [feed.title, feed.siteUrl, feed.icon].join("\n");
      if (!feed.renamed) {
        feed.title = pulled.feed.title || feed.title;
      }
      feed.siteUrl = upgradedAddress(pulled.feed.siteUrl) || feed.siteUrl;
      feed.icon = upgradedAddress(pulled.feed.iconUrl) || feed.icon;
      if ([feed.title, feed.siteUrl, feed.icon].join("\n") !== before) {
        await this.db?.putFeed($state.snapshot(feed));
      }
    }
    return prepared;
  }

  private async applyStatus(statuses: FeedStatus[]): Promise<void> {
    const changed: Feed[] = [];
    for (const status of statuses) {
      const feed = this.feeds.find(
        (candidate) => candidate.id === status.feedId,
      );
      if (
        feed &&
        (feed.lastFetched !== status.lastChecked || feed.error !== status.error)
      ) {
        feed.lastFetched = status.lastChecked;
        feed.error = status.error;
        changed.push(feed);
      }
    }
    await Promise.all(
      changed.map((feed) => this.db?.putFeed($state.snapshot(feed))),
    );
  }

  private async trackFeeds(): Promise<void> {
    if (this.extension.version) {
      const withEntries = new Set(this.entries.map((entry) => entry.feedId));
      const feeds: TrackedFeed[] = this.feeds.map((feed) => ({
        id: feed.id,
        url: feed.url,
        empty: !withEntries.has(feed.id),
      }));
      await this.extension.send({ type: "track", feeds });
    }
  }
}
