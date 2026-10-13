# H Reader — project context

An RSS reader that works with no account: every client fetches feeds itself
and keeps everything on the device. Google sign-in (Firebase Auth) only turns
on sync through Firestore. No server code. The design, data model and sync
algorithm are in `PLAN.md`; read it before changing behavior.

## Layout

- `web/` — Svelte 5 single-page app (SvelteKit, Tailwind, bun), static on
  GitHub Pages at https://reader.hafa.cc. Needs the extension in desktop
  Chrome to fetch feeds.
- `extension/` — Chrome MV3 extension: fetches feeds and runs the per-feed
  schedule for the web app (`externally_connectable`). No UI.
- `android/` — Android Studio project: Kotlin + Compose, `:core` (JVM, ports
  of `shared/src` and the web's sync) + `:app`. Works on its own; Google
  sign-in turns on sync (Firebase settings in `SyncConfig.kt`, steps in
  `android/README.md`).
- `shared/` — TypeScript the web app and extension import directly, and
  fixtures every client's tests read. Install it first: its dependencies
  resolve from `shared/node_modules`.
- `firebase/` — Firestore rules and the manual setup steps; `firebase.json` at
  the root points into it.
- `design/icons/logo.svg` — the logo; the web and extension icons are made
  from it (`web/scripts/icons.ts`, `bun icons` in `extension/`).

## Conventions

- Public config (extension id, store page, Firebase web config) is committed
  in `web/src/lib/config.ts`, and Android's Firebase config in
  `android/app/.../sync/SyncConfig.kt`. No environment variables, no `.env`
  files.
- User-facing wording lives in `web/src/lib/copy.ts`.
- The phone web app and the Android app stay at parity: a behavior change to
  one goes to the other in the same pass.
- The privacy policy and terms (`web/src/routes/(policy)/`) must stay true:
  update them when what is fetched, stored or synced changes.
- Lint treats warnings as errors everywhere (`bun lint`, Android lint and
  Kotlin).

## Gotchas

- `~/.gitignore` is `*`. Outside a repository Tailwind reads it and generates
  no utilities; if the built CSS lacks `.flex`, look there first.
- The extension's id comes from the `key` in `extension/manifest.json`, the
  Chrome Web Store listing's public key, so an unpacked build gets the store's
  id; `bun pack` leaves it out of the upload (`extension/README.md`).
