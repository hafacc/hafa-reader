import { type FirebaseSettings, firebaseConfig } from "./config.ts";
import { copy } from "./copy.ts";
import { accountPhoto } from "./photo.ts";
import { rememberSignedIn, wasSignedIn } from "./signed-in.ts";
import type { Retention } from "./sync/documents.ts";
import { errorSummary } from "./sync/errors.ts";
import type { Engine } from "./sync/firebase.ts";
import type {
  LocalChange,
  SyncHost,
  SyncReport,
  SyncStatus,
} from "./sync/host.ts";

/** First wait before a failed start is tried again; it doubles with each failure. */
const START_RETRY_MS = 5000;
/** Longest wait between tries of a failed start. */
const START_RETRY_LIMIT_MS = 5 * 60 * 1000;

/** How long to wait before trying a failed start again, after `failures` in a row. */
export function startRetryMs(failures: number): number {
  return Math.min(START_RETRY_MS * 2 ** failures, START_RETRY_LIMIT_MS);
}

/** Engines this module started, stopped when it is replaced by a hot reload in development. */
const liveEngines = new Set<Engine>();
import.meta.hot?.dispose(() => {
  for (const engine of liveEngines) {
    engine.dispose();
  }
  liveEngines.clear();
});

/**
 * Optional Google sign-in and Firestore sync.
 *
 * Firebase is loaded only when someone heads for sign-in (`preload`), signs
 * in, or comes back on a later visit after they did, so signed-out use never
 * downloads it.
 */
export class Sync {
  /** How sync is doing. */
  status = $state<SyncStatus>("off");
  /** The signed-in account's email; empty when signed out. */
  email = $state("");
  /** The signed-in account's picture, https only and sized for the avatar; empty when none. */
  photo = $state("");
  /** Whether entries older than the last 90 days are still loading. */
  loadingOlder = $state(false);
  /** Whether the last sign-in attempt failed. */
  signInFailed = $state(false);
  /** Whether the browser blocked the last sign-in window. */
  popupBlocked = $state(false);
  /** Whether the account is being deleted. */
  deleting = $state(false);
  /** Whether the last attempt to delete the account failed. */
  deleteFailed = $state(false);
  /** How long the signed-in account keeps entries. */
  retention = $state<Retention>("forever");
  /** Whether the signed-in account's feeds have arrived from the server since signing in. */
  feedsKnown = $state(false);

  private readonly host: SyncHost;
  private readonly settings: FirebaseSettings;
  private engine: Promise<Engine> | undefined;
  private firebase: Promise<typeof import("./sync/firebase.ts")> | undefined;
  private buffered: LocalChange[] = [];
  private signingIn = false;
  /** Failed starts in a row, for the wait before the next try. */
  private failedStarts = 0;
  private startTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(host: SyncHost) {
    this.host = host;
    this.settings = firebaseConfig;
  }

  /** Whether an account is signed in. */
  get signedIn(): boolean {
    return this.email !== "" || this.status === "connecting";
  }

  /** Words for the current status. */
  get statusText(): string {
    const words: Record<SyncStatus, string> = {
      off: copy.syncSignIn,
      connecting: copy.syncConnecting,
      syncing: copy.syncSyncing,
      synced: copy.syncSynced,
      offline: copy.syncOffline,
      error: copy.syncError,
      waiting: copy.syncWaiting,
    };
    return words[this.status];
  }

  /** Reconnect an account signed in on an earlier visit; does nothing otherwise. */
  resume(): void {
    if (wasSignedIn()) {
      // a failed start is logged where it sets the error status
      this.load().catch(() => undefined);
    }
  }

  /**
   * Download Firebase ahead of a sign-in, as when the account opens, so the
   * sign-in window opens straight from the click instead of after the
   * download, which browsers may then block.
   */
  preload(): void {
    this.loadFirebase().catch((error: unknown) => {
      console.debug("Reader sync: preload failed", error);
    });
  }

  /** Sign in with Google and start syncing; whether it failed or was blocked, with a message to show. */
  async signIn(): Promise<boolean> {
    this.signInFailed = false;
    this.popupBlocked = false;
    this.signingIn = true;
    try {
      const engine = await this.load();
      this.popupBlocked = (await engine.signIn()) === "blocked";
    } catch (error) {
      console.error("Reader sign-in failed:", error);
      this.signInFailed = true;
    } finally {
      this.signingIn = false;
      rememberSignedIn(this.email !== "");
    }
    return this.signInFailed || this.popupBlocked;
  }

