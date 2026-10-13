const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

/** Bins of the hour-of-day factor: one per UTC hour. */
const HOURS_PER_DAY = 24;

/** Bins of the day-of-week factor: one per UTC day, Monday first. */
const DAYS_PER_WEEK = 7;

/** Bins of the day-of-month factor: one per UTC date, the 1st first. */
export const DAYS_PER_MONTH = 31;

/**
 * Pseudo-posts added to every bin when reading a pattern, so an unseen feed is
 * flat and a sparse one stays close to flat.
 */
const PATTERN_SMOOTHING = 0.1;

/**
 * Pseudo-posts added to every bin of the day-of-month factor instead, so that
 * factor stays near flat until a few dozen posts have been seen.
 */
const DAY_OF_MONTH_SMOOTHING = 1;

/** Longest span stepped hour by hour; beyond it each day counts as one. */
const ACTIVITY_STEP_LIMIT_MS = 35 * DAY_MS;

/** Refreshes of at most this many feeds fetch them all before replying. */
export const SMALL_REFRESH = 5;

/** Feeds at least this likely to hold news are always waited for. */
const LIKELY_PROBABILITY = 0.5;

/** Chance of news given to a failing feed that is due again. */
const FAILING_PROBABILITY = 0.5;

/** Expected feeds with news a refresh may leave to the background, at least. */
const LEFT_OVER_FEEDS = 0.5;

/** Share of all expected feeds with news a refresh may leave to the background. */
const LEFT_OVER_SHARE = 0.02;

// The epoch fell on a Thursday, three days into a week that starts on Monday.
const EPOCH_DAY_OF_WEEK = 3;

/** Shortest wait between checks of one feed, in milliseconds. */
export const MIN_INTERVAL_MS = 15 * 60 * 1000;

/** Longest wait between checks of one feed, in milliseconds. */
export const MAX_INTERVAL_MS = 7 * DAY_MS;

/** Longest wait after a failure, in milliseconds, however many came before. */
const MAX_RETRY_MS = DAY_MS;

/** Chance, per wait, that more items appear than the feed can show. */
export const MISS_PROBABILITY = 1e-4;

/**
 * Share of a feed's window counted as usable, since real posts come in clumps
 * rather than evenly.
 */
export const BURST_MARGIN = 0.5;

/** Window size assumed before any feed has been seen. */
const UNKNOWN_CAPACITY = 10;

/** Days after which old observations count half as much. */
const HALF_LIFE_DAYS = 60;

// Largest window counted, however many items a feed shows.
const MAX_CAPACITY = 500;
// Lanczos approximation coefficients (g = 7, n = 9) for the log-gamma function.
const LANCZOS = [
  676.520_368_121_885_1, -1259.139_216_722_402_8, 771.323_428_777_653_1,
  -176.615_029_162_140_6, 12.507_343_278_686_905, -0.138_571_095_265_720_12,
  9.984_369_578_019_572e-6, 1.505_632_735_149_311_6e-7,
];

/**
 * What is known about how often one feed posts.
 *
 * Posts are modeled as a Poisson process whose rate at time `t`, per day, is
 * `rate * hourOfDay(t) * dayOfWeek(t) * dayOfMonth(t)`: `rate` has a Gamma
 * distribution with shape `alpha` and rate `beta`, and each factor is learned
 * from when posts appear and averages one over its period. Each check adds the
 * new items seen and the expected activity of the time they took to appear.
 */
export interface FeedModel {
  /** Posts behind the rate estimate, plus the prior's one. */
  alpha: number;
  /** Days of observation behind `alpha`, in expected activity. */
  beta: number;
  /** Items the feed showed at its last changed check; zero if unknown. */
  capacity: number;
  /** Faded post counts by hour of day, day of week and day of month; absent means flat. */
  pattern?: PostingPattern;
}

