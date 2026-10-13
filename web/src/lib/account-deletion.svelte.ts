import { rememberSignedIn, wasSignedIn } from "./signed-in.ts";
import { errorSummary } from "./sync/errors.ts";
import type { SignInResult } from "./sync/firebase.ts";

/** Where the delete page is: before deleting, while deleting, done, or failed. */
export type DeletionStage = "idle" | "working" | "deleted" | "failed";

/** What the delete page shows: a wait for the remembered account, or signed out, or signed in. */
export type DeletionView = "loading" | "signedOut" | "signedIn";

/** What the delete page needs from Firebase; tests pass a stand-in. */
export interface DeletionBackend {
  /** Follow who is signed in: their email, or undefined when no one is. */
  watch: (onUser: (email: string | undefined) => void) => void;
  /** Sign in with Google in a pop-up window. */
  signIn: () => Promise<SignInResult>;
  /** Sign out. */
  signOut: () => Promise<void>;
  /** Delete the signed-in account the way the app does, then sign out. */
  deleteAccount: () => Promise<void>;
}

/**
 * The delete page's state: who is signed in, and how deleting went. Firebase
 * loads only when asked for (a pointer or focus on a button, or right away
 * when this browser was signed in last time), so the sign-in window can open
 * straight from the click.
 */
export class AccountDeletion {
  /** The signed-in account's email; undefined when no one is signed in or it is not known yet. */
  email = $state<string | undefined>();
  /** How deleting went. */
  stage = $state<DeletionStage>("idle");
  /** Why the last sign-in did not happen: blocked window, failure, or nothing. */
  signInProblem = $state<"" | "blocked" | "failed">("");
  /**
   * Whether who is signed in is known: at once for a browser not signed in
   * last time, otherwise once Firebase has said, so the page does not show
   * signed out and then switch.
   */
  known = $state(true);

  /** What the page shows. */
  view = $derived.by((): DeletionView => {
    let shown: DeletionView = "signedOut";
    if (!this.known) {
      shown = "loading";
    } else if (this.email !== undefined) {
      shown = "signedIn";
    }
    return shown;
  });

  private readonly load: () => Promise<DeletionBackend>;
  private backend: Promise<DeletionBackend> | undefined;

  constructor(load: () => Promise<DeletionBackend>) {
    this.load = load;
    if (wasSignedIn()) {
      this.known = false;
      this.preload();
    }
  }

  /** Load Firebase ahead of a click. */
  preload(): void {
    this.connect().catch((error: unknown) => {
      console.debug("Reader: sign-in failed to load", errorSummary(error));
      // without Firebase the page can still offer sign-in, which loads it again
      this.known = true;
    });
  }

  /** Sign in with Google. */
  async signIn(): Promise<void> {
    this.signInProblem = "";
    try {
      const backend = await this.connect();
      if ((await backend.signIn()) === "blocked") {
        this.signInProblem = "blocked";
      }
    } catch (error) {
      console.error("Reader sign-in failed:", errorSummary(error));
      this.signInProblem = "failed";
    }
  }

  /** Sign out, to sign in with another account. */
  async useOtherAccount(): Promise<void> {
    const backend = await this.connect();
    await backend.signOut();
  }

  /** Delete the signed-in account and everything synced to it. */
  async remove(): Promise<void> {
    this.stage = "working";
    try {
      const backend = await this.connect();
      await backend.deleteAccount();
      rememberSignedIn(false);
      this.stage = "deleted";
    } catch (error) {
      console.error(
        "Reader could not delete the account:",
        errorSummary(error),
      );
      this.stage = "failed";
    }
  }

  /** Load Firebase once; a failed load can be tried again. */
  private connect(): Promise<DeletionBackend> {
    if (this.backend === undefined) {
      const loading = this.load().then((backend) => {
        backend.watch((email) => {
          this.email = email;
          this.known = true;
        });
        return backend;
      });
      this.backend = loading;
      loading.catch(() => {
        if (this.backend === loading) {
          this.backend = undefined;
        }
      });
    }
    return this.backend;
  }
}
