import { upgradedAddress } from "./address.ts";

const LINK_TAG_PATTERN = /<link\b[^>]*>/giu;
const ATTRIBUTE_PATTERN =
  /(?<name>[\w:-]+)\s*=\s*(?:"(?<double>[^"]*)"|'(?<single>[^']*)'|(?<bare>[^\s"'<>`]+))/gu;
const FEED_TYPES = [
  "application/rss+xml",
  "application/atom+xml",
  "application/feed+json",
  "application/json",
];
const COMMON_PATHS = [
  "/feed",
  "/rss",
  "/feed.xml",
  "/rss.xml",
  "/atom.xml",
  "/index.xml",
];
const YOUTUBE_CHANNEL_PATTERN = /^\/channel\/(?<id>UC[\w-]{20,})/u;
const MASTODON_PATH_PATTERN = /^\/@[\w.-]+\/?$/u;
const BLUESKY_PATH_PATTERN = /^\/profile\/(?<handle>[^/]+)\/?$/u;
const REDDIT_PATH_PATTERN = /^\/(?<kind>r|user|u)\/(?<name>[\w-]+)\/?$/u;
const GITHUB_PATH_PATTERN = /^\/(?<owner>[\w.-]+)\/(?<repo>[\w.-]+)\/?$/u;
const WHITESPACE_PATTERN = /\s+/u;
const TRAILING_SLASH_PATTERN = /\/$/u;
const MEDIUM_PATH_PATTERN = /^\/(?<name>@?[\w.-]+)\/?$/u;

function hostIs(host: string, domain: string): boolean {
  return host === domain || host.endsWith(`.${domain}`);
}

function youtube(url: URL): string[] {
  const channel = YOUTUBE_CHANNEL_PATTERN.exec(url.pathname)?.groups?.id;
  const playlist = url.searchParams.get("list");
  const found: string[] = [];
  if (channel) {
    found.push(
      `https://www.youtube.com/feeds/videos.xml?channel_id=${channel}`,
    );
  } else if (url.pathname === "/playlist" && playlist) {
    found.push(
      `https://www.youtube.com/feeds/videos.xml?playlist_id=${playlist}`,
    );
  }
  return found;
}

function medium(url: URL): string[] {
  const found: string[] = [];
  if (url.hostname === "medium.com") {
    const name = MEDIUM_PATH_PATTERN.exec(url.pathname)?.groups?.name;
    if (name) {
      found.push(`https://medium.com/feed/${name}`);
    }
  } else {
    found.push(`${url.origin}/feed`);
  }
  return found;
}

/**
 * Feed addresses a service is known to offer for a page on it, most likely
 * first. Nothing here is checked; the caller tries each in turn.
 */
export function knownFeedUrls(url: URL): string[] {
  const host = url.hostname.toLowerCase();
  const found: string[] = [];
  const reddit = REDDIT_PATH_PATTERN.exec(url.pathname)?.groups;
  const github = GITHUB_PATH_PATTERN.exec(url.pathname)?.groups;
  const bluesky = BLUESKY_PATH_PATTERN.exec(url.pathname)?.groups;
  if (hostIs(host, "youtube.com")) {
    found.push(...youtube(url));
  } else if (hostIs(host, "reddit.com") && reddit) {
    found.push(`https://www.reddit.com/${reddit.kind}/${reddit.name}/.rss`);
  } else if (host === "github.com" && github) {
    const base = `https://github.com/${github.owner}/${github.repo}`;
    found.push(`${base}/releases.atom`, `${base}/commits.atom`);
  } else if (hostIs(host, "medium.com")) {
    found.push(...medium(url));
  } else if (hostIs(host, "tumblr.com")) {
    found.push(`${url.origin}/rss`);
  } else if (host === "bsky.app" && bluesky) {
    found.push(`https://bsky.app/profile/${bluesky.handle}/rss`);
  } else if (MASTODON_PATH_PATTERN.test(url.pathname)) {
    found.push(
      `${url.origin}${url.pathname.replace(TRAILING_SLASH_PATTERN, "")}.rss`,
    );
  }
  return found;
}

