# H Reader web

The H Reader web app, live at [reader.hafa.cc](https://reader.hafa.cc/). It
runs in desktop Chrome with the [H Reader extension](../extension), which
fetches its feeds.

```sh
cd ../shared && bun install   # the web app imports shared/src
cd ../web
bun install
bun dev      # http://localhost:5173
bun test
bun export   # static site in build/
```

Public settings (the extension's id, the Firebase web config) are committed in
`src/lib/config.ts`; there are no environment variables. The privacy policy and
terms are plain pages in `src/routes/(policy)/`, built without script.
`bun scripts/icons.ts` regenerates `static/` icons from
`../design/icons/logo.svg` (needs rsvg-convert and ImageMagick).
