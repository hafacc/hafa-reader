import { expect, test } from "bun:test";
import {
  judge,
  RETIRED_KEEP_MS,
  retrack,
  type ScheduledFeed,
  type Schedules,
  statusProblem,
  takeFirstChecks,
} from "./check.ts";
import { sha256Hex } from "./id.ts";
import type { FetchOutcome } from "./protocol.ts";
import { MIN_INTERVAL_MS, PRIOR, retryInterval } from "./schedule.ts";

const NOW = Date.UTC(2026, 0, 5, 12);
const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

function rss(guids: string[], built = "Mon, 05 Jan 2026 12:00:00 GMT"): string {
  const items = guids
    .map(
      (guid) =>
        `<item><title>${guid}</title><guid>${guid}</guid><link>/${guid}</link></item>`,
    )
    .join("");
  return `<rss><channel><title>Feed</title><link>https://site.example/</link><lastBuildDate>${built}</lastBuildDate>${items}</channel></rss>`;
}

function answer(text: string, extra: Partial<FetchOutcome> = {}): FetchOutcome {
  return {
    status: 200,
    url: "https://feed.example/rss",
    text,
    etag: "",
    lastModified: "",
    hint: 0,
    insecure: false,
    ...extra,
  };
}

function scheduled(extra: Partial<ScheduledFeed> = {}): ScheduledFeed {
  return {
    id: "feed",
    url: "https://feed.example/rss",
    etag: "",
    lastModified: "",
    model: PRIOR,
    nextFetch: 0,
    lastChecked: 0,
    hash: "",
    guids: [],
    failures: 0,
    error: "",
    ...extra,
  };
}

test("a first look hands the whole feed on", async () => {
  const result = await judge(scheduled(), answer(rss(["a", "b"])), NOW);
  expect(result.pulled?.feed.entries.map((entry) => entry.guid)).toEqual([
    "a",
    "b",
  ]);
  expect(result.feed.guids).toEqual(["a", "b"]);
});

test("a changed body with nothing new is not handed on", async () => {
  const first = await judge(scheduled(), answer(rss(["a", "b"])), NOW);
  const rebuilt = rss(["a", "b"], "Mon, 05 Jan 2026 13:00:00 GMT");
  const second = await judge(first.feed, answer(rebuilt), NOW + HOUR);
  expect(second.pulled).toBeUndefined();
  expect(second.feed.hash).toBe(await sha256Hex(rebuilt));
  const third = await judge(
    second.feed,
    answer(rss(["c", "a"])),
    NOW + 2 * HOUR,
  );
  expect(third.pulled?.feed.entries).toHaveLength(2);
});

test("an unchanged body still keeps the answer's new validators", async () => {
  const text = rss(["a"]);
  const first = await judge(scheduled(), answer(text, { etag: '"1"' }), NOW);
  const second = await judge(
    first.feed,
    answer(text, {
      etag: '"2"',
      lastModified: "Mon, 05 Jan 2026 13:00:00 GMT",
    }),
    NOW + HOUR,
  );
  expect(second.pulled).toBeUndefined();
  expect(second.feed.etag).toBe('"2"');
  expect(second.feed.lastModified).toBe("Mon, 05 Jan 2026 13:00:00 GMT");
});

test("links resolve against the address after redirects", async () => {
  const result = await judge(
    scheduled({ url: "https://old.example/feed" }),
    answer(rss(["a"]), { url: "https://new.example/rss" }),
    NOW,
  );
  expect(result.pulled?.feed.entries[0]?.url).toBe("https://new.example/a");
});

test("a failure waits at least as long as the site asks", async () => {
  const limited = await judge(
    scheduled(),
    answer("", { status: 429, hint: 3 * HOUR }),
    NOW,
  );
  expect(limited.feed.nextFetch).toBe(NOW + 3 * HOUR);
  expect(limited.feed.error).toBe("The site asked Reader to slow down.");
  const plain = await judge(scheduled(), answer("", { status: 500 }), NOW);
  expect(plain.feed.nextFetch).toBe(NOW + retryInterval(0));
});

