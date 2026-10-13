import { expect, test } from "bun:test";
import { detectPlatform } from "./platform.ts";

const androidPhone =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36";
const androidTablet =
  "Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";
const iphone =
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1";
const oldIpad =
  "Mozilla/5.0 (iPad; CPU OS 12_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/12.1 Mobile/15E148 Safari/604.1";
const desktopChrome =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";
const macSafari =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15";

test("detectPlatform sends Android phones and tablets to the app", () => {
  expect(detectPlatform(androidPhone, 5)).toBe("android");
  expect(detectPlatform(androidTablet, 10)).toBe("android");
});

test("detectPlatform knows iPhones and iPads", () => {
  expect(detectPlatform(iphone, 5)).toBe("ios");
  expect(detectPlatform(oldIpad, 5)).toBe("ios");
});

test("detectPlatform treats a touch Mac as an iPad", () => {
  expect(detectPlatform(macSafari, 5)).toBe("ios");
});

test("detectPlatform leaves desktop browsers alone", () => {
  expect(detectPlatform(desktopChrome, 0)).toBe("desktop");
  expect(detectPlatform(macSafari, 0)).toBe("desktop");
  expect(detectPlatform(macSafari, 1)).toBe("desktop");
});
