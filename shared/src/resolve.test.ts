import { expect, test } from "bun:test";
import {
  discoverFeedLinks,
  discoverIconLinks,
  feedCandidates,
  knownFeedUrls,
} from "./resolve.ts";

function known(address: string): string[] {
  return knownFeedUrls(new URL(address));
}

test("services with a feed address pattern", () => {
  expect(
    known("https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"),
  ).toEqual([
    "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ",
  ]);
  expect(known("https://www.youtube.com/playlist?list=PL123")).toEqual([
    "https://www.youtube.com/feeds/videos.xml?playlist_id=PL123",
  ]);
  expect(known("https://www.reddit.com/r/rust/")).toEqual([
    "https://www.reddit.com/r/rust/.rss",
  ]);
  expect(known("https://github.com/rust-lang/rust")).toEqual([
    "https://github.com/rust-lang/rust/releases.atom",
    "https://github.com/rust-lang/rust/commits.atom",
  ]);
  expect(known("https://medium.com/@someone")).toEqual([
    "https://medium.com/feed/@someone",
  ]);
  expect(known("https://writer.medium.com/")).toEqual([
    "https://writer.medium.com/feed",
  ]);
  expect(known("https://staff.tumblr.com/")).toEqual([
    "https://staff.tumblr.com/rss",
  ]);
  expect(known("https://bsky.app/profile/someone.bsky.social")).toEqual([
    "https://bsky.app/profile/someone.bsky.social/rss",
  ]);
  expect(known("https://mastodon.social/@Gargron")).toEqual([
    "https://mastodon.social/@Gargron.rss",
  ]);
});

test("pages with no known pattern give nothing", () => {
  expect(known("https://example.com/about")).toEqual([]);
});

test("finds feed links in a page head", () => {
  const html = `<head>
    <link rel="stylesheet" href="/a.css">
    <link rel="alternate" type="application/rss+xml" title="Posts" href="/posts.xml">
    <link href='https://other.example/atom?x=1&amp;y=2' type='application/atom+xml' rel='alternate'>
  </head>`;
  expect(discoverFeedLinks(html, "https://example.com/blog/")).toEqual([
    "https://example.com/posts.xml",
    "https://other.example/atom?x=1&y=2",
  ]);
});

test("candidates start with the address itself and drop repeats", () => {
  const found = feedCandidates("https://example.com/");
  expect(found[0]).toBe("https://example.com/");
  expect(found).toContain("https://example.com/feed");
  expect(new Set(found).size).toBe(found.length);
  const reddit = feedCandidates("https://www.reddit.com/r/rust/");
  expect(reddit[1]).toBe("https://www.reddit.com/r/rust/.rss");
});

test("finds a site's pictures, touch icons first and web addresses only", () => {
  const html = `<head>
    <link rel="shortcut icon" href="/old.ico">
    <link rel="icon" type="image/png" href="/icon.png">
    <link rel="apple-touch-icon" sizes="180x180" href="/touch.png">
    <link rel="icon" href="/logo.svg">
    <link rel="icon" href="javascript:alert(1)">
    <link rel="stylesheet" href="/a.css">
  </head>`;
  expect(discoverIconLinks(html, "https://example.com/blog/")).toEqual([
    "https://example.com/touch.png",
    "https://example.com/logo.svg",
    "https://example.com/icon.png",
    "https://example.com/old.ico",
  ]);
  expect(discoverIconLinks("<head></head>", "https://example.com/")).toEqual(
    [],
  );
});

test("finds feed links whose attributes are not quoted", () => {
  const html =
    "<head><link rel=alternate type=application/rss+xml href=/index.xml></head>";
  expect(discoverFeedLinks(html, "https://example.com/")).toEqual([
    "https://example.com/index.xml",
  ]);
});

test("discovery upgrades http to https and drops other schemes", () => {
  const html = `<head>
    <link rel="alternate" type="application/rss+xml" href="http://s.example/rss">
    <link rel="alternate" type="application/atom+xml" href="javascript:alert(1)">
    <link rel="icon" href="http://s.example/i.png">
  </head>`;
  expect(discoverFeedLinks(html, "https://s.example/")).toEqual([
    "https://s.example/rss",
  ]);
  expect(discoverIconLinks(html, "https://s.example/")).toEqual([
    "https://s.example/i.png",
  ]);
  const found = feedCandidates("http://s.example/blog");
  expect(found[0]).toBe("https://s.example/blog");
  expect(found.every((address) => address.startsWith("https://"))).toBe(true);
  expect(() => feedCandidates("ftp://s.example/")).toThrow();
});
