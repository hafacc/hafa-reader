# Reader plan

An RSS reader for the web, Android and iOS. No server code: every device
fetches feeds itself and syncs through Firebase.

## Decisions

- **Stack:** SvelteKit 3, Svelte 5, Tailwind 4, Vite 8, bun, Biome, static
  adapter.
- **Layout:** `web/`, `extension/`, `android/`, `shared/`. Clients share
  behavior and fixtures, not code. `shared/fixtures` holds JSON every client's
  tests must pass.
- **Interface:** three panes (sources, article list with thumbnails, reader) on
  wide screens; list screen then reader screen on phones. Keys, shown by `?`: `j` `k` next and previous article (stepping past either end
  closes it), `v` or `o` open the original, `s` star, `m` read or unread, `Shift A`
  mark all read, `r` refresh the view, `Shift R` refresh every feed, `u` unread only,
  `a` add a feed, `,` view settings, `b` collapse the sidebar, `[` `]` previous and
  next feed or group, `g a` and `g s` go to All items and Starred, `Esc` closes.
- **Standalone first:** every client (web with the extension, Android, iOS)
  works completely with no account: feeds, history, read state and search all
  live in its local database. Signing in with Google only turns on upload and
  sync; signed-out data stays on the device. Signing in uploads what is already
  on the device and merges it with what the account has.
- **Sync:** Firebase Auth (Google sign-in) plus Firestore. No Cloud Functions.
  The web app loads Firebase only once someone signs in (`web/src/lib/sync/`);
  Android signs in through Credential Manager (`android/core/.../sync/`). The
  extension has no Firebase code. Rules and setup steps are in `firebase/`.
- **Fetching:** the device does it. Web requires the extension (cross-site reads);
  native apps fetch directly.
- **Formats:** RSS 2.0, Atom and JSON Feed.
- **Images:** never stored. The article keeps the image URL and the reader loads
  it from the original site.
- **https only:** no plain http anywhere. Addresses typed or imported as
  `http://` are rewritten to `https://` before the first try (a feed that then
  fails shows as failing); feed, site, icon and image addresses from feeds are
  kept only when https (http upgraded); links and pictures in article text are
  upgraded to https, and a picture that cannot load over https is dropped. The
  web app's content policy loads pictures and media from https and data only.

## Data model

All paths are Firestore. `{uid}` is the Firebase Auth id.

- `users/{uid}/subscriptions/{feedId}`: feed URL, title, folder, renamed, icon,
  site URL, `updatedAt`, `deleted`. `feedId` = sha256 of the normalized feed URL.
  Unsubscribing leaves a tombstone (`deleted` true, the other fields empty) so
  the removal reaches other devices, which then drop the feed's entries; the
  device that unsubscribes also deletes the feed's entry documents.
  `updatedAt` changes only when the person subscribes, renames or regroups. A
  feed's own new title, site or icon (and an icon found after one fails to
  load) is kept on the device that read it, without touching `updatedAt` or
  writing the document, so it never overwrites a person's change from another
  device; every device reads those from the feed itself, and they reach the
  account with the next change the person makes.
- `users/{uid}/entries/{entryId}`: one per article the user has received.
  Fields: `feedId`, `guid`, `title`, `published`, `dated`, `url`, `author`,
  `imageUrl`, `excerpt`, `summary`, `categories`, `bodyHash`, `read`, `starred`,
  `addedAt`, `updatedAt`, and `expireAt` only under a finite retention and
  when not starred.
  `entryId` = sha256(`feedId` + `guid`), so two devices adding the same article
  write the same document.
- `users/{uid}/settings/{viewKey}`: one view's sort, words and topics, with
  `updatedAt` and a `deleted` tombstone flag; the id is the URL-encoded view key
  (`all`, `starred`, `folder:{name}`, `feed:{id}`), which the document also
  holds as `key`.
