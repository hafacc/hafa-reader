import type { Engine } from "./firebase.ts";
import type { SyncReport } from "./host.ts";

/** The pretend account the sign-in demo shows. */
const DEMO_EMAIL = "reader@example.com";
/** How long the pretend deletion takes, so its progress shows. */
const DELETE_MS = 800;

/**
 * A sign-in that never touches the network, for looking at the sync screens
 * in development: `out`, `in`, `syncing`, `error`, `switch` (a different
 * account waiting for the choice), `failed` and `popup` start in that state.
 */
export function startDemoSync(
  mode: string,
  report: (update: SyncReport) => void,
): Engine {
  function signedIn(status: SyncReport["status"]): void {
    report({ status, email: DEMO_EMAIL, loadingOlder: false });
  }
  function signedOut(): void {
    report({ status: "off", email: "", loadingOlder: false });
  }
  const starting: Record<string, () => void> = {
    in: () => signedIn("synced"),
    syncing: () => signedIn("syncing"),
    error: () => signedIn("error"),
    switch: () => signedIn("waiting"),
  };
  (starting[mode] ?? signedOut)();
  return {
    signIn(): Promise<"signed-in"> {
      signedIn("synced");
      return Promise.resolve("signed-in");
    },
    replaceLocal(): Promise<void> {
      signedIn("synced");
      return Promise.resolve();
    },
    signOut(): Promise<void> {
      signedOut();
      return Promise.resolve();
    },
    async deleteAccount(): Promise<void> {
      await new Promise((resolve) => setTimeout(resolve, DELETE_MS));
      signedOut();
    },
    queue: () => undefined,
    fetchBody: () => Promise.resolve(""),
  };
}
