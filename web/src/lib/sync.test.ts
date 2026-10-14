import { expect, test } from "bun:test";
import type { Retention } from "./sync/documents.ts";
import type { Engine } from "./sync/firebase.ts";
import type { SyncHost } from "./sync/host.ts";
import { Sync, startRetryMs } from "./sync.svelte.ts";

const HOST: SyncHost = {
  local: () => ({
    feeds: [],
    entries: [],
    settings: {},
    tombstones: new Map<string, number>(),
  }),
  apply: () => Promise.resolve(),
  body: () => Promise.resolve(""),
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
    setRetention: () => undefined,
    restoreBody: () => undefined,
    dispose: () => undefined,
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

test("a blocked sign-in window says so, so the account pane can open", async () => {
  const sync = new Sync(HOST);
  sync["engine"] = Promise.resolve({
    ...engineWith(() => Promise.resolve()),
    signIn: () => Promise.resolve("blocked"),
  });
  expect(await sync.signIn()).toBe(true);
  expect(sync.popupBlocked).toBe(true);
  expect(sync.signInFailed).toBe(false);
});

test("what the engine reports sets the account's state and is remembered", () => {
  const sync = new Sync(HOST);
  expect(sync.signedIn).toBe(false);
  sync["report"]({
    status: "syncing",
    email: "reader@example.com",
    photoUrl: "",
    loadingOlder: false,
    retention: "forever",
    feedsKnown: false,
    caughtUp: false,
  });
  expect(sync.signedIn).toBe(true);
  expect(sync.status).toBe("syncing");
  expect(localStorage.getItem("h-reader:signed-in")).toBe("1");
  sync["report"]({
    status: "waiting",
    email: "reader@example.com",
    photoUrl: "",
    loadingOlder: false,
    retention: "forever",
    feedsKnown: false,
    caughtUp: false,
  });
  expect(sync.status).toBe("waiting");
  sync["report"]({
    status: "off",
    email: "",
    photoUrl: "",
    loadingOlder: false,
    retention: "forever",
    feedsKnown: false,
    caughtUp: false,
  });
  expect(sync.signedIn).toBe(false);
  expect(localStorage.getItem("h-reader:signed-in")).toBeNull();
});

test("a retention chosen here goes to the account, and the account's comes back", async () => {
  const sync = new Sync(HOST);
  const chosen: Retention[] = [];
  sync["engine"] = Promise.resolve({
    ...engineWith(() => Promise.resolve()),
    setRetention: (retention: Retention) => {
      chosen.push(retention);
    },
  });
  await sync.setRetention(90);
  expect(chosen).toEqual([90]);
  sync["report"]({
    status: "synced",
    email: "me@example.com",
    photoUrl: "",
    loadingOlder: false,
    retention: 30,
    feedsKnown: false,
    caughtUp: false,
  });
  expect(sync.retention).toBe(30);
});

test("signed out, the retention is forever and choosing one does nothing", async () => {
  const sync = new Sync(HOST);
  expect(sync.retention).toBe("forever");
  await sync.setRetention(30);
  expect(sync.retention).toBe("forever");
});

test("the account's picture is kept only over https, sized for the avatar", () => {
  const sync = new Sync(HOST);
  sync["report"]({
    status: "synced",
    email: "reader@example.com",
    photoUrl: "https://lh3.googleusercontent.com/a/abc=s400-c",
    loadingOlder: false,
    retention: "forever",
    feedsKnown: false,
    caughtUp: false,
  });
  expect(sync.photo).toBe("https://lh3.googleusercontent.com/a/abc=s96-c");
  sync["report"]({
    status: "synced",
    email: "reader@example.com",
    photoUrl: "http://lh3.googleusercontent.com/a/abc",
    loadingOlder: false,
    retention: "forever",
    feedsKnown: false,
    caughtUp: false,
  });
  expect(sync.photo).toBe("");
});

test("a failed start is tried again after a wait that doubles, up to five minutes", () => {
  expect(startRetryMs(0)).toBe(5000);
  expect(startRetryMs(1)).toBe(10_000);
  expect(startRetryMs(3)).toBe(40_000);
  expect(startRetryMs(20)).toBe(300_000);
});
