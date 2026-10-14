import { upgradedAddress } from "$shared/address.ts";
import { webAddress } from "$shared/https.ts";

const SCHEME_PATTERN = /^[a-z][a-z0-9+.-]*:\/\//iu;

/**
 * What a person typed or a file listed, as an address: https added when no
 * scheme is given, plain http kept as http (so it can be refused or tried
 * over https), and empty when it is not a web address.
 */
export function normalizeUrl(input: string): string {
  const trimmed = input.trim();
  return webAddress(
    SCHEME_PATTERN.test(trimmed) ? trimmed : `https://${trimmed}`,
  );
}

/** Whether an address can be fetched: an https address. */
export function isWebAddress(address: string): boolean {
  return address !== "" && upgradedAddress(address) === address;
}
