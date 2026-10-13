import { expect, test } from "bun:test";
import { firstRun } from "./first-run.ts";

test("signed out, the empty reader asks for a feed", () => {
  expect(firstRun(false, "off", false)).toBe("add");
});

test("signed in, it shows sync until the account's feeds have arrived", () => {
  expect(firstRun(true, "connecting", false)).toBe("syncing");
  expect(firstRun(true, "syncing", false)).toBe("syncing");
  expect(firstRun(true, "synced", false)).toBe("syncing");
});

test("an empty account, or sync that cannot bring the feeds, gets the add prompt", () => {
  expect(firstRun(true, "synced", true)).toBe("add");
  expect(firstRun(true, "syncing", true)).toBe("add");
  expect(firstRun(true, "offline", false)).toBe("add");
  expect(firstRun(true, "error", false)).toBe("add");
  expect(firstRun(true, "waiting", false)).toBe("add");
});
