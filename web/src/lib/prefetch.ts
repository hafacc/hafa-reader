import { mentionsUrl } from "./sanitize.ts";

/** Most pictures fetched ahead for one article, its lead picture included. */
export const PICTURES_PER_ARTICLE = 4;
const SECURE = /^https:/iu;

/** A picture to fetch ahead, as an article's `img` would load it. */
export interface AheadPicture {
  /** The picture's address; empty when only `srcset` gives one. */
  src: string;
  /** The `srcset` the sanitizer kept; empty when there is none. */
  srcset: string;
}

/**
 * The pictures an article shows first: its lead picture when the article
 * shows it (https, and not already in the body), then the body's first
 * pictures, at most `limit` in all. `body` must be the sanitized HTML the
 * article renders, so nothing the sanitizer dropped is fetched.
 */
export function aheadPictures(
  imageUrl: string,
  body: string,
  limit = PICTURES_PER_ARTICLE,
): AheadPicture[] {
  const pictures: AheadPicture[] = [];
  if (SECURE.test(imageUrl) && !mentionsUrl(body, imageUrl)) {
    pictures.push({ src: imageUrl, srcset: "" });
  }
  const parsed = new DOMParser().parseFromString(body, "text/html");
  for (const image of Array.from(parsed.querySelectorAll("img"))) {
    const src = image.getAttribute("src") ?? "";
    const srcset = image.getAttribute("srcset") ?? "";
    const fetchable = SECURE.test(src) ? src : "";
    if ((fetchable !== "" || srcset !== "") && pictures.length < limit) {
      pictures.push({ src: fetchable, srcset });
    }
  }
  return pictures.slice(0, limit);
}

/** Which way the reader last moved through the list: 1 down to the next row, -1 up. */
export type Heading = 1 | -1;

/** The rows on either side of the open one: `ahead` in the way the reader is moving. */
export interface Neighbors<Row> {
  /** The row the reader reaches next by going on the same way. */
  ahead: Row | undefined;
  /** The row the reader came from. */
  behind: Row | undefined;
}

/** The open row's neighbors; none when the list no longer holds it. */
export function neighborsOf<Row extends { id: string }>(
  rows: readonly Row[],
  openId: string,
  heading: Heading,
): Neighbors<Row> {
  const index = rows.findIndex((row) => row.id === openId);
  return index === -1
    ? { ahead: undefined, behind: undefined }
    : { ahead: rows[index + heading], behind: rows[index - heading] };
}

declare global {
  interface Navigator {
    /** The Network Information API, which only Chromium browsers offer. */
    readonly connection?: { readonly saveData?: boolean };
  }
}

/** Whether the browser asks to save data. */
export function savingData(): boolean {
  return navigator.connection?.saveData === true;
}

/**
 * Pictures of neighboring articles fetched ahead at low priority, without a
 * referrer, as the article itself would load them. Each call replaces the
 * pictures of the last, stopping any still loading.
 */
export class PictureFetcher {
  private loading: HTMLImageElement[] = [];

  /** Fetch these pictures ahead, dropping the ones asked for before. */
  replace(pictures: readonly AheadPicture[]): void {
    this.clear();
    if (typeof Image !== "undefined" && !savingData()) {
      this.loading = pictures.map(({ src, srcset }) => {
        const image = new Image();
        image.referrerPolicy = "no-referrer";
        image.decoding = "async";
        image.setAttribute("fetchpriority", "low");
        if (srcset !== "") {
          image.srcset = srcset;
        }
        if (src !== "") {
          image.src = src;
        }
        return image;
      });
    }
  }

  /** Stop fetching every picture asked for. */
  clear(): void {
    for (const image of this.loading) {
      image.removeAttribute("srcset");
      image.removeAttribute("src");
    }
    this.loading = [];
  }
}
