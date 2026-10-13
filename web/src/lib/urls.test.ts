import { expect, test } from "bun:test";
import { isWebAddress, normalizeUrl } from "./urls.ts";

test("normalizeUrl adds https, upgrades http and refuses other schemes", () => {
  expect(normalizeUrl(" example.com/feed ")).toBe("https://example.com/feed");
  expect(normalizeUrl("http://example.com/rss")).toBe(
    "https://example.com/rss",
  );
  expect(normalizeUrl("HTTP://Example.com:8080/a")).toBe(
    "https://example.com:8080/a",
  );
  expect(normalizeUrl("ftp://example.com/rss")).toBe("");
  expect(normalizeUrl("")).toBe("");
});

test("only https addresses are web addresses", () => {
  expect(isWebAddress("https://example.com/")).toBe(true);
  expect(isWebAddress("http://example.com/")).toBe(false);
  expect(isWebAddress(normalizeUrl("http://example.com"))).toBe(true);
  expect(isWebAddress("")).toBe(false);
});
