import { describe, expect, test } from "bun:test";
import { buildOpml, type OpmlFeed, parseOpml } from "./opml.ts";

const SAMPLE = `<?xml version="1.0"?>
<opml version="1.0">
  <head><title>Old reader</title></head>
  <body>
    <outline text="Tech">
      <outline text="Rust Blog" xmlUrl="https://blog.rust-lang.org/feed.xml"/>
      <outline title="Julia &amp; friends" text="ignored" xmlUrl="https://jvns.ca/atom.xml"/>
    </outline>
    <outline text="xkcd" xmlUrl="https://xkcd.com/atom.xml" htmlUrl="https://xkcd.com/"/>
    <outline text="No address"/>
  </body>
</opml>`;

describe("parseOpml", () => {
  test("lists feeds with their folder, preferring title over text", () => {
    expect(parseOpml(SAMPLE)).toEqual([
      {
        url: "https://blog.rust-lang.org/feed.xml",
        title: "Rust Blog",
        folder: "Tech",
        siteUrl: "",
      },
      {
        url: "https://jvns.ca/atom.xml",
        title: "Julia & friends",
        folder: "Tech",
        siteUrl: "",
      },
      {
        url: "https://xkcd.com/atom.xml",
        title: "xkcd",
        folder: "",
        siteUrl: "https://xkcd.com/",
      },
    ]);
  });

  test("reads attribute names in any case", () => {
    expect(
      parseOpml(
        '<opml><body><outline TEXT="Loud" XMLURL="https://loud.example/feed"/><outline text="Low" xmlurl="https://low.example/feed"/></body></opml>',
      ),
    ).toEqual([
      {
        url: "https://loud.example/feed",
        title: "Loud",
        folder: "",
        siteUrl: "",
      },
      {
        url: "https://low.example/feed",
        title: "Low",
        folder: "",
        siteUrl: "",
      },
    ]);
  });

  test("recovers the feeds of a damaged file", () => {
    const damaged = `<opml><body>
      <outline text="Tech">
        <outline text="A & B" xmlUrl="https://ab.example/feed"/>
        <outline text="C" xmlUrl="https://c.example/feed?x=1&y=2"/>
      </outline>
      <outline text="Loose" xmlUrl="https://loose.example/feed">
    </body>`;
    // the lenient parse ignores self-closing outlines, so folders after the
    // damage can be off; the feeds themselves are what must come through
    const parsed = parseOpml(damaged);
    expect(parsed.slice(0, 2).map((feed) => feed.folder)).toEqual([
      "Tech",
      "Tech",
    ]);
    expect(parsed.map(({ url, title }) => ({ url, title }))).toEqual([
      { url: "https://ab.example/feed", title: "A & B" },
      { url: "https://c.example/feed?x=1&y=2", title: "C" },
      { url: "https://loose.example/feed", title: "Loose" },
    ]);
  });

  test("an empty title falls back to the text", () => {
    expect(
      parseOpml(
        '<opml><body><outline title="" text="Tech"><outline title="" text="Rust" xmlUrl="https://r.example/feed"/></outline></body></opml>',
      ),
    ).toEqual([
      {
        url: "https://r.example/feed",
        title: "Rust",
        folder: "Tech",
        siteUrl: "",
      },
    ]);
  });

  test("a file with no body lists nothing", () => {
    expect(parseOpml("<opml><head/></opml>")).toEqual([]);
    expect(parseOpml("not xml at all")).toEqual([]);
  });
});

describe("buildOpml", () => {
  const feeds: OpmlFeed[] = [
    {
      url: "https://b.example/feed?x=1&y=2",
      title: 'B "quoted" <feed>',
      folder: "Zeta",
      siteUrl: "https://b.example/?a=1&b=2",
    },
    {
      url: "https://a.example/feed",
      title: "A",
      folder: "Alpha & Co",
      siteUrl: "",
    },
    {
      url: "https://loose.example/rss",
      title: "Loose",
      folder: "",
      siteUrl: "",
    },
  ];

  test("escapes attributes and puts folders first, sorted", () => {
    const text = buildOpml(feeds);
    expect(text).toContain('xmlUrl="https://b.example/feed?x=1&amp;y=2"');
    expect(text).toContain('htmlUrl="https://b.example/?a=1&amp;b=2"');
    expect(text).not.toContain('htmlUrl=""');
    expect(text).toContain('title="B &quot;quoted&quot; &lt;feed&gt;"');
    expect(text.indexOf("Alpha &amp; Co")).toBeLessThan(text.indexOf("Zeta"));
    expect(text.indexOf("Zeta")).toBeLessThan(text.indexOf("Loose"));
  });

  test("round trips through parseOpml", () => {
    const parsed = parseOpml(buildOpml(feeds));
    function byUrl(first: OpmlFeed, second: OpmlFeed): number {
      return first.url.localeCompare(second.url);
    }
    expect(parsed.toSorted(byUrl)).toEqual(feeds.toSorted(byUrl));
  });
});
