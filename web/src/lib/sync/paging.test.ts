import { describe, expect, test } from "bun:test";
import { sampleEntry } from "./fixtures.ts";
import { chunks, recentCutoff, settledRange, unlisted } from "./paging.ts";

describe("paging", () => {
  test("recent means the last 90 days", () => {
    expect(recentCutoff(90 * 24 * 60 * 60 * 1000)).toBe(0);
  });

  test("a full page leaves its last time open", () => {
    const range = settledRange(50, [{ published: 40 }, { published: 30 }], 2);
    expect(range).toEqual({ low: 30, high: 50 });
  });

  test("a short page settles everything older", () => {
    const range = settledRange(50, [{ published: 40 }], 2);
    expect(range.low).toBe(Number.NEGATIVE_INFINITY);
  });

  test("unlisted finds local entries the account lacks in the range", () => {
    const entries = [10, 30, 40, 60].map((published) =>
      sampleEntry({ id: `at-${published}`, published }),
    );
    const found = unlisted(entries, new Set(["at-40"]), { low: 30, high: 50 });
    expect(found.map((entry) => entry.id)).toEqual([]);
    const wider = unlisted(entries, new Set(["at-40"]), {
      low: Number.NEGATIVE_INFINITY,
      high: 50,
    });
    expect(wider.map((entry) => entry.id)).toEqual(["at-10", "at-30"]);
  });

  test("chunks", () => {
    expect(chunks([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
    expect(chunks([], 2)).toEqual([]);
  });
});