function attributesOf(tag: string): Map<string, string> {
  const attributes = new Map<string, string>();
  for (const match of tag.matchAll(ATTRIBUTE_PATTERN)) {
    const { name, double, single, bare } = match.groups ?? {};
    attributes.set((name ?? "").toLowerCase(), double ?? single ?? bare ?? "");
  }
  return attributes;
}

function feedLinkOf(tag: string, base: string): string | undefined {
  const attributes = attributesOf(tag);
  const rel = (attributes.get("rel") ?? "")
    .toLowerCase()
    .split(WHITESPACE_PATTERN);
  const kind = (attributes.get("type") ?? "").toLowerCase();
  const href = attributes.get("href") ?? "";
  let found: string | undefined;
  if (href !== "" && rel.includes("alternate") && FEED_TYPES.includes(kind)) {
    found = upgradedAddress(href.replaceAll("&amp;", "&"), base) || undefined;
  }
  return found;
}

/** Feed addresses a web page announces in its head. */
export function discoverFeedLinks(html: string, base: string): string[] {
  const found: string[] = [];
  for (const tag of html.match(LINK_TAG_PATTERN) ?? []) {
    const link = feedLinkOf(tag, base);
    if (link !== undefined) {
      found.push(link);
    }
  }
  return found;
}

function iconRank(rel: string[]): number {
  let rank = -1;
  if (rel.includes("apple-touch-icon")) {
    rank = 0;
  } else if (rel.includes("apple-touch-icon-precomposed")) {
    rank = 1;
  } else if (rel.includes("icon")) {
    rank = rel.includes("shortcut") ? 3 : 2;
  }
  return rank;
}

function iconAddress(
  tag: string,
  base: string,
): { href: string; rank: number } | undefined {
  const attributes = attributesOf(tag);
  const rel = (attributes.get("rel") ?? "")
    .toLowerCase()
    .split(WHITESPACE_PATTERN);
  const href = attributes.get("href") ?? "";
  const rank = iconRank(rel);
  let found: { href: string; rank: number } | undefined;
  const address =
    href !== "" && rank !== -1
      ? upgradedAddress(href.replaceAll("&amp;", "&"), base)
      : "";
  if (address !== "") {
    found = {
      href: address,
      rank: rank + (new URL(address).pathname.endsWith(".svg") ? 0 : 0.5),
    };
  }
  return found;
}

/**
 * Small pictures a web page announces in its head, best first: touch icons
 * (large and sharp), then icons, with the SVG ones ahead of the rest.
 */
export function discoverIconLinks(html: string, base: string): string[] {
  const found: { href: string; rank: number }[] = [];
  for (const tag of html.match(LINK_TAG_PATTERN) ?? []) {
    const icon = iconAddress(tag, base);
    if (icon) {
      found.push(icon);
    }
  }
  found.sort((first, second) => first.rank - second.rank);
  return [...new Set(found.map((item) => item.href))];
}

/** Paths many sites serve their feed at. */
function commonFeedUrls(url: URL): string[] {
  return COMMON_PATHS.map((path) => `${url.origin}${path}`);
}

/**
 * Addresses to try, in order, when someone pastes `input` and asks for its
 * feed: the address itself, what its service is known to offer, what the page
 * announces (when its HTML is given), then common paths. Plain `http` is
 * upgraded to `https`; throws when `input` is not a web address.
 */
export function feedCandidates(input: string, html = ""): string[] {
  const upgraded = upgradedAddress(input);
  if (upgraded === "") {
    throw new TypeError("not a web address");
  }
  const url = new URL(upgraded);
  const ordered = [
    url.href,
    ...knownFeedUrls(url),
    ...discoverFeedLinks(html, url.href),
    ...commonFeedUrls(url),
  ];
  return [...new Set(ordered)];
}
