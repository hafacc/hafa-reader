import { expect, test } from "bun:test";
import { hasLocalData } from "./host.ts";

test("removals alone count as an account's data", () => {
  const empty = {
    feeds: [],
    entries: [],
    settings: {},
    tombstones: new Map<string, number>(),
  };
  expect(hasLocalData(empty)).toBe(false);
  expect(hasLocalData({ ...empty, tombstones: new Map([["feed:x", 1]]) })).toBe(
    true,
  );
});
