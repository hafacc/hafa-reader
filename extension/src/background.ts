import { secureAddress } from "../../shared/src/address.ts";
import {
  type CheckResult,
  judge,
  retrack,
  type ScheduledFeed,
  takeFirstChecks,
} from "../../shared/src/check.ts";
import { decodeBody, readCapped } from "../../shared/src/decode.ts";
import {
  drainPage,
  type InboxRecord,
  mergePulled,
} from "../../shared/src/inbox.ts";
import { allowedOrigin } from "../../shared/src/origin.ts";
import type {
  ExtensionRequest,
  ExtensionResponse,
  FeedStatus,
  FetchOutcome,
  TrackedFeed,
} from "../../shared/src/protocol.ts";
import {
  type CheckHandle,
  type CheckRequest,
  createCheckQueue,
  type FinishedCheck,
} from "../../shared/src/queue.ts";
import {
  hintFromHeaders,
  planRefresh,
  refreshProbability,
  SMALL_REFRESH,
} from "../../shared/src/schedule.ts";

// Name of the timer that wakes the extension to check due feeds.
const DUE_ALARM = "due";
// Minutes between those wakes.
const DUE_CHECK_MINUTES = 5;
// Time a fetch gets when no refresh is waiting for it.
const FETCH_TIMEOUT_MS = 20_000;
// A refresh waits this long for a feed it needs; a slower one is retried in the background.
const FOREGROUND_TIMEOUT_MS = 8000;
// Most requests open at once in all.
const PULL_CONCURRENCY = 8;
// Most requests open at once to one host.
const HOST_CONCURRENCY = 2;
// Background results are saved once this many are waiting, or this long after the last save.
const SAVE_BATCH = 16;
const SAVE_INTERVAL_MS = 5000;
// Chrome stops an idle service worker after 30 seconds; an API call resets that.
const KEEP_ALIVE_MS = 20_000;
// Characters of JSON one drain hands over, well under Chrome's 64 MiB message limit.
const DRAIN_BUDGET = 8 * 1024 * 1024;
// Each waiting feed is stored under this prefix and its id, so a save touches only the feeds it changes.
const INBOX_PREFIX = "inbox:";
// Each tracked feed's schedule is stored the same way, under this prefix and its id.
const FEED_PREFIX = "feed:";

let chain: Promise<unknown> = Promise.resolve();

/** Run a read-modify-write of the stored state after any earlier one has finished. */
function exclusive<Result>(work: () => Promise<Result>): Promise<Result> {
  const next = chain.then(work, work);
  chain = next.catch(() => undefined);
  return next;
}

/** Stored state besides the per-feed keys. */
interface State {
  /** Ids of the tracked feeds, in the web app's order; each schedule sits under its own key. */
  trackedIds: string[];
  /** Feeds the web app stopped tracking, kept a while in case they return. */
  retired: ScheduledFeed[];
  nextSeq: number;
  /** Sequence number of each waiting feed's record, by feed id. */
  inboxSeqs: Record<string, number>;
}

function inboxKey(feedId: string): string {
  return `${INBOX_PREFIX}${feedId}`;
}

function feedKey(feedId: string): string {
  return `${FEED_PREFIX}${feedId}`;
}

async function loadState(): Promise<State> {
  const fallback: State = {
    trackedIds: [],
    retired: [],
    nextSeq: 1,
    inboxSeqs: {},
  };
  return await chrome.storage.local.get<State>(fallback);
}

async function loadFeeds(feedIds: string[]): Promise<ScheduledFeed[]> {
  const stored = await chrome.storage.local.get<Record<string, ScheduledFeed>>(
    feedIds.map(feedKey),
  );
  return feedIds.flatMap((feedId) => {
    const feed = stored[feedKey(feedId)];
    return feed === undefined ? [] : [feed];
  });
}

async function loadTracked(): Promise<ScheduledFeed[]> {
  const { trackedIds } = await loadState();
  return await loadFeeds(trackedIds);
}

function feedEntries(feeds: ScheduledFeed[]): Record<string, ScheduledFeed> {
  return Object.fromEntries(feeds.map((feed) => [feedKey(feed.id), feed]));
}

async function loadRecords(
  feedIds: string[],
): Promise<Map<string, InboxRecord>> {
  const stored = await chrome.storage.local.get<Record<string, InboxRecord>>(
    feedIds.map(inboxKey),
  );
  const records = new Map<string, InboxRecord>();
  for (const feedId of feedIds) {
    const record = stored[inboxKey(feedId)];
    if (record !== undefined) {
      records.set(feedId, record);
    }
  }
  return records;
}

