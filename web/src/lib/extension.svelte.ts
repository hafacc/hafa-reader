import type { ExtensionRequest, ExtensionResponse } from "$shared/protocol.ts";
import { extensionId } from "./config.ts";

/** Failed pings in a row before the extension counts as gone; a reload or update of it misses a few. */
const MISSES_BEFORE_GONE = 3;

function isResponse(value: unknown): value is ExtensionResponse {
  return (
    typeof value === "object" &&
    value !== null &&
    "type" in value &&
    typeof value.type === "string"
  );
}

/** Talks to the Reader browser extension. */
export class ExtensionClient {
  /** Version of the installed extension; empty when there is none. */
  version = $state("");

  private readonly id = extensionId;
  private misses = 0;

  /** Send a request; resolves to undefined when the extension is absent. */
  async send(
    request: ExtensionRequest,
  ): Promise<ExtensionResponse | undefined> {
    let response: ExtensionResponse | undefined;
    const runtime = typeof chrome === "undefined" ? undefined : chrome.runtime;
    if (runtime?.sendMessage) {
      try {
        const reply: unknown = await runtime.sendMessage(this.id, request);
        response = isResponse(reply) ? reply : undefined;
      } catch {
        response = undefined;
      }
    }
    return response;
  }

  /**
   * Check whether the extension is reachable now. A known extension is only
   * taken as gone after several failed checks in a row.
   */
  async detect(): Promise<boolean> {
    const response = await this.send({ type: "ping" });
    const found = response?.type === "pong";
    if (found) {
      this.version = response.version;
      this.misses = 0;
    } else {
      this.misses += 1;
      if (this.misses >= MISSES_BEFORE_GONE) {
        this.version = "";
      }
    }
    return found;
  }
}
