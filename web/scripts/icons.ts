/*
 * Regenerates the site's icons in static/ from the logo, design/icons/logo.svg (the navy
 * tile with rounded corners). Needs rsvg-convert (librsvg) and ImageMagick's magick. The
 * output is committed, so builds don't need either.
 */

import { $ } from "bun";

const STATIC_DIR = new URL("../static/", import.meta.url).pathname;
const LOGO = new URL("../../design/icons/logo.svg", import.meta.url).pathname;
const TILE = "#16233f";
const CORNER_RADIUS = / rx="[^"]*"/u;

const logo = await Bun.file(LOGO).text();
await Bun.write(`${STATIC_DIR}favicon.svg`, logo);
await Bun.write(`${STATIC_DIR}icon.svg`, logo);

const pngs: [number, string][] = [
  [16, "favicon-16.png"],
  [32, "favicon-32.png"],
  [192, "icon-192.png"],
  [512, "icon-512.png"],
];
await Promise.all(
  pngs.map(
    ([size, name]) =>
      $`rsvg-convert -w ${size} -h ${size} ${LOGO} -o ${STATIC_DIR}${name}`,
  ),
);
await $`magick ${STATIC_DIR}favicon-32.png ${STATIC_DIR}favicon-16.png ${STATIC_DIR}favicon.ico`;
await $`rm ${STATIC_DIR}favicon-16.png`;

// iOS rounds the corners itself and fills transparency with black, so the touch icon is
// the tile's full square
const square = `${STATIC_DIR}touch-square.svg`;
await Bun.write(square, logo.replace(CORNER_RADIUS, ""));
await $`rsvg-convert -w 180 -h 180 -b ${TILE} ${square} -o ${STATIC_DIR}apple-touch-icon.png`;
await $`magick ${STATIC_DIR}apple-touch-icon.png -alpha off ${STATIC_DIR}apple-touch-icon.png`;
await $`rm ${square}`;