const NO_ANSWER: FetchOutcome = {
  status: 0,
  url: "",
  text: "",
  etag: "",
  lastModified: "",
  hint: 0,
  insecure: false,
};

const NOT_HTTPS: FetchOutcome = { ...NO_ANSWER, insecure: true };

/**
 * Read an answer; one whose redirects left https is refused unread. The
 * manifest's `upgrade-insecure-requests` already sends every redirect hop as
 * https, so this is only a backstop; a site that sends https back to http
 * loops instead, which `failedFetch` reports.
 */
async function outcomeOf(
  response: Response,
  url: string,
): Promise<FetchOutcome> {
  if (response.url !== "" && secureAddress(response.url) === "") {
    await response.body?.cancel();
    return NOT_HTTPS;
  }
  // A body over the limit is left unread, so it counts as no readable feed.
  const bytes =
    response.status === 200
      ? await readCapped(
          response.body,
          response.headers.get("Content-Length") ?? "",
        )
      : undefined;
  return {
    status: response.status,
    url: response.url || url,
    text: bytes
      ? decodeBody(bytes, response.headers.get("Content-Type") ?? "")
      : "",
    etag: response.headers.get("ETag") ?? "",
    lastModified: response.headers.get("Last-Modified") ?? "",
    hint: hintFromHeaders(response.headers),
    insecure: false,
  };
}

/** Fetch an address conditionally; undefined when it took longer than `timeoutMs`. */
async function fetchOutcome(
  url: string,
  etag: string,
  lastModified: string,
  timeoutMs = FETCH_TIMEOUT_MS,
): Promise<FetchOutcome | undefined> {
  const headers = new Headers();
  if (etag) {
    headers.set("If-None-Match", etag);
  }
  if (lastModified) {
    headers.set("If-Modified-Since", lastModified);
  }
  let outcome: FetchOutcome | undefined;
  if (secureAddress(url) === "") {
    outcome = NOT_HTTPS;
  } else {
    try {
      const response = await fetch(url, {
        headers,
        // With validators of our own the browser bypasses its cache already; without them it
        // could answer from its cache, but checks run only when the schedule (which honors
        // max-age) or the reader asks, so the server is always asked.
        cache: etag || lastModified ? "default" : "no-cache",
        signal: AbortSignal.timeout(timeoutMs),
      });
      outcome = await outcomeOf(response, url);
    } catch (error) {
      outcome = await failedFetch(url, error, timeoutMs);
    }
  }
  return outcome;
}

/**
 * What a fetch that threw says: undefined when it timed out, `NOT_HTTPS` when
 * the address answers with a redirect (so following it failed: a loop, as the
 * browser turns a hop back to http into https again, or a target with nothing
 * on https), and `NO_ANSWER` otherwise.
 */
async function failedFetch(
  url: string,
  error: unknown,
  timeoutMs: number,
): Promise<FetchOutcome | undefined> {
  let outcome: FetchOutcome | undefined;
  if (error instanceof DOMException && error.name === "TimeoutError") {
    outcome = undefined;
  } else {
    try {
      const response = await fetch(url, {
        redirect: "manual",
        cache: "no-store",
        signal: AbortSignal.timeout(timeoutMs),
      });
      await response.body?.cancel();
      outcome = response.type === "opaqueredirect" ? NOT_HTTPS : NO_ANSWER;
    } catch {
      outcome = NO_ANSWER;
    }
  }
  return outcome;
}

/** Check one feed; undefined when it timed out before the full timeout. */
async function checkOne(
  feed: ScheduledFeed,
  now: number,
  timeoutMs: number,
): Promise<CheckResult | undefined> {
  const answer = await fetchOutcome(
    feed.url,
    feed.etag,
    feed.lastModified,
    timeoutMs,
  );
  let result: CheckResult | undefined;
  if (answer === undefined && timeoutMs < FETCH_TIMEOUT_MS) {
    result = undefined;
  } else {
    result = await judge(feed, answer ?? NO_ANSWER, now);
  }
  return result;
}

let keepAlive: ReturnType<typeof setInterval> | undefined;

function updateKeepAlive(busy: boolean): void {
  if (!busy) {
    clearInterval(keepAlive);
    keepAlive = undefined;
  } else if (keepAlive === undefined) {
    keepAlive = setInterval(() => {
      chrome.runtime.getPlatformInfo().catch(() => undefined);
    }, KEEP_ALIVE_MS);
  }
}

