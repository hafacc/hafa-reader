import { describe, expect, test } from "bun:test";
import { BODY_MAX_CHARS, bodyHash, matches, shareable } from "./bodies.ts";

describe("bodies", () => {
  test("hash is hex SHA-256 of the text", async () => {
    expect(await bodyHash("abc")).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
    expect(await bodyHash("")).toBe("");
  });

  test("downloads are checked against their hash", async () => {
    const hash = await bodyHash("<p>Hello</p>");
    expect(await matches(hash, "<p>Hello</p>")).toBe(true);
    expect(await matches(hash, "<p>Forged</p>")).toBe(false);
    expect(await matches("", "")).toBe(false);
  });

  test("size caps", () => {
    expect(shareable("")).toBe(false);
    expect(shareable("a".repeat(BODY_MAX_CHARS))).toBe(true);
    expect(shareable("a".repeat(BODY_MAX_CHARS + 1))).toBe(false);
    expect(shareable("😀".repeat(BODY_MAX_CHARS / 2 + 1))).toBe(false);
  });
});
