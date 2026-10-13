import { expect, test } from "bun:test";
import { showsListEnd } from "./list-end.ts";

test("the end footer shows once every row is drawn", () => {
  expect(showsListEnd(60, 12)).toBe(true);
  expect(showsListEnd(120, 120)).toBe(true);
});

test("the end footer hides while rows remain to draw, and for an empty list", () => {
  expect(showsListEnd(60, 61)).toBe(false);
  expect(showsListEnd(60, 0)).toBe(false);
});
