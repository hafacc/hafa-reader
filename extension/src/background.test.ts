import { beforeEach, expect, test } from "bun:test";
import type {
  ExtensionRequest,
  ExtensionResponse,
} from "../../shared/src/protocol.ts";

type Listener = (
  request: ExtensionRequest,
  sender: { origin: string },
  sendResponse: (response: ExtensionResponse) => void,
) => boolean;

const stored = new Map<string, unknown>();
const listeners: Listener[] = [];

function storedCopies(
  keys: string[] | Record<string, unknown>,
): Record<string, unknown> {
  const wanted: [string, unknown][] = Array.isArray(keys)
    ? keys.filter((key) => stored.has(key)).map((key) => [key, undefined])
    : Object.entries(keys);
  return Object.fromEntries(
    wanted.map(([key, fallback]) => [
      key,
      stored.has(key) ? structuredClone(stored.get(key)) : fallback,
    ]),
  );
}

function ignore(): void {
  // a listener the tests never call
}

Object.assign(globalThis, {
  chrome: {
    storage: {
      local: {
        get: (keys: string[] | Record<string, unknown>) =>
          Promise.resolve(storedCopies(keys)),
        set: (items: Record<string, unknown>) => {
          for (const [key, value] of Object.entries(items)) {
            stored.set(key, structuredClone(value));
          }
          return Promise.resolve();
        },
        remove: (keys: string[]) => {
          for (const key of keys) {
            stored.delete(key);
          }
          return Promise.resolve();
        },
      },
    },
    runtime: {
      getManifest: () => ({
        version: "0.0.0",
        // biome-ignore lint/style/useNamingConvention: the manifest's own key
        externally_connectable: { matches: ["https://reader.hafa.cc/*"] },
      }),
      getPlatformInfo: () => Promise.resolve({}),
      onMessageExternal: {
        addListener: (listener: Listener) => {
          listeners.push(listener);
        },
      },
      onInstalled: { addListener: ignore },
      onStartup: { addListener: ignore },
    },
    alarms: {
      get: () => Promise.resolve({ name: "due" }),
      create: () => Promise.resolve(),
      onAlarm: { addListener: ignore },
    },
  },
});

const FEED_URL = "https://feed.example/rss";
const RSS = `<rss><channel><title>Feed</title><link>https://feed.example/</link>
  <item><title>a</title><guid>a</guid></item></channel></rss>`;

/** Answers the fake fetch gives; each test sets its own. */
let respond: (init: RequestInit | undefined) => Promise<Response> = () =>
  Promise.reject(new TypeError("Failed to fetch"));
/** Addresses fetched, in order. */
const fetched: string[] = [];

Object.assign(globalThis, {
  fetch: (input: string | URL | Request, init?: RequestInit) => {
    fetched.push(String(input));
    return respond(init);
  },
});

await import("./background.ts");

function ask(request: ExtensionRequest): Promise<ExtensionResponse> {
  const [listener] = listeners;
  return new Promise((resolve, reject) => {
    if (listener === undefined) {
      reject(new Error("The background script added no listener."));
    } else {
      listener(request, { origin: "https://reader.hafa.cc" }, resolve);
    }
  });
}

async function drained(): Promise<
  Extract<ExtensionResponse, { type: "drained" }>
> {
  const response = await ask({ type: "drain" });
  if (response.type !== "drained") {
    throw new Error(`Expected a drain, got ${response.type}.`);
  }
  return response;
}

async function settle(): Promise<void> {
  // let the queue finish and save; nothing here waits on a real timer
  for (let round = 0; round < 50; round += 1) {
    // biome-ignore lint/performance/noAwaitInLoops: each turn lets the next step run
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
}

beforeEach(() => {
  stored.clear();
  fetched.length = 0;
});

test("checkDue answers once the checks are queued, and the drain says they are under way", async () => {
  let answer: (response: Response) => void = ignore;
  respond = (): Promise<Response> =>
    new Promise<Response>((resolve) => {
      answer = resolve;
    });
  expect(
    await ask({
      type: "track",
      feeds: [{ id: "feed", url: FEED_URL, empty: true }],
    }),
  ).toEqual({ type: "tracked" });
  const before = Date.now();
  expect(await ask({ type: "checkDue" })).toEqual({ type: "queued" });
  expect(fetched).toEqual([FEED_URL]);

  const during = await drained();
  expect(during.checking).toBe(true);
  expect(during.pulled).toEqual([]);
  expect(during.status).toEqual([
    { feedId: "feed", lastChecked: 0, error: "", nextCheck: 0 },
  ]);

  answer(new Response(RSS, { status: 200 }));
  await settle();
  const after = await drained();
  expect(after.checking).toBe(false);
  expect(after.pulled.map((pulled) => pulled.feedId)).toEqual(["feed"]);
  expect(after.status[0]?.lastChecked).toBeGreaterThanOrEqual(before);
  expect(after.status[0]?.nextCheck).toBeGreaterThan(before);
});

test("checkDue queues nothing for a feed that is not due", async () => {
  respond = (): Promise<Response> =>
    Promise.resolve(new Response(RSS, { status: 200 }));
  await ask({
    type: "track",
    feeds: [
      {
        id: "feed",
        url: FEED_URL,
        empty: true,
        first: {
          status: 200,
          url: FEED_URL,
          text: RSS,
          etag: "",
          lastModified: "",
          hint: 0,
          insecure: false,
        },
      },
    ],
  });
  expect(await ask({ type: "checkDue" })).toEqual({ type: "queued" });
  expect(fetched).toEqual([]);
  const page = await drained();
  expect(page.checking).toBe(false);
  expect(page.status[0]?.nextCheck).toBeGreaterThan(Date.now());
});

test("a malformed first answer leaves the feed due instead of failing the track", async () => {
  expect(
    await ask({
      type: "track",
      feeds: [
        {
          id: "feed",
          url: FEED_URL,
          empty: true,
          first: JSON.parse('{"status":200}'),
        },
      ],
    }),
  ).toEqual({ type: "tracked" });
  const page = await drained();
  expect(page.status).toEqual([
    { feedId: "feed", lastChecked: 0, error: "", nextCheck: 0 },
  ]);
});

test("an address whose redirects never settle on https is reported as not on https", async () => {
  const redirect = new Response(null);
  Object.defineProperty(redirect, "type", { value: "opaqueredirect" });
  // following the redirects fails; asking without following shows a redirect
  respond = (init): Promise<Response> =>
    init?.redirect === "manual"
      ? Promise.resolve(redirect)
      : Promise.reject(new TypeError("Failed to fetch"));
  const response = await ask({
    type: "fetch",
    url: FEED_URL,
    etag: "",
    lastModified: "",
  });
  expect(response).toMatchObject({
    type: "fetched",
    status: 0,
    insecure: true,
  });
});

test("an address that does not answer at all is no answer", async () => {
  respond = (): Promise<Response> =>
    Promise.reject(new TypeError("Failed to fetch"));
  const response = await ask({
    type: "fetch",
    url: FEED_URL,
    etag: "",
    lastModified: "",
  });
  expect(response).toMatchObject({
    type: "fetched",
    status: 0,
    insecure: false,
  });
});
