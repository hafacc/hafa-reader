import { expect, test } from "bun:test";
import { parseFeed } from "./feed.ts";

async function fixture(name: string): Promise<string> {
  return await Bun.file(new URL(`../fixtures/${name}`, import.meta.url)).text();
}

test("parses RSS 2.0 with content, media and relative links", async () => {
  const feed = parseFeed(await fixture("rss.xml"), "https://example.com/rss");
  expect(feed.title).toBe("Example Blog");
  expect(feed.siteUrl).toBe("https://example.com/");
  expect(feed.iconUrl).toBe("https://example.com/icon.png");
  expect(feed.entries).toHaveLength(2);
  const [first, second] = feed.entries;
  expect(first?.guid).toBe("post-1");
  expect(first?.url).toBe("https://example.com/posts/first");
  expect(first?.author).toBe("Ada");
  expect(first?.summary).toBe("Short & sweet summary");
  expect(first?.categories).toEqual(["Elections", "World"]);
  expect(first?.published).toBe(Date.parse("2026-10-06T09:14:00Z"));
  expect(first?.html).toContain("Full <b>body</b>");
  expect(first?.imageUrl).toBe("https://example.com/img/a.png");
  expect(second?.guid).toBe("https://example.com/posts/second");
  expect(second?.imageUrl).toBe("https://example.com/thumb.jpg");
  expect(second?.published).toBe(0);
});

test("parses Atom", async () => {
  const feed = parseFeed(
    await fixture("atom.xml"),
    "https://atom.example.org/feed.xml",
  );
  expect(feed.title).toBe("Atom Example");
  expect(feed.siteUrl).toBe("https://atom.example.org/");
  expect(feed.iconUrl).toBe("https://atom.example.org/atom.ico");
  const [entry] = feed.entries;
  expect(entry?.guid).toBe("tag:atom.example.org,2026:1");
  expect(entry?.url).toBe("https://atom.example.org/entries/1");
  expect(entry?.author).toBe("Grace");
  expect(entry?.html).toBe("<p>Hello</p>");
});

test("parses JSON Feed", async () => {
  const feed = parseFeed(
    await fixture("feed.json"),
    "https://json.example.net/feed.json",
  );
  expect(feed.title).toBe("JSON Example");
  const [entry] = feed.entries;
  expect(entry?.guid).toBe("42");
  expect(entry?.imageUrl).toBe("https://json.example.net/42.png");
  expect(entry?.author).toBe("Douglas");
});

test("rejects text that is not a feed", () => {
  expect(() =>
    parseFeed("<html><body>hi</body></html>", "https://x.test/"),
  ).toThrow();
});

test("only web addresses are kept as links and images", () => {
  const source = `<rss><channel><title>t</title><link>javascript:alert(1)</link>
    <item><title>x</title><link>javascript:alert(1)</link><guid>g1</guid></item>
    <item><title>y</title><link>https://ok.example/y</link><enclosure url="data:image/png;base64,AA" type="image/png"/></item>
    </channel></rss>`;
  const feed = parseFeed(source, "https://ok.example/feed");
  expect(feed.siteUrl).toBe("");
  expect(feed.entries[0]?.url).toBe("");
  expect(feed.entries[1]?.url).toBe("https://ok.example/y");
  expect(feed.entries[1]?.imageUrl).toBe("");
});

test("a site link survives an Atom link placed before it, and xhtml bodies are not empty", () => {
  const rss = `<rss xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>t</title>
    <atom:link href="https://w.example/feed" rel="self"/><link>https://w.example/</link></channel></rss>`;
  expect(parseFeed(rss, "https://w.example/feed").siteUrl).toBe(
    "https://w.example/",
  );
  const atom = `<feed xmlns="http://www.w3.org/2005/Atom"><title>a</title><entry><id>1</id><title>t</title>
    <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Hello</p><p>there</p></div></content></entry></feed>`;
  expect(parseFeed(atom, "https://a.example/feed").entries[0]?.html).toContain(
    "Hello",
  );
});

test("items that identify nothing are dropped and untitled ones keep a stable id", () => {
  const source = `<rss><channel><title>t</title><item><description>no id at all</description></item>
    <item><title>Same title</title></item></channel></rss>`;
  const first = parseFeed(source, "https://x.example/feed");
  const second = parseFeed(source, "https://x.example/feed");
  expect(first.entries).toHaveLength(1);
  expect(first.entries[0]?.guid).toBe(second.entries[0]?.guid);
});

test("an impossible character code does not spoil the feed", () => {
  const source = `<rss><channel><title>t</title><item><title>x</title><guid>1</guid>
    <description><![CDATA[bad &#99999999; and &#x110000; but fine &#233;]]></description></item></channel></rss>`;
  const feed = parseFeed(source, "https://x.example/feed");
  expect(feed.entries).toHaveLength(1);
  expect(feed.entries[0]?.summary).toContain("fine é");
});

test("Atom and JSON summaries are the feed's own summary, not the whole body", () => {
  const atom = `<feed xmlns="http://www.w3.org/2005/Atom"><title>a</title><entry><id>1</id><title>t</title>
    <content type="html">&lt;p&gt;long body&lt;/p&gt;</content></entry></feed>`;
  expect(parseFeed(atom, "https://a.example/feed").entries[0]?.summary).toBe(
    "",
  );
});

