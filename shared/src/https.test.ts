import { expect, test } from "bun:test";
import type { ParsedFeed } from "./feed.ts";
import {
  isPlainHttp,
  type KnownFeed,
  mapConcurrently,
  type ProbeOutcome,
  plainAddress,
  probeAllowed,
  probeHttps,
  sameFeed,
  settledAnswer,
  webAddress,
} from "./https.ts";

/** `fixtures/https.json`: which addresses are plain http, their http and written-out forms, which may be tried over https, which answers settle a probe, and which feeds count as the same. */
interface HttpsFixture {
  plainHttp: { input: string; output: boolean }[];
  plainAddress: { input: string; output: string }[];
  webAddress: { name: string; input: string; output: string }[];
  probeAllowed: { input: string; output: boolean }[];
  settledAnswer: {
    name: string;
    status: number;
    insecure: boolean;
    settled: boolean;
  }[];
  sameFeed: {
    name: string;
    known: KnownFeed;
    found: { title: string; siteUrl: string; guids: string[]; links: string[] };
    same: boolean;
  }[];
}

const fixture: HttpsFixture = await Bun.file(
  new URL("../fixtures/https.json", import.meta.url),
).json();

function parsedOf(
  found: HttpsFixture["sameFeed"][number]["found"],
): ParsedFeed {
  return {
    title: found.title,
    siteUrl: found.siteUrl,
    iconUrl: "",
    entries: found.guids.map((guid, index) => ({
      guid,
      title: "",
      url: found.links[index] ?? "",
      published: 0,
      author: "",
      imageUrl: "",
      summary: "",
      categories: [],
      html: "",
    })),
  };
}

test.each(fixture.plainHttp.map((item) => [item.input, item.output] as const))(
  "isPlainHttp(%p) is %p",
  (input, output) => {
    expect(isPlainHttp(input)).toBe(output);
  },
);

test.each(
  fixture.plainAddress.map((item) => [item.input, item.output] as const),
)("plainAddress(%p) is %p", (input, output) => {
  expect(plainAddress(input)).toBe(output);
});

test.each(fixture.settledAnswer.map((item) => [item.name, item] as const))(
  "settledAnswer: %s",
  (_name, item) => {
    const outcome = {
      status: item.status,
      url: "",
      text: "",
      insecure: item.insecure,
    };
    expect(settledAnswer(outcome)).toBe(item.settled);
  },
);

test("settledAnswer: no answer from the extension settles nothing", () => {
  expect(settledAnswer(undefined)).toBe(false);
});

test.each(fixture.sameFeed.map((item) => [item.name, item] as const))(
  "sameFeed: %s",
  (_name, item) => {
    expect(sameFeed(item.known, parsedOf(item.found))).toBe(item.same);
  },
);

test.each(fixture.webAddress.map((item) => [item.name, item] as const))(
  "webAddress: %s",
  (_name, item) => {
    expect(webAddress(item.input)).toBe(item.output);
  },
);

test.each(
  fixture.probeAllowed.map((item) => [item.input, item.output] as const),
)("probeAllowed(%p) is %p", (input, output) => {
  expect(probeAllowed(input)).toBe(output);
});

const KNOWN: KnownFeed = {
  url: "http://blog.example/rss",
  title: "Blog",
  siteUrl: "",
  guids: [],
  links: [],
};

const FEED = `<rss><channel><title>Blog</title><link>https://blog.example/</link>
  <item><title>a</title><guid>a</guid></item></channel></rss>`;

function answer(extra: Partial<ProbeOutcome> = {}): ProbeOutcome {
  return {
    status: 200,
    url: "https://blog.example/rss",
    text: FEED,
    insecure: false,
    ...extra,
  };
}

test("probeHttps fetches the https address and takes a matching feed", async () => {
  const asked: string[] = [];
  const match = await probeHttps(KNOWN, (url) => {
    asked.push(url);
    return Promise.resolve(answer());
  });
  expect(asked).toEqual(["https://blog.example/rss"]);
  expect(match?.url).toBe("https://blog.example/rss");
  expect(match?.feed.title).toBe("Blog");
});

test("probeHttps refuses failures, redirects off https, non-feeds and other feeds", async () => {
  const outcomes: (ProbeOutcome | undefined)[] = [
    undefined,
    answer({ status: 0 }),
    answer({ status: 404, text: "" }),
    answer({ status: 0, insecure: true }),
    answer({ text: "<html><body>Hello</body></html>" }),
    answer({
      text: "<rss><channel><title>Parked</title><link>https://parking.example/</link></channel></rss>",
    }),
  ];
  for (const outcome of outcomes) {
    // biome-ignore lint/performance/noAwaitInLoops: one fake answer at a time
    const match = await probeHttps(KNOWN, () => Promise.resolve(outcome));
    expect(match).toBeUndefined();
  }
});

test("probeHttps tries nothing for an address that is not plain http or may not be tried", async () => {
  let asked = 0;
  function fetchAddress(): Promise<ProbeOutcome> {
    asked += 1;
    return Promise.resolve(answer());
  }
  expect(
    await probeHttps(
      { ...KNOWN, url: "https://blog.example/rss" },
      fetchAddress,
    ),
  ).toBeUndefined();
  expect(
    await probeHttps({ ...KNOWN, url: "ftp://blog.example/rss" }, fetchAddress),
  ).toBeUndefined();
  expect(
    await probeHttps({ ...KNOWN, url: "http://192.168.1.1/rss" }, fetchAddress),
  ).toBeUndefined();
  expect(
    await probeHttps({ ...KNOWN, url: "http://nas.local/rss" }, fetchAddress),
  ).toBeUndefined();
  expect(asked).toBe(0);
});

test("mapConcurrently keeps order and limits how many run at once", async () => {
  let running = 0;
  let most = 0;
  const results = await mapConcurrently(
    [1, 2, 3, 4, 5, 6, 7],
    3,
    async (item) => {
      running += 1;
      most = Math.max(most, running);
      await new Promise((resolve) => setTimeout(resolve, 8 - item));
      running -= 1;
      return item * 10;
    },
  );
  expect(results).toEqual([10, 20, 30, 40, 50, 60, 70]);
  expect(most).toBe(3);
  expect(await mapConcurrently([], 3, () => Promise.resolve(1))).toEqual([]);
});
