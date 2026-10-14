import { upgradedAddress } from "$shared/address.ts";
import { DEFAULT_SETTINGS } from "$shared/filter.ts";
import { webAddress } from "$shared/https.ts";
import type { Entry, Feed, SavedSettings } from "../types.ts";

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The most the Firestore rules accept in a field (`firebase/firestore.rules`):
 * characters for text, items for lists. Documents are cut to fit; a feed,
 * entry or view whose identifying text is longer stays on this device.
 */
export const FIELD_LIMITS = {
  /** Feed, site, picture and article addresses. */
  address: 8192,
  /** An article's id within its feed. */
  guid: 8192,
  /** Feed names and headlines. */
  title: 2000,
  /** Group names. */
  folder: 1000,
  /** Writers. */
  author: 1000,
  /** An article's start, for the list. */
  excerpt: 1000,
  /** A feed's own description of an article. */
  summary: 20_000,
  /** Topics per article. */
  categories: 100,
  /** View keys. */
  key: 1100,
  /** Words or topics per list. */
  words: 500,
} as const;

/** Text cut to at most `limit` UTF-16 units, never in the middle of a character. */
export function cutText(source: string, limit: number): string {
  let end = Math.min(source.length, limit);
  const last = source.charCodeAt(end - 1);
  if (end < source.length && last >= 0xd8_00 && last <= 0xdb_ff) {
    end -= 1;
  }
  return source.slice(0, end);
}

/** An address that fits the rules; empty when it is too long to keep. */
function fitAddress(address: string): string {
  return address.length <= FIELD_LIMITS.address ? address : "";
}

/** Whether a feed's address is short enough for the account. */
export function storableFeed(feed: Feed): boolean {
  return feed.url.length <= FIELD_LIMITS.address;
}

/** Whether an entry's guid is short enough for the account. */
export function storableEntry(entry: Entry): boolean {
  return entry.guid.length <= FIELD_LIMITS.guid;
}

/** Longest document id Firestore accepts, in bytes; an encoded id is plain ASCII, so one byte a character. */
const DOCUMENT_ID_BYTES = 1500;

/** Whether a view key is short enough for the account, both as itself and as its encoded document id. */
export function storableKey(key: string): boolean {
  return (
    key.length <= FIELD_LIMITS.key &&
    settingsDocumentId(key).length <= DOCUMENT_ID_BYTES
  );
}

/** How long entries are kept in the account: a number of days, or forever. */
export type Retention = number | "forever";

/** The retention choices, shortest first; forever is the default. */
export const RETENTION_CHOICES: readonly Retention[] = [30, 90, 365, "forever"];

/** How long a body outlives the longest finite retention, so an entry never outlives its text. */
const BODY_GRACE_DAYS = 7;

/** Document id, in `users/{uid}/settings`, of the account's own settings; no view key encodes to it. */
export const ACCOUNT_SETTINGS_ID = "account";

/** The account's own settings, as stored at `users/{uid}/settings/account`. */
export interface AccountDocument {
  /** Days entries are kept in the account; 0 for forever. */
  retentionDays: number;
  /** When the setting last changed, on the changing device's clock. */
  updatedAt: number;
  /** The retention, in days, the account's entries were last all stamped with; 0 for forever. */
  stampedDays: number;
}

/** Whether a stored number of days is a retention the rules accept: 0 or an offered choice. */
function offeredDays(days: unknown): days is number {
  return (
    typeof days === "number" && (days === 0 || RETENTION_CHOICES.includes(days))
  );
}

/** A retention as its stored number of days; 0 for forever. */
export function retentionDays(retention: Retention): number {
  return retention === "forever" ? 0 : retention;
}

/** A retention from its stored number of days; anything not offered is forever. */
export function retentionFromDays(days: number): Retention {
  return RETENTION_CHOICES.find((choice) => choice === days) ?? "forever";
}

/** Read the account settings document, or undefined when it is not one the rules allow. */
export function parseAccount(fields: Fields): AccountDocument | undefined {
  const days = fields["retentionDays"];
  const updatedAt = fields["updatedAt"];
  const stamped = fields["stampedDays"];
  return offeredDays(days) && typeof updatedAt === "number"
    ? {
        retentionDays: days,
        updatedAt,
        // a document from before stamps were recorded: nothing was stamped yet
        stampedDays: offeredDays(stamped) ? stamped : 0,
      }
    : undefined;
}

