/** How close to the left edge a touch must start to open the feed list. */
export const EDGE_WIDTH = 24;
/** How far a swipe must travel to count. */
const SWIPE_DISTANCE = 60;
/** How many times more sideways than up or down a swipe must go. */
export const SIDEWAYS_RATIO = 1.5;

/** How far a touch moves before it counts as sideways or up and down. */
const AXIS_SLOP = 10;

/** Which way a touch is going. */
export type Axis = "none" | "sideways" | "vertical";

/**
 * Which way a touch that has moved `across` and `down` pixels is going: none
 * until it has moved far enough to tell, sideways only when clearly more
 * sideways than up or down.
 */
export function touchAxis(across: number, down: number): Axis {
  let axis: Axis = "none";
  if (Math.max(Math.abs(across), Math.abs(down)) >= AXIS_SLOP) {
    axis =
      Math.abs(across) > Math.abs(down) * SIDEWAYS_RATIO
        ? "sideways"
        : "vertical";
  }
  return axis;
}

/** A touch position. */
export interface Point {
  x: number;
  y: number;
}

/** Whether a touch starts at the screen's left edge, where it opens the feed list rather than swiping a card. */
export function startsAtEdge(start: Point): boolean {
  return start.x <= EDGE_WIDTH;
}

/** Whether a touch from `start` to `end` is a swipe in from the left edge. */
export function isEdgeSwipe(start: Point, end: Point): boolean {
  const across = end.x - start.x;
  return (
    startsAtEdge(start) &&
    across >= SWIPE_DISTANCE &&
    across > Math.abs(end.y - start.y) * SIDEWAYS_RATIO
  );
}

/** How far a sideways swipe on an open article must travel to leave it. */
export const ARTICLE_SWIPE_DISTANCE = 90;

/** What a finished sideways swipe on an open article does. */
export type ArticleSwipe = "stay" | "step" | "close";

/**
 * What a sideways swipe of `across` pixels does to the article at `index` of
 * a list of `count` rows: too short or with the article no longer listed, it
 * stays; otherwise it steps to the next (leftward) or previous article, and
 * past either end it closes the article, as stepping does with the keys.
 */
export function articleSwipe(
  across: number,
  index: number,
  count: number,
): ArticleSwipe {
  const target = index + (across < 0 ? 1 : -1);
  let result: ArticleSwipe = "stay";
  if (Math.abs(across) >= ARTICLE_SWIPE_DISTANCE && index !== -1) {
    result = target >= 0 && target < count ? "step" : "close";
  }
  return result;
}
