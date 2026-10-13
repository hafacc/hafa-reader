/** Firestore error codes that a retry cannot fix: the rules or the data refuse the write. */
const REFUSED_CODES = new Set([
  "permission-denied",
  "invalid-argument",
  "failed-precondition",
]);

/** Whether a write failed for good, refused rather than lost on the way. */
export function isRefused(error: unknown): boolean {
  return (
    error instanceof Error &&
    "code" in error &&
    typeof error.code === "string" &&
    REFUSED_CODES.has(error.code)
  );
}

/** An error's code and message for the console, with the account's id left out. */
export function errorSummary(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  const code =
    error instanceof Error && "code" in error && typeof error.code === "string"
      ? `${error.code}: `
      : "";
  return `${code}${message.replaceAll(/users\/[^/\s)]+/gu, "users/{uid}")}`;
}
