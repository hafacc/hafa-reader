const LETTER = /^[a-z]$/iu;

/**
 * The shortcut a key press means. A letter's case follows Shift alone, so
 * Caps Lock does not turn `a` (add a feed) into `A` (mark all read).
 */
export function shortcutKey(key: string, shift: boolean): string {
  let result = key;
  if (LETTER.test(key)) {
    result = shift ? key.toUpperCase() : key.toLowerCase();
  }
  return result;
}