/** Whether the account's entries still have to be stamped with its retention. */
export function needsRestamp(account: AccountDocument): boolean {
  return account.retentionDays !== account.stampedDays;
}

/**
 * Which copy of the account settings wins, last write wins: the account's
 * when newer, this device's when newer and set here, or neither when they agree.
 */
export function accountWinner(
  local: AccountDocument,
  remote: AccountDocument | undefined,
): "remote" | "local" | "same" {
  const remoteAt = remote?.updatedAt ?? 0;
  let winner: "remote" | "local" | "same" = "same";
  if (remote !== undefined && remoteAt > local.updatedAt) {
    winner = "remote";
  } else if (local.updatedAt > remoteAt) {
    winner = "local";
  }
  return winner;
}

/**
 * When an entry leaves the account: `addedAt` plus the retention; never for
 * a starred entry or under forever.
 */
export function entryExpiry(
  entry: Pick<Entry, "addedAt" | "starred">,
  retention: Retention,
): number | undefined {
  return retention === "forever" || entry.starred
    ? undefined
    : entry.addedAt + retention * DAY_MS;
}

/**
 * Whether an entry's body may be gone from the account: a body lasts at
 * least the longest finite retention plus a week after its upload, which
 * came no later than about when this device added the entry, so only an
 * entry older than that is worth looking up.
 */
export function bodyMayBeGone(addedAt: number, now: number): boolean {
  return now - addedAt >= bodyLifeDays() * DAY_MS;
}

function bodyLifeDays(): number {
  return (
    Math.max(...RETENTION_CHOICES.map((choice) => retentionDays(choice))) +
    BODY_GRACE_DAYS
  );
}

/**
 * When a body uploaded now leaves the account: after the longest finite
 * retention offered plus a week; never when this account keeps entries forever.
 */
export function bodyExpiry(
  retention: Retention,
  now: number,
): number | undefined {
  return retention === "forever" ? undefined : now + bodyLifeDays() * DAY_MS;
}

/**
 * The expiry an account entry must be changed to under a retention: a time,
 * `null` to clear it, or undefined when it is already right.
 */
export function restampedExpiry(
  entry: Pick<Entry, "addedAt" | "starred">,
  current: number | undefined,
  retention: Retention,
): number | null | undefined {
  const wanted = entryExpiry(entry, retention);
  let change: number | null | undefined;
  if (wanted === current) {
    change = undefined;
  } else if (wanted === undefined) {
    change = null;
  } else {
    change = wanted;
  }
  return change;
}

/** A feed as stored at `users/{uid}/subscriptions/{feedId}`; a tombstone when deleted. */
export interface SubscriptionDocument {
  /** Feed address. */
  url: string;
  /** Name shown for the feed. */
  title: string;
  /** Group name; empty for none. */
  folder: string;
  /** Whether the person chose the name. */
  renamed: boolean;
  /** Picture address; empty for none. */
  icon: string;
  /** Site address; empty when unknown. */
  siteUrl: string;
  /** When it last changed, by the changing device's clock. */
  updatedAt: number;
  /** The feed was unsubscribed; the other fields are empty. */
  deleted: boolean;
}

/** An article as stored at `users/{uid}/entries/{entryId}`. */
export interface EntryDocument {
  /** Id of its feed. */
  feedId: string;
  /** The feed's id for the article. */
  guid: string;
  /** Headline. */
  title: string;
  /** Date, or when first seen if undated. */
  published: number;
  /** Whether the feed gave the date. */
  dated: boolean;
  /** Link to the original. */
  url: string;
  /** Writer; empty for none. */
  author: string;
  /** Lead picture address; empty for none. */
  imageUrl: string;
  /** Plain-text start of the article. */
  excerpt: string;
  /** The feed's own short description. */
  summary: string;
  /** Topics the feed lists. */
  categories: string[];
  /** Hash of the shared body; empty when unknown. */
  bodyHash: string;
  /** Whether it has been read. */
  read: boolean;
  /** Whether it is starred. */
  starred: boolean;
  /** When a device first saw it. */
  addedAt: number;
  /** When the read or starred mark last changed. */
  updatedAt: number;
  /** Milliseconds since the epoch after which Firestore may delete it; only with a finite retention. */
  expireAt?: number;
}

