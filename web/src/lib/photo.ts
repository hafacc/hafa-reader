/** Hosts that serve Google account pictures and take a size suffix such as `=s96-c`. */
const GOOGLE_PHOTO_HOST = /(?:^|\.)googleusercontent\.com$/u;
/** A size suffix already on a Google picture's address. */
const SIZE_SUFFIX = /[=]s\d+(?:-c)?$/u;
/** Pixels to ask for: twice the largest avatar, for sharp high-density screens. */
export const PHOTO_PIXELS = 96;

/**
 * The address to show an account's picture from: https only, and for a
 * Google picture, a square copy of `pixels` a side; empty for anything else.
 */
export function accountPhoto(address: string, pixels = PHOTO_PIXELS): string {
  let url: URL | undefined;
  try {
    url = new URL(address);
  } catch {
    url = undefined;
  }
  let result = "";
  if (url?.protocol === "https:") {
    if (GOOGLE_PHOTO_HOST.test(url.hostname) && url.search === "") {
      url.pathname = `${url.pathname.replace(SIZE_SUFFIX, "")}=s${pixels}-c`;
    }
    result = url.href;
  }
  return result;
}
