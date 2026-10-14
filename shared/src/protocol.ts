import type { ParsedFeed } from "./feed.ts";

/** A feed the web app asks the extension to keep pulling. */
export interface TrackedFeed {
  /** The feed's identifier, from its address. */
  id: string;
  /** Address the feed is fetched from. */
  url: string;
  /** The web app holds no articles for this feed, so it needs them all again. */
  empty: boolean;
  /** An answer from the address the web app already took in, to count as the first check of a schedule that starts over; absent when none. */
  first?: FetchOutcome;
}

/** What the extension pulled from one feed since the web app last asked. */
export interface PulledFeed {
  /** Which feed this is. */
  feedId: string;
  /** Its details and entries. */
  feed: ParsedFeed;
  /** `ETag` of the answer it came from. */
  etag: string;
  /** `Last-Modified` of the answer it came from. */
  lastModified: string;
}

/** How the extension's last check of a feed went. */
export interface FeedStatus {
  feedId: string;
  /** Milliseconds since the epoch of the last successful check; zero if never. */
  lastChecked: number;
  /** Why the last check failed; empty when it did not. */
  error: string;
  /** Milliseconds since the epoch of the next scheduled check; absent from older extensions. */
  nextCheck?: number;
}

/** A message the web app sends to the extension. */
export type ExtensionRequest =
  | { type: "ping" }
  | {
      type: "fetch";
      url: string;
      etag: string;
      lastModified: string;
    }
  | { type: "track"; feeds: TrackedFeed[] }
  | { type: "refresh"; feedIds: string[] }
  | { type: "checkDue" }
  | { type: "drain" }
  | { type: "ack"; upTo: number };

/** Outcome of fetching one address. */
export interface FetchOutcome {
  /** HTTP status; zero when the request failed. */
  status: number;
  /** Address the answer came from after redirects; empty when there was none. */
  url: string;
  /** Body of a 200 answer within the size limit; empty otherwise. */
  text: string;
  /** The answer's `ETag`. */
  etag: string;
  /** The answer's `Last-Modified`. */
  lastModified: string;
  /** Milliseconds the site asked us to wait before checking again. */
  hint: number;
  /**
   * Refused because the address, or where it redirected, is not https; status
   * is then zero. The extension also sets it when the address redirects but
   * following the redirects never reaches an answer, since its content policy
   * turns each hop to http into https, and a site that sends https back to
   * http then loops.
   */
  insecure: boolean;
}

/** The extension's reply to each request type. */
export type ExtensionResponse =
  | { type: "pong"; version: string }
  | ({ type: "fetched" } & FetchOutcome)
  | { type: "tracked" }
  | { type: "refreshed" }
  | { type: "queued" }
  | {
      type: "drained";
      /** Acknowledge up to this to remove what was drained; zero when nothing was. */
      seq: number;
      pulled: PulledFeed[];
      status: FeedStatus[];
      /** More is waiting than fit in this reply; drain again after acknowledging. */
      more: boolean;
      /** Feeds are being checked or saved; absent from older extensions. */
      checking?: boolean;
    }
  | { type: "acked" }
  | { type: "error"; message: string };
