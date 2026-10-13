import {
  chooseInterval,
  dayOfMonth,
  dayOfWeek,
  expectedActivity,
  type FeedModel,
  HOURS_PER_WEEK,
  hintFromHeaders,
  hourOfDay,
  learnPattern,
  missProbability,
  newProbability,
  observe,
  observeWindow,
  type PlannableFeed,
  type PostingPattern,
  PRIOR,
  patternFactors,
  patternOf,
  planRefresh,
  retryInterval,
  updatePattern,
  type WeeklyProfile,
} from "./schedule.ts";

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;
// Monday 5 January 2026, 00:00 UTC.
const MONDAY = Date.UTC(2026, 0, 5);

/** One case of a section: what goes in and what must come out. */
export interface FixtureCase {
  /** Arguments, by name. */
  input: Record<string, unknown>;
  /** The expected result. */
  output: unknown;
}

/** The schedule fixture: cases by section, one section per function. */
export interface ScheduleFixture {
  /** Bumped when the format changes. */
  version: number;
  /** Cases by section name. */
  sections: Record<string, FixtureCase[]>;
}

interface PlanFeed extends PlannableFeed {
  id: string;
}

function asModel(value: unknown): FeedModel {
  // biome-ignore lint/nursery/noUnsafeTypeAssertion: fixture inputs are written by `buildScheduleFixture`
  return value as FeedModel;
}

function asNumbers(value: unknown): number[] {
  // biome-ignore lint/nursery/noUnsafeTypeAssertion: fixture inputs are written by `buildScheduleFixture`
  return value as number[];
}

function asNumber(value: unknown): number {
  return Number(value);
}

/** Compute one section's output for an input, with the TypeScript implementation. */
export function computeCase(
  section: string,
  input: Record<string, unknown>,
): unknown {
  const model = asModel(input.model);
  const computers: Record<string, () => unknown> = {
    bins: () => {
      const time = asNumber(input.timeMs);
      return {
        hourOfDay: hourOfDay(time),
        dayOfWeek: dayOfWeek(time),
        dayOfMonth: dayOfMonth(time),
      };
    },
    expectedActivity: () =>
      expectedActivity(model, asNumber(input.fromMs), asNumber(input.toMs)),
    newProbability: () =>
      newProbability(
        model,
        asNumber(input.lastCheckedMs),
        asNumber(input.nowMs),
      ),
    missProbability: () =>
      missProbability(model, asNumber(input.days), asNumber(input.capacity)),
    chooseInterval: () => chooseInterval(model, asNumber(input.nowMs)),
    planRefresh: () => {
      // biome-ignore lint/nursery/noUnsafeTypeAssertion: fixture inputs are written by `buildScheduleFixture`
      const feeds = input.feeds as PlanFeed[];
      const plan = planRefresh(feeds, asNumber(input.nowMs));
      return {
        ordered: plan.ordered.map((feed) => feed.id),
        probabilities: plan.probabilities,
        foreground: plan.foreground,
      };
    },
    hintFromHeaders: () =>
      hintFromHeaders(
        // biome-ignore lint/nursery/noUnsafeTypeAssertion: fixture inputs are written by `buildScheduleFixture`
        new Headers(input.headers as Record<string, string>),
        asNumber(input.nowMs),
      ),
    retryInterval: () => retryInterval(asNumber(input.failures)),
    observe: () =>
      observe(
        model,
        asNumber(input.newItems),
        { fromMs: asNumber(input.fromMs), toMs: asNumber(input.toMs) },
        asNumber(input.windowSize),
      ),
    observeWindow: () => observeWindow(model, asNumbers(input.published)),
    updatePattern: () =>
      updatePattern(
        // biome-ignore lint/nursery/noUnsafeTypeAssertion: fixture inputs are written by `buildScheduleFixture`
        input.pattern as PostingPattern | undefined,
        asNumbers(input.published),
        asNumber(input.elapsedMs),
      ),
    learnPattern: () => learnPattern(model, asNumbers(input.published)),
    patternOf: () => patternOf(model) ?? null,
    patternFactors: () =>
      // biome-ignore lint/nursery/noUnsafeTypeAssertion: fixture inputs are written by `buildScheduleFixture`
      patternFactors(input.pattern as PostingPattern | undefined),
  };
  const compute = computers[section];
  if (compute === undefined) {
    throw new Error(`unknown section ${section}`);
  }
  return compute();
}

