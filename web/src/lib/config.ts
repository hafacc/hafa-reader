/*
 * Public client config; safe to commit. Every visitor's browser receives
 * these values, and none of them grants anything on its own: Firestore's
 * rules (firebase/firestore.rules) are what protect the data. There are no
 * environment variables; change a value here.
 */

/** The Chrome extension's id, fixed by the `key` in `extension/manifest.json`. */
export const extensionId = "ehmpednijaggcbpmjldkdookepbjindc";

/** The privacy policy, a page of its own beside the app (`src/routes/(policy)/privacy`). */
export const privacyUrl = "/privacy";

/** The terms of service, a page of its own beside the app (`src/routes/(policy)/terms`), like the privacy policy. */
export const termsUrl = "/terms";

/** The extension's Chrome Web Store listing page. */
export const chromeWebStoreUrl =
  "https://chromewebstore.google.com/detail/ehmpednijaggcbpmjldkdookepbjindc";

/** The Android app's Google Play listing page, where Android browsers are sent. */
export const playStoreUrl =
  "https://play.google.com/store/apps/details?id=cc.hafa.reader";

/** What the Firebase SDK needs to reach a project. */
export interface FirebaseSettings {
  /** Web API key. */
  apiKey: string;
  /** Domain that hosts the sign-in helper. */
  authDomain: string;
  /** Project id. */
  projectId: string;
  /** Web app id. */
  appId: string;
}

/**
 * The Firebase web app's public config, from the Firebase console (Project
 * settings, General, Your apps).
 */
export const firebaseConfig: FirebaseSettings = {
  apiKey: "AIzaSyDvhp2F4uVXCL-BaN8WoJw-WEYxXnLn6WY",
  authDomain: "hafa-reader.firebaseapp.com",
  projectId: "hafa-reader",
  appId: "1:508750223929:web:40573d9ec3373a6d240be8",
};
