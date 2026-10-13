import type { FeedHit } from "./reader.svelte.ts";
import type { Entry, Feed } from "./types.ts";

function tint(hue: number): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="hsl(${hue} 55% 62%)"/><stop offset="1" stop-color="hsl(${(hue + 40) % 360} 50% 42%)"/></linearGradient></defs><rect width="64" height="64" fill="url(#g)"/></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

const NOW = Date.now();
const HOUR = 60 * 60 * 1000;

function feed(id: string, title: string, folder: string): Feed {
  return {
    id,
    url: `https://${id}.example/feed`,
    title,
    siteUrl: "",
    icon: "",
    renamed: false,
    folder,
    lastFetched: NOW,
    error: "",
    updatedAt: NOW,
  };
}

interface Seed {
  index: number;
  feedId: string;
  title: string;
  summary: string;
  hours: number;
  hue?: number;
  categories: string[];
  author: string;
}

function entry(seed: Seed): Entry {
  const { index, feedId, title, summary, hours, hue, categories, author } =
    seed;
  return {
    id: `demo-${index}`,
    feedId,
    guid: `demo-${index}`,
    title,
    url: `https://${feedId}.example/${index}`,
    published: NOW - hours * HOUR,
    dated: true,
    author,
    imageUrl: hue === undefined ? "" : tint(hue),
    excerpt: summary,
    summary,
    categories,
    read: index % 5 === 3,
    starred: index === 2,
    addedAt: NOW,
    position: 0,
    bodyHash: "",
    updatedAt: NOW,
  };
}

/** Example search results; development only. */
export const DEMO_HITS: FeedHit[] = [
  {
    url: "https://cooking.example/feed",
    title: "Serious Cooking",
    description: "Recipes, techniques and the science behind them.",
    icon: "",
    subscribers: 48_210,
  },
  {
    url: "https://bakers.example/feed",
    title: "The Weekend Baker",
    description: "Bread, pastry and patience, one loaf at a time.",
    icon: "",
    subscribers: 9120,
  },
  {
    url: "https://pantry.example/feed",
    title: "Pantry Notes",
    description: "Cooking from what is already in the cupboard.",
    icon: "",
    subscribers: 1530,
  },
];

/** Example data for looking at the interface; development only. */
export const DEMO_FEEDS: Feed[] = [
  feed("daringfireball", "Daring Fireball", "Tech"),
  feed("julia", "Julia Evans", "Tech"),
  feed("rustblog", "Rust Blog", "Tech"),
  feed("kottke", "Kottke.org", "Daily"),
  feed("marginalian", "The Marginalian", "Daily"),
  feed("nytimes", "The New York Times", "News"),
  feed("xkcd", "xkcd", ""),
];

