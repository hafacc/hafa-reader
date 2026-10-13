import { expect, test } from "bun:test";
import {
  BURST_MARGIN,
  chooseInterval,
  DAYS_PER_MONTH,
  dayOfMonth,
  dayOfWeek,
  expectedActivity,
  type FeedModel,
  HOURS_PER_WEEK,
  hintFromHeaders,
  hourOfDay,
  learnPattern,
  MAX_INTERVAL_MS,
  MIN_INTERVAL_MS,
  MISS_PROBABILITY,
  missProbability,
  newProbability,
  observe,
  observeWindow,
  type PlannableFeed,
  PRIOR,
  patternFactors,
  patternOf,
  planRefresh,
  retryInterval,
  updatePattern,
} from "./schedule.ts";

const DAY = 24 * 60 * 60 * 1000;
// Monday 5 January 2026, 00:00 UTC.
const MONDAY = Date.UTC(2026, 0, 5);
const HOUR = 60 * 60 * 1000;

function poissonTail(mean: number, capacity: number): number {
  let term = Math.exp(-mean);
  let covered = term;
  for (let count = 1; count <= capacity; count += 1) {
    term *= mean / count;
    covered += term;
  }
  return 1 - covered;
}

test("a firmly known rate matches the exact Poisson tail", () => {
  const model = { alpha: 2e6, beta: 1e6, capacity: 20 };
  for (const days of [1, 3, 5]) {
    const exact = poissonTail(2 * days, 10);
    expect(missProbability(model, days, 10)).toBeCloseTo(exact, 4);
  }
});

test("the chosen wait keeps the chance of a miss under the limit", () => {
  const model = { alpha: 2e6, beta: 1e6, capacity: 20 };
  const interval = chooseInterval(model, MONDAY);
  const usable = Math.floor(20 * BURST_MARGIN);
  expect(missProbability(model, interval / DAY, usable)).toBeLessThanOrEqual(
    MISS_PROBABILITY * 1.01,
  );
  expect(
    missProbability(model, (interval * 1.1) / DAY, usable),
  ).toBeGreaterThan(MISS_PROBABILITY);
});

test("busier feeds are checked sooner, larger windows later", () => {
  const quiet = { alpha: 1e4, beta: 1e4, capacity: 20 };
  const busy = { alpha: 1e4, beta: 1e3, capacity: 20 };
  expect(chooseInterval(busy, MONDAY)).toBeLessThan(
    chooseInterval(quiet, MONDAY),
  );
  expect(chooseInterval({ ...quiet, capacity: 100 }, MONDAY)).toBeGreaterThan(
    chooseInterval(quiet, MONDAY),
  );
});

test("waits stay within the bounds", () => {
  expect(chooseInterval({ alpha: 1e6, beta: 1, capacity: 10 }, MONDAY)).toBe(
    MIN_INTERVAL_MS,
  );
  expect(chooseInterval({ alpha: 1, beta: 1e6, capacity: 500 }, MONDAY)).toBe(
    MAX_INTERVAL_MS,
  );
});

test("the first look uses the dates on the items", () => {
  const model = observeWindow(PRIOR, [DAY, 3 * DAY, 5 * DAY, 7 * DAY]);
  expect(model.alpha).toBe(PRIOR.alpha + 3);
  expect(model.beta).toBe(PRIOR.beta + 6);
  expect(model.capacity).toBe(4);
  expect(observeWindow(PRIOR, [5, 5]).alpha).toBe(PRIOR.alpha);
});

test("the first look skips undated items but counts them in the window", () => {
  const dated = [DAY, 3 * DAY, 5 * DAY, 7 * DAY];
  const withUndated = observeWindow(PRIOR, [...dated, 0]);
  const datedOnly = observeWindow(PRIOR, dated);
  expect(withUndated.alpha).toBe(datedOnly.alpha);
  expect(withUndated.beta).toBe(datedOnly.beta);
  expect(withUndated.capacity).toBe(5);
});

