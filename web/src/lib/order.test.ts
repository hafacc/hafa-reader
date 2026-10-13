import { describe, expect, test } from "bun:test";
import { byCodeUnits, compareEntries } from "./order.ts";
import { sampleEntry } from "./sync/fixtures.ts";
import type { Entry } from "./types.ts";

function ids(entries: Entry[], sort: "newest" | "oldest"): string[] {
  return entries
    .toSorted((first, second) => compareEntries(first, second, sort))
    .map((entry) => entry.id);
}

describe("compareEntries", () => {
  const entries = [
    sampleEntry({ id: "z-late", published: 9, addedAt: 1, position: 3 }),
    sampleEntry({ id: "b-third", published: 5, addedAt: 1, position: 2 }),
    sampleEntry({ id: "a-first", published: 5, addedAt: 1, position: 0 }),
    sampleEntry({ id: "c-refetch", published: 5, addedAt: 4, position: 7 }),
    sampleEntry({ id: "x-tie", published: 5, addedAt: 1, position: 2 }),
  ];

  test("newest first breaks date ties by first sighting, then feed place, then id", () => {
    expect(ids(entries, "newest")).toEqual([
      "z-late",
      "c-refetch",
      "a-first",
      "b-third",
      "x-tie",
    ]);
  });

  test("synced entries with no place fall back to guid, then address", () => {
    const synced = [
      sampleEntry({ id: "a", guid: "post-2", url: "https://x/2" }),
      sampleEntry({ id: "b", guid: "post-1", url: "https://x/9" }),
      sampleEntry({ id: "c", guid: "post-1", url: "https://x/1" }),
    ];
    expect(ids(synced, "newest")).toEqual(["c", "b", "a"]);
  });

  test("oldest first is the exact reverse, whatever the load order", () => {
    const expected = ids(entries, "newest").toReversed();
    expect(ids(entries, "oldest")).toEqual(expected);
    expect(ids(entries.toReversed(), "oldest")).toEqual(expected);
  });
});

test("text compares by code units, the same on every device", () => {
  expect(["b", "B", "a", "_", "A", "-"].toSorted(byCodeUnits)).toEqual([
    "-",
    "A",
    "B",
    "_",
    "a",
    "b",
  ]);
  expect(byCodeUnits("same", "same")).toBe(0);
});