- `users/{uid}/settings/account`: the account's own settings, `retentionDays`
  (an integer: 30, 90 or 365, or 0 for forever), `updatedAt`, and
  `stampedDays`, the retention the account's entries were last all stamped
  with (same values; missing reads as 0); last write wins by `updatedAt`. No
  view key encodes to `account`.
- Every document under `users` carries `syncedAt`, the server time of its last
  write; devices listen for changes by it, so a device's wrong clock cannot hide
  a change. `updatedAt` is the device's clock and decides last write wins.
- `bodies/{hash}`: article text only, shared by all users. `hash` = sha256 of the
  stored text. Never updated or deleted by clients.

Size: an entry is about 0.5 KB; a body about 10 KB.

## History

Each account chooses how long the account keeps entries: 30 days, 90 days, 1
year or forever, the default ("Keep synced articles" in the sync section, shown
only when signed in). The choice is saved in `users/{uid}/settings/account`, so
every device follows it. Expiry removes articles from the account only, never
from a device.

- **Finite retention:** each entry carries an `expireAt` (a time-to-live field)
  of its `addedAt` plus the retention, so Firestore deletes it for $0.01 per
  100,000. Starred entries never carry one: starring clears it and unstarring
  sets it again. A body uploaded under a finite retention carries an `expireAt`
  of its creation time plus the longest retention offered, plus a week (365 + 7
  days); under forever it carries none. A device that still needs a body that
  has expired uploads its local copy again; the rules accept it because the hash
  matches. The web app does this when an article it holds the body of is
  opened and was added longer ago than a body lasts (365 + 7 days): it reads
  `bodies/{hash}` from the server once a session per hash (none when it has
  just downloaded that body) and, if the document does not exist, creates it
  with the usual expiry, the same read and write as the first upload.
- **Changing the retention:** new uploads and mark changes use it at once. The
  device where it was changed then goes over the account's entries once, in
  pages of 500 by id, and sets or clears each one's `expireAt` (starred ones
  cleared), writing only the entries that change and only that field, so other
  devices download nothing again. It shows as syncing, and a reload carries on
  from the last page done; signing out keeps the unfinished pass, which goes on
  at the next sign-in. When the pass ends it writes `stampedDays` (a merge of
  that field alone). Any device that sees the account's `retentionDays` differ
  from its `stampedDays`, and has no pass of its own under way, runs the pass,
  so one stopped midway, or on a device that never came back, still finishes.
- **Forever:** storage grows about 62 MB per user per year at 100 new items a
  day. 1,000 users cost about $9 a month after one year and $47 after five;
  10,000 users cost $93 and $465. Bodies carry no `expireAt`.
- **New devices** load entries newest first in pages of 500: the last 90 days
  straight away, then older pages in the background with a short pause between
  them, so first sync stays fast however long the history is. Progress is kept,
  so a reload carries on where it stopped.
- **Account deletion:** Delete account in the sync section (pressed twice to
  confirm) first signs in again, straight from the press, so Firebase accepts
  deleting the sign-in; closing that changes nothing. It then waits for writes
  on their way, removes the user's subscriptions, entries and settings from
  Firestore, the device's sync progress for that account, and the Firebase
  sign-in itself, then signs out. If removing the documents fails, sync carries
  on; if only removing the sign-in fails, the device signs out anyway (with the
  failure shown), so its data is not uploaded into the emptied account. What is on the device stays. Bodies are shared and
  left alone; they are public article text. Expiring bodies (finite retention)
  clear themselves.
- **Signing out** first sends queued changes and waits up to 5 seconds, when
  online, for writes on their way to reach the account, since they land only
  while signed in.
- **Refused writes:** a batch the rules or the data refuse (permission denied,
  invalid argument, failed precondition) is tried again one document at a
  time; refused documents are dropped and logged, so the valid changes land
  instead of the batch being retried forever.
