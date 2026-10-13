/** A feed listed in an OPML file. */
export interface OpmlFeed {
  /** Feed address. */
  url: string;
  /** Feed name; empty when the file gives none. */
  title: string;
  /** Group name; empty for none. */
  folder: string;
}

/** An attribute's value whatever the case of its name, as exporters vary; undefined when absent. */
function attribute(element: Element, name: string): string | undefined {
  const wanted = name.toLowerCase();
  return Array.from(element.attributes).find(
    (candidate) => candidate.name.toLowerCase() === wanted,
  )?.value;
}

function collect(parent: Element, folder: string, feeds: OpmlFeed[]): void {
  for (const child of Array.from(parent.children)) {
    if (child.tagName.toLowerCase() === "outline") {
      const url = attribute(child, "xmlUrl") ?? "";
      // an empty title falls back to the text, as exporters fill either
      const title =
        [attribute(child, "title"), attribute(child, "text")]
          .map((value) => value?.trim() ?? "")
          .find((value) => value !== "") ?? "";
      if (url === "") {
        collect(child, title, feeds);
      } else {
        feeds.push({ url, title, folder });
        // the lenient parse nests outlines that follow an unclosed one inside it
        collect(child, folder, feeds);
      }
    } else {
      // wrappers such as body, which the lenient parse can move or drop
      collect(child, folder, feeds);
    }
  }
}

/**
 * List the feeds in OPML text, with the folder each sits in. Text that is
 * not well-formed XML is read the way a browser reads HTML, which recovers
 * the outlines of most damaged files.
 */
export function parseOpml(source: string): OpmlFeed[] {
  const parser = new DOMParser();
  let document = parser.parseFromString(source, "text/xml");
  if (document.querySelector("parsererror") !== null) {
    document = parser.parseFromString(source, "text/html");
  }
  const feeds: OpmlFeed[] = [];
  const root = document.querySelector("opml") ?? document.documentElement;
  if (root !== null) {
    collect(root, "", feeds);
  }
  return feeds;
}

function escapeAttribute(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function outlineOf(feed: OpmlFeed, indent: string): string {
  const title = escapeAttribute(feed.title);
  return `${indent}<outline type="rss" text="${title}" title="${title}" xmlUrl="${escapeAttribute(feed.url)}"/>`;
}

/** Write feeds as OPML text, listing each group's feeds inside a folder outline. */
export function buildOpml(feeds: OpmlFeed[]): string {
  const folders = [
    ...new Set(feeds.map((feed) => feed.folder).filter((name) => name !== "")),
  ].sort((first, second) => first.localeCompare(second));
  const lines = folders.map((name) => {
    const inside = feeds
      .filter((feed) => feed.folder === name)
      .map((feed) => outlineOf(feed, "      "));
    const label = escapeAttribute(name);
    return [
      `    <outline text="${label}" title="${label}">`,
      ...inside,
      "    </outline>",
    ].join("\n");
  });
  const loose = feeds
    .filter((feed) => feed.folder === "")
    .map((feed) => outlineOf(feed, "    "));
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<opml version="2.0">',
    "  <head><title>Reader</title></head>",
    "  <body>",
    ...lines,
    ...loose,
    "  </body>",
    "</opml>",
    "",
  ].join("\n");
}
