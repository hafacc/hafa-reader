import { expect, test } from "bun:test";
import { type ParsedFeed, parseFeed } from "./feed.ts";
import { entryId, feedId } from "./id.ts";

/** `fixtures/parse.json`: feed files and what parsing each must give. */
interface ParseFixture {
  cases: { input: { file: string; feedUrl: string }; output: ParsedFeed }[];
}

/** `fixtures/ids.json`: addresses and guids with their literal ids. */
interface IdsFixture {
  feeds: { input: { url: string }; output: string }[];
  entries: { input: { feedId: string; guid: string }; output: string }[];
}

function fixtureFile(name: string): ReturnType<typeof Bun.file> {
  return Bun.file(new URL(`../fixtures/${name}`, import.meta.url));
}

const parseCases: ParseFixture = await fixtureFile("parse.json").json();
const idCases: IdsFixture = await fixtureFile("ids.json").json();

test.each(parseCases.cases.map((item) => [item.input.file, item] as const))(
  "parses %s as the fixture expects",
  async (_name, item) => {
    const source = await fixtureFile(item.input.file).text();
    expect(parseFeed(source, item.input.feedUrl)).toEqual(item.output);
  },
);

test("feed ids match the fixture", async () => {
  for (const item of idCases.feeds) {
    // biome-ignore lint/performance/noAwaitInLoops: a handful of hashes
    expect(await feedId(item.input.url)).toBe(item.output);
  }
});

test("entry ids match the fixture", async () => {
  for (const item of idCases.entries) {
    // biome-ignore lint/performance/noAwaitInLoops: a handful of hashes
    expect(await entryId(item.input.feedId, item.input.guid)).toBe(item.output);
  }
});
