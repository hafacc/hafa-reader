/** The font for article text and titles; the rest of the interface stays in Public Sans. */
export type ReadingFont = "literata" | "public-sans";

/** The font used until another is chosen. */
const DEFAULT_READING_FONT: ReadingFont = "literata";

const READING_FONT_KEY = "h-reader:reading-font";

/** The reading font chosen on this browser, or the default. */
export function storedReadingFont(): ReadingFont {
  try {
    return localStorage.getItem(READING_FONT_KEY) === "public-sans"
      ? "public-sans"
      : DEFAULT_READING_FONT;
  } catch {
    return DEFAULT_READING_FONT;
  }
}

/** Remember the reading font on this browser. */
export function storeReadingFont(font: ReadingFont): void {
  try {
    localStorage.setItem(READING_FONT_KEY, font);
  } catch {
    // storage can be blocked; the choice just does not persist
  }
}

/** Switch the page's article text and titles to `font` (see app.css). */
export function applyReadingFont(font: ReadingFont): void {
  document.documentElement.dataset.readingFont = font;
}
