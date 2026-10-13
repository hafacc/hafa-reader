import { expect, test } from "bun:test";
import {
  ARTICLE_SWIPE_DISTANCE,
  articleSwipe,
  isEdgeSwipe,
  startsAtEdge,
  touchAxis,
} from "./gestures.ts";

test("a swipe in from the left edge opens the feed list", () => {
  expect(isEdgeSwipe({ x: 4, y: 300 }, { x: 120, y: 320 })).toBe(true);
  expect(startsAtEdge({ x: 4, y: 0 })).toBe(true);
});

test("swipes that start inside, are short, go left or mostly down do not", () => {
  expect(isEdgeSwipe({ x: 80, y: 300 }, { x: 220, y: 300 })).toBe(false);
  expect(isEdgeSwipe({ x: 4, y: 300 }, { x: 40, y: 300 })).toBe(false);
  expect(isEdgeSwipe({ x: 20, y: 300 }, { x: 0, y: 300 })).toBe(false);
  expect(isEdgeSwipe({ x: 4, y: 300 }, { x: 90, y: 420 })).toBe(false);
});

test("a touch is sideways only once it has moved and mostly sideways", () => {
  expect(touchAxis(4, 2)).toBe("none");
  expect(touchAxis(-30, 5)).toBe("sideways");
  expect(touchAxis(20, 18)).toBe("vertical");
  expect(touchAxis(2, 40)).toBe("vertical");
});

test("a long swipe on an article steps to the next or previous one", () => {
  expect(articleSwipe(-ARTICLE_SWIPE_DISTANCE, 1, 3)).toBe("step");
  expect(articleSwipe(ARTICLE_SWIPE_DISTANCE, 1, 3)).toBe("step");
});

test("a long swipe past either end of the list closes the article", () => {
  expect(articleSwipe(-ARTICLE_SWIPE_DISTANCE, 2, 3)).toBe("close");
  expect(articleSwipe(ARTICLE_SWIPE_DISTANCE, 0, 3)).toBe("close");
  expect(articleSwipe(-200, 0, 1)).toBe("close");
});

test("a short nudge, or an article no longer listed, stays", () => {
  expect(articleSwipe(-(ARTICLE_SWIPE_DISTANCE - 1), 2, 3)).toBe("stay");
  expect(articleSwipe(ARTICLE_SWIPE_DISTANCE - 1, 0, 3)).toBe("stay");
  expect(articleSwipe(-200, -1, 3)).toBe("stay");
});