test("checks add what they saw and fade old evidence", () => {
  const first = observe(
    PRIOR,
    3,
    { fromMs: MONDAY, toMs: MONDAY + 2 * DAY },
    10,
  );
  expect(first.alpha).toBe(PRIOR.alpha + 3);
  expect(first.beta).toBeCloseTo(PRIOR.beta + 2, 9);
  const later = observe(
    { alpha: 11, beta: 11, capacity: 10 },
    0,
    { fromMs: MONDAY, toMs: MONDAY + 60 * DAY },
    10,
  );
  expect(later.alpha).toBeCloseTo(1 + 10 / 2, 6);
  expect(later.beta).toBeCloseTo(1 + 10 / 2 + 60, 6);
});

test("failures back off and reads waits from headers", () => {
  expect(retryInterval(0)).toBe(MIN_INTERVAL_MS);
  expect(retryInterval(2)).toBe(MIN_INTERVAL_MS * 4);
  expect(retryInterval(50)).toBe(MAX_INTERVAL_MS / 7);
  expect(
    hintFromHeaders(new Headers({ "Cache-Control": "public, max-age=600" })),
  ).toBe(600_000);
  expect(hintFromHeaders(new Headers({ "Retry-After": "120" }))).toBe(120_000);
  expect(hintFromHeaders(new Headers())).toBe(0);
});

test("a Retry-After date is read as the wait until then", () => {
  const now = Date.UTC(2026, 0, 5, 12);
  const later = new Date(now + 3_600_000).toUTCString();
  expect(hintFromHeaders(new Headers({ "Retry-After": later }), now)).toBe(
    3_600_000,
  );
  const past = new Date(now - 3_600_000).toUTCString();
  expect(hintFromHeaders(new Headers({ "Retry-After": past }), now)).toBe(0);
  expect(hintFromHeaders(new Headers({ "Retry-After": "soon" }), now)).toBe(0);
});

test("a very long requested wait is capped at the longest wait", () => {
  expect(
    hintFromHeaders(new Headers({ "Cache-Control": "max-age=31536000" })),
  ).toBe(MAX_INTERVAL_MS);
});

function mondayFeed(): FeedModel {
  const posts = Array.from(
    { length: 104 },
    (_, week) => MONDAY - week * 7 * DAY + 9 * HOUR,
  );
  return learnPattern({ alpha: 13, beta: 91, capacity: 4 }, posts);
}

function average(values: number[]): number {
  return values.reduce((total, value) => total + value, 0) / values.length;
}

test("bins are UTC hours, days of the week from Monday and dates from the 1st", () => {
  expect(hourOfDay(MONDAY + 9 * HOUR + 59_000)).toBe(9);
  expect(hourOfDay(MONDAY - HOUR)).toBe(23);
  expect(dayOfWeek(MONDAY)).toBe(0);
  expect(dayOfWeek(MONDAY - HOUR)).toBe(6);
  expect(dayOfWeek(0)).toBe(3);
  expect(dayOfMonth(Date.UTC(2026, 0, 1, 23))).toBe(0);
  expect(dayOfMonth(Date.UTC(2026, 0, 31))).toBe(DAYS_PER_MONTH - 1);
});

test("the factors learn daily, weekly and monthly habits and each average one", () => {
  const daily = Array.from(
    { length: 60 },
    (_, day) => MONDAY + day * DAY + 14 * HOUR,
  );
  const weekly = Array.from(
    { length: 20 },
    (_, week) => MONDAY + week * 7 * DAY + 3 * DAY,
  );
  const monthly = Array.from({ length: 12 }, (_, month) =>
    Date.UTC(2025, month, 1, 6),
  );
  const dailyFactors = patternFactors(learnPattern(PRIOR, daily).pattern);
  const weeklyFactors = patternFactors(learnPattern(PRIOR, weekly).pattern);
  const monthlyFactors = patternFactors(learnPattern(PRIOR, monthly).pattern);
  expect(dailyFactors.hourOfDay[14]).toBeGreaterThan(20);
  expect(dailyFactors.hourOfDay[3]).toBeLessThan(0.05);
  expect(Math.max(...dailyFactors.dayOfWeek)).toBeLessThan(1.2);
  expect(weeklyFactors.dayOfWeek[3]).toBeGreaterThan(6);
  expect(weeklyFactors.dayOfWeek[0]).toBeLessThan(0.05);
  expect(monthlyFactors.dayOfMonth[0]).toBeGreaterThan(8);
  for (const factors of [dailyFactors, weeklyFactors, monthlyFactors]) {
    expect(average(factors.hourOfDay)).toBeCloseTo(1, 9);
    expect(average(factors.dayOfWeek)).toBeCloseTo(1, 9);
    expect(average(factors.dayOfMonth)).toBeCloseTo(1, 9);
  }
});

