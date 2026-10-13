import type { SyncStatus } from "./sync/host.ts";

/** What the empty reader shows: the add prompt, or sync bringing the account's feeds. */
export type FirstRun = "add" | "syncing";

/**
 * What the reader with no feeds shows. Signed in, it waits for the account's
 * feeds rather than asking for one, until they have come from the server or
 * sync cannot bring them (offline or failing); an empty account then gets the
 * add prompt.
 */
export function firstRun(
  signedIn: boolean,
  status: SyncStatus,
  feedsKnown: boolean,
): FirstRun {
  const bringing =
    signedIn &&
    !feedsKnown &&
    (status === "connecting" || status === "syncing" || status === "synced");
  return bringing ? "syncing" : "add";
}