/** A view's settings as stored at `users/{uid}/settings/{settingsDocumentId(key)}`. */
export interface SettingsDocument {
  /** The view key, since document ids cannot hold every character a key can. */
  key: string;
  /** Newest or oldest first. */
  sort: "newest" | "oldest";
  /** Words that hide articles. */
  hideWords: string[];
  /** Words an article must have to show. */
  onlyWords: string[];
  /** Topics turned off. */
  offTopics: string[];
  /** When they last changed, by the changing device's clock. */
  updatedAt: number;
  /** The settings were removed; the lists are empty. */
  deleted: boolean;
}

/** The fields of a feed that sync between devices. */
export function subscriptionDocument(feed: Feed): SubscriptionDocument {
  return {
    url: feed.url,
    title: cutText(feed.title, FIELD_LIMITS.title),
    folder: cutText(feed.folder, FIELD_LIMITS.folder),
    renamed: feed.renamed,
    icon: fitAddress(feed.icon),
    siteUrl: fitAddress(feed.siteUrl),
    updatedAt: feed.updatedAt,
    deleted: false,
  };
}

/** The document that records an unsubscribe. */
export function subscriptionTombstone(updatedAt: number): SubscriptionDocument {
  return {
    url: "",
    title: "",
    folder: "",
    renamed: false,
    icon: "",
    siteUrl: "",
    updatedAt,
    deleted: true,
  };
}

/**
 * A feed from its document, keeping this device's own fields from `local` when
 * given. Each device reads the feed's own name, site and picture itself, so a
 * change from another device (such as a new group) does not replace them with
 * that device's older copy: the name is taken only when someone named the feed
 * by hand, and the site and picture only when this device has none.
 */
export function feedFromDocument(
  id: string,
  stored: SubscriptionDocument,
  local?: Feed,
): Feed {
  const keepName = local !== undefined && !stored.renamed;
  const localSite = local?.siteUrl ?? "";
  const localIcon = local?.icon ?? "";
  return {
    id,
    url: stored.url,
    title: keepName ? local.title : stored.title,
    siteUrl: localSite === "" ? stored.siteUrl : localSite,
    icon: localIcon === "" ? stored.icon : localIcon,
    renamed: keepName ? local.renamed : stored.renamed,
    folder: stored.folder,
    lastFetched: local?.lastFetched ?? 0,
    error: local?.error ?? "",
    updatedAt: stored.updatedAt,
  };
}

/** An entry's document; `expireAt` is set only when retention is finite and the entry is not starred. */
export function entryDocument(
  entry: Entry,
  retention: Retention = "forever",
): EntryDocument {
  const stored: EntryDocument = {
    feedId: entry.feedId,
    guid: entry.guid,
    title: cutText(entry.title, FIELD_LIMITS.title),
    published: entry.published,
    dated: entry.dated,
    url: fitAddress(entry.url),
    author: cutText(entry.author, FIELD_LIMITS.author),
    imageUrl: fitAddress(entry.imageUrl),
    excerpt: cutText(entry.excerpt, FIELD_LIMITS.excerpt),
    summary: cutText(entry.summary, FIELD_LIMITS.summary),
    categories: entry.categories.slice(0, FIELD_LIMITS.categories),
    bodyHash: entry.bodyHash,
    read: entry.read,
    starred: entry.starred,
    addedAt: entry.addedAt,
    updatedAt: entry.updatedAt,
  };
  const expireAt = entryExpiry(entry, retention);
  if (expireAt !== undefined) {
    stored.expireAt = expireAt;
  }
  return stored;
}

/** An entry from its document. */
export function entryFromDocument(id: string, stored: EntryDocument): Entry {
  return {
    id,
    feedId: stored.feedId,
    guid: stored.guid,
    title: stored.title,
    url: stored.url,
    published: stored.published,
    dated: stored.dated,
    author: stored.author,
    imageUrl: stored.imageUrl,
    excerpt: stored.excerpt,
    summary: stored.summary,
    categories: [...stored.categories],
    read: stored.read,
    starred: stored.starred,
    addedAt: stored.addedAt,
    position: 0,
    bodyHash: stored.bodyHash,
    updatedAt: stored.updatedAt,
  };
}