function previous(...tracked: ScheduledFeed[]): Schedules {
  return { tracked, retired: [] };
}

test("tracking resets an emptied feed without forgetting what it learned", () => {
  const known = scheduled({
    hash: "h",
    guids: ["a"],
    lastChecked: NOW,
    nextFetch: NOW + HOUR,
    model: { alpha: 5, beta: 3, capacity: 1 },
  });
  const [reset] = retrack(
    [{ id: "feed", url: known.url, empty: true }],
    previous(known),
    new Set(),
    NOW,
  ).tracked;
  expect(reset).toEqual({ ...known, hash: "", nextFetch: 0 });
  const [kept] = retrack(
    [{ id: "feed", url: known.url, empty: false }],
    previous(known),
    new Set(),
    NOW,
  ).tracked;
  expect(kept).toEqual(known);
});

test("tracking leaves alone an empty feed with nothing to resend", () => {
  const known = scheduled({
    hash: "h",
    lastChecked: NOW,
    nextFetch: NOW + HOUR,
  });
  const feeds = [{ id: "feed", url: known.url, empty: true }];
  expect(retrack(feeds, previous(known), new Set(), NOW).tracked).toEqual([
    known,
  ]);
  const withItems = { ...known, guids: ["a"] };
  expect(
    retrack(feeds, previous(withItems), new Set(["feed"]), NOW).tracked,
  ).toEqual([withItems]);
});

test("tracking a new address resets the feed but keeps what it learned", () => {
  const known = scheduled({
    hash: "h",
    etag: '"1"',
    guids: ["a"],
    lastChecked: NOW,
    nextFetch: NOW + HOUR,
  });
  const [moved] = retrack(
    [{ id: "feed", url: "https://moved.example/rss", empty: false }],
    previous(known),
    new Set(),
    NOW,
  ).tracked;
  expect(moved).toEqual({
    ...known,
    url: "https://moved.example/rss",
    etag: "",
    hash: "",
    nextFetch: 0,
  });
});

test("an answer already taken in counts as the first check of a feed starting over", async () => {
  const first = answer(rss(["a", "b"]), { etag: '"1"' });
  const fresh = scheduled({ id: "new" });
  const known = scheduled({
    id: "known",
    hash: "h",
    lastChecked: NOW - HOUR,
    nextFetch: NOW + HOUR,
  });
  const [taken, kept, untouched] = await takeFirstChecks(
    [fresh, known, scheduled({ id: "other" })],
    [
      { id: "new", url: fresh.url, empty: false, first },
      { id: "known", url: known.url, empty: false, first },
      { id: "other", url: fresh.url, empty: false },
    ],
    NOW,
  );
  expect(taken).toEqual((await judge(fresh, first, NOW)).feed);
  expect(taken?.lastChecked).toBe(NOW);
  expect(taken?.nextFetch).toBeGreaterThan(NOW);
  expect(taken?.etag).toBe('"1"');
  expect(kept).toEqual(known);
  expect(untouched).toEqual(scheduled({ id: "other" }));
});

test("a malformed answer is skipped and leaves its feed due, without spoiling the rest", async () => {
  const first = answer(rss(["a"]));
  const { text: _text, ...withoutText } = first;
  const malformed: unknown[] = [
    null,
    "first",
    42,
    withoutText,
    { ...first, status: "200" },
    { ...first, insecure: 0 },
  ];
  const feeds = malformed.map((value, index) => ({
    id: `bad-${index}`,
    url: "https://feed.example/rss",
    empty: false,
    // what a web app with a bug, or a newer one, might send
    first: JSON.parse(JSON.stringify(value)),
  }));
  const tracked = [
    ...feeds.map((feed) => scheduled({ id: feed.id })),
    scheduled({ id: "good" }),
  ];
  const result = await takeFirstChecks(
    tracked,
    [...feeds, { id: "good", url: tracked[0]?.url ?? "", empty: false, first }],
    NOW,
  );
  expect(result.slice(0, -1)).toEqual(tracked.slice(0, -1));
  expect(result.at(-1)?.lastChecked).toBe(NOW);
});

