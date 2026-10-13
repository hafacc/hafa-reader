import { expect, test } from "bun:test";
import { allowedOrigin } from "./origin.ts";

const PATTERNS = ["http://localhost/*", "https://reader.hafa.cc/*"];

test("the web app's origins are let in", () => {
  expect(allowedOrigin("https://reader.hafa.cc", PATTERNS)).toBe(true);
  expect(allowedOrigin("http://localhost:5173", PATTERNS)).toBe(true);
});

test("other pages are turned away", () => {
  expect(allowedOrigin("https://evil.example", PATTERNS)).toBe(false);
  expect(allowedOrigin("http://reader.hafa.cc", PATTERNS)).toBe(false);
  expect(allowedOrigin("https://reader.hafa.cc.evil.example", PATTERNS)).toBe(
    false,
  );
  expect(allowedOrigin(undefined, PATTERNS)).toBe(false);
  expect(allowedOrigin("not an origin", PATTERNS)).toBe(false);
});

test("the store build, without localhost, turns local pages away", () => {
  expect(allowedOrigin("http://localhost:5173", PATTERNS.slice(1))).toBe(false);
});

test("a pattern with a port matches only that port", () => {
  expect(
    allowedOrigin("http://localhost:5173", ["http://localhost:4000/*"]),
  ).toBe(false);
});
