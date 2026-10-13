import { expect, test } from "bun:test";
import type { CheckResult, ScheduledFeed } from "./check.ts";
import {
  type CheckRequest,
  createCheckQueue,
  type FinishedCheck,
  type QueueLimits,
} from "./queue.ts";
import { PRIOR } from "./schedule.ts";

const LIMITS: QueueLimits = {
  concurrency: 3,
  perHost: 2,
  fullTimeoutMs: 20_000,
  saveBatch: 4,
  saveIntervalMs: 5000,
};

interface Call {
  feedId: string;
  timeoutMs: number;
  answer: PromiseWithResolvers<CheckResult | undefined>;
}

function scheduled(id: string, host = `${id}.example`): ScheduledFeed {
  return {
    id,
    url: `https://${host}/feed`,
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

function request(
  feed: ScheduledFeed,
  extra: Partial<CheckRequest> = {},
): CheckRequest {
  return {
    feed,
    probability: 0.5,
    foreground: false,
    timeoutMs: LIMITS.fullTimeoutMs,
    ...extra,
  };
}

function done(feed: ScheduledFeed): CheckResult {
  return { feed: { ...feed, lastChecked: 1 }, pulled: undefined };
}

async function settle(): Promise<void> {
  for (let round = 0; round < 5; round += 1) {
    // biome-ignore lint/performance/noAwaitInLoops: each round lets the next chain of promise callbacks run
    await Promise.resolve();
  }
  await Bun.sleep(0);
}

/** A queue over fake fetches, saves and timers, with a clock moved by hand. */
function harness(limits = LIMITS): {
  queue: ReturnType<typeof createCheckQueue>;
  calls: Call[];
  saves: FinishedCheck[][];
  busy: boolean[];
  advance: (milliseconds: number) => Promise<void>;
  answer: (feedId: string, result: CheckResult | undefined) => Promise<void>;
} {
  const calls: Call[] = [];
  const saves: FinishedCheck[][] = [];
  const busy: boolean[] = [];
  let now = 1_000_000;
  let timers: { at: number; callback: () => void }[] = [];
  const queue = createCheckQueue(
    {
      check: (feed, timeoutMs) => {
        const pending = Promise.withResolvers<CheckResult | undefined>();
        calls.push({ feedId: feed.id, timeoutMs, answer: pending });
        return pending.promise;
      },
      save: (batch) => {
        saves.push(batch);
        return Promise.resolve();
      },
      setTimer: (callback, delayMs) => {
        timers.push({ at: now + delayMs, callback });
      },
      now: () => now,
      onBusy: (value) => {
        busy.push(value);
      },
    },
    limits,
  );
  async function advance(milliseconds: number): Promise<void> {
    now += milliseconds;
    const due = timers.filter((timer) => timer.at <= now);
    timers = timers.filter((timer) => timer.at > now);
    for (const timer of due) {
      timer.callback();
    }
    await settle();
  }
  async function answer(
    feedId: string,
    result: CheckResult | undefined,
  ): Promise<void> {
    const call = calls.findLast((candidate) => candidate.feedId === feedId);
    call?.answer.resolve(result);
    await settle();
  }
  return { queue, calls, saves, busy, advance, answer };
}

test("at most the limit run at once, and at most two to one host", async () => {
  const { queue, calls } = harness();
  queue.enqueue([
    request(scheduled("a", "same.example")),
    request(scheduled("b", "same.example")),
    request(scheduled("c", "same.example")),
    request(scheduled("d")),
    request(scheduled("e")),
  ]);
  await settle();
  expect(calls.map((call) => call.feedId)).toEqual(["a", "b", "d"]);
});

test("waited-for checks and likelier ones go first, and a joined check moves up", async () => {
  const { queue, calls, answer } = harness({ ...LIMITS, concurrency: 1 });
  const blocker = scheduled("blocker");
  queue.enqueue([request(blocker)]);
  queue.enqueue([
    request(scheduled("low"), { probability: 0.1 }),
    request(scheduled("high"), { probability: 0.9 }),
    request(scheduled("waited"), { probability: 0.01, foreground: true }),
    request(scheduled("joined"), { probability: 0.2 }),
  ]);
  queue.enqueue([request(scheduled("joined"), { probability: 0.95 })]);
  for (const feedId of ["blocker", "waited", "joined", "high"]) {
    // biome-ignore lint/performance/noAwaitInLoops: checks finish one at a time
    await answer(feedId, done(scheduled(feedId)));
  }
  expect(calls.map((call) => call.feedId)).toEqual([
    "blocker",
    "waited",
    "joined",
    "high",
    "low",
  ]);
});

test("a feed asked for twice is checked once and both callers hear back", async () => {
  const { queue, calls, answer } = harness();
  const feed = scheduled("a");
  const [first] = queue.enqueue([request(feed)]);
  const [second] = queue.enqueue([request(feed, { foreground: true })]);
  await answer("a", done(feed));
  await Promise.all([first?.stored, second?.stored, second?.settled]);
  expect(calls).toHaveLength(1);
});

test("a waited-for check that runs out of time stops being waited for and is saved later", async () => {
  const { queue, calls, saves, answer } = harness();
  const feed = scheduled("slow");
  const [handle] = queue.enqueue([
    request(feed, { foreground: true, timeoutMs: 8000 }),
  ]);
  let stored = false;
  handle?.stored.then(() => {
    stored = true;
  });
  await answer("slow", undefined);
  await handle?.settled;
  expect(stored).toBe(false);
  expect(calls.map((call) => call.timeoutMs)).toEqual([8000, 20_000]);
  await answer("slow", done(feed));
  expect(saves).toHaveLength(1);
  expect(stored).toBe(true);
});

test("waited-for results are saved as soon as they are all done, even in a small batch", async () => {
  const { queue, saves, answer } = harness();
  queue.enqueue([
    request(scheduled("fast"), { foreground: true }),
    request(scheduled("background")),
  ]);
  await answer("fast", done(scheduled("fast")));
  expect(saves.map((batch) => batch.map(({ feed }) => feed.id))).toEqual([
    ["fast"],
  ]);
});

test("background results are saved in batches, or on time while a slow check runs", async () => {
  const { queue, saves, answer, advance } = harness();
  await advance(LIMITS.saveIntervalMs);
  queue.enqueue([
    request(scheduled("a")),
    request(scheduled("b")),
    request(scheduled("hung")),
  ]);
  await advance(1000);
  await answer("a", done(scheduled("a")));
  await answer("b", done(scheduled("b")));
  expect(saves).toHaveLength(1);
  queue.enqueue([request(scheduled("c")), request(scheduled("d"))]);
  await answer("c", done(scheduled("c")));
  expect(saves).toHaveLength(1);
  await advance(LIMITS.saveIntervalMs);
  expect(saves.map((batch) => batch.map(({ feed }) => feed.id))).toEqual([
    ["a"],
    ["b", "c"],
  ]);
});

test("a refresh that joins a running check stops waiting after its own time", async () => {
  const { queue, advance } = harness();
  const feed = scheduled("running");
  queue.enqueue([request(feed)]);
  await settle();
  const [joined] = queue.enqueue([
    request(feed, { foreground: true, timeoutMs: 8000 }),
  ]);
  let settled = false;
  joined?.settled.then(() => {
    settled = true;
  });
  await advance(7999);
  expect(settled).toBe(false);
  await advance(1);
  expect(settled).toBe(true);
});

test("the queue says when it is busy and when it is done", async () => {
  const { queue, busy, answer } = harness();
  queue.enqueue([request(scheduled("a"))]);
  expect(queue.busy()).toBe(true);
  await answer("a", done(scheduled("a")));
  expect(queue.busy()).toBe(false);
  expect(busy.at(-1)).toBe(false);
});
