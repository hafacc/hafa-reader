import type { ViewSettings } from "$shared/filter.ts";

/** A subscribed feed. */
export interface Feed {
  /** Hash of the feed address. */
  id: string;
  /** Feed address. */
  url: string;
  /** Name shown for the feed. */
  title: string;
  /** Address of the site the feed belongs to; empty when unknown. */
  siteUrl: string;
  /** Address of the feed's small picture; empty to use the site's favicon. */
  icon: string;
  /** The person gave the feed its own name, so updates must not replace it. */
  renamed: boolean;
  /** Group the feed is listed under; empty for none. */
  folder: string;
  /** Milliseconds since the epoch of the last successful check; zero if never. */
  lastFetched: number;
  /** Why the last check failed; empty when it did not. */
  error: string;
  /** When the person last subscribed, renamed or regrouped it; the feed's own updates leave it alone. */
  updatedAt: number;
}

/** An article, without its body. */
export interface Entry {
  /** Hash of the feed id and the article's guid, the same on every device. */
  id: string;
  /** Id of its feed. */
  feedId: string;
  /** The feed's id for the article. */
  guid: string;
  /** Headline. */
  title: string;
  /** Link to the original; empty for none. */
  url: string;
  /** When the feed says it was posted; the first sighting if the feed gives no date. */
  published: number;
  /** Whether `published` came from the feed. */
  dated: boolean;
  /** Writer; empty for none. */
  author: string;
  /** Lead picture address; empty for none. */
  imageUrl: string;
  /** Plain-text start of the article, for the list. */
  excerpt: string;
  /** The feed's own short description, as plain text. */
  summary: string;
  /** Sections, topics and tags the feed lists for the article. */
  categories: string[];
  /** Whether it has been read. */
  read: boolean;
  /** Whether it is starred. */
  starred: boolean;
  /** When this device first saw the article. */
  addedAt: number;
  /** Place in the feed when first seen, from 0 at the top; orders articles with the same date. Kept on this device only. */
  position: number;
  /** Hex SHA-256 of the stored body; empty when unknown or there is none. */
  bodyHash: string;
  /** When the read or starred mark last changed; zero if never, so the account's marks win. */
  updatedAt: number;
}

/** One view's settings with when they last changed. */
export interface SavedSettings extends ViewSettings {
  updatedAt: number;
}

/** What the article list shows. */
export type Selection =
  | { kind: "all" }
  | { kind: "starred" }
  | { kind: "folder"; name: string }
  | { kind: "feed"; id: string };

/** Where the person is in the app, kept in each browser history entry. */
export interface Place {
  /** Which column is on screen on a phone. */
  screen: "list" | "detail";
  /** What the detail column shows. */
  detail: "article" | "settings" | "add" | "help";
  /** The view the list shows. */
  selection: Selection;
  /** The open article; empty for none. */
  entryId: string;
}
