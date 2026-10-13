<script lang="ts">
  import Check from "@lucide/svelte/icons/check";
  import Star from "@lucide/svelte/icons/star";
  import Undo2 from "@lucide/svelte/icons/undo-2";
  import { copy } from "#lib/copy.ts";
  import { SIDEWAYS_RATIO, startsAtEdge } from "#lib/gestures.ts";
  import type { Entry } from "#lib/types.ts";

  const {
    entry,
    source,
    active,
    onopen,
    ontoggle,
    onstar,
  }: {
    entry: Entry;
    source: string;
    active: boolean;
    onopen: () => void;
    ontoggle: () => void;
    onstar: () => void;
  } = $props();

  // how far a card must move to toggle, the movement before it counts as a swipe, and the most it moves
  const SWIPE_DISTANCE = 80;
  const SWIPE_START = 12;
  const SWIPE_LIMIT = 140;

  let offset = $state(0);
  let startX = 0;
  // a touch from the screen's edge opens the feed list instead
  let fromEdge = false;
  let startY = 0;
  let swiping = false;
  let moved = false;

  function age(published: number): string {
    const date = new Date(published);
    const today = new Date();
    return date.toDateString() === today.toDateString()
      ? date.toLocaleTimeString([], {
          hour: "2-digit",
          minute: "2-digit",
          hourCycle: "h23",
        })
      : date.toLocaleDateString([], { month: "short", day: "numeric" });
  }

  function onStart(event: TouchEvent): void {
    const [touch] = event.touches;
    if (touch) {
      startX = touch.clientX;
      startY = touch.clientY;
      swiping = false;
      moved = false;
      fromEdge = startsAtEdge({ x: startX, y: startY });
    }
  }

  function onMove(event: TouchEvent): void {
    const [touch] = event.touches;
    if (touch) {
      const across = touch.clientX - startX;
      const down = touch.clientY - startY;
      if (
        !(swiping || fromEdge) &&
        Math.abs(across) > SWIPE_START &&
        Math.abs(across) > Math.abs(down) * SIDEWAYS_RATIO
      ) {
        swiping = true;
      }
      if (swiping) {
        moved = true;
        offset = Math.max(-SWIPE_LIMIT, Math.min(SWIPE_LIMIT, across));
      }
    }
  }

  function onEnd(): void {
    if (Math.abs(offset) >= SWIPE_DISTANCE) {
      ontoggle();
    }
    offset = 0;
    swiping = false;
  }

  function onCancel(): void {
    offset = 0;
    swiping = false;
  }

  function onClick(): void {
    if (moved) {
      moved = false;
    } else {
      onopen();
    }
  }
</script>

<div
  class="relative overflow-hidden border-b border-line [contain-intrinsic-size:auto_7.5rem] [content-visibility:auto]"
>
  <div
    class="absolute inset-0 flex items-center justify-between bg-accent-soft px-6 text-accent"
    aria-hidden="true"
  >
    {#if entry.read}
      <Undo2 class="size-5" />
      <Undo2 class="size-5" />
    {:else}
      <Check class="size-5" />
      <Check class="size-5" />
    {/if}
  </div>
  <div
    class="relative touch-pan-y touch-pinch-zoom {active
      ? "bg-accent-soft"
      : "bg-page hover:bg-side"} {offset === 0
      ? "transition-transform duration-200"
      : ""}"
    style:transform="translateX({offset}px)"
    role="presentation"
    ontouchstart={onStart}
    ontouchmove={onMove}
    ontouchend={onEnd}
    ontouchcancel={onCancel}
  >
    <button
      type="button"
      id="entry-{entry.id}"
      class="flex w-full gap-3 px-4 py-3.5 text-left {entry.imageUrl
        ? "min-h-[7.5rem]"
        : ""}"
      onclick={onClick}
    >
      <span class="flex min-w-0 flex-1 flex-col gap-1">
        <span class="truncate font-mono text-xs text-muted">
          {source}{entry.dated === false ? "" : ` · ${age(entry.published)}`}
        </span>
        <span
          class="font-semibold leading-snug {entry.read && !active
            ? "text-faded"
            : ""} {entry.imageUrl || entry.excerpt ? "" : "pr-[4.5rem]"}"
          >{entry.title}</span
        >
        <span
          class="text-sm text-muted {entry.imageUrl
            ? "line-clamp-2"
            : "line-clamp-3 pr-[4.5rem]"}"
          >{entry.excerpt}</span
        >
        {#if entry.categories.length > 0}
          <span class="truncate pr-[4.5rem] text-xs text-faded"
            >{entry.categories.join(" · ")}</span
          >
        {/if}
      </span>
      {#if entry.imageUrl}
        <img
          src={entry.imageUrl}
          alt=""
          referrerpolicy="no-referrer"
          loading="lazy"
          class="size-16 shrink-0 self-start rounded-md object-cover"
        >
      {/if}
    </button>
    <div class="absolute bottom-1.5 right-2 flex">
      <button
        type="button"
        class="grid size-8 place-items-center rounded-md text-muted hover:bg-line hover:text-ink"
        aria-label={copy.star}
        aria-pressed={entry.starred}
        title={copy.star}
        onclick={onstar}
      >
        <Star class="size-4 {entry.starred ? "fill-star text-star" : ""}" />
      </button>
      <button
        type="button"
        class="grid size-8 place-items-center rounded-md text-muted hover:bg-line hover:text-ink"
        aria-label={entry.read ? copy.markUnread : copy.markRead}
        title={entry.read ? copy.markUnread : copy.markRead}
        onclick={ontoggle}
      >
        {#if entry.read}
          <Undo2 class="size-4" />
        {:else}
          <Check class="size-4" />
        {/if}
      </button>
    </div>
  </div>
</div>
