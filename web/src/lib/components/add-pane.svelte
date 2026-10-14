<script lang="ts">
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import X from "@lucide/svelte/icons/x";
  import { copy } from "#lib/copy.ts";
  import type { Reader } from "#lib/reader.svelte.ts";
  import AddFeed from "./add-feed.svelte";
  import PhoneBar from "./phone-bar.svelte";

  const {
    reader,
    onback,
    ondone,
  }: { reader: Reader; onback: () => void; ondone: () => void } = $props();
</script>

<section class="flex h-full min-h-0 flex-col">
  <PhoneBar title={copy.addFeed} label={copy.back} onpress={onback}>
    <ArrowLeft class="size-5" />
  </PhoneBar>
  <div class="min-h-0 flex-1 overflow-y-auto px-5 py-8 md:px-12 md:py-10">
    <div class="mx-auto flex max-w-xl flex-col gap-6">
      <!-- on a phone the title is in the bottom bar alone -->
      <div class="hidden items-start justify-between gap-4 md:flex">
        <h1 class="font-reading text-3xl font-bold leading-tight">
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