/** Faded counts of posts in each bin of the three periodic factors, all in UTC. */
export interface PostingPattern {
  /** `HOURS_PER_DAY` counts, hour 0 first. */
  hourOfDay: number[];
  /** `DAYS_PER_WEEK` counts, Monday first. */
  dayOfWeek: number[];
  /** `DAYS_PER_MONTH` counts, the 1st first. */
  dayOfMonth: number[];
}

/** Belief before a feed has been seen: about one post a day, weakly held. */
export const PRIOR: FeedModel = { alpha: 1, beta: 1, capacity: 0 };

function logGamma(value: number): number {
  let result: number;
  if (value < 0.5) {
    result =
      Math.log(Math.PI / Math.sin(Math.PI * value)) - logGamma(1 - value);
  } else {
    const shifted = value - 1;
    let sum = 0.999_999_999_999_809_93;
    for (const [index, coefficient] of LANCZOS.entries()) {
      sum += coefficient / (shifted + index + 1);
    }
    const lanczosBase = shifted + LANCZOS.length - 0.5;
    result =
      0.5 * Math.log(2 * Math.PI) +
      (shifted + 0.5) * Math.log(lanczosBase) -
      lanczosBase +
      Math.log(sum);
  }
  return result;
}

/**
 * Fold in a check that found `newItems` between `fromMs` and `toMs`, in a
 * window of `windowSize` (zero for a check that saw no new body, which keeps
 * the window known). Old evidence fades by the elapsed days, `beta` grows
 * by the expected activity in between, and the pattern fades too; its new
 * posts are added with `learnPattern`.
 */
export function observe(
  model: FeedModel,
  newItems: number,
  span: { fromMs: number; toMs: number },
  windowSize: number,
): FeedModel {
  const elapsedMs = Math.max(0, span.toMs - span.fromMs);
  const keep = 0.5 ** (elapsedMs / DAY_MS / HALF_LIFE_DAYS);
  const observed: FeedModel = {
    ...model,
    alpha: PRIOR.alpha + (model.alpha - PRIOR.alpha) * keep + newItems,
    beta:
      PRIOR.beta +
      (model.beta - PRIOR.beta) * keep +
      expectedActivity(model, span.fromMs, span.toMs),
    capacity: windowSize > 0 ? windowSize : model.capacity,
  };
  const { pattern } = model;
  if (pattern) {
    observed.pattern = updatePattern(pattern, [], elapsedMs);
  }
  return observed;
}

/**
 * Fold in the first look at a feed, using the dates of the items it shows.
 * `published` are millisecond timestamps; unknown dates (zero) are skipped.
 */
export function observeWindow(
  model: FeedModel,
  published: number[],
): FeedModel {
  const sorted = published
    .filter((time) => Number.isFinite(time) && time > 0)
    .sort((earlier, later) => earlier - later);
  const [first] = sorted;
  const last = sorted.at(-1);
  const span = first !== undefined && last !== undefined ? last - first : 0;
  const capacity = published.length > 0 ? published.length : model.capacity;
  return span > 0
    ? {
        ...model,
        alpha: model.alpha + sorted.length - 1,
        beta: model.beta + span / DAY_MS,
        capacity,
      }
    : { ...model, capacity };
}

/**
 * Chance that more new items appear within `days` than a window of
 * `capacity` can show, so that some are never seen.
 */
export function missProbability(
  model: FeedModel,
  days: number,
  capacity: number,
): number {
  let result = 0;
  if (days > 0) {
    result = missProbabilityOver(model, days, capacity);
  }
  return result;
}

function missProbabilityOver(
  model: FeedModel,
  days: number,
  capacity: number,
): number {
  const stay = model.beta / (model.beta + days);
  const logStay = Math.log(stay);
  const logGo = Math.log(1 - stay);
  const base = logGamma(model.alpha);
  let covered = 0;
  for (let count = 0; count <= capacity; count += 1) {
    covered += Math.exp(
      logGamma(count + model.alpha) -
        base -
        logGamma(count + 1) +
        model.alpha * logStay +
        count * logGo,
    );
  }
  return Math.max(0, 1 - covered);
}

