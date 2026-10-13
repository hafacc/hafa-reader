import DomPurify from "dompurify";
import { upgradedAddress } from "$shared/address.ts";

const MIN_ALT_LENGTH = 12;
// a srcset candidate: its address after any separators, then descriptors up to a comma
const CANDIDATE_START = /[\s,]*(?<address>\S+)/uy;
const DESCRIPTORS = /(?<descriptors>[^,]*),?/uy;
const TRAILING_COMMAS = /,+$/u;
/** Attributes holding the address of a picture or video, which must load over https. */
const MEDIA_ATTRIBUTES = ["src", "poster"];
const LOADABLE = /^(?:https|data):/iu;
const SVG_NAMESPACE = "http://www.w3.org/2000/svg";
const XLINK_NAMESPACE = "http://www.w3.org/1999/xlink";
// elements whose text stands apart from what follows, as a browser lays it out
const BLOCKS =
  "address, article, aside, blockquote, dd, div, dl, dt, figcaption, figure, footer, h1, h2, h3, h4, h5, h6, header, hr, li, main, nav, ol, p, pre, section, table, td, th, tr, ul";

/**
 * Attributes article markup may not keep: inline styles, and classes and ids,
 * which would let an article use the app's own styles to cover or hide it.
 */
export const FORBIDDEN_ATTRIBUTES = ["style", "class", "id"];

let hooked = false;
/** Base address of the article being sanitized; the hook below runs inside one `sanitize` call. */
let currentBase = "";

/**
 * An address made absolute against a web base, with plain http upgraded to
 * https; unchanged when it is not a web address, as `mailto:` links.
 */
export function absoluteUrl(address: string, base: string): string {
  const usable = upgradedAddress(base);
  const trimmed = address.trim();
  const resolved =
    trimmed === "" ? "" : upgradedAddress(trimmed, usable || undefined);
  return resolved || address;
}

/** Whether a picture may load from an address: https, or data held in the page. */
function loadable(address: string): boolean {
  return LOADABLE.test(address);
}

/** One picture offered by a `srcset`. */
export interface SrcsetCandidate {
  /** The picture's address. */
  address: string;
  /** Its width or density, such as `424w` or `2x`; empty when not given. */
  descriptors: string;
}

/**
 * The candidates of a `srcset`, read as the HTML spec does: an address runs
 * to the next whitespace, so it may hold commas (as CDN addresses and data
 * addresses do), and its descriptors run to the next comma.
 */
export function srcsetCandidates(srcset: string): SrcsetCandidate[] {
  const candidates: SrcsetCandidate[] = [];
  let position = 0;
  CANDIDATE_START.lastIndex = position;
  let start = CANDIDATE_START.exec(srcset);
  while (start !== null) {
    let address = start.groups?.["address"] ?? "";
    let descriptors = "";
    position = CANDIDATE_START.lastIndex;
    if (address.endsWith(",")) {
      address = address.replace(TRAILING_COMMAS, "");
    } else {
      DESCRIPTORS.lastIndex = position;
      descriptors = (
        DESCRIPTORS.exec(srcset)?.groups?.["descriptors"] ?? ""
      ).trim();
      position = DESCRIPTORS.lastIndex;
    }
    candidates.push({ address, descriptors });
    CANDIDATE_START.lastIndex = position;
    start = CANDIDATE_START.exec(srcset);
  }
  return candidates;
}

/**
 * A `srcset` with each candidate's address made absolute against a web base
 * and upgraded to https; candidates that still cannot load over https are
 * left out.
 */
export function absoluteSrcset(srcset: string, base: string): string {
  return srcsetCandidates(srcset)
    .map(({ address, descriptors }) => ({
      address: absoluteUrl(address, base),
      descriptors,
    }))
    .filter(({ address }) => loadable(address))
    .map(({ address, descriptors }) => `${address} ${descriptors}`.trim())
    .join(", ");
}

/** Whether sanitized HTML refers to an address, written plainly or with `&` escaped. */
export function mentionsUrl(html: string, address: string): boolean {
  return (
    address !== "" &&
    (html.includes(address) || html.includes(address.replaceAll("&", "&amp;")))
  );
}