/** Write a batch of results to the inbox and tracked feeds. */
function store(batch: FinishedCheck[]): Promise<void> {
  return exclusive(async () => {
    const latest = await loadState();
    const byId = new Map(batch.map((entry) => [entry.feed.id, entry]));
    const current = await loadFeeds([...byId.keys()]);
    // A feed untracked meanwhile has no key, and one tracked again (new address, or emptied) keeps its reset; neither's result is kept.
    const kept = current.flatMap((feed) => {
      const entry = byId.get(feed.id);
      const unchanged =
        entry !== undefined &&
        feed.url === entry.feed.url &&
        feed.hash === entry.feed.hash;
      return unchanged ? [entry] : [];
    });
    const updated = kept.map((entry) => entry.result.feed);
    const pulledIds = kept.flatMap(({ result }) =>
      result.pulled ? [result.pulled.feedId] : [],
    );
    const records = await loadRecords(pulledIds);
    let { nextSeq } = latest;
    const inboxSeqs = { ...latest.inboxSeqs };
    for (const { result } of kept) {
      if (result.pulled) {
        const { feedId } = result.pulled;
        records.set(feedId, {
          seq: nextSeq,
          pulled: mergePulled(
            records.get(feedId)?.pulled,
            result.pulled,
            DRAIN_BUDGET,
          ),
        });
        inboxSeqs[feedId] = nextSeq;
        nextSeq += 1;
      }
    }
    await chrome.storage.local.set({
      ...feedEntries(updated),
      nextSeq,
      inboxSeqs,
      ...Object.fromEntries(
        [...records].map(([feedId, record]) => [inboxKey(feedId), record]),
      ),
    });
  });
}

const queue = createCheckQueue(
  {
    check: (feed, timeoutMs) => checkOne(feed, Date.now(), timeoutMs),
    save: store,
    setTimer: (callback, delayMs) => {
      setTimeout(callback, delayMs);
    },
    now: () => Date.now(),
    onBusy: updateKeepAlive,
  },
  {
    concurrency: PULL_CONCURRENCY,
    perHost: HOST_CONCURRENCY,
    fullTimeoutMs: FETCH_TIMEOUT_MS,
    saveBatch: SAVE_BATCH,
    saveIntervalMs: SAVE_INTERVAL_MS,
  },
);

/**
 * Queue checks of the feeds `choose` picks from the stored state, joining any
 * check of the same feed already under way, and return each request with its
 * check's handle.
 */
function enqueue(
  choose: (tracked: ScheduledFeed[], now: number) => CheckRequest[],
): Promise<{ check: CheckHandle; request: CheckRequest }[]> {
  return exclusive(async () => {
    const requests = choose(await loadTracked(), Date.now());
    const handles = queue.enqueue(requests);
    return requests.flatMap((request, index) => {
      const check = handles[index];
      return check ? [{ check, request }] : [];
    });
  });
}

/** Queue background checks of the feeds whose schedule says they are due. */
function queueDue(): Promise<{ check: CheckHandle; request: CheckRequest }[]> {
  return enqueue((tracked, now) =>
    tracked
      .filter((feed) => feed.nextFetch <= now)
      .map((feed) => ({
        feed,
        probability: refreshProbability(feed, now),
        foreground: false,
        timeoutMs: FETCH_TIMEOUT_MS,
      })),
  );
}

/** Check the feeds that are due and resolve once they are saved. */
async function checkDue(): Promise<void> {
  const checks = await queueDue();
  await Promise.all(checks.map(({ check }) => check.stored));
}

/**
 * Check the given feeds (all with none given) and resolve once the ones likely
 * to have news are saved; the rest keep being checked in the background.
 */
async function refresh(feedIds: string[]): Promise<void> {
  const wanted = new Set(feedIds);
  const checks = await enqueue((tracked, now) => {
    const chosen = tracked.filter(
      (feed) => wanted.size === 0 || wanted.has(feed.id),
    );
    let requests: CheckRequest[];
    if (chosen.length <= SMALL_REFRESH) {
      requests = chosen.map((feed) => ({
        feed,
        probability: 1,
        foreground: true,
        timeoutMs: FOREGROUND_TIMEOUT_MS,
      }));
    } else {
      const plan = planRefresh(chosen, now);
      requests = plan.ordered.map((feed, index) => ({
        feed,
        probability: plan.probabilities[index] ?? 0,
        foreground: index < plan.foreground,
        timeoutMs:
          index < plan.foreground ? FOREGROUND_TIMEOUT_MS : FETCH_TIMEOUT_MS,
      }));
    }
    return requests;
  });
  await Promise.all(
    checks
      .filter(({ request }) => request.foreground)
      .map(({ check }) => check.settled),
  );
}

