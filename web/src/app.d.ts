import type { Place } from "#lib/types.ts";

declare global {
  // biome-ignore lint/style/noNamespace: SvelteKit reads its page state type from this namespace
  namespace App {
    interface PageState {
      place?: Place;
    }
  }
}