test("the day-of-month factor stays near flat with few posts", () => {
  const weekly = Array.from(
    { length: 8 },
    (_, week) => MONDAY - week * 7 * DAY + 9 * HOUR,
  );
  const factors = patternFactors(learnPattern(PRIOR, weekly).pattern);
  expect(Math.min(...factors.dayOfMonth)).toBeGreaterThan(0.75);
  expect(Math.max(...factors.dayOfMonth)).toBeLessThan(1.6);
});

test("the pattern fades and skips undated posts", () => {
  const pattern = updatePattern(
    undefined,
    [MONDAY + HOUR, 0, MONDAY + HOUR],
    0,
  );
  expect(pattern.hourOfDay[1]).toBe(2);
  expect(pattern.dayOfWeek[0]).toBe(2);
  expect(pattern.dayOfMonth[4]).toBe(2);
  const faded = updatePattern(pattern, [MONDAY + 2 * HOUR], 60 * DAY);
  expect(faded.hourOfDay[1]).toBeCloseTo(1, 9);
  expect(faded.hourOfDay[2]).toBe(1);
});

test("a weekly profile from an older version becomes hours and days, with flat dates", () => {
  const profile = new Array<number>(HOURS_PER_WEEK).fill(0);
  profile[9] = 3;
  profile[2 * 24 + 14] = 1;
  const pattern = patternOf({ ...PRIOR, profile });
  expect(pattern?.hourOfDay[9]).toBe(3);
  expect(pattern?.hourOfDay[14]).toBe(1);
  expect(pattern?.dayOfWeek).toEqual([3, 0, 1, 0, 0, 0, 0]);
  expect(pattern?.dayOfMonth).toEqual(
    new Array<number>(DAYS_PER_MONTH).fill(0),
  );
  expect(patternOf(PRIOR)).toBeUndefined();
  expect(patternOf({ ...PRIOR, profile: [1, 2] })).toBeUndefined();
  const learned = learnPattern({ ...PRIOR, profile }, []);
  expect(learned.profile).toBeUndefined();
  expect(learned.pattern).toEqual(pattern);
});

test("a flat model's activity is elapsed days, also past the stepped span", () => {
  expect(expectedActivity(PRIOR, MONDAY, MONDAY + 3.5 * DAY)).toBeCloseTo(
    3.5,
    9,
  );
  expect(
    expectedActivity(PRIOR, MONDAY + 0.3 * HOUR, MONDAY + 50 * DAY),
  ).toBeCloseTo(50 - 0.3 / 24, 9);
  expect(expectedActivity(PRIOR, MONDAY, MONDAY - DAY)).toBe(0);
});

test("a flat model chooses the same wait as the plain rate", () => {
  const model = { alpha: 40, beta: 20, capacity: 20 };
  const flat = learnPattern(model, []);
  const interval = chooseInterval(model, MONDAY + 5 * HOUR);
  expect(chooseInterval(flat, MONDAY + 5 * HOUR)).toBeCloseTo(interval, -3);
  const usable = Math.floor(20 * BURST_MARGIN);
  expect(missProbability(model, interval / DAY, usable)).toBeLessThanOrEqual(
    MISS_PROBABILITY * 1.01,
  );
  expect(chooseInterval(model, MONDAY + 13 * DAY)).toBeCloseTo(interval, -3);
});

