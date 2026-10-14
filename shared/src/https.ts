import { upgradedAddress } from "./address.ts";
import { type ParsedFeed, parseFeed } from "./feed.ts";
import type { FetchOutcome } from "./protocol.ts";

/** Most https addresses tried at once. */
export const PROBE_CONCURRENCY = 6;

/**
 * Most tries of a plain http feed's https address in one session that get no
 * definite answer; after that it waits for the app to start again.
 */
export const UNSETTLED_TRIES = 3;

const SPACES_PATTERN = /\s+/u;
const SCHEME_PATTERN = /^https?:\/\//iu;
const WWW_PATTERN = /^www\./u;
const TRAILING_DOT = /\.$/u;
// A last label that is a number, as in an IPv4 address or the forms browsers read as one.
const NUMBER_LABEL = /^(?:0x[0-9a-f]*|[0-9]+)$/iu;
// Names that only mean something on the local network.
const LOCAL_NAMES = ["localhost", "local", "internal", "home.arpa", "lan"];

function parsed(address: string): URL | undefined {
  let url: URL | undefined;
  try {
    url = new URL(address);
  } catch {
    url = undefined;
  }
  return url;
}

/** Whether an address is plain `http`. */
export function isPlainHttp(address: string): boolean {
  return parsed(address.trim())?.protocol === "http:";
}

/**
 * An `https` address written as plain `http`, to find a feed followed at its
 * http address; empty for anything else.
 */
export function plainAddress(address: string): string {
  const url = parsed(address.trim());
  let result = "";
  if (url?.protocol === "https:") {
    url.protocol = "http:";
    result = url.href;
  }
  return result;
}

/**
 * An `http` or `https` address written out the way browsers write it (the
 * scheme and host in lower case, the scheme's own default port left out, an
 * international name in its `xn--` form, the fragment kept); empty for
 * anything else, and for an address with a user name or password, which
 * browsers refuse to fetch. Plain http is kept as it is, so it can be shown
 * as http and tried over https before it is used.
 */
export function webAddress(address: string): string {
  const url = parsed(address.trim());
  return (url?.protocol === "http:" || url?.protocol === "https:") &&
    url.username === "" &&
    url.password === ""
    ? url.href
    : "";
}

/**
 * Whether a plain http address may be tried at its https address: its host
 * is a public name, not an IP address (nor a number browsers read as one), a
 * name of one label, `localhost`, or a name under `.local`, `.internal`,
 * `.home.arpa` or `.lan`, and it has no user name or password. Addresses
 * from a feed list or a search come from others, and trying them must not
 * reach this device or its network.
 */
export function probeAllowed(address: string): boolean {
  const url = parsed(address.trim());
  const host = (url?.hostname ?? "").replace(TRAILING_DOT, "");
  const labels = host.split(".");
  return (
    url !== undefined &&
    url.username === "" &&
    url.password === "" &&
    labels.length > 1 &&
    !host.startsWith("[") &&
    !NUMBER_LABEL.test(labels.at(-1) ?? "") &&
    !LOCAL_NAMES.some((name) => host === name || host.endsWith(`.${name}`))
  );
}

/** What is known of a plain http feed before its https address is tried. */
export interface KnownFeed {
  /** Its plain http address. */
  url: string;
  /** Its name; empty when unknown. */
  title: string;
  /** Its site's address; empty when unknown. */
  siteUrl: string;
  /** Guids of its articles already held. */
  guids: string[];
  /** Links of its articles already held. */
  links: string[];
}

/** The parts of a fetch the probe reads. */
export type ProbeOutcome = Pick<
  FetchOutcome,
  "status" | "url" | "text" | "insecure"
>;

/** A plain http feed's https address, found to serve the same feed. */
export interface HttpsMatch<Outcome extends ProbeOutcome = ProbeOutcome> {
  /** The https address. */
  url: string;
  /** What it served. */
  feed: ParsedFeed;
  /** The answer it came in. */
  outcome: Outcome;
}

