import { XMLParser } from "fast-xml-parser";
import { upgradedAddress } from "./address.ts";

/** One article from a feed. */
export interface FeedEntry {
  /** Identifier unique within the feed. */
  guid: string;
  /** Headline, or the link when the feed gives none. */
  title: string;
  /** Link to the original article. */
  url: string;
  /** Milliseconds since the epoch; zero when the feed gives no readable date. */
  published: number;
  /** Name of the writer; empty if the feed gives none. */
  author: string;
  /** Lead image address. */
  imageUrl: string;
  /** The feed's short description of the article, as plain text. */
  summary: string;
  /** Sections, topics and tags the feed lists for the article. */
  categories: string[];
  /** Article body as HTML, not yet sanitized. */
  html: string;
}

/** A feed's own details and its current entries. */
export interface ParsedFeed {
  /** The feed's own name. */
  title: string;
  /** Address of the site the feed belongs to. */
  siteUrl: string;
  /** Address of the feed's own small picture; empty if it names none. */
  iconUrl: string;
  entries: FeedEntry[];
}

type Node = Record<string, unknown>;

const ARRAY_TAGS = new Set([
  "item",
  "entry",
  "link",
  "enclosure",
  "thumbnail",
  "content",
  "author",
  "category",
]);

const xmlParser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "@_",
  textNodeName: "#text",
  // biome-ignore lint/style/useNamingConvention: option name from the library
  removeNSPrefix: true,
  parseTagValue: false,
  parseAttributeValue: false,
  trimValues: true,
  isArray: (name): boolean => ARRAY_TAGS.has(name),
});