- **No per-body user counts.** A count needs one extra write per entry (about
  $27 a month more at 10,000 users) and the rules cannot check that a client's
  count is honest, so a bad client could delete text others need. Expiry plus
  re-upload gives the same cleanup with no extra writes.

## Rules

- Entries and subscriptions: read and write only when `request.auth.uid == uid`.
  Writes must hold exactly the fields the clients write, with their types,
  text within the limits in `FIELD_LIMITS` (`web/src/lib/sync/documents.ts`;
  clients cut text to fit, and keep on the device a feed, entry or view whose
  address, guid or key is longer), and `syncedAt` equal to the write's server
  time.
- Bodies: any signed-in user may read one by its hash (listing is not allowed).
  Create only when the document does not
  exist, the id equals `hashing.sha256(text)` (rules have this function), and the
  text is under a size cap. No update, no delete.
- Because the rules check the hash, a forged body cannot sit under a real
  article's hash. Clients still compare the hash after download.
- Turn on App Check so only the real apps can write; it limits junk uploads.

## Sync algorithm (every client)

1. Refresh: the extension owns fetching and timing; the web app cannot read most
   feeds itself, so it requires the extension and links to it when it is missing.
   Phones and tablets cannot install it, so, judged by the user agent alone, an
   Android browser goes straight to the Play Store listing and an iPhone or iPad
   browser gets a short note that Reader is not there yet.
   For each feed the extension keeps one model of when it posts
   (`shared/src/schedule.ts`): a Poisson process whose rate at a time is a base
   daily rate times three factors, for the UTC hour of the day (24 bins), the day
   of the week (7, Monday first) and the day of the month (31). The base rate has
   a Gamma distribution: the first look uses the dates on the items in the feed,
   later checks add how many new items appeared over the expected activity of the
   time in between, and old evidence fades with a 60-day half-life. Each factor
   counts the published dates of the items in the first look and of each check's
   new items (undated items are skipped), fading with the same half-life; reading
   one adds 0.1 to every bin (1 for the day of the month, so it stays near flat
   until a few dozen posts are seen) and divides by the mean, so each factor averages one
   over its own period and a new feed is flat. The expected activity between two
   times is the integral of the three factors' product, in days, stepped hour by
   hour along UTC hours for up to 35 days, each later day counting as one; a flat
   feed's activity is the elapsed days, and a model with no pattern is flat. It
   also remembers the most items the feed has shown at once (its window).

   That one model drives both the timer and refreshes. The next check is the
   longest wait whose expected activity keeps the chance that more new items
   appear than the window can show under 1 in 10,000, counting only half the
   window to allow for clumped posts, so a feed that posts only on weekdays sleeps
   over the weekend and quiet hours are skipped. Waits range from 15 minutes to 7
   days, never shorter than the site's `Retry-After` or `Cache-Control: max-age`,
   and a failing feed backs off from 15 minutes by doubling, or longer when the
   site asks. Opening the app and the refresh control force a check of every
   feed, so freshness comes from the reader and the schedule only has to avoid
   missing posts. Every fetch sends `ETag` and `If-Modified-Since`; a `304` ends
   it. The chance a feed has news since its last successful check is
   `1 - (beta / (beta + activity))^alpha` over the expected activity since then;
   a feed never checked counts as one, a failing feed as 0.5 if it is due and
   zero if not.

   A refresh of more than five feeds sorts them by that chance and waits only
   for the first ones: the shortest prefix after which the chances left add up
   to at most 0.5 or 2% of the total, whichever is more, and never fewer than
   the feeds at 0.5 or above. It replies once those are saved; the others are
   still fetched, in order, and land in the inbox, where the web app's drain
   every few seconds picks them up. A refresh of five or fewer waits for all.
   Feeds the refresh waits for get 8 seconds and, if slower, are retried in
   the background with the usual 20. The extension fetches 8 feeds at once, at
   most 2 from one host, one queue shared by refreshes and the timer (a feed
   already queued or being fetched is joined, not fetched twice, and moves up
   if a refresh needs it). Results are saved in batches (16, or every 5
   seconds, or as soon as the waited-for feeds are done), so a stopped
   extension loses at most a batch, and an unfinished feed simply stays due.
   While work is queued the extension makes a cheap browser call every 20
   seconds so Chrome does not stop it for being idle.
