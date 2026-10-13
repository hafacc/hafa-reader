function parsed(address: string, base?: string): URL | undefined {
  let url: URL | undefined;
  try {
    url = new URL(address, base);
  } catch {
    url = undefined;
  }
  return url;
}

/**
 * An address resolved against `base` if it is `https`; empty for anything
 * else, plain `http` included.
 */
export function secureAddress(address: string, base?: string): string {
  const url = address ? parsed(address, base) : undefined;
  return url?.protocol === "https:" ? url.href : "";
}

/**
 * An address resolved against `base` with plain `http` upgraded to `https`,
 * for addresses someone typed, imported or a page announced; empty when it is
 * not a web address.
 */
export function upgradedAddress(address: string, base?: string): string {
  const url = address ? parsed(address, base) : undefined;
  let result = "";
  if (url?.protocol === "http:") {
    url.protocol = "https:";
    result = url.href;
  } else if (url?.protocol === "https:") {
    result = url.href;
  }
  return result;
}