function isNode(value: unknown): value is Node {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function list(value: unknown): unknown[] {
  if (Array.isArray(value)) {
    return value;
  } else if (value === undefined || value === null) {
    return [];
  } else {
    return [value];
  }
}

function nestedText(node: Node): string {
  const parts: string[] = [];
  for (const [key, child] of Object.entries(node)) {
    if (!key.startsWith("@_")) {
      parts.push(text(child));
    }
  }
  return parts.filter(Boolean).join(" ");
}

function text(value: unknown): string {
  let result = "";
  if (typeof value === "string") {
    result = value.trim();
  } else if (typeof value === "number" && Number.isFinite(value)) {
    result = String(value);
  } else if (isNode(value)) {
    result = text(value["#text"]) || nestedText(value);
  } else if (Array.isArray(value)) {
    result = value.map(text).find((part) => part !== "") ?? "";
  }
  return result;
}

const TAG_PATTERN = /<[^>]*>/gu;
const DECIMAL_PATTERN = /&#(?<digits>\d+);/gu;
const HEX_PATTERN = /&#x(?<digits>[\da-f]+);/giu;
const NAMED_PATTERN = /&(?<name>[a-z]+);/giu;
const NAMED_ENTITIES: Record<string, string> = {
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  nbsp: " ",
};

const MAX_CODE_POINT = 0x10_ff_ff;

const HTML_ESCAPES: Record<string, string> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
};
const HTML_SPECIAL_PATTERN = /[&<>"']/gu;
const LINE_BREAK_PATTERN = /\r\n?|\n/gu;

/** HTML that shows plain text as it is, line breaks included. */
function plainTextHtml(source: string): string {
  return source
    .replace(HTML_SPECIAL_PATTERN, (character) => HTML_ESCAPES[character] ?? "")
    .replace(LINE_BREAK_PATTERN, "<br>");
}

function codePointOr(code: number, fallback: string): string {
  return Number.isInteger(code) && code > 0 && code <= MAX_CODE_POINT
    ? String.fromCodePoint(code)
    : fallback;
}

/** HTML text with numeric and common named character references decoded. */
function decodeCharacterReferences(source: string): string {
  return source
    .replace(DECIMAL_PATTERN, (match, digits: string) =>
      codePointOr(Number(digits), match),
    )
    .replace(HEX_PATTERN, (match, digits: string) =>
      codePointOr(Number.parseInt(digits, 16), match),
    );
}

function decodeEntities(html: string): string {
  return decodeCharacterReferences(html).replace(
    NAMED_PATTERN,
    (match, name: string) => NAMED_ENTITIES[name.toLowerCase()] ?? match,
  );
}

/**
 * Text of an XML value for display. The parser decodes named references but
 * leaves numeric ones such as `&#8217;`, so those are decoded here; guids keep
 * the raw text so entry ids stay as they were.
 */
function displayText(value: unknown): string {
  return decodeCharacterReferences(text(value));
}

/** Text of an HTML snippet with tags removed and common entities decoded. */
function plainText(html: string): string {
  return decodeEntities(html.replace(TAG_PATTERN, " "))
    .replace(/\s+/gu, " ")
    .trim();
}

function categoryNames(values: unknown[]): string[] {
  const names: string[] = [];
  for (const value of values) {
    const name = isNode(value)
      ? displayText(value["@_term"]) || displayText(value)
      : displayText(value);
    if (name && !names.includes(name)) {
      names.push(name);
    }
  }
  return names;
}

const ZONE_OFFSETS = new Map([
  ["CET", "+0100"],
  ["CEST", "+0200"],
  ["EET", "+0200"],
  ["EEST", "+0300"],
  ["WET", "+0000"],
  ["WEST", "+0100"],
  ["BST", "+0100"],
  ["MSK", "+0300"],
  ["JST", "+0900"],
  ["KST", "+0900"],
  ["AEST", "+1000"],
  ["AEDT", "+1100"],
  ["NZST", "+1200"],
  ["NZDT", "+1300"],
  ["HST", "-1000"],
  ["AKST", "-0900"],
  ["AKDT", "-0800"],
]);
const ZONE_PATTERN = /\s(?<zone>[A-Z]{3,4})\s*$/u;

/** Milliseconds since the epoch for a feed date, or zero when it cannot be read. */
function parseDate(value: string): number {
  const zone = ZONE_PATTERN.exec(value)?.groups?.zone ?? "";
  const offset = ZONE_OFFSETS.get(zone);
  const normalized =
    offset === undefined ? value : value.replace(ZONE_PATTERN, ` ${offset}`);
  const parsed = Date.parse(normalized);
  return Number.isNaN(parsed) ? 0 : parsed;
}

function firstDate(node: Node, fields: string[]): number {
  let found = 0;
  for (const field of fields) {
    found = found || parseDate(text(node[field]));
  }
  return found;
}

const IMAGE_PATTERN = /<img\b[^>]*?\bsrc\s*=\s*["'](?<src>[^"']+)["']/iu;

function firstImage(html: string, base: string): string {
  const source = IMAGE_PATTERN.exec(html)?.groups?.src;
  return source ? upgradedAddress(decodeEntities(source), base) : "";
}

function atomHref(links: unknown[]): string {
  const nodes = links.filter(isNode);
  const alternate = nodes.find(
    (link) => !link["@_rel"] || link["@_rel"] === "alternate",
  );
  const href = alternate?.["@_href"];
  return typeof href === "string" ? href : "";
}

/** The Media RSS groups of an item, which YouTube and others put its picture and description in. */
function mediaGroups(item: Node): Node[] {
  return list(item.group).filter(isNode);
}

function mediaImage(item: Node, base: string): string {
  let image = "";
  for (const thumbnail of list(item.thumbnail).filter(isNode)) {
    const url = thumbnail["@_url"];
    if (!image && typeof url === "string") {
      image = upgradedAddress(url, base);
    }
  }
  for (const content of list(item.content).filter(isNode)) {
    const url = content["@_url"];
    const kind = `${content["@_medium"] ?? ""}${content["@_type"] ?? ""}`;
    if (!image && typeof url === "string" && kind.includes("image")) {
      image = upgradedAddress(url, base);
    }
  }
  for (const enclosure of list(item.enclosure).filter(isNode)) {
    const url = enclosure["@_url"];
    const kind = enclosure["@_type"];
    if (
      !image &&
      typeof url === "string" &&
      typeof kind === "string" &&
      kind.startsWith("image/")
    ) {
      image = upgradedAddress(url, base);
    }
  }
  for (const group of mediaGroups(item)) {
    image ||= mediaImage(group, base);
  }
  return image;
}

/**
 * An item's Media RSS description as a body and a summary; empty when it has
 * none. `ownDescription` also reads a description on the item itself, which
 * in RSS would be the item's own description instead.
 */
function mediaDescription(
  item: Node,
  ownDescription: boolean,
): { html: string; summary: string } {
  const candidates = [
    ...(ownDescription ? list(item.description) : []),
    ...mediaGroups(item).flatMap((group) => list(group.description)),
  ];
  const description = candidates.find((candidate) => text(candidate) !== "");
  let result = { html: "", summary: "" };
  if (isNode(description) && description["@_type"] === "html") {
    const html = text(description);
    result = { html, summary: plainText(html) };
  } else if (description !== undefined) {
    const shown = displayText(description);
    result = {
      html: plainTextHtml(shown),
      summary: shown.replace(/\s+/gu, " ").trim(),
    };
  }
  return result;
}

/** An Atom entry's own content: not a Media RSS content, which has an address. */
function atomContent(values: unknown[]): string {
  const own = values.find(
    (value) =>
      typeof value === "string" || (isNode(value) && !("@_url" in value)),
  );
  return text(own);
}

function parseRssItem(item: Node, base: string): FeedEntry {
  const link = upgradedAddress(text(item.link), base);
  const title = text(item.title);
  const shownTitle = displayText(item.title);
  const media = mediaDescription(item, false);
  const html = text(item.encoded) || text(item.description) || media.html;
  const published = firstDate(item, [
    "pubDate",
    "date",
    "published",
    "issued",
    "created",
    "updated",
    "modified",
  ]);
  return {
    guid: text(item.guid) || link || title,
    title: shownTitle || link,
    url: link,
    published,
    author: displayText(item.creator) || displayText(item.author),
    imageUrl: mediaImage(item, base) || firstImage(html, base),
    summary: plainText(text(item.description)) || media.summary,
    categories: categoryNames(list(item.category)),
    html,
  };
}

function parseAtomEntry(entry: Node, base: string): FeedEntry {
  const link = upgradedAddress(atomHref(list(entry.link)), base);
  const title = text(entry.title);
  const shownTitle = displayText(entry.title);
  const media = mediaDescription(entry, true);
  const html =
    atomContent(list(entry.content)) || text(entry.summary) || media.html;
  const published = firstDate(entry, [
    "published",
    "issued",
    "created",
    "updated",
    "modified",
  ]);
  const author = list(entry.author).find(isNode);
  return {
    guid: text(entry.id) || link || title,
    title: shownTitle || link,
    url: link,
    published,
    author: author ? displayText(author.name) : "",
    imageUrl: mediaImage(entry, base) || firstImage(html, base),
    summary: plainText(text(entry.summary)) || media.summary,
    categories: categoryNames(list(entry.category)),
    html,
  };
}

const JSON_FEED_VERSION_PATTERN = /^https?:\/\/jsonfeed\.org\/version\//u;

/** Whether a JSON object is a JSON Feed: it names the version or lists items. */
function isJsonFeed(source: Node): boolean {
  return (
    Array.isArray(source.items) ||
    (typeof source.version === "string" &&
      JSON_FEED_VERSION_PATTERN.test(source.version))
  );
}

function parseJsonFeed(source: Node, base: string): ParsedFeed {
  const entries: FeedEntry[] = [];
  for (const item of list(source.items).filter(isNode)) {
    const url = upgradedAddress(text(item.url), base);
    const html =
      text(item.content_html) || plainTextHtml(text(item.content_text));
    const title = text(item.title);
    const published = firstDate(item, ["date_published", "date_modified"]);
    const authors = list(item.authors).filter(isNode);
    entries.push({
      guid: text(item.id) || url || title,
      title: title || url,
      url,
      published,
      author: authors[0] ? text(authors[0].name) : "",
      imageUrl:
        upgradedAddress(text(item.image), base) || firstImage(html, base),
      summary: plainText(text(item.summary)),
      categories: list(item.tags)
        .map((tag) => text(tag))
        .filter(Boolean),
      html,
    });
  }
  return {
    title: text(source.title),
    siteUrl: upgradedAddress(text(source.home_page_url), base),
    iconUrl: upgradedAddress(text(source.favicon) || text(source.icon), base),
    entries,
  };
}

// Atom content and summary given as XHTML, whose markup the parser would split into nodes.
const XHTML_PATTERN =
  /(?<open><(?<tag>(?:[\w-]+:)?(?:content|summary))\b(?:[^>/]|\/(?!>))*>)(?<inner>[\s\S]*?)(?<close><\/\k<tag>\s*>)/gu;
const XHTML_TYPE_PATTERN = /\btype\s*=\s*["']xhtml["']/u;

/** Wrap XHTML content in CDATA so it reaches the entry as HTML text. */
function protectXhtml(source: string): string {
  return source.replace(
    XHTML_PATTERN,
    (match, open: string, _tag: string, inner: string, close: string) =>
      XHTML_TYPE_PATTERN.test(open) && !inner.includes("<![CDATA[")
        ? `${open}<![CDATA[${inner}]]>${close}`
        : match,
  );
}

function parseXml(source: string, feedUrl: string): ParsedFeed {
  const document: unknown = xmlParser.parse(protectXhtml(source));
  if (!isNode(document)) {
    throw new Error("not a feed");
  }
  const { rss, RDF: rdf, feed: atom } = document;
  let result: ParsedFeed;
  if (isNode(rss) && isNode(rss.channel)) {
    const { channel } = rss;
    result = {
      title: displayText(channel.title),
      siteUrl: upgradedAddress(text(channel.link), feedUrl),
      iconUrl: upgradedAddress(
        isNode(channel.image) ? text(channel.image.url) : "",
        feedUrl,
      ),
      entries: list(channel.item)
        .filter(isNode)
        .map((item) => parseRssItem(item, feedUrl)),
    };
  } else if (isNode(rdf)) {
    const channel = isNode(rdf.channel) ? rdf.channel : {};
    result = {
      title: displayText(channel.title),
      siteUrl: upgradedAddress(text(channel.link), feedUrl),
      iconUrl: "",
      entries: list(rdf.item)
        .filter(isNode)
        .map((item) => parseRssItem(item, feedUrl)),
    };
  } else if (isNode(atom)) {
    result = {
      title: displayText(atom.title),
      siteUrl: upgradedAddress(atomHref(list(atom.link)), feedUrl),
      iconUrl: upgradedAddress(text(atom.icon) || text(atom.logo), feedUrl),
      entries: list(atom.entry)
        .filter(isNode)
        .map((entry) => parseAtomEntry(entry, feedUrl)),
    };
  } else {
    throw new Error("not a feed");
  }
  return result;
}

/**
 * Parse RSS 2.0, RSS 1.0, Atom or JSON Feed text.
 *
 * Relative links are resolved against `feedUrl`. An entry whose date is missing
 * or unreadable has `published` zero. Throws when the text is none of the
 * supported formats.
 */
export function parseFeed(source: string, feedUrl: string): ParsedFeed {
  const trimmed = source.trim();
  let result: ParsedFeed;
  if (trimmed.startsWith("{")) {
    const json: unknown = JSON.parse(trimmed);
    if (!(isNode(json) && isJsonFeed(json))) {
      throw new Error("not a feed");
    }
    result = parseJsonFeed(json, feedUrl);
  } else {
    result = parseXml(trimmed, feedUrl);
  }
  return {
    ...result,
    entries: result.entries.filter((entry) => entry.guid !== ""),
  };
}
