import { expect, test } from "bun:test";
import type { Engine } from "./sync/firebase.ts";
import type { SyncHost } from "./sync/host.ts";
import { Sync } from "./sync.svelte.ts";

const HOST: SyncHost = {
  local: () => ({
    feeds: [],
    entries: [],
    settings: {},
    tombstones: new Map<string, number>(),
  }),
  apply: () => Promise.resolve(),
  body: () => Promise.resolve(""),
  saveHashes: () => Promise.resolve(),
  clear: () => Promise.resolve(),
};

function engineWith(deleteAccount: () => Promise<void>): Engine {
  return {
    signIn: () => Promise.resolve("signed-in"),
    replaceLocal: () => Promise.resolve(),
    signOut: () => Promise.resolve(),
    deleteAccount,
    queue: () => undefined,
    fetchBody: () => Promise.resolve(""),
  };
}

test("deleting the account forgets the sign-in on this device", async () => {
  localStorage.setItem("h-reader:signed-in", "1");
  const sync = new Sync(HOST);
  let deleted = 0;
  sync["engine"] = Promise.resolve(
    engineWith(() => {
      deleted += 1;
      return Promise.resolve();
    }),
  );
  await sync.deleteAccount();
  expect(deleted).toBe(1);
  expect(sync.deleteFailed).toBe(false);
  expect(sync.deleting).toBe(false);
  expect(localStorage.getItem("h-reader:signed-in")).toBeNull();
});

test("a failed deletion is reported and keeps the sign-in", async () => {
  localStorage.setItem("h-reader:signed-in", "1");
  const sync = new Sync(HOST);
  sync["engine"] = Promise.resolve(
    engineWith(() => Promise.reject(new Error("offline"))),
  );
  const quiet = console.error;
  console.error = (): void => undefined;
  await sync.deleteAccount();
  console.error = quiet;
  expect(sync.deleteFailed).toBe(true);
  expect(localStorage.getItem("h-reader:signed-in")).toBe("1");
});

test("a sign-in that fails says so, so the account pane can open", async () => {
  const sync = new Sync(HOST);
  const quiet = console.error;
  console.error = (): void => undefined;
  const needsAttention = await sync.signIn();
  console.error = quiet;
  expect(needsAttention).toBe(true);
  expect(sync.signInFailed).toBe(true);
});