function weeklyPosts(weeks: number, offsetMs: number): number[] {
  return Array.from(
    { length: weeks },
    (_, week) => MONDAY - week * 7 * DAY + offsetMs,
  );
}

function legacyProfile(): WeeklyProfile {
  const profile = new Array<number>(HOURS_PER_WEEK).fill(0);
  profile[9] = 3;
  profile[2 * 24 + 14] = 1.5;
  profile[6 * 24 + 23] = 0.25;
  return profile;
}

type ModelName = "prior" | "busy" | "quiet" | "monday" | "weekdays" | "legacy";

function models(): Record<ModelName, FeedModel> {
  return {
    prior: PRIOR,
    busy: { alpha: 40, beta: 20, capacity: 20 },
    quiet: { alpha: 1, beta: 200, capacity: 10 },
    monday: learnPattern(
      { alpha: 13, beta: 91, capacity: 4 },
      weeklyPosts(104, 9 * HOUR),
    ),
    weekdays: learnPattern(
      { alpha: 60, beta: 20, capacity: 10 },
      Array.from(
        { length: 60 },
        (_, day) => MONDAY - day * DAY + 14 * HOUR,
      ).filter((time) => dayOfWeek(time) < 5),
    ),
    legacy: { alpha: 5, beta: 7, capacity: 10, profile: legacyProfile() },
  };
}

function cases(
  section: string,
  inputs: Record<string, unknown>[],
): FixtureCase[] {
  return inputs.map((input) => ({
    input,
    output: computeCase(section, input),
  }));
}

function planFeeds(nowMs: number): PlanFeed[] {
  const known = models();
  const daily = { alpha: 30, beta: 30, capacity: 10 };
  const rare = { alpha: 1, beta: 200, capacity: 10 };
  return [
    ...Array.from({ length: 8 }, (_, index) => ({
      id: `rare${index}`,
      model: rare,
      lastChecked: nowMs - HOUR,
      nextFetch: 0,
      failures: 0,
    })),
    {
      id: "daily",
      model: daily,
      lastChecked: nowMs - 2 * DAY,
      nextFetch: 0,
      failures: 0,
    },
    { id: "new", model: daily, lastChecked: 0, nextFetch: 0, failures: 0 },
    {
      id: "failing due",
      model: daily,
      lastChecked: nowMs - DAY,
      nextFetch: nowMs - 1,
      failures: 2,
    },
    {
      id: "failing later",
      model: daily,
      lastChecked: nowMs - DAY,
      nextFetch: nowMs + HOUR,
      failures: 2,
    },
    {
      id: "monday",
      model: known.monday,
      lastChecked: nowMs - 2 * DAY,
      nextFetch: 0,
      failures: 0,
    },
    {
      id: "weekdays",
      model: known.weekdays,
      lastChecked: nowMs - DAY,
      nextFetch: 0,
      failures: 0,
    },
  ];
}