/**
 * Longest wait, in milliseconds, from `nowMs` within the minimum and maximum
 * waits for which the chance of missing an item, over the expected activity of
 * that wait, stays under `MISS_PROBABILITY`.
 */
export function chooseInterval(model: FeedModel, nowMs: number): number {
  const known = model.capacity > 0 ? model.capacity : UNKNOWN_CAPACITY;
  const usable = Math.max(
    1,
    Math.floor(Math.min(known, MAX_CAPACITY) * BURST_MARGIN),
  );
  const factors = patternFactors(model.pattern);
  function isSafe(waitMs: number): boolean {
    const activity = activityBetween(factors, nowMs, nowMs + waitMs);
    return missProbability(model, activity, usable) <= MISS_PROBABILITY;
  }
  let result: number;
  if (!isSafe(MIN_INTERVAL_MS)) {
    result = MIN_INTERVAL_MS;
  } else if (isSafe(MAX_INTERVAL_MS)) {
    result = MAX_INTERVAL_MS;
  } else {
    let low = MIN_INTERVAL_MS;
    let high = MAX_INTERVAL_MS;
    for (let step = 0; step < 40; step += 1) {
      const middle = (low + high) / 2;
      if (isSafe(middle)) {
        low = middle;
      } else {
        high = middle;
      }
    }
    result = low;
  }
  return result;
}

/** UTC hour of day, 0 to 23, of a millisecond timestamp. */
export function hourOfDay(timeMs: number): number {
  const hour = Math.floor(timeMs / HOUR_MS);
  return ((hour % HOURS_PER_DAY) + HOURS_PER_DAY) % HOURS_PER_DAY;
}

/** UTC day of the week, Monday being zero, of a millisecond timestamp. */
export function dayOfWeek(timeMs: number): number {
  const day = Math.floor(timeMs / DAY_MS) + EPOCH_DAY_OF_WEEK;
  return ((day % DAYS_PER_WEEK) + DAYS_PER_WEEK) % DAYS_PER_WEEK;
}

/** UTC day of the month, the 1st being zero, of a millisecond timestamp. */
export function dayOfMonth(timeMs: number): number {
  return new Date(timeMs).getUTCDate() - 1;
}

function zeros(length: number): number[] {
  return new Array<number>(length).fill(0);
}

function counts(values: number[] | undefined, length: number): number[] {
  return values?.length === length ? [...values] : zeros(length);
}

/**
 * Fade a pattern over `elapsedMs` with the model's half-life and add one post
 * to each factor's bin for each `published` timestamp; unknown dates (zero)
 * are skipped. An absent pattern starts empty.
 */
export function updatePattern(
  pattern: PostingPattern | undefined,
  published: number[],
  elapsedMs: number,
): PostingPattern {
  const keep = 0.5 ** (Math.max(0, elapsedMs / DAY_MS) / HALF_LIFE_DAYS);
  function fade(values: number[] | undefined, length: number): number[] {
    return counts(values, length).map((count) => count * keep);
  }
  const updated: PostingPattern = {
    hourOfDay: fade(pattern?.hourOfDay, HOURS_PER_DAY),
    dayOfWeek: fade(pattern?.dayOfWeek, DAYS_PER_WEEK),
    dayOfMonth: fade(pattern?.dayOfMonth, DAYS_PER_MONTH),
  };
  for (const time of published) {
    if (Number.isFinite(time) && time > 0) {
      // A date with no time of day reads as midnight UTC and says nothing about the hour.
      if (time % DAY_MS !== 0) {
        updated.hourOfDay[hourOfDay(time)] += 1;
      }
      updated.dayOfWeek[dayOfWeek(time)] += 1;
      updated.dayOfMonth[dayOfMonth(time)] += 1;
    }
  }
  return updated;
}

/** The model with the posts at `published` added to its pattern, without fading. */
export function learnPattern(model: FeedModel, published: number[]): FeedModel {
  return { ...model, pattern: updatePattern(model.pattern, published, 0) };
}