// Statuses that ask to try again later rather than say anything of the address.
const LATER_STATUSES = new Set([408, 425, 429]);

/**
 * Whether a probe's answer settles if a feed has an https address: any answer
 * from the site, except one asking to try again later (a timeout, too many
 * requests, or a server error). A redirect off https settles it too: Android
 * sees one as it follows redirects itself, and the extension reports one when
 * the https address redirects but following it never reaches an answer over
 * https, as when the site sends https back to http and the browser's upgrade
 * of that hop loops. No answer at all, as when offline or the fetch timed
 * out, settles nothing.
 */
export function settledAnswer(outcome: ProbeOutcome | undefined): boolean {
  return (
    outcome !== undefined &&
    (outcome.insecure ||
      (outcome.status !== 0 &&
        outcome.status < 500 &&
        !LATER_STATUSES.has(outcome.status)))
  );
}

function normalTitle(title: string): string {
  return title.toLowerCase().split(SPACES_PATTERN).filter(Boolean).join(" ");
}

function withoutScheme(address: string): string {
  return address.replace(SCHEME_PATTERN, "");
}

function siteHost(address: string): string {
  return (parsed(address)?.hostname ?? "").replace(WWW_PATTERN, "");
}

/**
 * Whether a feed read over https looks like the plain http one it stands in
 * for: the same name (ignoring capitals and spacing), an article in common (by
 * guid or link, ignoring the scheme), or a site on the same host as the known
 * site or, failing that, as the http address itself (ignoring `www.`).
 */
export function sameFeed(known: KnownFeed, found: ParsedFeed): boolean {
  const title = normalTitle(known.title);
  const guids = new Set(known.guids.map(withoutScheme));
  const links = new Set(known.links.filter(Boolean).map(withoutScheme));
  const shared = found.entries.some(
    (entry) =>
      guids.has(withoutScheme(entry.guid)) ||
      (entry.url !== "" && links.has(withoutScheme(entry.url))),
  );
  const knownHost = siteHost(known.siteUrl) || siteHost(known.url);
  const foundHost = siteHost(found.siteUrl);
  return (
    (title !== "" && title === normalTitle(found.title)) ||
    shared ||
    (foundHost !== "" && foundHost === knownHost)
  );
}

/**
 * Try a plain http feed at its https address. It counts only when the answer
 * is a 200 that stays on https, parses as a feed and passes `sameFeed`;
 * otherwise, or when `known` is not plain http or not `probeAllowed`, there
 * is no match, and then nothing is fetched.
 */
export async function probeHttps<Outcome extends ProbeOutcome>(
  known: KnownFeed,
  fetchAddress: (url: string) => Promise<Outcome | undefined>,
): Promise<HttpsMatch<Outcome> | undefined> {
  const url =
    isPlainHttp(known.url) && probeAllowed(known.url)
      ? upgradedAddress(known.url)
      : "";
  const outcome = url === "" ? undefined : await fetchAddress(url);
  let match: HttpsMatch<Outcome> | undefined;
  if (outcome?.status === 200 && !outcome.insecure) {
    let feed: ParsedFeed | undefined;
    try {
      feed = parseFeed(outcome.text, outcome.url || url);
    } catch {
      feed = undefined;
    }
    if (feed !== undefined && sameFeed(known, feed)) {
      match = { url, feed, outcome };
    }
  }
  return match;
}

/** Run `work` on every item, at most `limit` at once, keeping the order. */
export async function mapConcurrently<Item, Result>(
  items: readonly Item[],
  limit: number,
  work: (item: Item) => Promise<Result>,
): Promise<Result[]> {
  const results = new Array<Result>(items.length);
  // one iterator shared by every worker, so each item is taken once
  const pending = items.entries();
  async function worker(): Promise<void> {
    for (const [index, item] of pending) {
      // biome-ignore lint/performance/noAwaitInLoops: each worker takes one item at a time
      results[index] = await work(item);
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, worker),
  );
  return results;
}