/** Build the schedule fixture from the TypeScript implementation. */
export function buildScheduleFixture(): ScheduleFixture {
  const known = Object.values(models());
  const spans: [number, number][] = [
    [MONDAY, MONDAY + 3.5 * DAY],
    [MONDAY + 0.3 * HOUR, MONDAY + 50 * DAY],
    [MONDAY + 12 * HOUR, MONDAY + 7 * DAY + 12 * HOUR],
    [MONDAY + 5 * DAY + 20 * HOUR, MONDAY + 7 * DAY + 10 * HOUR],
    [MONDAY, MONDAY - DAY],
  ];
  const nows = [
    MONDAY + 5 * HOUR,
    MONDAY + 12 * HOUR,
    MONDAY + 5 * DAY + 18 * HOUR,
    MONDAY + 6 * DAY + 12 * HOUR,
  ];
  const sections: Record<string, FixtureCase[]> = {
    bins: cases(
      "bins",
      [
        MONDAY,
        MONDAY - HOUR,
        MONDAY + 9 * HOUR + 59_000,
        1,
        Date.UTC(2026, 0, 31, 23, 59),
        Date.UTC(2024, 1, 29, 12),
        Date.UTC(2025, 11, 31, 23, 59, 59, 999),
      ].map((timeMs) => ({ timeMs })),
    ),
    expectedActivity: cases(
      "expectedActivity",
      known.flatMap((model) =>
        spans.map(([fromMs, toMs]) => ({ model, fromMs, toMs })),
      ),
    ),
    newProbability: cases(
      "newProbability",
      known.flatMap((model) => [
        ...spans.map(([lastCheckedMs, nowMs]) => ({
          model,
          lastCheckedMs,
          nowMs,
        })),
        { model, lastCheckedMs: 0, nowMs: MONDAY },
      ]),
    ),
    missProbability: cases("missProbability", [
      { model: { alpha: 2e6, beta: 1e6, capacity: 20 }, days: 3, capacity: 10 },
      { model: { alpha: 13, beta: 91, capacity: 4 }, days: 7, capacity: 2 },
      { model: { alpha: 1, beta: 1, capacity: 0 }, days: 0.5, capacity: 5 },
      { model: { alpha: 5, beta: 1, capacity: 1 }, days: 0, capacity: 1 },
    ]),
    chooseInterval: cases("chooseInterval", [
      ...known.flatMap((model) => nows.map((nowMs) => ({ model, nowMs }))),
      { model: { alpha: 1e6, beta: 1, capacity: 10 }, nowMs: MONDAY },
      { model: { alpha: 1, beta: 1e6, capacity: 500 }, nowMs: MONDAY },
    ]),
    planRefresh: cases(
      "planRefresh",
      [MONDAY + 30 * DAY + 10 * HOUR, MONDAY + 33 * DAY + 2 * HOUR].map(
        (nowMs) => ({ nowMs, feeds: planFeeds(nowMs) }),
      ),
    ),
    hintFromHeaders: cases(
      "hintFromHeaders",
      [
        {},
        { "Retry-After": "120" },
        { "Retry-After": "0x10" },
        { "Retry-After": "1.5" },
        { "Retry-After": "Mon, 05 Jan 2026 13:00:00 GMT" },
        { "Retry-After": "Mon, 05 Jan 2026 11:00:00 GMT" },
        { "Retry-After": "2026-01-05T13:00:00Z" },
        { "Cache-Control": "public, max-age=600" },
        { "Cache-Control": "max-age=31536000" },
        { "Retry-After": "60", "Cache-Control": "max-age=300" },
      ].map((headers) => ({ headers, nowMs: MONDAY + 12 * HOUR })),
    ),
    retryInterval: cases(
      "retryInterval",
      [0, 1, 2, 3, 5, 7, 8, 50].map((failures) => ({ failures })),
    ),
    observe: cases(
      "observe",
      known.flatMap((model) => [
        {
          model,
          newItems: 3,
          fromMs: MONDAY,
          toMs: MONDAY + 2 * DAY,
          windowSize: 10,
        },
        {
          model,
          newItems: 0,
          fromMs: MONDAY,
          toMs: MONDAY + 60 * DAY,
          windowSize: 0,
        },
      ]),
    ),
    observeWindow: cases("observeWindow", [
      { model: PRIOR, published: [DAY, 3 * DAY, 5 * DAY, 7 * DAY] },
      { model: PRIOR, published: [DAY, 3 * DAY, 0, 7 * DAY] },
      { model: PRIOR, published: [5, 5] },
      { model: { alpha: 3, beta: 4, capacity: 12 }, published: [] },
    ]),
    updatePattern: cases("updatePattern", [
      { published: [MONDAY + HOUR, 0, MONDAY + HOUR, MONDAY], elapsedMs: 0 },
      {
        pattern: updatePattern(undefined, [MONDAY + HOUR], 0),
        published: [MONDAY + 2 * HOUR],
        elapsedMs: 60 * DAY,
      },
    ]),
    learnPattern: cases("learnPattern", [
      { model: PRIOR, published: weeklyPosts(3, 9 * HOUR) },
      {
        model: { alpha: 5, beta: 7, capacity: 10, profile: legacyProfile() },
        published: [MONDAY + 30 * HOUR],
      },
    ]),
    patternOf: cases("patternOf", [
      { model: PRIOR },
      { model: { ...PRIOR, profile: legacyProfile() } },
      { model: { ...PRIOR, profile: [1, 2] } },
    ]),
    patternFactors: cases("patternFactors", [
      {},
      { pattern: updatePattern(undefined, weeklyPosts(8, 9 * HOUR), 0) },
    ]),
  };
  return { version: 1, sections };
}