/** Relative intensity of each bin of each factor, averaging one per factor. */
export interface PatternFactors {
  /** `HOURS_PER_DAY` factors, hour 0 first. */
  hourOfDay: number[];
  /** `DAYS_PER_WEEK` factors, Monday first. */
  dayOfWeek: number[];
  /** `DAYS_PER_MONTH` factors, the 1st first. */
  dayOfMonth: number[];
}

function normalized(
  values: number[] | undefined,
  length: number,
  smoothing: number,
): number[] {
  const smoothed = counts(values, length).map(
    (count) => Math.max(0, count) + smoothing,
  );
  const mean = smoothed.reduce((total, count) => total + count, 0) / length;
  return smoothed.map((count) => count / mean);
}

/** The factors a pattern gives, each bin smoothed and each factor averaging one. */
export function patternFactors(
  pattern: PostingPattern | undefined,
): PatternFactors {
  return {
    hourOfDay: normalized(pattern?.hourOfDay, HOURS_PER_DAY, PATTERN_SMOOTHING),
    dayOfWeek: normalized(pattern?.dayOfWeek, DAYS_PER_WEEK, PATTERN_SMOOTHING),
    dayOfMonth: normalized(
      pattern?.dayOfMonth,
      DAYS_PER_MONTH,
      DAY_OF_MONTH_SMOOTHING,
    ),
  };
}

function intensityAt(factors: PatternFactors, timeMs: number): number {
  return (
    (factors.hourOfDay[hourOfDay(timeMs)] ?? 1) *
    (factors.dayOfWeek[dayOfWeek(timeMs)] ?? 1) *
    (factors.dayOfMonth[dayOfMonth(timeMs)] ?? 1)
  );
}

function activityBetween(
  factors: PatternFactors,
  fromMs: number,
  toMs: number,
): number {
  const stepEnd = Math.min(toMs, fromMs + ACTIVITY_STEP_LIMIT_MS);
  let days = 0;
  let cursor = fromMs;
  while (cursor < stepEnd) {
    const hourEnd = (Math.floor(cursor / HOUR_MS) + 1) * HOUR_MS;
    const end = Math.min(hourEnd, stepEnd);
    days += ((end - cursor) / DAY_MS) * intensityAt(factors, cursor);
    cursor = end;
  }
  return days + Math.max(0, toMs - stepEnd) / DAY_MS;
}

/**
 * Expected activity from `fromMs` to `toMs`, in days: the integral of the
 * feed's relative posting intensity, stepped hour by hour along UTC hours for
 * up to `ACTIVITY_STEP_LIMIT_MS`, each later day counting as one. A flat model
 * gives elapsed days; a backward span gives zero.
 */
export function expectedActivity(
  model: FeedModel,
  fromMs: number,
  toMs: number,
): number {
  return activityBetween(patternFactors(model.pattern), fromMs, toMs);
}

/**
 * Chance that a feed has posted at least once since it was last checked:
 * `1 - (beta / (beta + activity))^alpha` over the expected activity in
 * between. A feed never checked (`lastCheckedMs` zero) gets one.
 */
export function newProbability(
  model: FeedModel,
  lastCheckedMs: number,
  nowMs: number,
): number {
  let probability: number;
  if (lastCheckedMs <= 0) {
    probability = 1;
  } else {
    const activity = expectedActivity(model, lastCheckedMs, nowMs);
    probability = 1 - (model.beta / (model.beta + activity)) ** model.alpha;
  }
  return probability;
}

/** What `planRefresh` needs to know about a feed. */
export interface PlannableFeed {
  model: FeedModel;
  /** Milliseconds since the epoch of the last successful check; zero if never. */
  lastChecked: number;
  /** Milliseconds since the epoch of the next scheduled check. */
  nextFetch: number;
  /** Failed checks in a row. */
  failures: number;
}

/** Feeds in the order a refresh fetches them. */
export interface RefreshPlan<Feed extends PlannableFeed> {
  /** Feeds, most likely to hold news first. */
  ordered: Feed[];
  /** Chance each feed in `ordered` holds news. */
  probabilities: number[];
  /** How many of the first `ordered` feeds the refresh waits for. */
  foreground: number;
}

