import { upgradedAddress } from "$shared/address.ts";

const SCHEME_PATTERN = /^[a-z][a-z0-9+.-]*:\/\//iu;
const PLAIN_HTTP = /^http:/iu;

/**
 * What a person typed or a file listed, as an address: https added when no
 * scheme is given, plain http upgraded to https, and empty when it is not a
 * web address.
 */
export function normalizeUrl(input: string): string {
  const trimmed = input.trim();
  return upgradedAddress(
    SCHEME_PATTERN.test(trimmed) ? trimmed : `https://${trimmed}`,
  );
}

/** Whether an address can be fetched: an https address. */
export function isWebAddress(address: string): boolean {
  return address !== "" && upgradedAddress(address) === address;
}

/** A stored value with a plain http address upgraded to https; anything else is kept as it is. */
export function upgradeHttp<Value>(value: Value): Value | string {
  return typeof value === "string" && PLAIN_HTTP.test(value)
    ? upgradedAddress(value)
    : value;
}