test("dates come from the usual fields and from common time zone names", () => {
  const source = `<rss xmlns:a10="http://www.w3.org/2005/Atom"><channel><title>t</title>
    <item><title>a</title><guid>a</guid><a10:updated>2026-10-06T09:14:00Z</a10:updated></item>
    <item><title>b</title><guid>b</guid><pubDate>Tue, 06 Oct 2026 11:14:00 CEST</pubDate></item>
    <item><title>c</title><guid>c</guid><pubDate>sometime last week</pubDate></item>
    </channel></rss>`;
  const [first, second, third] = parseFeed(
    source,
    "https://x.example/feed",
  ).entries;
  expect(first?.published).toBe(Date.parse("2026-10-06T09:14:00Z"));
  expect(second?.published).toBe(Date.parse("2026-10-06T09:14:00Z"));
  expect(third?.published).toBe(0);
});

test("a JSON Feed item with a numeric id keeps it as its guid", () => {
  const source =
    '{"version":"https://jsonfeed.org/version/1.1","items":[{"id":42,"content_text":"x"}]}';
  const feed = parseFeed(source, "https://j.example/feed.json");
  expect(feed.entries).toHaveLength(1);
  expect(feed.entries[0]?.guid).toBe("42");
});

test("JSON Feed plain text is shown as text, with its line breaks", () => {
  const source = `{"version":"https://jsonfeed.org/version/1.1","items":[{"id":"1","content_text":"Use <b> for bold\\nand & more"}]}`;
  expect(
    parseFeed(source, "https://j.example/feed.json").entries[0]?.html,
  ).toBe("Use &lt;b&gt; for bold<br>and &amp; more");
});

test("JSON that is not a JSON Feed is not a feed", () => {
  expect(() =>
    parseFeed('{"title":"api","data":[1,2]}', "https://j.example/api"),
  ).toThrow();
  expect(
    parseFeed('{"items":[]}', "https://j.example/feed.json").entries,
  ).toEqual([]);
});

test("Atom XHTML content keeps its markup and all its text", () => {
  const atom = `<feed xmlns="http://www.w3.org/2005/Atom"><title>a</title><entry><id>1</id><title>t</title>
    <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Hello <a href="/x">link</a> &amp; more</p></div></content>
    <media:content xmlns:media="http://search.yahoo.com/mrss/" url="https://a.example/i.png" medium="image"/></entry></feed>`;
  const [entry] = parseFeed(atom, "https://a.example/feed").entries;
  expect(entry?.html).toContain(
    '<p>Hello <a href="/x">link</a> &amp; more</p>',
  );
  expect(entry?.imageUrl).toBe("https://a.example/i.png");
});

test("image addresses taken from article HTML have their entities decoded", () => {
  const rss = `<rss><channel><title>t</title>
    <item><guid>1</guid><description><![CDATA[<img src="https://i.example/x.png?w=1&amp;h=2">]]></description></item>
    <item><guid>2</guid><description>&lt;img src="https://i.example/y.png?w=1&amp;amp;h=2&amp;#38;q=3"&gt;</description></item>
    <item><guid>3</guid><enclosure type="image/png" url="https://i.example/e.png?w=1&amp;h=2"/></item>
    </channel></rss>`;
  expect(
    parseFeed(rss, "https://s.example/feed").entries.map(
      (item) => item.imageUrl,
    ),
  ).toEqual([
    "https://i.example/x.png?w=1&h=2",
    "https://i.example/y.png?w=1&h=2&q=3",
    "https://i.example/e.png?w=1&h=2",
  ]);
});

test("numeric character references are decoded in titles, authors and topics but not guids", () => {
  const rss = `<rss><channel><title>Tom&#8217;s &amp;amp; blog</title>
    <item><title>It&#8217;s &#x2014; done</title><guid>post&#8217;1</guid><author>Ren&#233;e</author><category>Q&#38;A</category></item>
    <item><title>Only&#8217;title</title></item>
    </channel></rss>`;
  const feed = parseFeed(rss, "https://s.example/feed");
  expect(feed.title).toBe("Tom’s &amp; blog");
  const [first, second] = feed.entries;
  expect(first?.title).toBe("It’s — done");
  expect(first?.author).toBe("Renée");
  expect(first?.categories).toEqual(["Q&A"]);
  expect(first?.guid).toBe("post&#8217;1");
  expect(second?.title).toBe("Only’title");
  expect(second?.guid).toBe("Only&#8217;title");
  const atom = `<feed xmlns="http://www.w3.org/2005/Atom"><title>A&#8217;s</title><entry><id>1</id><title>B&#8217;s</title><author><name>C&#8217;s</name></author></entry></feed>`;
  const parsedAtom = parseFeed(atom, "https://a.example/feed");
  expect(parsedAtom.title).toBe("A’s");
  expect(parsedAtom.entries[0]?.title).toBe("B’s");
  expect(parsedAtom.entries[0]?.author).toBe("C’s");
});

test("plain http addresses in a feed are upgraded to https, other schemes dropped, and guids from links are upgraded too", () => {
  const rss = `<rss><channel><title>t</title><link>http://s.example/</link><image><url>http://s.example/i.png</url></image>
    <item><title>a</title><link>http://s.example/a</link><enclosure type="image/png" url="http://s.example/e.png"/></item>
    <item><title>b</title><link>javascript:alert(1)</link>
    <description><![CDATA[<img src="http://s.example/x.png">]]></description></item>
    <item><title>c</title><guid>c</guid><link>/c</link></item>
    </channel></rss>`;
  const feed = parseFeed(rss, "http://s.example/feed");
  expect(feed.siteUrl).toBe("https://s.example/");
  expect(feed.iconUrl).toBe("https://s.example/i.png");
  const [first, second, third] = feed.entries;
  expect(first?.url).toBe("https://s.example/a");
  expect(first?.guid).toBe("https://s.example/a");
  expect(first?.imageUrl).toBe("https://s.example/e.png");
  expect(second?.url).toBe("");
  expect(second?.imageUrl).toBe("https://s.example/x.png");
  expect(third?.url).toBe("https://s.example/c");
});