/** Chance a feed holds news, as a refresh counts it. */
export function refreshProbability(feed: PlannableFeed, nowMs: number): number {
  let probability: number;
  if (feed.failures > 0) {
    probability = feed.nextFetch <= nowMs ? FAILING_PROBABILITY : 0;
  } else {
    probability = newProbability(feed.model, feed.lastChecked, nowMs);
  }
  return probability;
}

/**
 * Order feeds for a refresh, most likely to hold news first, and choose how
 * many to wait for.
 *
 * The refresh waits for the shortest prefix after which the feeds left hold,
 * in expectation, at most `LEFT_OVER_FEEDS` or `LEFT_OVER_SHARE` of all the
 * expected feeds with news, whichever is more; it always waits for every
 * feed at least `LIKELY_PROBABILITY` likely. A failing feed counts as
 * `FAILING_PROBABILITY` when due and zero otherwise. The rest are fetched
 * afterwards.
 */
export function planRefresh<Feed extends PlannableFeed>(
  feeds: Feed[],
  nowMs: number,
): RefreshPlan<Feed> {
  const ranked = feeds
    .map((feed) => ({ feed, probability: refreshProbability(feed, nowMs) }))
    .sort((left, right) => right.probability - left.probability);
  const probabilities = ranked.map(({ probability }) => probability);
  const total = probabilities.reduce(
    (sum, probability) => sum + probability,
    0,
  );
  const allowance = Math.max(LEFT_OVER_FEEDS, LEFT_OVER_SHARE * total);
  let foreground = probabilities.filter(
    (probability) => probability >= LIKELY_PROBABILITY,
  ).length;
  let leftOver = probabilities
    .slice(foreground)
    .reduce((sum, probability) => sum + probability, 0);
  while (leftOver > allowance && foreground < probabilities.length) {
    leftOver -= probabilities[foreground] ?? 0;
    foreground += 1;
  }
  return {
    ordered: ranked.map(({ feed }) => feed),
    probabilities,
    foreground,
  };
}

/** Wait after the given number of failures in a row, doubling each time. */
export function retryInterval(failures: number): number {
  return Math.min(MAX_RETRY_MS, MIN_INTERVAL_MS * 2 ** failures);
}

const CACHE_SECONDS_PATTERN = /max-age\s*=\s*(?<seconds>\d+)/iu;
const DELAY_SECONDS_PATTERN = /^\d+$/u;
const HTTP_DATE_PATTERN =
  /^(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}:\d{2} GMT$/u;

/**
 * Seconds a `Retry-After` value asks for: whole decimal seconds, or an
 * HTTP-date (`Sun, 06 Nov 1994 08:49:37 GMT`) from now; zero for anything else.
 */
function retryAfterSeconds(value: string, nowMs: number): number {
  let result = 0;
  if (DELAY_SECONDS_PATTERN.test(value)) {
    result = Number(value);
  } else if (HTTP_DATE_PATTERN.test(value)) {
    const date = Date.parse(value);
    result = Number.isNaN(date) ? 0 : (date - nowMs) / 1000;
  }
  return result;
}

/**
 * Milliseconds a site asked us to wait, from `Retry-After` or `Cache-Control`
 * `max-age`, the larger of the two, rounded up to a whole millisecond and
 * between zero and `MAX_INTERVAL_MS`.
 */
export function hintFromHeaders(headers: Headers, nowMs = Date.now()): number {
  const retry = retryAfterSeconds(
    (headers.get("Retry-After") ?? "").trim(),
    nowMs,
  );
  const cache = CACHE_SECONDS_PATTERN.exec(headers.get("Cache-Control") ?? "")
    ?.groups?.seconds;
  const seconds = Math.max(0, retry, cache ? Number(cache) : 0);
  return Math.ceil(Math.min(MAX_INTERVAL_MS, seconds * 1000));
}
