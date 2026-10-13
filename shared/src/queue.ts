import type { CheckResult, ScheduledFeed } from "./check.ts";

/** One feed a caller wants checked. */
export interface CheckRequest {
  /** The feed as stored when it was asked for. */
  feed: ScheduledFeed;
  /** Chance it holds news; likelier feeds are fetched first. */
  probability: number;
  /** A refresh is waiting for it. */
  foreground: boolean;
  /** Time its fetch gets, in milliseconds. */
  timeoutMs: number;
}

/** A finished check waiting to be saved, with the feed as it was queued. */
export interface FinishedCheck {
  /** The feed as stored when it was queued. */
  feed: ScheduledFeed;
  /** What the check found. */
  result: CheckResult;
}

/** What a caller can wait on for one feed's check. */
export interface CheckHandle {
  /** Resolves once the result is saved or the check stops being waited for. */
  settled: Promise<void>;
  /** Resolves once the result is saved, or the check failed outright. */
  stored: Promise<void>;
}

/** What the queue needs from its host. */
export interface QueueHost {
  /**
   * Fetch and judge a feed within `timeoutMs`; undefined when the fetch ran
   * out of a time shorter than `QueueLimits.fullTimeoutMs`.
   */
  check: (
    feed: ScheduledFeed,
    timeoutMs: number,
  ) => Promise<CheckResult | undefined>;
  /** Save a batch of finished checks. */
  save: (batch: FinishedCheck[]) => Promise<void>;
  /** Run `callback` after `delayMs`. */
  setTimer: (callback: () => void, delayMs: number) => void;
  /** Milliseconds since the epoch. */
  now: () => number;
  /** Told whether anything is queued, running or unsaved, after each change. */
  onBusy: (busy: boolean) => void;
}

/** How hard the queue works. */
export interface QueueLimits {
  /** Most checks running at once. */
  concurrency: number;
  /** Most checks running at once against one host. */
  perHost: number;
  /** Time a check gets once it has timed out as a waited-for check. */
  fullTimeoutMs: number;
  /** Finished checks that trigger a save. */
  saveBatch: number;
  /** Longest time a finished check waits to be saved, in milliseconds. */
  saveIntervalMs: number;
}

/** A queue of feed checks; see `createCheckQueue`. */
export interface CheckQueue {
  /**
   * Queue the requests, joining any check of the same feed already queued,
   * running or unsaved, and return each one's handle in order.
   */
  enqueue: (requests: CheckRequest[]) => CheckHandle[];
  /** Whether anything is queued, running or unsaved. */
  busy: () => boolean;
}

interface QueuedCheck {
  feed: ScheduledFeed;
  probability: number;
  foreground: boolean;
  timeoutMs: number;
  /** It timed out once, so it now gets the full timeout. */
  slow: boolean;
  settled: PromiseWithResolvers<void>;
  stored: PromiseWithResolvers<void>;
}

function hostOf(url: string): string {
  let host = "";
  try {
    ({ host } = new URL(url));
  } catch {
    host = "";
  }
  return host;
}

function ranksBefore(candidate: QueuedCheck, best: QueuedCheck): boolean {
  return candidate.foreground === best.foreground
    ? candidate.probability > best.probability
    : candidate.foreground;
}

/**
 * A queue of feed checks shared by refreshes and the timer.
 *
 * Waited-for checks go first, then likelier ones; at most `concurrency` run at
 * once and `perHost` against one host. A feed already queued, running or
 * unsaved is joined rather than checked twice: the join keeps the higher
 * probability, becomes waited-for if either was, and takes the shorter
 * timeout unless the check already timed out once; a waited-for join of a
 * running check stops being waited for after its own timeout. A waited-for
 * check that runs out of time stops being waited for and is queued again in
 * the background with `fullTimeoutMs`. Finished checks are saved in batches:
 * once no waited-for check is left unfinished and one is done, at
 * `saveBatch`, `saveIntervalMs` after the last save, or when nothing is left
 * to run.
 */