function markAttributes(node: Element): void {
  const href = node.getAttribute("href");
  if (href !== null) {
    node.setAttribute("href", absoluteUrl(href, currentBase));
  }
  for (const name of MEDIA_ATTRIBUTES) {
    const value = node.getAttribute(name);
    if (value !== null) {
      const address = absoluteUrl(value, currentBase);
      if (loadable(address)) {
        node.setAttribute(name, address);
      } else {
        node.removeAttribute(name);
      }
    }
  }
  const srcset = node.getAttribute("srcset");
  if (srcset !== null) {
    const resolved = absoluteSrcset(srcset, currentBase);
    if (resolved === "") {
      node.removeAttribute("srcset");
    } else {
      node.setAttribute("srcset", resolved);
    }
  }
  markLink(node);
  if (node instanceof HTMLImageElement) {
    node.setAttribute("referrerpolicy", "no-referrer");
    node.setAttribute("loading", "lazy");
  }
}

/** Make links open apart without a referrer; links inside SVG lose their address. */
function markLink(node: Element): void {
  if (node.namespaceURI === SVG_NAMESPACE && node.localName === "a") {
    // browsers differ on whether an SVG link honors rel, so it is no link at all
    node.removeAttribute("href");
    node.removeAttributeNS(XLINK_NAMESPACE, "href");
    node.removeAttribute("target");
  } else if (
    node instanceof HTMLAnchorElement ||
    node instanceof HTMLAreaElement
  ) {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
    node.setAttribute("referrerpolicy", "no-referrer");
  }
}

function caption(image: HTMLImageElement): string {
  const title = (image.getAttribute("title") ?? "").trim();
  const alt = (image.getAttribute("alt") ?? "").trim();
  return title || (alt.length > MIN_ALT_LENGTH ? alt : "");
}

/**
 * Make article HTML safe to show. Relative links and pictures are resolved
 * against `base`, the article's own address. An image's title or longer alt
 * text, where web comics hide their jokes, is added beneath it as plain text.
 */
export function sanitize(html: string, base = ""): string {
  if (!hooked) {
    hooked = true;
    DomPurify.addHook("afterSanitizeAttributes", markAttributes);
  }
  currentBase = base;
  const fragment = DomPurify.sanitize(html, {
    // biome-ignore lint/style/useNamingConvention: option name from the library
    FORBID_TAGS: ["style", "form", "input", "button", "textarea", "select"],
    // biome-ignore lint/style/useNamingConvention: option name from the library
    FORBID_ATTR: FORBIDDEN_ATTRIBUTES,
    // biome-ignore lint/style/useNamingConvention: option name from the library
    RETURN_DOM_FRAGMENT: true,
  });
  currentBase = "";
  for (const image of Array.from(fragment.querySelectorAll("img"))) {
    const text = caption(image);
    if (text) {
      const note = document.createElement("p");
      note.className = "alt-text";
      note.textContent = text;
      (image.closest("a") ?? image).after(note);
    }
  }
  for (const table of Array.from(fragment.querySelectorAll("table"))) {
    const scroller = document.createElement("div");
    scroller.className = "table-scroll";
    table.replaceWith(scroller);
    scroller.append(table);
  }
  const holder = document.createElement("div");
  holder.append(fragment);
  return holder.innerHTML;
}

/** Plain text as one paragraph of HTML, for an article with no stored body; empty for no text. */
export function plainBody(text: string): string {
  const escaped = text
    .trim()
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
  return escaped === "" ? "" : `<p>${escaped}</p>`;
}

/** The start of an article's text with markup, styles and scripts removed. */
export function excerpt(html: string, limit = 220): string {
  const document = new DOMParser().parseFromString(html, "text/html");
  for (const hidden of Array.from(
    document.querySelectorAll("style, script, noscript, template"),
  )) {
    hidden.remove();
  }
  // text from neighboring blocks, or either side of a line break, must not run together
  for (const block of Array.from(document.querySelectorAll(BLOCKS))) {
    block.append(" ");
  }
  for (const lineBreak of Array.from(document.querySelectorAll("br"))) {
    lineBreak.replaceWith(" ");
  }
  const text = (document.body.textContent ?? "").replace(/\s+/gu, " ").trim();
  return text.slice(0, limit);
}