2. For each item, compute `entryId`. If it is in the local database, skip. This
   covers items another of your devices already added.
3. Otherwise compute the text hash, read `bodies/{hash}` (one read, a third the
   price of a write), and write it only if missing. Then write the entry.
4. Listen to `entries` with `syncedAt > lastSync`; store results locally.
   Subscriptions and settings are few, so devices listen to all of them. On a
   new device the first run downloads the last 90 days. Signing in on a device
   for the first time also uploads its own feeds, settings and entries that the
   account lacks (each entry looked up by id first) and merges the rest by last
   write wins. Bodies are downloaded only when they are not on the device: for an
   article when it is opened, and for the next article in the way the reader
   is moving, so stepping on shows its pictures at once (at most one extra read
   per open, none when the browser asks to save data or Android's Data Saver
   is on). The hash is
   checked before a body is kept.
5. Local database: IndexedDB on web, SQLite on Android and iOS. Request
   persistent storage on web. Safari clears unused site data, so a cleared device
   re-syncs from Firestore, which is the point of syncing.
6. Read and starred changes write only the entry, last write wins by `updatedAt`;
   so do subscriptions and settings. Changes from the account are stored with
   their own `updatedAt` and never sent back. Writes are batched (at most 500)
   and queued by Firestore's offline cache while offline.

Background updates: the extension wakes on a timer; Android uses WorkManager
(about every 15 minutes at best); iOS uses background app refresh (system
decides). Web with the extension closed gets nothing until a device opens.

## Reading features

- **Per-view settings:** every feed, group, All items and Starred keeps one set of
  settings, the same panel everywhere, stored in the local database (`settings`
  store) and synced like entries. The panel opens where the article does,
  and as a full page on a phone. It holds sort (Newest or Oldest), words and
  topics (Starred has only the sort); the unread-only switch is in the list's
  bar, not here. A feed's panel also has its group and Unsubscribe.
- **Words and topics** (`shared/src/filter.ts`): type a word or phrase and press
  Enter; two buttons choose whether new words hide matching articles or, once any
  "only show" word exists, keep only articles that have one. Words match the title
  and summary with capitals ignored, any run of
  whitespace matches any whitespace, a word boundary is required at an end only
  when that end is a word character, and each list is compiled once. Every topic
  the feed supplies starts on; tap one to turn it off, and an article whose
  topics are all off is hidden. Chips never move when toggled. A feed's own
  settings also apply in every group and view it appears in.
- **Managing feeds and groups:** a feed's panel has its name (rename), a group
  field (type a name, with the existing groups suggested; empty for no group) and
  Unsubscribe; a group's
  panel has its name (renaming to an existing name merges the two) and Delete
  group, which leaves its feeds in no group. Adding a feed opens in the same
  detail column, as a full page on a phone, not as a pop-up. A renamed feed keeps
  its name when the feed's own title changes.
- **Feed search and discovery** (`shared/src/resolve.ts`): the add panel takes
  any address or other text. An address is tried as a feed, then what the
  service is known to offer, then feed links in the page, then common paths.
  Known patterns: YouTube channels and playlists, Reddit, GitHub releases and
  commits, Substack and other publications at `/feed`, Medium, Tumblr, Bluesky
  profiles, Mastodon profiles. YouTube `@handle` pages are found through their
  page link. Any other text searches Feedly's public feed search through the
  extension (a dependency to watch: it is not a documented open API and the
  query goes to Feedly).
- **OPML import:** drop a file on the panel or choose one; folders are kept;
  unusable addresses are skipped; the panel says when a file lists nothing or
  nothing new.
