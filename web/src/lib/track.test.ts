import { expect, test } from "bun:test";
import type { FetchOutcome, TrackedFeed } from "$shared/protocol.ts";
import { trackMessages } from "./track.ts";

function answer(text: string): FetchOutcome {
  return {
    status: 200,
    url: "https://feed.example/rss",
    text,
    etag: "",
    lastModified: "",
    hint: 0,
    insecure: false,
  };
}

const FEEDS: TrackedFeed[] = ["a", "b", "c", "d"].map((id) => ({
  id,
  url: `https://${id}.example/rss`,
  empty: false,
}));

function firstsOf(message: TrackedFeed[]): string[] {
  return message.flatMap((feed) => (feed.first ? [feed.id] : []));
}

test("without answers the feeds go in one message", () => {
  expect(trackMessages(FEEDS, new Map())).toEqual([FEEDS]);
  expect(trackMessages([], new Map())).toEqual([[]]);
});

test("answers that fit go in one message with every feed", () => {
  const messages = trackMessages(
    FEEDS,
    new Map([
      ["a", answer("x")],
      ["c", answer("y")],
    ]),
  );
  expect(messages).toHaveLength(1);
  expect(messages[0]?.map((feed) => feed.id)).toEqual(["a", "b", "c", "d"]);
  expect(messages[0]?.map((feed) => firstsOf([feed]))).toEqual([
    ["a"],
    [],
    ["c"],
    [],
  ]);
});

test("answers too big for one message are spread over several, each listing every feed", () => {
  const big = answer("x".repeat(600));
  const size = JSON.stringify(big).length;
  const messages = trackMessages(
    FEEDS,
    new Map([
      ["a", big],
      ["b", big],
      ["c", big],
      // an answer over the budget still goes, alone
      ["d", answer("x".repeat(3000))],
    ]),
    size * 2,
  );
  expect(messages.map(firstsOf)).toEqual([["a", "b"], ["c"], ["d"]]);
  for (const message of messages) {
    expect(message.map((feed) => feed.id)).toEqual(["a", "b", "c", "d"]);
  }
});