test("a Monday feed sleeps after Monday and is checked soon before the next", () => {
  const model = mondayFeed();
  const afterMonday = chooseInterval(model, MONDAY + 12 * HOUR);
  const beforeMonday = chooseInterval(model, MONDAY + 6 * DAY + 12 * HOUR);
  const flatAfter = chooseInterval(
    { ...model, pattern: undefined },
    MONDAY + 12 * HOUR,
  );
  expect(afterMonday).toBeGreaterThan(5 * DAY);
  expect(beforeMonday).toBeLessThan(DAY);
  expect(afterMonday).toBeGreaterThan(flatAfter);
});

test("a Monday feed is unlikely on Tuesday and likely the next Monday", () => {
  const model = mondayFeed();
  const lastChecked = MONDAY + 12 * HOUR;
  const tuesday = newProbability(model, lastChecked, lastChecked + DAY);
  const nextMonday = newProbability(
    model,
    lastChecked,
    MONDAY + 7 * DAY + 12 * HOUR,
  );
  const flatTuesday = newProbability(
    { ...model, pattern: undefined },
    lastChecked,
    lastChecked + DAY,
  );
  expect(tuesday).toBeLessThan(0.1);
  expect(tuesday).toBeLessThan(flatTuesday / 2);
  expect(nextMonday).toBeGreaterThan(0.5);
});

test("the chance of news grows with time and starts at one for new feeds", () => {
  const model = { alpha: 3, beta: 6, capacity: 10 };
  let previous = 0;
  for (const days of [0.1, 0.5, 1, 3, 10]) {
    const probability = newProbability(model, MONDAY, MONDAY + days * DAY);
    expect(probability).toBeGreaterThan(previous);
    expect(probability).toBeLessThan(1);
    previous = probability;
  }
  expect(newProbability(model, MONDAY, MONDAY)).toBe(0);
  expect(newProbability(model, 0, MONDAY)).toBe(1);
});

interface TestFeed extends PlannableFeed {
  id: string;
}

function feed(
  id: string,
  model: FeedModel,
  lastChecked: number,
  failures = 0,
  nextFetch = 0,
): TestFeed {
  return { id, model, lastChecked, failures, nextFetch };
}

test("a refresh waits for likely feeds and leaves unlikely ones", () => {
  const now = MONDAY + 30 * DAY;
  const daily = { alpha: 30, beta: 30, capacity: 10 };
  const rare = { alpha: 1, beta: 200, capacity: 10 };
  const plan = planRefresh(
    [
      ...Array.from({ length: 20 }, (_, index) =>
        feed(`rare${index}`, rare, now - HOUR),
      ),
      feed("daily", daily, now - 2 * DAY),
      feed("new", daily, 0),
      feed("failing due", daily, now - DAY, 2, now - 1),
      feed("failing later", daily, now - DAY, 2, now + HOUR),
      feed("fresh daily", daily, now - HOUR),
    ],
    now,
  );
  expect(plan.ordered.map(({ id }) => id).slice(0, 3)).toEqual([
    "new",
    "daily",
    "failing due",
  ]);
  expect(plan.ordered.at(-1)?.id).toBe("failing later");
  for (const [index, probability] of plan.probabilities.entries()) {
    expect(probability).toBeLessThanOrEqual(plan.probabilities[index - 1] ?? 1);
  }
  const rest = plan.probabilities
    .slice(plan.foreground)
    .reduce((sum, probability) => sum + probability, 0);
  const total = plan.probabilities.reduce(
    (sum, probability) => sum + probability,
    0,
  );
  expect(rest).toBeLessThanOrEqual(Math.max(0.5, 0.02 * total));
  expect(plan.foreground).toBeGreaterThanOrEqual(3);
  expect(plan.foreground).toBeLessThan(plan.ordered.length);
  const without = plan.probabilities
    .slice(plan.foreground - 1)
    .reduce((sum, probability) => sum + probability, 0);
  expect(plan.foreground === 3 || without > Math.max(0.5, 0.02 * total)).toBe(
    true,
  );
});