- **Feed pictures:** the feed's own icon, else the site's `/favicon.ico`, else a
  letter tile. The sidebar rows keep one icon column in both states.
- **Refreshing:** the Reader name goes to All items and checks every feed, as
  does `Shift R`; clicking the row of the view you are in, the `r` key and pulling
  the list down from its top on a phone check the feeds of that view, as does the
  Refresh button in the footer that ends every non-empty list ("End of list"
  and Refresh, shown once every row is drawn); there is no Refresh
  button in the bars and no gesture at the bottom. Opening the app checks every feed. A refresh drops the read articles
  kept in the list as soon as it starts; on a phone the list shows a turning
  marker until it ends.
- **Unread only:** one switch for the whole app, an eye button in the list's bar (at the bottom on a phone),
  on by default; it lists only unread articles in every feed and group (not in
  Starred) and hides feeds in the sidebar that have nothing unread (the one you are
  viewing stays). Changing view closes the open article. A failed feed is not
  colored: after two failures in a row a small muted marker appears, with the
  reason in the feed's panel and in a band at the top of its list.
- **Speed:** the list draws only the first 60 rows and more as the end nears;
  sorting and the word and topic filters are computed once per change, not per
  toggle. With 20,000 articles, toggling unread only takes about 60 ms, all on
  the main thread.
- **Reading state:** opening an article does not mark
  it read, leaving it does; read articles stay listed until the view changes;
  stepping past either end closes the article.
- **Sidebar order:** groups come first, then loose feeds; each list is ordered by
  unread count (most first), then working before failing, then by name; the feeds inside a group
  follow the same rule, and a group counts as failing only when all of its feeds
  do. The `[` and `]` keys follow the same order. The order is worked out at
  load and again after each refresh, and holds in between; counts still update live, and feeds added
  later go after the rest.
- **Loading logo:** the logo pulses while the app loads, feeds refresh, or a feed is
  being added (not with reduced motion).
- **First run:** with no feeds, the reader asks for a first feed and, below
  that, offers sign-in ("Already use Reader?" and a "Sign in to sync your
  feeds" button, the same sign-in as the sidebar's), for someone whose feeds
  are in an account. Signed in, it shows the sync status instead until the
  account's feeds have come from the server; an empty account, or sync that is
  offline or failing, gets the add prompt.
- **Always a view:** the app opens on the last feed or group used and falls back to
  All items when it is gone.
- **Dates:** an article takes its date from `pubDate`, `dc:date`, `published`,
  `issued`, `created`, `updated` or `modified`, with common time zone names read
  correctly. When a feed gives none, the article shows no time (it is placed by when
  it was first seen) rather than the time of the fetch.
- **Pictures:** an article with no image shows as text only. An image's title or
  longer alt text is shown under it, since web comics hide jokes there. With an
  article open, the app fetches ahead up to four pictures of each article on
  either side (the lead picture, then the body's first, as the sanitizer keeps
  them), unless the browser asks to save data or Android's Data Saver is on; the
  web fetches them without a referrer and at low priority.
- **Star and share:** the article's button is "Star", a status that fills when set.
  Share uses the device's share sheet where the browser has one, and otherwise
  copies the link.
- **Not supported:** Instagram, X and Threads publish no feeds and are left out.
  Telegram public channels have only a web preview page, which is not read:
  such readers break when the site changes.
- **Navigation:** one page app; the extension plays the part a service worker does
  for a PWA (fetching, schedule, background). On a phone the menu button in the
  list's bottom bar opens the feed list over the articles (the title is plain text),
  as does a swipe in from the left edge on the web (the Android app opens it only from
  the button, since a sideways swipe on a card marks it read); moving between the article list and an article
  adds browser history entries, so Back steps out. On Android, Back on the article list
  leaves the app, Back with the feed list open closes it, and Back from an article or a
  panel returns to the list.
- **Bars (phone):** every screen puts its controls in a bar at the bottom, where a
  thumb reaches them (Material's bottom app bar on Android, the same bar on the web,
  above the home indicator and the keyboard). The list has the menu button, the view's
  name, unread only, view settings and mark all as read; the article has Back, star,
  read, share and open the original, which on a phone appear only there; view settings,
  add feed and sync have Back and the screen's name. On Android the bars slide away while
  scrolling down and come back on the way up, on every new screen or article, and
  whenever the article is too short to scroll. Wide screens keep the bars at the top.
- **Gestures (phone):** swipe a card sideways to mark it read or unread; swipe an
  article left or right and it slides to the next or previous one in the list's order
  (a pager on Android); a swipe of at least 90 pixels (90 dp on Android) past the first or last slides
  the article off and closes it, as stepping past either end does with the keys,
  while a shorter one springs back; pull down at the top of the
  list to check the view's feeds (Material's pull to refresh on Android, the same
  marker on the web).