/** A settings document; `settings` undefined makes a tombstone. */
export function settingsDocument(
  key: string,
  settings: SavedSettings | undefined,
  updatedAt: number,
): SettingsDocument {
  return {
    key,
    sort: settings?.sort ?? DEFAULT_SETTINGS.sort,
    hideWords: (settings?.hideWords ?? []).slice(0, FIELD_LIMITS.words),
    onlyWords: (settings?.onlyWords ?? []).slice(0, FIELD_LIMITS.words),
    offTopics: (settings?.offTopics ?? []).slice(0, FIELD_LIMITS.words),
    updatedAt,
    deleted: settings === undefined,
  };
}

/** Saved settings from their document. */
export function settingsFromDocument(stored: SettingsDocument): SavedSettings {
  return {
    sort: stored.sort,
    hideWords: [...stored.hideWords],
    onlyWords: [...stored.onlyWords],
    offTopics: [...stored.offTopics],
    updatedAt: stored.updatedAt,
  };
}

/** A Firestore document id for a view key; ids may not contain a slash. */
export function settingsDocumentId(key: string): string {
  return encodeURIComponent(key);
}

type Fields = Record<string, unknown>;

function text(fields: Fields, name: string): string {
  const value = fields[name];
  return typeof value === "string" ? value : "";
}

function number(fields: Fields, name: string): number {
  const value = fields[name];
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function flag(fields: Fields, name: string): boolean {
  const value = fields[name];
  return typeof value === "boolean" && value;
}

function texts(fields: Fields, name: string): string[] {
  const value = fields[name];
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string")
    : [];
}

/** Read a subscription document, or undefined when it lacks its timestamp. */
export function parseSubscription(
  fields: Fields,
): SubscriptionDocument | undefined {
  let stored: SubscriptionDocument | undefined;
  if (typeof fields["updatedAt"] === "number") {
    stored = {
      // a plain http feed is kept as http, to be tried over https before any fetch; other addresses are dropped
      url: webAddress(text(fields, "url")),
      title: text(fields, "title"),
      folder: text(fields, "folder"),
      renamed: flag(fields, "renamed"),
      icon: upgradedAddress(text(fields, "icon")),
      siteUrl: upgradedAddress(text(fields, "siteUrl")),
      updatedAt: number(fields, "updatedAt"),
      deleted: flag(fields, "deleted"),
    };
    if (!stored.deleted && stored.url === "") {
      stored = undefined;
    }
  }
  return stored;
}

/** Read an entry document, or undefined when it lacks its feed or timestamp. */
export function parseEntry(fields: Fields): EntryDocument | undefined {
  let stored: EntryDocument | undefined;
  if (
    typeof fields["updatedAt"] === "number" &&
    text(fields, "feedId") !== ""
  ) {
    stored = {
      feedId: text(fields, "feedId"),
      guid: text(fields, "guid"),
      title: text(fields, "title"),
      published: number(fields, "published"),
      dated: flag(fields, "dated"),
      url: upgradedAddress(text(fields, "url")),
      author: text(fields, "author"),
      imageUrl: upgradedAddress(text(fields, "imageUrl")),
      excerpt: text(fields, "excerpt"),
      summary: text(fields, "summary"),
      categories: texts(fields, "categories"),
      bodyHash: text(fields, "bodyHash"),
      read: flag(fields, "read"),
      starred: flag(fields, "starred"),
      addedAt: number(fields, "addedAt"),
      updatedAt: number(fields, "updatedAt"),
    };
  }
  return stored;
}

/** Read a settings document, or undefined when it lacks its key or timestamp. */
export function parseSettings(fields: Fields): SettingsDocument | undefined {
  let stored: SettingsDocument | undefined;
  if (typeof fields["updatedAt"] === "number" && text(fields, "key") !== "") {
    stored = {
      key: text(fields, "key"),
      sort: fields["sort"] === "oldest" ? "oldest" : "newest",
      hideWords: texts(fields, "hideWords"),
      onlyWords: texts(fields, "onlyWords"),
      offTopics: texts(fields, "offTopics"),
      updatedAt: number(fields, "updatedAt"),
      deleted: flag(fields, "deleted"),
    };
  }
  return stored;
}
