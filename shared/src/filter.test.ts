import { expect, test } from "bun:test";
import {
  DEFAULT_SETTINGS,
  type FilterEntry,
  hasWord,
  keeper,
  passes,
  topicCounts,
  type ViewSettings,
} from "./filter.ts";

const story: FilterEntry = {
  title: "Senate Passes Budget After Long Night",
  summary: "Lawmakers ended a standoff over spending.",
  categories: ["U.S.", "Politics"],
};

function settings(change: Partial<ViewSettings>): ViewSettings {
  return { ...DEFAULT_SETTINGS, ...change };
}

test("with nothing set everything stays", () => {
  expect(passes(DEFAULT_SETTINGS, story)).toBe(true);
});

test("hide words remove articles whose title or summary has one", () => {
  expect(passes(settings({ hideWords: ["budget"] }), story)).toBe(false);
  expect(passes(settings({ hideWords: ["standoff"] }), story)).toBe(false);
  expect(passes(settings({ hideWords: ["sports"] }), story)).toBe(true);
});

test("words match whole words and ignore capitals", () => {
  expect(hasWord(["bud"], story)).toBe(false);
  expect(hasWord(["SENATE passes"], story)).toBe(true);
  expect(hasWord(["long night"], story)).toBe(true);
});

test("spaces match any whitespace and boundaries sit only at word ends", () => {
  const spaced = { ...story, title: "Senate  passes budget\tafter" };
  expect(hasWord(["passes budget after"], spaced)).toBe(true);
  const abbreviation = { ...story, title: "Plans for the U.S.A. tour" };
  expect(hasWord(["u.s."], abbreviation)).toBe(true);
  expect(hasWord([".s.a"], abbreviation)).toBe(true);
  expect(hasWord(["plan"], abbreviation)).toBe(false);
  expect(hasWord(["a tour"], abbreviation)).toBe(false);
});

test("any of several words is enough, and blank ones count for nothing", () => {
  expect(hasWord(["poll", "budget", "crossword"], story)).toBe(true);
  expect(hasWord(["poll", "quiz"], story)).toBe(false);
  expect(hasWord(["", "  "], story)).toBe(false);
  expect(passes(settings({ onlyWords: ["", " "] }), story)).toBe(false);
});

test("only words keep articles that have one of them", () => {
  expect(passes(settings({ onlyWords: ["world", "senate"] }), story)).toBe(
    true,
  );
  expect(passes(settings({ onlyWords: ["world"] }), story)).toBe(false);
});

test("hide wins over only", () => {
  expect(
    passes(settings({ onlyWords: ["senate"], hideWords: ["budget"] }), story),
  ).toBe(false);
});

test("an article is hidden when all of its topics are off", () => {
  expect(passes(settings({ offTopics: ["politics"] }), story)).toBe(true);
  expect(passes(settings({ offTopics: ["Politics", "u.s."] }), story)).toBe(
    false,
  );
  const untagged = { ...story, categories: [] };
  expect(passes(settings({ offTopics: ["politics"] }), untagged)).toBe(true);
});

test("topics are counted, commonest first", () => {
  const other = { ...story, categories: ["Politics", "Business"] };
  expect(topicCounts([story, other, other])).toEqual([
    { topic: "Politics", count: 3 },
    { topic: "Business", count: 2 },
    { topic: "U.S.", count: 1 },
  ]);
});

test("a list of words is compiled once and reused", () => {
  const OriginalRegExp = RegExp;
  let built = 0;
  globalThis.RegExp = new Proxy(OriginalRegExp, {
    construct(target, args) {
      built += 1;
      return Reflect.construct(target, args);
    },
  });
  try {
    for (let index = 0; index < 50; index += 1) {
      hasWord(["reuse check"], story);
    }
  } finally {
    globalThis.RegExp = OriginalRegExp;
  }
  expect(built).toBe(1);
});

test("a built filter answers as passes does", () => {
  const cases: [ViewSettings, FilterEntry][] = [
    [DEFAULT_SETTINGS, story],
    [{ ...DEFAULT_SETTINGS, hideWords: ["budget"] }, story],
    [{ ...DEFAULT_SETTINGS, onlyWords: ["senate"] }, story],
    [{ ...DEFAULT_SETTINGS, onlyWords: ["weather"] }, story],
    [
      { ...DEFAULT_SETTINGS, offTopics: ["Politics"] },
      { ...story, categories: ["politics "] },
    ],
    [
      { ...DEFAULT_SETTINGS, offTopics: ["Politics"] },
      { ...story, categories: [] },
    ],
  ];
  for (const [chosen, entry] of cases) {
    expect(keeper(chosen)(entry)).toBe(passes(chosen, entry));
  }
});
