# shared

The behavior Reader's clients must agree on, as code and fixtures: the web app (`web/`) and the Chrome extension
(`extension/`) import `src/` directly, and the Android app's `core` module
ports it to Kotlin and reads `fixtures/` in its tests.

| Path | What |
| --- | --- |
| `src/feed.ts` | RSS 2.0, Atom and JSON Feed parsing |
| `src/decode.ts` | a response body's text in the character set it names |
| `src/address.ts` | https-only addresses resolved against a base |
| `src/resolve.ts` | feed discovery: the addresses a service is known to offer, and links in a page |
| `src/filter.ts` | per-view words and topics |
| `src/id.ts` | feed and entry ids (SHA-256) |
| `src/schedule.ts` | when to check each feed next, and the chance it has news |
| `src/check.ts` | one check of a feed and what it found |
| `src/inbox.ts` | feeds the extension fetched, waiting for the web app |
| `src/protocol.ts` | the messages between the web app and the extension |
| `fixtures/` | feeds (`rss.xml`, `atom.xml`, `feed.json`) every client's parser must read |
| `fixtures/schedule.json` | schedule cases with the numbers every client's schedule must produce |
| `fixtures/parse.json` | feeds (`youtube.xml`, `atom-media.xml`) and the exact result every client's parser must give |
| `fixtures/ids.json` | feed addresses and entry guids with the ids every client must compute |
| `src/schedule-fixture.ts` | builds those cases; `bun scripts/schedule-fixture.ts` rewrites the file |

```sh
bun install   # the web app and extension resolve these dependencies from here
bun test
bun lint
```

A change to parsing, ids, filters or the schedule starts here, then goes to
the Kotlin port in `android/core`.

## Parse and id fixtures

`fixtures/parse.json` is written by hand and checked against the parser:

```json
{ "cases": [ { "input": { "file": "youtube.xml", "feedUrl": "..." }, "output": { "title", "siteUrl", "iconUrl", "entries": [ { "guid", "title", "url", "published", "author", "imageUrl", "summary", "categories", "html" } ] } } ] }
```

`file` is next to it in `fixtures/`; `output` is the whole parse result and
must match exactly. `fixtures/ids.json` holds literal SHA-256 hex worked out
outside the code:

```json
{ "feeds": [ { "input": { "url" }, "output": "<feed id>" } ], "entries": [ { "input": { "feedId", "guid" }, "output": "<entry id>" } ] }
```

## Schedule fixture

`fixtures/schedule.json` is generated from `src/schedule.ts`; regenerate it
with `bun scripts/schedule-fixture.ts` after a change to the schedule, and port
the change until the Kotlin tests pass against it again.

```json
{ "version": 1, "sections": { "<section>": [ { "input": { ... }, "output": ... } ] } }
```

Times are milliseconds since the epoch, UTC. A `model` is
`{ alpha, beta, capacity, pattern?: { hourOfDay[24], dayOfWeek[7], dayOfMonth[31] } }`.
Each section is one function; its inputs are named as below and the output is
its return value.

| Section | Input | Output |
| --- | --- | --- |
| `bins` | `timeMs` | `{ hourOfDay, dayOfWeek, dayOfMonth }` |
| `expectedActivity` | `model`, `fromMs`, `toMs` | days |
| `newProbability` | `model`, `lastCheckedMs`, `nowMs` | chance |
| `missProbability` | `model`, `days`, `capacity` | chance |
| `chooseInterval` | `model`, `nowMs` | milliseconds |
| `planRefresh` | `nowMs`, `feeds: [{ id, model, lastChecked, nextFetch, failures }]` | `{ ordered: ids, probabilities, foreground }` |
| `hintFromHeaders` | `headers: { name: value }`, `nowMs` | milliseconds |
| `retryInterval` | `failures` | milliseconds |
| `observe` | `model`, `newItems`, `fromMs`, `toMs`, `windowSize` | model |
| `observeWindow` | `model`, `published: number[]` | model |
| `updatePattern` | `pattern?` (absent means none), `published`, `elapsedMs` | pattern |
| `learnPattern` | `model`, `published` | model |
| `patternFactors` | `pattern?` | `{ hourOfDay, dayOfWeek, dayOfMonth }` factors |

Compare numbers within a relative 1e-9 (absolute 1e-12 near zero); arrays by
position, objects by key, and a key absent on one side only must be absent on
both. `planRefresh`
sorts with a stable sort, so feeds of equal chance keep their input order.
`chooseInterval` bisects on a threshold, so a last-bit difference in `log` or
`exp` between runtimes can move it further; within 1 second counts as a match.