test("many middling feeds are all waited for", () => {
  const now = MONDAY;
  const model = { alpha: 1, beta: 5, capacity: 10 };
  const feeds = Array.from({ length: 40 }, (_, index) => ({
    index,
    model,
    lastChecked: now - DAY,
    failures: 0,
    nextFetch: 0,
  }));
  const plan = planRefresh(feeds, now);
  expect(plan.foreground).toBeGreaterThan(30);
});

test("every check fades the pattern and moves an old profile into it", () => {
  const model = learnPattern(PRIOR, [MONDAY + HOUR]);
  const faded = observe(
    model,
    0,
    { fromMs: MONDAY, toMs: MONDAY + 60 * DAY },
    10,
  );
  expect(faded.pattern?.hourOfDay[1]).toBeCloseTo(0.5, 9);
  expect(faded.pattern?.dayOfWeek[0]).toBeCloseTo(0.5, 9);
  expect(
    observe(PRIOR, 0, { fromMs: MONDAY, toMs: MONDAY + DAY }, 10).pattern,
  ).toBeUndefined();
  const profile = new Array<number>(HOURS_PER_WEEK).fill(0);
  profile[9] = 2;
  const migrated = observe({ ...PRIOR, profile }, 0, { fromMs: 0, toMs: 0 }, 1);
  expect(migrated.profile).toBeUndefined();
  expect(migrated.pattern?.hourOfDay[9]).toBe(2);
});

test("a refresh ranks a Monday feed by its pattern", () => {
  const monday = mondayFeed();
  const flat = { alpha: 13, beta: 91, capacity: 4 };
  const lastChecked = MONDAY + 7 * DAY - 2 * DAY;
  function ids(nowMs: number): string[] {
    return planRefresh(
      [feed("monday", monday, lastChecked), feed("flat", flat, lastChecked)],
      nowMs,
    ).ordered.map(({ id }) => id);
  }
  expect(ids(MONDAY + 7 * DAY - HOUR)).toEqual(["flat", "monday"]);
  expect(ids(MONDAY + 7 * DAY + 11 * HOUR)).toEqual(["monday", "flat"]);
});

test("a date without a time of day teaches the day but not the hour", () => {
  const pattern = updatePattern(undefined, [MONDAY, MONDAY + 7 * DAY], 0);
  expect(pattern.hourOfDay.every((count) => count === 0)).toBe(true);
  expect(pattern.dayOfWeek[0]).toBe(2);
  expect(pattern.dayOfMonth[4]).toBe(1);
});

test("the window is the one last seen, and quiet checks keep it", () => {
  const archive = observeWindow(PRIOR, [DAY, 2 * DAY]);
  expect(archive.capacity).toBe(2);
  const big = { ...PRIOR, capacity: 200 };
  const shrunk = observe(big, 3, { fromMs: MONDAY, toMs: MONDAY + DAY }, 10);
  expect(shrunk.capacity).toBe(10);
  const quiet = observe(shrunk, 0, { fromMs: MONDAY, toMs: MONDAY + DAY }, 0);
  expect(quiet.capacity).toBe(10);
  expect(observeWindow(shrunk, []).capacity).toBe(10);
});

test("no time means no chance of a miss", () => {
  expect(missProbability({ alpha: 5, beta: 1, capacity: 1 }, 0, 1)).toBe(0);
  expect(missProbability({ alpha: 5, beta: 1, capacity: 1 }, -1, 1)).toBe(0);
});

test("Retry-After is whole seconds or an HTTP date, and nothing else", () => {
  const now = Date.UTC(2026, 0, 5, 12);
  function hint(value: string): number {
    return hintFromHeaders(new Headers({ "Retry-After": value }), now);
  }
  expect(hint("120")).toBe(120_000);
  expect(hint("0x10")).toBe(0);
  expect(hint("1.5")).toBe(0);
  expect(hint("Mon, 05 Jan 2026 13:00:00 GMT")).toBe(3_600_000);
  expect(hint("2026-01-05T13:00:00Z")).toBe(0);
});