- **End of list:** the last row of a list that has articles says "End of list" and
  has a "Refresh" button that checks the view's feeds as pulling down does; while
  they are checked the button keeps its label, is disabled and its icon turns
  (still when the system or browser turns motion off). It sits above the bottom
  bar; there is no pull-up gesture.
- **Sidebar:** a brand header (the icon and "Reader") stays in place while the
  sidebar collapses to an icon strip, which opens over the list when hovered; icons
  and rows keep their positions so the change is a smooth width and fade.
- **Account picture:** when signed in, the sidebar's sync row and the account
  card show the Google account's picture in a round avatar (asked for at 96
  pixels, https only, without a referrer), with the email's first letter until
  it loads and instead when there is none or it fails. The status dot sits on
  it. The picture's address comes with the sign-in and is not stored apart
  from it.
- **Icon:** built on the feed symbol: a navy tile with an orange dot, a white
  inner arc and a light-blue outer arc. Files: `design/icons/logo.svg` (the source; `bun scripts/icons.ts` in
  `web/` and `bun icons` in `extension/` make the PNGs from it),
  `web/src/lib/components/logo.svelte`, the Android launcher vectors.
- **Store build:** `bun pack` in `extension/` builds `reader-extension.zip`
  without the `key` (the store listing's public key) or the localhost entry in the web app's allowed
  origins.

## Cost

Firestore prices in us-central1, read from Google's pricing page: reads $0.03,
writes $0.09, deletes $0.01 per 100,000; storage about $0.15 per GB-month. Free
daily quota: 50,000 reads, 20,000 writes, 1 GiB stored. The figures below are my
arithmetic from those prices, with 100 feeds and 100 new items a day per user,
2.5 devices per user, 90 days kept.

| Users | Entry writes | Body writes | Reads (checks and syncing) | Storage | About, per month |
| --- | --- | --- | --- | --- | --- |
| 1,000 | 3M | 0.4M | 12M | 14 GB | $10 |
| 10,000 | 30M | 3.6M | 120M | 150 GB | $100 |
| 100,000 | 300M | 20M | 1.2B | 1.5 TB | $1,000+ |

The table assumes 90 days, but forever is the default, so storage keeps growing
by the amounts in the History section every year. Writes and reads stay as shown.

Levers if it grows: keep fewer days, store an excerpt and load full text from the
original site, pack a day of one feed's entries into a single document, make
sync a paid tier.

Billing of a create that fails because the document exists is not stated in
Google's pricing page, so the design never relies on it: always read first. Run
real traffic against a test project and read the billing report.

## Risks

- Feeds with full text in the feed versus summary only: the reader needs a
  readability fallback that fetches the page, which also needs cross-site access.
- Feed URLs and reading history sit in Firestore under the user's id; the
  privacy policy must say so.
- Per-user writes mean a heavy user costs about $0.01 a month; a user who
  subscribes to hundreds of busy feeds costs far more. Cap items per feed per day.
