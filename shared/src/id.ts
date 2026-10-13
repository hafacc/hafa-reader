/** Hex SHA-256 of a string's UTF-8 bytes. */
export async function sha256Hex(source: string): Promise<string> {
  const bytes = new TextEncoder().encode(source);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

/** Identifier of a feed, from its address. */
export function feedId(feedUrl: string): Promise<string> {
  return sha256Hex(feedUrl.trim());
}

/** Identifier of an entry, the same on every device. */
export function entryId(feed: string, guid: string): Promise<string> {
  return sha256Hex(`${feed}\n${guid}`);
}
