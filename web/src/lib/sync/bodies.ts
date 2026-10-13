import { sha256Hex } from "$shared/id.ts";

/**
 * Most UTF-16 code units a shared body may have; `firebase/firestore.rules`
 * has the same cap. At most 3 UTF-8 bytes per unit keeps it under Firestore's
 * 1 MiB document limit.
 */
export const BODY_MAX_CHARS = 250_000;
/** Collection of shared article bodies. */
export const BODIES = "bodies";

/** The hash a body is stored under: hex SHA-256 of its text; empty for no text. */
export function bodyHash(body: string): Promise<string> {
  return body === "" ? Promise.resolve("") : sha256Hex(body);
}

/** Whether a body may be shared: not empty and under the size cap. */
export function shareable(body: string): boolean {
  return body !== "" && body.length <= BODY_MAX_CHARS;
}

/** Whether downloaded text is really the body stored under a hash. */
export async function matches(hash: string, body: string): Promise<boolean> {
  return hash !== "" && (await bodyHash(body)) === hash;
}
