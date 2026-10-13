import { type ParsedFeed, parseFeed } from "./feed.ts";
import { sha256Hex } from "./id.ts";
import type { FetchOutcome, PulledFeed, TrackedFeed } from "./protocol.ts";
import {
  chooseInterval,
  type FeedModel,
  learnPattern,
  MIN_INTERVAL_MS,
  observe,
  observeWindow,
  PRIOR,
  retryInterval,
} from "./schedule.ts";

/** Words for an HTTP status other than success, the same in every client. */
export function statusProblem(status: number): string {
  let words = `The site answered ${status}.`;
  if (status === 401 || status === 403) {
    words = "The site refused to let Reader read it.";
  } else if (status === 404 || status === 410) {
    words = "Nothing was found at that address.";
  } else if (status === 429) {
    words = "The site asked Reader to slow down.";
  } else if (status >= 500 && status <= 599) {
    words = "The site is having trouble.";
  }
  return words;
}

/** A tracked feed with its schedule and what its last check saw. */
export interface ScheduledFeed extends Omit<TrackedFeed, "empty"> {
  /** `ETag` of the last body seen, sent back as `If-None-Match`. */
  etag: string;
  /** `Last-Modified` of the last body seen, sent back as `If-Modified-Since`. */
  lastModified: string;
  /** What is known about when the feed posts. */
  model: FeedModel;
  /** Milliseconds since the epoch of the next check; zero means due now. */
  nextFetch: number;
  /** Milliseconds since the epoch of the last successful check; zero if never. */
  lastChecked: number;
  /** Hash of the last body seen, to tell a changed feed from a repeat; empty to send the next one whole. */
  hash: string;
  /** Guids in the last window, to count which items are new. */
  guids: string[];
  /** The feed's title, site link and icon at the last change; absent if unknown. */
  details?: string;
  /** Failed checks in a row. */
  failures: number;
  /** Why the last check failed; empty when it did not. */
  error: string;
  /** Milliseconds since the epoch when the web app stopped tracking it; absent while tracked. */
  retiredAt?: number;
}

/** What one check of a feed found. */
export interface CheckResult {
  /** The feed's schedule after the check. */
  feed: ScheduledFeed;
  /** The feed's contents, when the web app has something new to take from them. */
  pulled: PulledFeed | undefined;
}

function sinceLastCheck(
  feed: ScheduledFeed,
  now: number,
): { fromMs: number; toMs: number } {
  return { fromMs: feed.lastChecked === 0 ? now : feed.lastChecked, toMs: now };
}

function feedDetails(parsed: ParsedFeed): string {
  return [parsed.title, parsed.siteUrl, parsed.iconUrl].join("\n");
}

function learn(
  feed: ScheduledFeed,
  parsed: ParsedFeed,
  now: number,
): FeedModel {
  let model: FeedModel;
  if (feed.lastChecked === 0) {
    const published = parsed.entries.map((entry) => entry.published);
    model = learnPattern(observeWindow(feed.model, published), published);
  } else {
    const known = new Set(feed.guids);
    const added = parsed.entries.filter((entry) => !known.has(entry.guid));
    const observed = observe(
      feed.model,
      added.length,
      sinceLastCheck(feed, now),
      parsed.entries.length,
    );
    model = learnPattern(
      observed,
      added.map((entry) => entry.published),
    );
  }
  return model;
}

function succeeded(
  feed: ScheduledFeed,
  now: number,
  model: FeedModel,
  hint: number,
): ScheduledFeed {
  return {
    ...feed,
    model,
    lastChecked: now,
    nextFetch: now + Math.max(chooseInterval(model, now), hint),
    failures: 0,
    error: "",
  };
}

function quiet(
  feed: ScheduledFeed,
  now: number,
  outcome: FetchOutcome,
): ScheduledFeed {
  const model = observe(feed.model, 0, sinceLastCheck(feed, now), 0);
  return succeeded(feed, now, model, outcome.hint);
}

function failed(
  feed: ScheduledFeed,
  now: number,
  error: string,
  hint: number,
): ScheduledFeed {
  return {
    ...feed,
    failures: feed.failures + 1,
    nextFetch: now + Math.max(retryInterval(feed.failures), hint),
    error,
  };
}

function changed(
  feed: ScheduledFeed,
  outcome: FetchOutcome,
  parsed: ParsedFeed,
  context: { now: number; hash: string },
): CheckResult {
  const known = new Set(feed.guids);
  const details = feedDetails(parsed);
  const added = parsed.entries.filter((entry) => !known.has(entry.guid));
  const news = feed.hash === "" || details !== feed.details || added.length > 0;
  // Every item new means more may have been missed, so look again soon to measure the rate.
  const saturated =
    known.size > 0 &&
    added.length > 0 &&
    added.length === parsed.entries.length;
  const scheduled = succeeded(
    feed,
    context.now,
    learn(feed, parsed, context.now),
    outcome.hint,
  );
  return {
    feed: {
      ...scheduled,
      nextFetch: saturated
        ? context.now + Math.max(MIN_INTERVAL_MS, outcome.hint)
        : scheduled.nextFetch,
      hash: context.hash,
      etag: outcome.etag,
      lastModified: outcome.lastModified,
      guids: parsed.entries.map((entry) => entry.guid),
      details,
    },
    pulled: news
      ? {
          feedId: feed.id,
          feed: parsed,
          etag: outcome.etag,
          lastModified: outcome.lastModified,
        }
      : undefined,
  };
}

