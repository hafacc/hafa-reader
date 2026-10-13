// a match pattern's scheme and host, as `https://reader.hafa.cc/*` has them
const PATTERN = /^(?<scheme>https?):\/\/(?<host>[^/:]+)(?::(?<port>\d+))?\//u;

/**
 * Whether a page's origin is one an extension's `externally_connectable`
 * match patterns let in: the same scheme and host, and the same port when the
 * pattern names one (a pattern without a port matches any, as in Chrome).
 */
export function allowedOrigin(
  origin: string | undefined,
  patterns: readonly string[],
): boolean {
  let page: URL | undefined;
  try {
    page = origin === undefined ? undefined : new URL(origin);
  } catch {
    page = undefined;
  }
  return (
    page !== undefined &&
    patterns.some((pattern) => {
      const groups = PATTERN.exec(pattern)?.groups;
      return (
        groups !== undefined &&
        page.protocol === `${groups["scheme"]}:` &&
        page.hostname === groups["host"] &&
        (groups["port"] === undefined || page.port === groups["port"])
      );
    })
  );
}
