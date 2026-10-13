/** The details of an article that filters look at. */
export interface FilterEntry {
  /** The article's title. */
  title: string;
  /** The article's summary, as plain text. */
  summary: string;
  /** Sections, topics and tags the feed lists for the article. */
  categories: string[];
}

/** How one feed, group or the main list is shown. */
export interface ViewSettings {
  /** Order of the list by date. */
  sort: "newest" | "oldest";
  /** Words or phrases that hide an article when its title or summary has one. */
  hideWords: string[];
  /** When not empty, only articles whose title or summary has one of these stay. */
  onlyWords: string[];
  /** Topics turned off; an article whose topics are all off is hidden. */
  offTopics: string[];
}

/** Settings before anything is changed. */
export const DEFAULT_SETTINGS: ViewSettings = {
  sort: "newest",
  hideWords: [],
  onlyWords: [],
  offTopics: [],
};

// Same word and whitespace sets as subtube's pattern language, so a phrase
// means the same on every platform and in every engine.
const WORD = "A-Za-z0-9_";
const SPACE =
  "\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
const WORD_CHARACTER = new RegExp(`^[${WORD}]$`, "u");
const SPACE_CHARACTER = new RegExp(`^[${SPACE}]$`, "u");
const SPECIAL = new Set("\\^$.|?*+()[]{}");

type Matcher = (text: string) => boolean;

/** Matchers already built, so each list of words is compiled once. */
const matchers = new Map<string, Matcher>();
// Lists kept before the cache starts over, so edited lists do not pile up.
const MATCHER_LIMIT = 64;

function isSpace(character: string | undefined): boolean {
  return character !== undefined && SPACE_CHARACTER.test(character);
}

function isWordCharacter(character: string | undefined): boolean {
  return character !== undefined && WORD_CHARACTER.test(character);
}

function trimSpace(characters: string[]): string[] {
  let start = 0;
  let end = characters.length;
  while (start < end && isSpace(characters[start])) {
    start += 1;
  }
  while (end > start && isSpace(characters[end - 1])) {
    end -= 1;
  }
  return characters.slice(start, end);
}

function escapeBody(characters: string[]): string {
  let body = "";
  for (const [index, character] of characters.entries()) {
    if (isSpace(character)) {
      body += isSpace(characters[index - 1]) ? "" : `[${SPACE}]+`;
    } else if (SPECIAL.has(character)) {
      body += `\\${character}`;
    } else {
      body += character;
    }
  }
  return body;
}

/**
 * One phrase as a pattern: runs of whitespace match any whitespace, and a
 * word boundary is required at an end only when that end is a word character.
 */
function phrasePattern(phrase: string): string {
  const trimmed = trimSpace(Array.from(phrase));
  let pattern = "";
  if (trimmed.length > 0) {
    const before = isWordCharacter(trimmed[0]) ? `(?<![${WORD}])` : "";
    const after = isWordCharacter(trimmed.at(-1)) ? `(?![${WORD}])` : "";
    pattern = `${before}${escapeBody(trimmed)}${after}`;
  }
  return pattern;
}

function never(): boolean {
  return false;
}

/** A function telling whether a text has any of the words; built once per list. */
function matcherFor(words: string[]): Matcher {
  const key = words.join("\n");
  let matcher = matchers.get(key);
  if (matcher === undefined) {
    const parts = new Set(words.map(phrasePattern));
    parts.delete("");
    matcher = never;
    if (parts.size > 0) {
      const pattern = new RegExp([...parts].join("|"), "iu");
      matcher = (text): boolean => pattern.test(text);
    }
    if (matchers.size >= MATCHER_LIMIT) {
      matchers.clear();
    }
    matchers.set(key, matcher);
  }
  return matcher;
}

/** Whether an article's title or summary has any of the words. */
export function hasWord(words: string[], entry: FilterEntry): boolean {
  const matcher = matcherFor(words);
  return matcher(entry.title) || matcher(entry.summary);
}

/** The topics the articles carry, commonest first, with how many have each. */
export function topicCounts(
  entries: FilterEntry[],
): { topic: string; count: number }[] {
  const counts = new Map<string, { topic: string; count: number }>();
  for (const entry of entries) {
    for (const topic of entry.categories) {
      const key = topic.trim().toLowerCase();
      const known = counts.get(key);
      counts.set(key, { topic, count: (known?.count ?? 0) + 1 });
    }
  }
  return [...counts.values()].sort(
    (first, second) =>
      second.count - first.count || first.topic.localeCompare(second.topic),
  );
}

/** Whether an article stays after the settings' filters are applied. */
export function passes(settings: ViewSettings, entry: FilterEntry): boolean {
  const off = new Set(settings.offTopics.map((topic) => topic.toLowerCase()));
  const topicsOff =
    entry.categories.length > 0 &&
    entry.categories.every((topic) => off.has(topic.trim().toLowerCase()));
  const hidden = hasWord(settings.hideWords, entry);
  const kept =
    settings.onlyWords.length === 0 || hasWord(settings.onlyWords, entry);
  return !(topicsOff || hidden) && kept;
}
