import { describe, expect, test } from "bun:test";
import {
  freshState,
  listenFrom,
  needsChoice,
  parseState,
  stateKey,
} from "./state.ts";

describe("state", () => {
  test("round trip", () => {
    const state = {
      cursor: 5,
      merged: true,
      page: { published: 3, id: "x" },
      pending: ["a"],
    };
    expect(parseState(JSON.stringify(state))).toEqual(state);
  });

  test("anything unreadable starts over", () => {
    expect(parseState(null)).toEqual(freshState());
    expect(parseState("{")).toEqual(freshState());
    expect(parseState('{"cursor":"x","pending":[1,"b"]}')).toEqual({
      ...freshState(),
      pending: ["b"],
    });
  });

  test("each account has its own key", () => {
    expect(stateKey("one")).not.toBe(stateKey("two"));
  });
});

test("the entry listener starts an overlap before the cursor", () => {
  expect(listenFrom(7000, 1000)).toBe(6000);
  expect(listenFrom(500, 1000)).toBe(0);
  expect(listenFrom(0, 1000)).toBe(0);
});

test("a different account waits for a choice only when this device holds data", () => {
  expect(needsChoice("a", "b", true)).toBe(true);
  expect(needsChoice("a", "b", false)).toBe(false);
  expect(needsChoice("a", "a", true)).toBe(false);
  expect(needsChoice("", "b", true)).toBe(false);
});
