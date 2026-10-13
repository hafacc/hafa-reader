import { expect, test } from "bun:test";
import { isEdgeSwipe, startsAtEdge, touchAxis } from "./gestures.ts";

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
