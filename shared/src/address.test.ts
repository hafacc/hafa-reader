import { expect, test } from "bun:test";
import { secureAddress, upgradedAddress } from "./address.ts";

test("only https addresses are kept", () => {
  expect(secureAddress("/a?b=1", "https://s.example/feed")).toBe(
    "https://s.example/a?b=1",
  );
  expect(secureAddress("http://s.example/a")).toBe("");
  expect(secureAddress("/a", "http://s.example/feed")).toBe("");
  expect(secureAddress("javascript:alert(1)")).toBe("");
  expect(secureAddress("")).toBe("");
  expect(secureAddress("not an address")).toBe("");
});

test("typed and discovered http addresses are upgraded to https", () => {
  expect(upgradedAddress("http://s.example:8080/feed")).toBe(
    "https://s.example:8080/feed",
  );
  expect(upgradedAddress("/feed", "http://s.example/")).toBe(
    "https://s.example/feed",
  );
  expect(upgradedAddress("https://s.example/feed")).toBe(
    "https://s.example/feed",
  );
  expect(upgradedAddress("ftp://s.example/feed")).toBe("");
});
