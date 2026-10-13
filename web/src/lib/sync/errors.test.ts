import { expect, test } from "bun:test";
import { errorSummary, isRefused } from "./errors.ts";

test("an error's summary keeps its code and message but not the account's id", () => {
  const error = Object.assign(
    new Error("Missing field (found in document users/abc123/entries/e1)"),
    { code: "invalid-argument" },
  );
  expect(errorSummary(error)).toBe(
    "invalid-argument: Missing field (found in document users/{uid}/entries/e1)",
  );
  expect(errorSummary("plain")).toBe("plain");
});

function coded(code: string): Error {
  return Object.assign(new Error(code), { code });
}

test("only refusals a retry cannot fix count as refused", () => {
  expect(isRefused(coded("permission-denied"))).toBe(true);
  expect(isRefused(coded("invalid-argument"))).toBe(true);
  expect(isRefused(coded("failed-precondition"))).toBe(true);
  expect(isRefused(coded("unavailable"))).toBe(false);
  expect(isRefused(coded("deadline-exceeded"))).toBe(false);
  expect(isRefused(new Error("plain"))).toBe(false);
  expect(isRefused("permission-denied")).toBe(false);
});
