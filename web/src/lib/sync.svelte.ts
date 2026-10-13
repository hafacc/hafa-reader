import { type FirebaseSettings, firebaseConfig } from "./config.ts";
import { copy } from "./copy.ts";
import type { Engine } from "./sync/firebase.ts";
import type {
  LocalChange,
  SyncHost,
  SyncReport,
  SyncStatus,
} from "./sync/host.ts";

/** Set while an account is signed in, so Firebase loads on the next visit. */
const SIGNED_IN_KEY = "h-reader:signed-in";

function rememberSignedIn(signedIn: boolean): void {
  try {
    if (signedIn) {
      localStorage.setItem(SIGNED_IN_KEY, "1");
    } else {
      localStorage.removeItem(SIGNED_IN_KEY);
    }
  } catch {
    // storage can be blocked; sign-in then lasts only this visit
  }
}

function wasSignedIn(): boolean {
  try {
    return localStorage.getItem(SIGNED_IN_KEY) === "1";
  } catch {
    return false;
  }
}

/**
 * Optional Google sign-in and Firestore sync.
 *
 * Firebase is loaded only when someone signs in, or on a later visit after
 * they did, so signed-out use never downloads it.
 */
export class Sync {
  /** How sync is doing. */
  status = $state<SyncStatus>("off");
  /** The signed-in account's email; empty when signed out. */
  email = $state("");
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

  private readonly host: SyncHost;
  private readonly settings: FirebaseSettings;
  private engine: Promise<Engine> | undefined;
  private buffered: LocalChange[] = [];
  private signingIn = false;

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

  /** Offer a pretend sign-in that never touches the network; development only. See `startDemoSync` for the modes. */
  async demo(mode: string): Promise<void> {
    if (import.meta.env.DEV) {
      const { startDemoSync } = await import("./sync/demo-engine.ts");
      this.engine = Promise.resolve(
        startDemoSync(mode, (update) => this.report(update)),
      );
      this.signInFailed = mode === "failed";
      this.popupBlocked = mode === "popup";
    }
  }

  /** Reconnect an account signed in on an earlier visit; does nothing otherwise. */
  resume(): void {
    if (wasSignedIn()) {
      this.load().catch((error: unknown) => {
        console.error("H Reader sync could not start:", error);
      });
    }
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
      console.error("H Reader sign-in failed:", error);
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
      console.error("H Reader could not delete the account:", error);
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

  /** Load Firebase and start the engine; a failed load, as offline or after a deploy, can be tried again. */
  private start(settings: FirebaseSettings): Promise<Engine> {
    const loading = import("./sync/firebase.ts").then(({ startFirebase }) => {
      const engine = startFirebase(settings, this.host, (update) =>
        this.report(update),
      );
      for (const change of this.buffered) {
        engine.queue(change);
      }
      this.buffered = [];
      return engine;
    });
    loading.catch(() => {
      if (this.engine === loading) {
        this.engine = undefined;
        this.status = "error";
        // offline at start: try again once the browser is back online
        globalThis.addEventListener("online", () => this.resume(), {
          once: true,
        });
      }
    });
    return loading;
  }

  private report(update: SyncReport): void {
    this.status = update.status;
    this.email = update.email;
    this.loadingOlder = update.loadingOlder;
    if (update.email !== "") {
      rememberSignedIn(true);
    } else if (update.status === "off" && !this.signingIn) {
      rememberSignedIn(false);
    }
  }
}
