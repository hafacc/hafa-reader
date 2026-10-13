import { expect, test } from "bun:test";
import { accountPhoto } from "./photo.ts";

test("a Google picture is asked for at a set size", () => {
  expect(
    accountPhoto("https://lh3.googleusercontent.com/a/ACg8ocK=s96-c", 64),
  ).toBe("https://lh3.googleusercontent.com/a/ACg8ocK=s64-c");
  expect(accountPhoto("https://lh3.googleusercontent.com/a/ACg8ocK")).toBe(
    "https://lh3.googleusercontent.com/a/ACg8ocK=s96-c",
  );
});

test("other https pictures are kept as they are", () => {
  expect(accountPhoto("https://photos.example/me.png")).toBe(
    "https://photos.example/me.png",
  );
});

test("anything not https gives no picture", () => {
  expect(accountPhoto("")).toBe("");
  expect(accountPhoto("http://lh3.googleusercontent.com/a/x")).toBe("");
  expect(accountPhoto("javascript:alert(1)")).toBe("");
  expect(accountPhoto("not an address")).toBe("");
});
