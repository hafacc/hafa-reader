# Reader

[![build](https://github.com/hafacc/reader/actions/workflows/build.yml/badge.svg)](https://github.com/hafacc/reader/actions/workflows/build.yml)
[![extension](https://img.shields.io/badge/dynamic/json?label=extension&prefix=v&query=%24.version&url=https://raw.githubusercontent.com/hafacc/reader/main/extension/manifest.json)](https://github.com/hafacc/reader/releases?q=extension-v)
[![android](https://img.shields.io/badge/dynamic/regex?label=android&search=versionName%20%3D%20%22%28%5B%5E%22%5D%2B%29%22&replace=v%241&url=https://raw.githubusercontent.com/hafacc/reader/main/android/app/build.gradle.kts)](https://github.com/hafacc/reader/releases?q=android-v)

A simple local RSS reader with optional sync. The web app at [reader.hafa.cc](https://reader.hafa.cc/) reads
feeds that its Chrome extension fetches and schedules; the Android app works on
its own. The design is in `PLAN.md`.

| Directory | What | Open with |
| --- | --- | --- |
| `web/` | the web app (Svelte) | `cd web && bun install && bun dev` |
| `extension/` | the Chrome extension the web app needs | `cd extension && bun install && bun export` |
| `android/` | Android app (Compose) | `android/` in Android Studio; see `android/README.md` |
| `shared/` | feed parser, filters and schedule the web app and extension use, and test fixtures every client passes | `cd shared && bun install && bun test` |
| `firebase/` | Firestore rules and setup for optional sync | see `firebase/README.md` |
| `design/` | the logo the web app's and extension's icons are made from | |

Install `shared/` first: the web app and extension import its code, and its
dependencies resolve from `shared/node_modules`.

## Try it

1. `cd shared && bun install`
2. `cd extension && bun install && bun export`
3. In Chrome open `chrome://extensions`, turn on Developer mode, choose
   "Load unpacked" and pick `extension/dist`. The manifest carries the Web Store
   listing's public key, so the extension gets the id the web app expects.
4. `cd web && bun install && bun dev`, then open http://localhost:5173.

`bun pack` in `extension/` builds `reader-extension.zip` for the Web Store: no
`key` (the store sets it) and no localhost.

## Releases

Run by hand from the Actions tab:

- `ext-cut` bumps `extension/manifest.json`, tags `extension-v*` and attaches
  the store zip to a GitHub release.
- `android-cut` bumps `versionName` and `versionCode` in
  `android/app/build.gradle.kts`, tags `android-v*`, then `android-publish`
  builds the signed bundle and APK, attaches the APK to a GitHub release and
  rolls the bundle out on Play's internal track, or uploads it as a draft with
  a warning when Play refuses (an app that has not passed review yet). It needs the secrets
  `ANDROID_KEYSTORE` (the upload keystore, base64), `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` and `PLAY_SERVICE_ACCOUNT_JSON`.
- `web-deploy` publishes `main` to GitHub Pages (Settings, Pages: source
  GitHub Actions, custom domain `reader.hafa.cc`).