function track(feeds: TrackedFeed[]): Promise<void> {
  return exclusive(async () => {
    const state = await loadState();
    const previous = {
      tracked: await loadFeeds(state.trackedIds),
      retired: state.retired,
    };
    const waiting = new Set(Object.keys(state.inboxSeqs));
    const now = Date.now();
    const next = retrack(feeds, previous, waiting, now);
    const tracked = await takeFirstChecks(next.tracked, feeds, now);
    const kept = new Set(tracked.map((feed) => feed.id));
    await chrome.storage.local.set({
      trackedIds: tracked.map((feed) => feed.id),
      retired: next.retired,
      ...feedEntries(tracked),
    });
    await chrome.storage.local.remove(
      state.trackedIds.filter((feedId) => !kept.has(feedId)).map(feedKey),
    );
  });
}

function drain(): Promise<ExtensionResponse> {
  return exclusive(async () => {
    const state = await loadState();
    const page = await drainPage(
      state.inboxSeqs,
      async (feedId) => (await loadRecords([feedId])).get(feedId),
      DRAIN_BUDGET,
    );
    const tracked = await loadFeeds(state.trackedIds);
    return {
      type: "drained",
      seq: page.records.at(-1)?.seq ?? 0,
      pulled: page.records.map((record) => record.pulled),
      status: tracked.map(
        (feed): FeedStatus => ({
          feedId: feed.id,
          lastChecked: feed.lastChecked,
          error: feed.failures >= 2 ? feed.error : "",
          nextCheck: feed.nextFetch,
        }),
      ),
      more: page.more,
      checking: queue.busy(),
    };
  });
}

function acknowledge(upTo: number): Promise<void> {
  return exclusive(async () => {
    const { inboxSeqs } = await loadState();
    const done = Object.keys(inboxSeqs).filter(
      (feedId) => (inboxSeqs[feedId] ?? 0) <= upTo,
    );
    const left = Object.fromEntries(
      Object.entries(inboxSeqs).filter(([, seq]) => seq > upTo),
    );
    await chrome.storage.local.set({ inboxSeqs: left });
    await chrome.storage.local.remove(done.map(inboxKey));
  });
}

async function handle(request: ExtensionRequest): Promise<ExtensionResponse> {
  let response: ExtensionResponse;
  if (request.type === "ping") {
    response = { type: "pong", version: chrome.runtime.getManifest().version };
  } else if (request.type === "fetch") {
    const outcome = await fetchOutcome(
      request.url,
      request.etag,
      request.lastModified,
    );
    response = { type: "fetched", ...(outcome ?? NO_ANSWER) };
  } else if (request.type === "track") {
    await track(request.feeds);
    response = { type: "tracked" };
  } else if (request.type === "refresh") {
    await refresh(request.feedIds);
    response = { type: "refreshed" };
  } else if (request.type === "checkDue") {
    await queueDue();
    response = { type: "queued" };
  } else if (request.type === "drain") {
    response = await drain();
  } else if (request.type === "ack" && Number.isFinite(request.upTo)) {
    await acknowledge(request.upTo);
    response = { type: "acked" };
  } else {
    // A request from a newer web app, or a malformed one, must not be taken as an ack.
    response = { type: "error", message: "Unknown request." };
  }
  return response;
}

function ensureAlarm(): void {
  chrome.alarms
    .get(DUE_ALARM)
    .then(async (existing) => {
      if (!existing) {
        await chrome.alarms.create(DUE_ALARM, {
          periodInMinutes: DUE_CHECK_MINUTES,
        });
      }
    })
    .catch(() => undefined);
}

chrome.runtime.onMessageExternal.addListener(
  (request: ExtensionRequest, sender, sendResponse) => {
    // Chrome already limits senders to these pages; this also holds if the manifest is ever loosened
    const patterns =
      chrome.runtime.getManifest().externally_connectable?.matches ?? [];
    if (!allowedOrigin(sender.origin, patterns)) {
      sendResponse({
        type: "error",
        message: "Not allowed.",
      } satisfies ExtensionResponse);
      return false;
    }
    handle(request).then(sendResponse, (error: unknown) =>
      sendResponse({
        type: "error",
        message: error instanceof Error ? error.message : String(error),
      } satisfies ExtensionResponse),
    );
    return true;
  },
);

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === DUE_ALARM) {
    checkDue().catch(() => undefined);
  }
});

chrome.runtime.onInstalled.addListener(ensureAlarm);
chrome.runtime.onStartup.addListener(ensureAlarm);
// Chrome can drop alarms (on restart, or when the extension is turned off and on), so check on every wake.
ensureAlarm();