/** Example articles for the example feeds; development only. */
export const DEMO_ENTRIES: Entry[] = [
  entry({
    index: 1,
    feedId: "daringfireball",
    title: "The notch finally has a purpose",
    summary: "Small changes to the menu bar make the cutout feel intentional.",
    hours: 1,
    hue: 210,
    categories: ["Apple"],
    author: "John Gruber",
  }),
  entry({
    index: 2,
    feedId: "julia",
    title: "How DNS lookups actually fall back",
    summary:
      "A zine page on what happens when the first resolver does not answer.",
    hours: 2,
    hue: 28,
    categories: ["Networking"],
    author: "Julia Evans",
  }),
  entry({
    index: 3,
    feedId: "kottke",
    title: "A map of every lighthouse still using its original lens",
    summary: "Around 120 remain, most of them on the Atlantic coast.",
    hours: 3,
    hue: 190,
    categories: ["Maps"],
    author: "Jason Kottke",
  }),
  entry({
    index: 4,
    feedId: "nytimes",
    title: "Senate Passes Budget After Long Night",
    summary:
      "Lawmakers ended a standoff over spending that had stalled the government for nine days.",
    hours: 4,

    categories: ["U.S.", "Politics"],
    author: "Jane Roe",
  }),
  entry({
    index: 5,
    feedId: "nytimes",
    title: "Ten Quizzes to Test Your Week in News",
    summary:
      "Think you followed everything? Find out with this week's poll and quiz.",
    hours: 5,

    categories: ["Games", "Quiz"],
    author: "The Editors",
  }),
  entry({
    index: 6,
    feedId: "rustblog",
    title: "Announcing a faster incremental build",
    summary: "Linking is now done in parallel by default on Linux and macOS.",
    hours: 26,
    hue: 18,
    categories: ["Releases"],
    author: "The Rust Team",
  }),
  entry({
    index: 7,
    feedId: "marginalian",
    title: "On the discipline of noticing",
    summary:
      "Notes from a naturalist who counted the same twelve birds for forty years.",
    hours: 27,
    hue: 140,
    categories: ["Nature"],
    author: "Maria Popova",
  }),
  entry({
    index: 8,
    feedId: "nytimes",
    title: "What the Latest Jobs Numbers Say About the Economy",
    summary: "Hiring slowed in September, but wages kept climbing.",
    hours: 28,

    categories: ["Business", "Politics"],
    author: "Alex Doe",
  }),
  entry({
    index: 9,
    feedId: "nytimes",
    title: "Opinion: The Case for a Shorter Work Week",
    summary: "A columnist argues that four days is enough.",
    hours: 29,

    categories: ["Opinion", "Politics"],
    author: "Sam Poe",
  }),
  entry({
    index: 10,
    feedId: "xkcd",
    title: "Orbital Mechanics Lecture",
    summary: "Hover text: the audience asks about the second moon.",
    hours: 50,
    hue: 50,
    categories: ["Comics"],
    author: "Randall",
  }),
  entry({
    index: 11,
    feedId: "julia",
    title: "Debugging with strace, again",
    summary: "A short post about the three flags worth remembering.",
    hours: 52,
    hue: 28,
    categories: ["Tools"],
    author: "Julia Evans",
  }),
  entry({
    index: 12,
    feedId: "nytimes",
    title: "Sports: Late Goal Settles the Derby",
    summary: "A stoppage-time header decided a tense match.",
    hours: 53,

    categories: ["Sports"],
    author: "Lee Roe",
  }),
  entry({
    index: 13,
    feedId: "nytimes",
    title: "Quiz: How Well Did You Follow the Sports Week?",
    summary: "Ten questions on goals, trades and one very long match.",
    hours: 54,
    categories: ["Quiz", "Sports"],
    author: "The Editors",
  }),
];

/** The body every example article shows; development only. */
export const DEMO_BODY =
  "<p>The post opens with a short example, then spends most of its length on the one case that surprised the author.</p><p>There is a link to the original data, a diagram, and a closing paragraph that says what to try next.</p>";

/** A large number of articles, to see how the lists cope; development only. */
export function bigEntries(count: number): Entry[] {
  const names = [
    "Politics",
    "Business",
    "Sports",
    "Arts",
    "Science",
    "World",
    "Tech",
    "Opinion",
  ];
  const feedIds = DEMO_FEEDS.map((item) => item.id);
  return Array.from({ length: count }, (_unused, index) => ({
    id: `big-${index}`,
    feedId: feedIds[index % feedIds.length] ?? "xkcd",
    guid: `big-${index}`,
    title: `Article number ${index} about ${names[index % names.length]}`,
    url: `https://big.example/${index}`,
    published: NOW - index * 60_000,
    dated: true,
    author: "Writer",
    imageUrl: index % 3 === 0 ? tint(index % 360) : "",
    excerpt: `A short summary of article ${index}, long enough to wrap onto a second line in the list.`,
    summary: `A short summary of article ${index}, long enough to wrap onto a second line in the list.`,
    categories: [
      names[index % names.length] ?? "World",
      names[(index + 3) % names.length] ?? "Tech",
    ],
    read: index % 4 !== 0,
    starred: false,
    addedAt: NOW,
    position: 0,
    bodyHash: "",
    updatedAt: NOW,
  }));
}
