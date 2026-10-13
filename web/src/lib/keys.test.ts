import { expect, test } from "bun:test";
import { shortcutKey } from "./keys.ts";

test("a letter's case follows Shift, not Caps Lock", () => {
  expect(shortcutKey("A", false)).toBe("a");
  expect(shortcutKey("a", true)).toBe("A");
  expect(shortcutKey("A", true)).toBe("A");
  expect(shortcutKey("r", false)).toBe("r");
});

test("other keys pass through", () => {
  expect(shortcutKey("Escape", false)).toBe("Escape");
  expect(shortcutKey("?", true)).toBe("?");
  expect(shortcutKey("[", false)).toBe("[");
});