test("an answer for a feed not tracked is ignored", async () => {
  const tracked = [scheduled()];
  expect(
    await takeFirstChecks(
      tracked,
      [
        {
          id: "elsewhere",
          url: "https://feed.example/rss",
          empty: false,
          first: answer(rss(["a"])),
        },
      ],
      NOW,
    ),
  ).toEqual(tracked);
});

test("tracking starts a new feed due now and retires untracked ones", () => {
  const old = scheduled({ lastChecked: NOW, guids: ["a"] });
  const next = retrack(
    [{ id: "new", url: "https://n.example/rss", empty: true }],
    previous(old),
    new Set(),
    NOW,
  );
  expect(next.tracked).toEqual([
    scheduled({ id: "new", url: "https://n.example/rss" }),
  ]);
  expect(next.retired).toEqual([{ ...old, retiredAt: NOW }]);
});

test("a retired feed comes back with its schedule and is dropped after a while", () => {
  const old = scheduled({ lastChecked: NOW, guids: ["a"], hash: "h" });
  const emptied = retrack([], previous(old), new Set(), NOW);
  const back = retrack(
    [{ id: "feed", url: old.url, empty: false }],
    emptied,
    new Set(),
    NOW + HOUR,
  );
  expect(back).toEqual({ tracked: [old], retired: [] });
  const later = retrack([], emptied, new Set(), NOW + RETIRED_KEEP_MS);
  expect(later.retired).toEqual([]);
});

test("a 304 is a quiet check and an unreadable body a failure", async () => {
  const known = scheduled({ lastChecked: NOW - HOUR, hash: "h", guids: ["a"] });
  const quiet = await judge(known, answer("", { status: 304 }), NOW);
  expect(quiet.pulled).toBeUndefined();
  expect(quiet.feed.lastChecked).toBe(NOW);
  expect(quiet.feed.failures).toBe(0);
  const broken = await judge(known, answer("<html>nope</html>"), NOW);
  expect(broken.pulled).toBeUndefined();
  expect(broken.feed.failures).toBe(1);
  expect(broken.feed.error).toBe(
    "The site answered, but not with a readable feed.",
  );
  expect(broken.feed.lastChecked).toBe(NOW - HOUR);
});

test("a feed refused for not being on https says so", async () => {
  const refused = await judge(
    scheduled(),
    answer("", { status: 0, insecure: true }),
    NOW,
  );
  expect(refused.feed.error).toBe("The feed is not on https.");
  expect(refused.feed.failures).toBe(1);
});

test("new details alone are handed on", async () => {
  const first = await judge(scheduled(), answer(rss(["a"])), NOW);
  const renamed = rss(["a"]).replace(
    "<title>Feed</title>",
    "<title>New</title>",
  );
  const second = await judge(first.feed, answer(renamed), NOW + HOUR);
  expect(second.pulled?.feed.title).toBe("New");
});

test("a window of only new items is checked again soon", async () => {
  const first = await judge(scheduled(), answer(rss(["a", "b"])), NOW);
  const outran = await judge(first.feed, answer(rss(["c", "d"])), NOW + DAY);
  expect(outran.feed.nextFetch).toBe(NOW + DAY + MIN_INTERVAL_MS);
  const partly = await judge(first.feed, answer(rss(["c", "a"])), NOW + DAY);
  expect(partly.feed.nextFetch).toBeGreaterThan(NOW + DAY + MIN_INTERVAL_MS);
});

test("a status says in plain words what the site did", () => {
  expect(statusProblem(403)).toBe("The site refused to let Reader read it.");
  expect(statusProblem(401)).toBe("The site refused to let Reader read it.");
  expect(statusProblem(404)).toBe("Nothing was found at that address.");
  expect(statusProblem(503)).toBe("The site is having trouble.");
  expect(statusProblem(418)).toBe("The site answered 418.");
});
