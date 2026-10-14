import { expect, test } from "bun:test";
import { wrappedRows } from "./topic-rows.ts";

test("items wrap when the next one would pass the available width", () => {
  expect(wrappedRows([], 100, 8)).toBe(0);
  expect(wrappedRows([46, 46], 100, 8)).toBe(1);
  expect(wrappedRows([46, 47], 100, 8)).toBe(2);
  expect(wrappedRows([40, 40, 40, 40, 40], 100, 8)).toBe(3);
});

test("an item wider than the space takes a row of its own", () => {
  expect(wrappedRows([150, 20, 20], 100, 8)).toBe(2);
});
