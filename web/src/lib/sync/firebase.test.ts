import { expect, mock, spyOn, test } from "bun:test";
// biome-ignore lint/performance/noNamespaceImport: the stand-in keeps every other export
import * as authModule from "firebase/auth";
// biome-ignore lint/performance/noNamespaceImport: the stand-in keeps every other export
import * as firestoreModule from "firebase/firestore";
import { firebaseConfig } from "../config.ts";
import type { LocalData } from "./host.ts";

const realInitializeFirestore = firestoreModule.initializeFirestore;
let firestoreSetups = 0;
let authListenersStopped = 0;

// the Node build of Firebase Auth cannot hold browser sign-in, so a stand-in
mock.module("firebase/auth", () => ({
  ...authModule,
  initializeAuth: (): object => ({}),
  onAuthStateChanged: (): (() => void) => () => {
    authListenersStopped += 1;
  },
}));
mock.module("firebase/firestore", () => ({
  ...firestoreModule,
  initializeFirestore: (
    ...args: Parameters<typeof realInitializeFirestore>
  ): ReturnType<typeof realInitializeFirestore> => {
    firestoreSetups += 1;
    return realInitializeFirestore(...args);
  },
}));

const { firebaseServices, startFirebase } = await import("./firebase.ts");

const host = {
  local: (): LocalData => ({
    feeds: [],
    entries: [],
    settings: {},
    tombstones: new Map<string, number>(),
  }),
  apply: (): Promise<void> => Promise.resolve(),
  body: (): Promise<string> => Promise.resolve(""),
  clear: (): Promise<void> => Promise.resolve(),
};

test("starting sync again reuses Firebase instead of setting it up twice", () => {
  const first = firebaseServices(firebaseConfig);
  expect(() => {
    startFirebase(firebaseConfig, host, () => undefined);
    startFirebase(firebaseConfig, host, () => undefined);
  }).not.toThrow();
  expect(firebaseServices(firebaseConfig)).toBe(first);
  expect(firestoreSetups).toBe(1);
});

test("Firestore refuses a second setup with its options, which a restart once hit", () => {
  const { db } = firebaseServices(firebaseConfig);
  expect(() =>
    realInitializeFirestore(db.app, {
      localCache: firestoreModule.persistentLocalCache({
        tabManager: firestoreModule.persistentMultipleTabManager(),
      }),
    }),
  ).toThrow("initializeFirestore() has already been called");
});

test("disposing the engine stops its sign-in listener and browser handlers", () => {
  const windowRemoved = spyOn(globalThis, "removeEventListener");
  const documentRemoved = spyOn(document, "removeEventListener");
  const before = authListenersStopped;
  startFirebase(firebaseConfig, host, () => undefined).dispose();
  const removed = new Set(
    [...windowRemoved.mock.calls, ...documentRemoved.mock.calls].map(
      ([type]) => type,
    ),
  );
  windowRemoved.mockRestore();
  documentRemoved.mockRestore();
  expect(authListenersStopped).toBe(before + 1);
  expect(removed).toEqual(
    new Set(["offline", "online", "pagehide", "visibilitychange"]),
  );
});
