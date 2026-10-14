import { afterEach, expect, test } from "bun:test";
import {
  applyReadingFont,
  storedReadingFont,
  storeReadingFont,
} from "./reading-font.ts";

afterEach(() => {
  localStorage.clear();
});

test("Literata until another font is chosen", () => {
  expect(storedReadingFont()).toBe("literata");
});

test("a chosen font is remembered", () => {
  storeReadingFont("public-sans");
  expect(storedReadingFont()).toBe("public-sans");
  storeReadingFont("literata");
  expect(storedReadingFont()).toBe("literata");
});

test("an unknown stored value reads as the default", () => {
  localStorage.setItem("h-reader:reading-font", "fraunces");
  expect(storedReadingFont()).toBe("literata");
});

test("the page is marked with the font", () => {
  applyReadingFont("public-sans");
  expect(document.documentElement.dataset.readingFont).toBe("public-sans");
});
