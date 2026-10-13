# Reader Chrome extension

Fetches feeds for the Reader web app, which can't read most sites itself, and
checks them on a schedule in the background. No UI of its own; the page talks
to it with `chrome.runtime.sendMessage` (message types in
`../shared/src/protocol.ts`).

## Build

```sh
cd ../shared && bun install   # the extension imports shared/src
cd ../extension
bun install
bun export      # bundles to dist/
bun lint        # type-check and biome
bun pack        # reader-extension.zip for the Web Store
bun icons       # icons/ from ../design/icons/logo.svg; needs rsvg-convert
```

## Load unpacked

1. Open `chrome://extensions` and turn on **Developer mode**.
2. **Load unpacked** → pick `extension/dist`.
3. The card shows the extension **ID**, `ehmpednijaggcbpmjldkdookepbjindc`.

## The ID

`manifest.json` carries the Chrome Web Store listing's public key as `key`
(store dashboard → Package → View public key, as one base64 line), so an
unpacked build has the same ID as the store's, which `web/src/lib/config.ts`
expects. `bun pack` leaves `key` out of the upload, which the store requires,
and `http://localhost/*` out of the pages that may message it.
