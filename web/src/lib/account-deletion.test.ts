import { beforeEach, expect, test } from "bun:test";
import {
  AccountDeletion,
  type DeletionBackend,
} from "./account-deletion.svelte.ts";
import type { SignInResult } from "./sync/firebase.ts";

/** A stand-in for Firebase: who is signed in, and how each call goes. */
function backend(options: {
  signIn?: SignInResult | Error;
  deleted?: Error;
}): DeletionBackend & { calls: string[] } {
  let listener: (email: string | undefined) => void = () => undefined;
  const calls: string[] = [];
  return {
    calls,
    watch(onUser): void {
      listener = onUser;
      onUser(undefined);
    },
    signIn(): Promise<SignInResult> {
      calls.push("signIn");
      const outcome = options.signIn ?? "signed-in";
      if (outcome instanceof Error) {
        return Promise.reject(outcome);
      } else {
        if (outcome === "signed-in") {
          listener("reader@example.com");
        }
        return Promise.resolve(outcome);
      }
    },
    signOut(): Promise<void> {
      calls.push("signOut");
      listener(undefined);
      return Promise.resolve();
    },
    deleteAccount(): Promise<void> {
      calls.push("deleteAccount");
      if (options.deleted === undefined) {
        listener(undefined);
        return Promise.resolve();
      } else {
        return Promise.reject(options.deleted);
      }
    },
  };
}

function quietly<Result>(task: () => Promise<Result>): Promise<Result> {
  const original = console.error;
  console.error = (): void => undefined;
  return task().finally(() => {
    console.error = original;
  });
}

beforeEach(() => {
  localStorage.clear();
});

test("signing in shows the account, and deleting it ends signed out and done", async () => {
  localStorage.setItem("h-reader:signed-in", "1");
  const stand = backend({});
  const deletion = new AccountDeletion(() => Promise.resolve(stand));
  await deletion.signIn();
  expect(deletion.email).toBe("reader@example.com");
  await deletion.remove();
  expect(deletion.stage).toBe("deleted");
  expect(deletion.email).toBeUndefined();
  expect(stand.calls).toEqual(["signIn", "deleteAccount"]);
  expect(localStorage.getItem("h-reader:signed-in")).toBeNull();
});

test("a failed deletion says so and can be tried again", async () => {
  const deletion = new AccountDeletion(() =>
    Promise.resolve(backend({ deleted: new Error("offline") })),
  );
  await deletion.signIn();
  await quietly(() => deletion.remove());
  expect(deletion.stage).toBe("failed");
  expect(deletion.email).toBe("reader@example.com");
});

test("a blocked or failed sign-in is reported, and a closed one is not", async () => {
  const blocked = new AccountDeletion(() =>
    Promise.resolve(backend({ signIn: "blocked" })),
  );
  await blocked.signIn();
  expect(blocked.signInProblem).toBe("blocked");
  const failed = new AccountDeletion(() =>
    Promise.resolve(backend({ signIn: new Error("network") })),
  );
  await quietly(() => failed.signIn());
  expect(failed.signInProblem).toBe("failed");
  const closed = new AccountDeletion(() =>
    Promise.resolve(backend({ signIn: "closed" })),
  );
  await closed.signIn();
  expect(closed.signInProblem).toBe("");
  expect(closed.email).toBeUndefined();
});

test("another account signs the first one out", async () => {
  const stand = backend({});
  const deletion = new AccountDeletion(() => Promise.resolve(stand));
  await deletion.signIn();
  await deletion.useOtherAccount();
  expect(deletion.email).toBeUndefined();
  expect(stand.calls).toEqual(["signIn", "signOut"]);
});

test("Firebase loads once, and again only after a failed load", async () => {
  let loads = 0;
  const deletion = new AccountDeletion(() => {
    loads += 1;
    return loads === 1
      ? Promise.reject(new Error("offline"))
      : Promise.resolve(backend({}));
  });
  await quietly(() => deletion.signIn());
  expect(deletion.signInProblem).toBe("failed");
  deletion.preload();
  await deletion.signIn();
  expect(loads).toBe(2);
  expect(deletion.email).toBe("reader@example.com");
});

test("a browser signed in last time waits for its account instead of showing signed out first", async () => {
  localStorage.setItem("h-reader:signed-in", "1");
  const { promise: loaded, resolve: finishLoading } =
    Promise.withResolvers<DeletionBackend>();
  const deletion = new AccountDeletion(() => loaded);
  expect(deletion.view).toBe("loading");
  let report: (email: string | undefined) => void = () => undefined;
  finishLoading({
    ...backend({}),
    watch(onUser): void {
      report = onUser;
    },
  });
  await loaded;
  expect(deletion.view).toBe("loading");
  report("reader@example.com");
  expect(deletion.view).toBe("signedIn");
});

test("a browser never signed in shows signed out at once, without loading Firebase", () => {
  let loads = 0;
  const deletion = new AccountDeletion(() => {
    loads += 1;
    return Promise.resolve(backend({}));
  });
  expect(deletion.view).toBe("signedOut");
  expect(loads).toBe(0);
});

test("a remembered sign-in that has ended shows signed out once Firebase says so", async () => {
  localStorage.setItem("h-reader:signed-in", "1");
  const stand = backend({});
  const deletion = new AccountDeletion(() => Promise.resolve(stand));
  expect(deletion.view).toBe("loading");
  await Promise.resolve();
  await Promise.resolve();
  expect(deletion.view).toBe("signedOut");
});