export function createCheckQueue(
  host: QueueHost,
  limits: QueueLimits,
): CheckQueue {
  const queued: QueuedCheck[] = [];
  const inFlight = new Set<QueuedCheck>();
  const checking = new Map<string, QueuedCheck>();
  const activeByHost = new Map<string, number>();
  let unsaved: { check: QueuedCheck; result: CheckResult }[] = [];
  let lastSave = 0;
  let saveTimerArmed = false;

  function busy(): boolean {
    return queued.length > 0 || inFlight.size > 0 || unsaved.length > 0;
  }

  function release(check: QueuedCheck): void {
    checking.delete(check.feed.id);
    check.settled.resolve();
    check.stored.resolve();
  }

  function takeNext(): QueuedCheck | undefined {
    let bestIndex = -1;
    for (const [index, check] of queued.entries()) {
      const best = queued[bestIndex];
      if (
        (activeByHost.get(hostOf(check.feed.url)) ?? 0) < limits.perHost &&
        (best === undefined || ranksBefore(check, best))
      ) {
        bestIndex = index;
      }
    }
    return bestIndex < 0 ? undefined : queued.splice(bestIndex, 1)[0];
  }

  function store(batch: { check: QueuedCheck; result: CheckResult }[]): void {
    host
      .save(batch.map(({ check, result }) => ({ feed: check.feed, result })))
      .catch(() => undefined)
      .finally(() => {
        for (const { check } of batch) {
          release(check);
        }
        host.onBusy(busy());
      });
  }

  function saveIfReady(): void {
    const foregroundLeft = [...queued, ...inFlight].some(
      (check) => check.foreground,
    );
    const ready =
      (!foregroundLeft && unsaved.some(({ check }) => check.foreground)) ||
      unsaved.length >= limits.saveBatch ||
      host.now() - lastSave >= limits.saveIntervalMs ||
      (queued.length === 0 && inFlight.size === 0);
    if (unsaved.length > 0 && ready) {
      const batch = unsaved;
      unsaved = [];
      lastSave = host.now();
      store(batch);
    } else if (unsaved.length > 0 && !saveTimerArmed) {
      // Results finished early are saved on time even while a slow check holds the rest.
      saveTimerArmed = true;
      host.setTimer(
        () => {
          saveTimerArmed = false;
          saveIfReady();
        },
        Math.max(0, lastSave + limits.saveIntervalMs - host.now()),
      );
    }
  }

  function finished(check: QueuedCheck, result: CheckResult | undefined): void {
    if (result) {
      unsaved.push({ check, result });
    } else {
      check.slow = true;
      check.foreground = false;
      check.timeoutMs = limits.fullTimeoutMs;
      queued.push(check);
      check.settled.resolve();
    }
  }

  function start(check: QueuedCheck): void {
    const checkHost = hostOf(check.feed.url);
    inFlight.add(check);
    activeByHost.set(checkHost, (activeByHost.get(checkHost) ?? 0) + 1);
    host
      .check(check.feed, check.timeoutMs)
      .then(
        (result) => finished(check, result),
        () => release(check),
      )
      .finally(() => {
        inFlight.delete(check);
        activeByHost.set(checkHost, (activeByHost.get(checkHost) ?? 1) - 1);
        saveIfReady();
        pump();
      });
  }

  function pump(): void {
    let next = inFlight.size < limits.concurrency ? takeNext() : undefined;
    while (next) {
      start(next);
      next = inFlight.size < limits.concurrency ? takeNext() : undefined;
    }
    host.onBusy(busy());
  }

  function join(check: QueuedCheck, request: CheckRequest): void {
    check.probability = Math.max(check.probability, request.probability);
    check.foreground ||= request.foreground;
    if (!check.slow) {
      check.timeoutMs = Math.min(check.timeoutMs, request.timeoutMs);
    }
    if (request.foreground && inFlight.has(check)) {
      // The fetch already running keeps its own time limit, so the refresh stops waiting on its own.
      host.setTimer(() => check.settled.resolve(), request.timeoutMs);
    }
  }

  function enqueue(requests: CheckRequest[]): CheckHandle[] {
    const handles = requests.map((request) => {
      let check = checking.get(request.feed.id);
      if (check) {
        join(check, request);
      } else {
        check = {
          ...request,
          slow: false,
          settled: Promise.withResolvers<void>(),
          stored: Promise.withResolvers<void>(),
        };
        checking.set(request.feed.id, check);
        queued.push(check);
      }
      return { settled: check.settled.promise, stored: check.stored.promise };
    });
    saveIfReady();
    pump();
    return handles;
  }

  return { enqueue, busy };
}
