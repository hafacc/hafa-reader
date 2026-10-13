import { expect, test } from "bun:test";
import { decodeBody, readCapped } from "./decode.ts";

// "café" in windows-1252.
const CAFE = [0x63, 0x61, 0x66, 0xe9];

test("the Content-Type charset decides the text", () => {
  const bytes = new Uint8Array(CAFE);
  expect(decodeBody(bytes, "application/rss+xml; charset=windows-1252")).toBe(
    "café",
  );
});

test("an XML declaration's encoding is used when the header names none", () => {
  const declaration = new TextEncoder().encode(
    '<?xml version="1.0" encoding="ISO-8859-1"?><t>',
  );
  const bytes = new Uint8Array([...declaration, ...CAFE]);
  expect(decodeBody(bytes, "text/xml")).toEndWith("<t>café");
});

test("UTF-8 is the default, a byte order mark wins and unknown names fall through", () => {
  const utf8 = new TextEncoder().encode("café");
  expect(decodeBody(utf8, "")).toBe("café");
  expect(decodeBody(utf8, "text/xml; charset=no-such-set")).toBe("café");
  const marked = new Uint8Array([0xef, 0xbb, 0xbf, ...utf8]);
  expect(decodeBody(marked, "text/xml; charset=windows-1252")).toBe("café");
});

function streamOf(chunks: number[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller): void {
      for (const length of chunks) {
        controller.enqueue(new Uint8Array(length).fill(65));
      }
      controller.close();
    },
  });
}

test("a body within the limit is read whole", async () => {
  const bytes = await readCapped(streamOf([3, 4]), "", 7);
  expect(bytes).toEqual(new Uint8Array(7).fill(65));
  expect(await readCapped(null, "", 7)).toEqual(new Uint8Array(0));
});

test("a body over the limit is refused by its length or while reading", async () => {
  expect(await readCapped(streamOf([1]), "8", 7)).toBeUndefined();
  expect(await readCapped(streamOf([4, 4, 4]), "", 7)).toBeUndefined();
  expect(await readCapped(streamOf([4, 4]), "7", 7)).toBeUndefined();
});
