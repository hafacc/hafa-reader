# Firebase setup

The web app syncs through Firebase Auth (Google sign-in) and Firestore. The
project `hafa-reader` exists, with its settings committed in
`web/src/lib/config.ts` and the Android app's `SyncConfig.kt`, `reader.hafa.cc`
among its authorized domains, and the rules and indexes deployed. Still to do:

- App Check (step 9).
- Budget alerts (step 10).
- The Play app signing key's SHA-1 on the Android app (step 6), once the app is
  in the Play Console.

The steps below are also how to set the project up again.

1. **Create a project** at <https://console.firebase.google.com>. Analytics is
   not needed.
2. **Enable Google sign-in:** Authentication, Sign-in method, Google, Enable.
   Set the support email.
3. **Authorized domains:** Authentication, Settings, Authorized domains. Add
   `reader.hafa.cc`. `localhost` is there by default; keep it for `bun run dev`.
4. **Create the Firestore database:** Firestore Database, Create database,
   production mode, location `us-central1` (the prices in `PLAN.md` assume it;
   the location cannot be changed later).
5. **Register a web app:** Project settings, General, Your apps, Add app, Web.
   No Hosting is needed (the site is on GitHub Pages).
6. **Give the settings to the web app:** copy `apiKey`, `authDomain`,
   `projectId` and `appId` from the web app's config into `firebaseConfig` in
   `web/src/lib/config.ts` and commit them. They are public by design; the
   rules below are what protect the data. The site's content policy allows
   any `*.firebaseapp.com` auth domain and the Google hosts Firebase calls.

   **Android:** add the Android app, its SHA-1 keys (upload, Play app signing
   and debug) and the web client id Google sign-in uses, as described under
   Sync in `android/README.md`.
7. **Deploy the rules** from the repository root, where `firebase.json` is:

   ```sh
   npm install -g firebase-tools   # or: bunx firebase-tools
   firebase login
   firebase deploy --only firestore   # .firebaserc picks hafa-reader
   ```

   Or paste `firestore.rules` into Firestore Database, Rules, and Publish.
8. **Check in the rules playground** (Firestore Database, Rules, Rules
   playground) before relying on them: a signed-in create of `bodies/{hash}`
   with `{ text }` passes only when `hash` is the lower-case hex SHA-256 of the
   text, and reading or writing another user's `users/{uid}` fails.
9. **App Check:** App Check, register the web app with reCAPTCHA
   Enterprise and the Android app with Play Integrity, then enforce it for
   Firestore. Enforcing it while an app does not initialize App Check blocks
   that app's sync.
10. **Billing:** in Google Cloud Billing, Budgets and alerts, create a budget
    for the project with email alerts (for example at 50%, 90% and 100% of a
    small monthly amount). A budget only warns; it does not stop spending. The
    free tier covers testing.
11. **Expiry:** the sync section's "Keep synced articles" setting stamps
    entries, and bodies uploaded under it, with `expireAt`. The time-to-live
    policies on that field, for the `entries` and `bodies` collection groups,
    are declared in `firestore.indexes.json` and created by
    `firebase deploy --only firestore`. Firestore then deletes expired
    documents from the account only; copies on devices stay. Check them at
    https://console.firebase.google.com/project/hafa-reader/firestore/databases/-default-/ttl.

## What is stored

- `users/{uid}/subscriptions/{feedId}`: feed address, title, folder, renamed,
  icon, site address, `updatedAt`, `deleted` (a tombstone when unsubscribed).
- `users/{uid}/entries/{entryId}`: the article's details, `bodyHash`, read,
  starred, `addedAt`, `updatedAt`. `expireAt` only under a finite retention and
  when not starred, which the TTL policy from step 11 acts on.
- `users/{uid}/settings/{viewKey}`: one view's sort, words and topics,
  `updatedAt`, `deleted`. The document id is the view key, URL-encoded.
- `users/{uid}/settings/account`: `retentionDays` (30, 90, 365, or 0 for
  forever), `updatedAt`, and `stampedDays` (the retention the entries were
  last all stamped with; same values, optional).
- `bodies/{hash}`: `{ text }`, shared by everyone, created once; `expireAt`
  too when uploaded under a finite retention.

Delete account, in the web app's sync section, removes everything under
`users/{uid}` (subscriptions, entries, settings) and the Firebase Auth user;
`bodies` stay, since they are shared and hold nothing about who stored them.

Every document under `users` also has `syncedAt`, the server time of its last
write, which devices listen on for changes.

## Indexes

None to create: every query uses one field (`syncedAt`, `published` or
`feedId`) plus the document id, which the automatic single-field indexes cover.
If Firestore logs a "requires an index" error with a link, follow the link,
and add the index to `firestore.indexes.json` so a deploy keeps it.

`firestore.indexes.json` turns indexing off for text no query reads (`bodies`
`text`; `entries` `title`, `excerpt`, `summary`, `categories`, `guid`, `url`,
`author`, `imageUrl`), which saves index storage and write cost. A query on
one of them needs its override removed first.

## Rules

`firestore.rules` accepts only the fields the clients write, with their types,
a size limit on each text field (the same as `FIELD_LIMITS` in
`web/src/lib/sync/documents.ts`; clients cut text to fit before writing) and
`syncedAt` equal to the server time of the write. A client that writes another
field, or longer text, has its whole batch refused.
