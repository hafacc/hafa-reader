import { expect, test } from "bun:test";
import { Health } from "./health.ts";

test("a listener error clears once that listener delivers again", () => {
  const health = new Health();
  health.listenerFailed("entries");
  expect(health.failed).toBe(true);
  expect(health.heard("settings")).toBe(false);
  expect(health.failed).toBe(true);
  expect(health.heard("entries")).toBe(true);
  expect(health.failed).toBe(false);
});

test("a failed read clears with the next read or delivery, not with a write", () => {
  const health = new Health();
  health.readFailed();
  health.writeWorked();
  expect(health.failed).toBe(true);
  health.readWorked();
  expect(health.failed).toBe(false);
  health.readFailed();
  expect(health.heard("subscriptions")).toBe(true);
  expect(health.failed).toBe(false);
});

test("a failed write clears only with a write that goes through", () => {
  const health = new Health();
  health.writeFailed();
  health.readWorked();
  expect(health.heard("entries")).toBe(false);
  expect(health.failed).toBe(true);
  health.writeWorked();
  expect(health.failed).toBe(false);
});
