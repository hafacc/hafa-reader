const ANDROID = /Android/iu;
const APPLE_MOBILE = /iPhone|iPad|iPod/iu;
const MAC = /Macintosh/iu;

/** Which kind of device the browser runs on, as far as the app cares. */
export type Platform = "android" | "ios" | "desktop";

/**
 * Tell the device from the browser's user agent, never from the window's
 * width: phones and tablets cannot install the Chrome extension.
 *
 * Since iPadOS 13, Safari on an iPad reports itself as a Mac; a Mac with
 * more than one touch point is taken to be an iPad.
 */
export function detectPlatform(
  userAgent: string,
  maxTouchPoints: number,
): Platform {
  if (ANDROID.test(userAgent)) {
    return "android";
  } else if (
    APPLE_MOBILE.test(userAgent) ||
    (MAC.test(userAgent) && maxTouchPoints > 1)
  ) {
    return "ios";
  } else {
    return "desktop";
  }
}