  /** With a different account waiting, replace this device's data with that account's. */
  async replaceLocal(): Promise<void> {
    const engine = await this.engine;
    await engine?.replaceLocal();
  }

  /**
   * Delete the signed-in account and everything stored under it, then sign
   * out; everything on this device stays.
   */
  async deleteAccount(): Promise<void> {
    this.deleting = true;
    this.deleteFailed = false;
    try {
      const engine = await this.engine;
      await engine?.deleteAccount();
      rememberSignedIn(false);
    } catch (error) {
      console.error("Reader could not delete the account:", error);
      this.deleteFailed = true;
    } finally {
      this.deleting = false;
    }
  }

  /** Sign out; everything stays on this device. */
  async signOut(): Promise<void> {
    rememberSignedIn(false);
    const engine = await this.engine;
    await engine?.signOut();
  }

  /** Choose how long the signed-in account keeps entries; ignored when signed out. */
  async setRetention(retention: Retention): Promise<void> {
    const engine = await this.engine;
    engine?.setRetention(retention);
  }

  /** Upload a body this device holds if the account no longer has it, as after it expired there; ignored when signed out. */
  restoreBody(hash: string, text: string): void {
    this.engine
      ?.then((engine) => engine.restoreBody(hash, text))
      .catch(() => undefined);
  }

  /** Send local changes when signed in; ignored otherwise. */
  queue(change: LocalChange): void {
    if (this.engine === undefined) {
      if (wasSignedIn()) {
        this.buffered.push(change);
      }
    } else {
      this.engine.then((engine) => engine.queue(change)).catch(() => undefined);
    }
  }

  /** Download an article body shared under a hash; empty when not signed in, not found or sync failed to start. */
  async fetchBody(hash: string): Promise<string> {
    let body = "";
    if (this.engine !== undefined && hash !== "") {
      try {
        const engine = await this.engine;
        body = await engine.fetchBody(hash);
      } catch {
        body = "";
      }
    }
    return body;
  }

  private load(): Promise<Engine> {
    if (this.engine === undefined) {
      this.status = "connecting";
      this.engine = this.start(this.settings);
    }
    return this.engine;
  }

  /** Download Firebase once; a failed download can be tried again. */
  private loadFirebase(): Promise<typeof import("./sync/firebase.ts")> {
    if (this.firebase === undefined) {
      const loading = import("./sync/firebase.ts");
      this.firebase = loading;
      loading.catch(() => {
        if (this.firebase === loading) {
          this.firebase = undefined;
        }
      });
    }
    return this.firebase;
  }

  /** Load Firebase and start the engine; a failed load, as offline or after a deploy, can be tried again. */
  private start(settings: FirebaseSettings): Promise<Engine> {
    const loading = this.loadFirebase().then(({ startFirebase }) => {
      const engine = startFirebase(settings, this.host, (update) =>
        this.report(update),
      );
      liveEngines.add(engine);
      this.failedStarts = 0;
      for (const change of this.buffered) {
        engine.queue(change);
      }
      this.buffered = [];
      return engine;
    });
    loading.catch((error: unknown) => {
      if (this.engine === loading) {
        console.error("Reader sync could not start:", errorSummary(error));
        this.engine = undefined;
        this.status = "error";
        // try again after a wait that grows, or as soon as the browser is back online
        clearTimeout(this.startTimer);
        this.startTimer = setTimeout(
          () => this.resume(),
          startRetryMs(this.failedStarts),
        );
        this.failedStarts += 1;
        globalThis.addEventListener(
          "online",
          () => {
            clearTimeout(this.startTimer);
            this.resume();
          },
          { once: true },
        );
      }
    });
    return loading;
  }

  private report(update: SyncReport): void {
    this.status = update.status;
    this.email = update.email;
    this.photo = accountPhoto(update.photoUrl);
    this.loadingOlder = update.loadingOlder;
    this.retention = update.retention;
    this.feedsKnown = update.feedsKnown;
    if (update.email !== "") {
      rememberSignedIn(true);
    } else if (update.status === "off" && !this.signingIn) {
      rememberSignedIn(false);
    }
  }
}