/**
 * Fold one fetch of a feed into its schedule.
 *
 * A 304 or an unchanged body adds a quiet check and keeps the answer's
 * validators. A changed body is parsed against the address it came from, its
 * new items are counted, and it is handed on only when it has a new item, new
 * details, or the feed was reset. Anything else is a failure, retried after the
 * longer of the doubling backoff and the site's requested wait.
 */
export async function judge(
  feed: ScheduledFeed,
  outcome: FetchOutcome,
  now: number,
): Promise<CheckResult> {
  let result: CheckResult;
  if (outcome.status === 304) {
    result = { feed: quiet(feed, now, outcome), pulled: undefined };
  } else if (outcome.status === 200) {
    const hash = await sha256Hex(outcome.text);
    if (hash === feed.hash) {
      result = {
        feed: {
          ...quiet(feed, now, outcome),
          etag: outcome.etag,
          lastModified: outcome.lastModified,
        },
        pulled: undefined,
      };
    } else {
      let parsed: ParsedFeed | undefined;
      try {
        parsed = parseFeed(outcome.text, outcome.url || feed.url);
      } catch {
        parsed = undefined;
      }
      result = parsed
        ? changed(feed, outcome, parsed, { now, hash })
        : {
            feed: failed(
              feed,
              now,
              "The site answered, but not with a readable feed.",
              outcome.hint,
            ),
            pulled: undefined,
          };
    }
  } else {
    let error = statusProblem(outcome.status);
    if (outcome.insecure) {
      error = "The feed is not on https.";
    } else if (outcome.status === 0) {
      error = "The site did not answer.";
    }
    result = {
      feed: failed(feed, now, error, outcome.hint),
      pulled: undefined,
    };
  }
  return result;
}

/** Days a feed the web app stopped tracking keeps its schedule, in milliseconds. */
export const RETIRED_KEEP_MS = 30 * 24 * 60 * 60 * 1000;

/** Schedules of tracked feeds and of feeds recently untracked. */
export interface Schedules {
  /** Feeds the web app tracks, in its order. */
  tracked: ScheduledFeed[];
  /** Feeds it stopped tracking, kept for `RETIRED_KEEP_MS` in case they return. */
  retired: ScheduledFeed[];
}

function fresh(feed: TrackedFeed): ScheduledFeed {
  return {
    id: feed.id,
    url: feed.url,
    etag: "",
    lastModified: "",
    model: PRIOR,
    nextFetch: 0,
    lastChecked: 0,
    hash: "",
    guids: [],
    failures: 0,
    error: "",
  };
}

function needsReset(
  existing: ScheduledFeed,
  feed: TrackedFeed,
  waiting: Set<string>,
): boolean {
  return (
    existing.url !== feed.url ||
    (feed.empty &&
      existing.hash !== "" &&
      existing.guids.length > 0 &&
      !waiting.has(feed.id))
  );
}

/**
 * Schedules for the feeds the web app tracks, from those already known.
 *
 * A new feed starts from the prior and is due now. A known feed, tracked or
 * recently retired, keeps its schedule unless its address changed, or the web
 * app holds none of its articles while the feed has some that no waiting
 * record (`waiting`, by feed id) will deliver; then it is reset: due now, sent
 * whole on its next check, and still keeping its model, guids and last check
 * so nothing is counted twice. Feeds no longer tracked are retired at `nowMs`
 * rather than forgotten, so a list sent by mistake costs nothing learned;
 * retired feeds older than `RETIRED_KEEP_MS` are dropped.
 */
export function retrack(
  feeds: TrackedFeed[],
  previous: Schedules,
  waiting: Set<string>,
  nowMs: number,
): Schedules {
  const byId = new Map<string, ScheduledFeed>();
  for (const feed of [...previous.retired, ...previous.tracked]) {
    byId.set(feed.id, feed);
  }
  const tracked = feeds.map((feed): ScheduledFeed => {
    const known = byId.get(feed.id);
    let scheduled: ScheduledFeed;
    if (known === undefined) {
      scheduled = fresh(feed);
    } else {
      const { retiredAt: _retiredAt, ...existing } = known;
      scheduled = needsReset(existing, feed, waiting)
        ? {
            ...existing,
            url: feed.url,
            etag: "",
            lastModified: "",
            nextFetch: 0,
            hash: "",
          }
        : existing;
    }
    return scheduled;
  });
  const trackedIds = new Set(feeds.map((feed) => feed.id));
  const retired = [
    ...previous.retired.filter(
      (feed) =>
        !trackedIds.has(feed.id) &&
        nowMs - (feed.retiredAt ?? nowMs) < RETIRED_KEEP_MS,
    ),
    ...previous.tracked
      .filter((feed) => !trackedIds.has(feed.id))
      .map((feed) => ({ ...feed, retiredAt: nowMs })),
  ];
  return { tracked, retired };
}
