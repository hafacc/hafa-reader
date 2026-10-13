<script lang="ts">
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import X from "@lucide/svelte/icons/x";
  import { copy } from "#lib/copy.ts";
  import type { Reader } from "#lib/reader.svelte.ts";
  import AddFeed from "./add-feed.svelte";

  const {
    reader,
    onback,
    ondone,
  }: { reader: Reader; onback: () => void; ondone: () => void } = $props();
</script>

<section class="flex h-full min-h-0 flex-col">
  <!-- on a phone Back and the screen's name sit at the bottom, where a thumb reaches them, as on Android -->
  <header
    class="order-last flex h-[calc(4rem+env(safe-area-inset-bottom))] shrink-0 items-center gap-1 border-t border-line bg-side px-1 pb-[env(safe-area-inset-bottom)] md:hidden"
  >
    <button
      type="button"
      class="grid size-12 place-items-center rounded-md"
      aria-label={copy.back}
      onclick={onback}
    >
      <ArrowLeft class="size-5" />
    </button>
    <span class="min-w-0 truncate px-1 text-lg font-semibold"
      >{copy.addFeed}</span
    >
  </header>
  <div class="min-h-0 flex-1 overflow-y-auto px-5 py-8 md:px-12 md:py-10">
    <div class="mx-auto flex max-w-xl flex-col gap-6">
      <!-- on a phone the title is in the bottom bar alone -->
      <div class="hidden items-start justify-between gap-4 md:flex">
        <h1 class="font-serif text-3xl font-bold leading-tight">
          {copy.addFeed}
        </h1>
        <button
          type="button"
          class="hidden rounded-md p-1.5 text-muted hover:bg-side md:block"
          aria-label={copy.close}
          onclick={onback}
        >
          <X class="size-5" />
        </button>
      </div>
      <AddFeed {reader} first={false} {ondone} />
    </div>
  </div>
</section>
