import { describe, expect, test } from "bun:test";
import { aheadPictures, neighborsOf } from "./prefetch.ts";
import { sanitize } from "./sanitize.ts";

const BASE = "https://blog.example/posts/hello/";

describe("aheadPictures", () => {
  test("takes the lead picture, then the body's pictures in order", () => {
    const body = sanitize(
      '<p><img src="/one.png"><img src="https://cdn.example/two.png"></p>',
      BASE,
    );
    expect(aheadPictures("https://cdn.example/lead.png", body)).toEqual([
      { src: "https://cdn.example/lead.png", srcset: "" },
      { src: "https://blog.example/one.png", srcset: "" },
      { src: "https://cdn.example/two.png", srcset: "" },
    ]);
  });

  test("skips the lead picture when the body already shows it", () => {
    const body = sanitize('<img src="https://cdn.example/a.png?w=1&h=2">');
    expect(aheadPictures("https://cdn.example/a.png?w=1&h=2", body)).toEqual([
      { src: "https://cdn.example/a.png?w=1&h=2", srcset: "" },
    ]);
  });

  test("fetches only https pictures the sanitizer keeps", () => {
    const body = sanitize(
      '<img src="http://old.example/up.png"><img src="ftp://x.example/a.png"><img src="javascript:alert(1)"><img src="data:image/png;base64,AAAA">',
      BASE,
    );
    expect(aheadPictures("http://old.example/lead.png", body)).toEqual([
      { src: "https://old.example/up.png", srcset: "" },
    ]);
  });

  test("keeps the sanitized srcset so the same candidate loads", () => {
    const body = sanitize(
      '<img srcset="/small.png 400w, ftp://x.example/big.png 800w">',
      BASE,
    );
    expect(aheadPictures("", body)).toEqual([
      { src: "", srcset: "https://blog.example/small.png 400w" },
    ]);
  });

  test("stops at the limit, the lead picture counted", () => {
    const body = sanitize(
      Array.from(
        { length: 6 },
        (_item, index) => `<img src="/${index}.png">`,
      ).join(""),
      BASE,
    );
    const pictures = aheadPictures("https://cdn.example/lead.png", body, 3);
    expect(pictures.map(({ src }) => src)).toEqual([
      "https://cdn.example/lead.png",
      "https://blog.example/0.png",
      "https://blog.example/1.png",
    ]);
  });

  test("finds nothing in a body without pictures", () => {
    expect(aheadPictures("", sanitize("<p>Just text</p>"))).toEqual([]);
  });
});

describe("neighborsOf", () => {
  const rows = [{ id: "a" }, { id: "b" }, { id: "c" }];

  test("the row ahead is the next one when moving down, the previous when moving up", () => {
    expect(neighborsOf(rows, "b", 1)).toEqual({
      ahead: { id: "c" },
      behind: { id: "a" },
    });
    expect(neighborsOf(rows, "b", -1)).toEqual({
      ahead: { id: "a" },
      behind: { id: "c" },
    });
  });

  test("nothing lies past either end, or around a row the list no longer holds", () => {
    expect(neighborsOf(rows, "c", 1)).toEqual({
      ahead: undefined,
      behind: { id: "b" },
    });
    expect(neighborsOf(rows, "a", -1)).toEqual({
      ahead: undefined,
      behind: { id: "b" },
    });
    expect(neighborsOf(rows, "gone", 1)).toEqual({
      ahead: undefined,
      behind: undefined,
    });
  });
});
