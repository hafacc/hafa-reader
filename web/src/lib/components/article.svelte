<script lang="ts">
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import Check from "@lucide/svelte/icons/check";
  import ExternalLink from "@lucide/svelte/icons/external-link";
  import Share from "@lucide/svelte/icons/share-2";
  import Star from "@lucide/svelte/icons/star";
  import Undo2 from "@lucide/svelte/icons/undo-2";
  import { copy } from "#lib/copy.ts";
  import { type Axis, articleSwipe, touchAxis } from "#lib/gestures.ts";
  import { logFailure, type Reader } from "#lib/reader.svelte.ts";
  import { mentionsUrl } from "#lib/sanitize.ts";

  const {
    reader,
    onback,
    onstep,
  }: {
    reader: Reader;
    onback: () => void;
    onstep: (delta: number) => Promise<void>;
  } = $props();

  // how long "Copied" shows
  const COPIED_MS = 2000;
  // how long an article takes to slide off or on, as Android's pager
  const SLIDE_MS = 200;
  // how much an article follows the finger when the list no longer holds it
  const END_RESISTANCE = 0.25;

  const entry = $derived(reader.current);
  const showImage = $derived(
    entry !== undefined &&
      entry.imageUrl !== "" &&
      !mentionsUrl(reader.body, entry.imageUrl),
  );
  const byline = $derived(
    [
      reader.feeds.find((feed) => feed.id === entry?.feedId)?.title ?? "",
      entry?.author ?? "",
      entry && entry.dated !== false
        ? new Date(entry.published).toLocaleString([], {
            dateStyle: "medium",
            timeStyle: "short",
            hourCycle: "h23",
          })
        : "",
    ]
      .filter((part) => part !== "")
      .join(" · "),
  );
  const canShare = typeof navigator !== "undefined" && "share" in navigator;

  let copied = $state(false);
  let scroller: HTMLDivElement | undefined = $state();
  let startX = 0;
  let startY = 0;
  let ignoreSwipe = false;
  // which way the current touch is going, once it is clear
  let axis: Axis = "none";
  // how far the article is moved sideways, in pixels, and whether it is sliding on its own
  let offset = $state(0);
  let sliding = $state(false);

  /** Where the open article sits in the list; -1 when the list no longer holds it. */
  const index = $derived(
    reader.visible.findIndex((row) => row.id === reader.currentId),
  );

  function reducedMotion(): boolean {
    return globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  function wait(milliseconds: number): Promise<void> {
    return new Promise((resolve) => {
      setTimeout(resolve, milliseconds);
    });
  }

  /**
   * Slide the open article off, then open the next or previous one and slide
   * it on from the other side while its text loads, or, past either end of
   * the list, close it. Nothing happens when the article changed or closed
   * during the slide, such as by Back.
   */
  async function slideTo(delta: number, closes: boolean): Promise<void> {
    const width = scroller?.clientWidth ?? globalThis.innerWidth;
    const startId = reader.currentId;
    if (reducedMotion()) {
      offset = 0;
      await onstep(delta);
    } else {
      sliding = true;
      offset = -delta * width;
      await wait(SLIDE_MS);
      sliding = false;
      if (reader.currentId === startId && closes) {
        await onstep(delta);
        offset = 0;
      } else if (reader.currentId === startId) {
        // the next article is chosen at once; its text comes in while it slides on
        const stepping = onstep(delta);
        offset = delta * width;
        // let the new article be drawn off to the side before it slides on
        await wait(16);
        sliding = true;
        offset = 0;
        await wait(SLIDE_MS);
        sliding = false;
        await stepping;
      } else {
        offset = 0;
      }
    }
  }

  async function settle(): Promise<void> {
    sliding = true;
    offset = 0;
    await wait(SLIDE_MS);
    sliding = false;
  }

  $effect(() => {
    if (entry?.id && scroller) {
      scroller.scrollTop = 0;
    }
  });

  async function share(): Promise<void> {
    if (entry) {
      if (canShare) {
        try {
          await navigator.share({ title: entry.title, url: entry.url });
        } catch {
          // the person closed the share sheet
        }
      } else {
        try {
          await navigator.clipboard.writeText(entry.url);
          copied = true;
          setTimeout(() => {
            copied = false;
          }, COPIED_MS);
        } catch {
          copied = false;
        }
      }
    }
  }

  function onStart(event: TouchEvent): void {
    const [touch] = event.touches;
    if (touch && !sliding) {
      startX = touch.clientX;
      startY = touch.clientY;
      axis = "none";
      const { target } = event;
      ignoreSwipe =
        target instanceof Element &&
        target.closest("pre, .table-scroll, code") !== null;
    }
  }

  /** The article follows a sideways finger, with resistance when the list no longer holds it. */
  function onMove(event: TouchEvent): void {
    const [touch] = event.touches;
    if (touch && !ignoreSwipe && !sliding) {
      const across = touch.clientX - startX;
      const down = touch.clientY - startY;
      if (axis === "none") {
        axis = touchAxis(across, down);
      }
      if (axis === "sideways") {
        offset = index === -1 ? across * END_RESISTANCE : across;
      }
    }
  }

  function onEnd(event: TouchEvent): void {
    const [touch] = event.changedTouches;
    if (touch && axis === "sideways") {
      const across = touch.clientX - startX;
      const outcome = articleSwipe(across, index, reader.visible.length);
      if (outcome === "stay") {
        logFailure(settle());
      } else {
        logFailure(slideTo(across < 0 ? 1 : -1, outcome === "close"));
      }
    }
    axis = "none";
  }
</script>

<article class="flex h-full min-h-0 flex-col">
  <!-- on a phone Back and the article's actions sit at the bottom, where a thumb reaches them, as on Android -->
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
    <span class="flex-1"></span>
    {#if entry}
      <button
        type="button"
        class="grid size-12 place-items-center rounded-md text-muted"
        aria-label={copy.star}
        aria-pressed={entry.starred}
        onclick={() => logFailure(reader.toggleStar())}
      >
        <Star class="size-5 {entry.starred ? "fill-star text-star" : ""}" />
      </button>
      <button
        type="button"
        class="grid size-12 place-items-center rounded-md text-muted"
        aria-label={entry.read ? copy.markUnread : copy.markRead}
        onclick={() => logFailure(reader.toggleRead())}
      >
        {#if entry.read}
          <Undo2 class="size-5" />
        {:else}
          <Check class="size-5" />
        {/if}
      </button>
      {#if entry.url}
        <button
          type="button"
          class="grid size-12 place-items-center rounded-md text-muted"
          aria-label={copied ? copy.copied : copy.share}
          onclick={share}
        >
          <Share class="size-5" />
        </button>
        <a
          class="grid size-12 place-items-center rounded-md text-muted"
          href={entry.url}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={copy.openOriginal}
        >
          <ExternalLink class="size-5" />
        </a>
      {/if}
    {/if}
  </header>
  <div
    class="min-h-0 flex-1 touch-pan-y touch-pinch-zoom overflow-x-hidden overflow-y-auto px-5 py-8 md:px-12 md:py-10"
    bind:this={scroller}
    role="presentation"
    ontouchstart={onStart}
    ontouchmove={onMove}
    ontouchend={onEnd}
    ontouchcancel={onEnd}
  >
    {#if entry}
      <div
        class="mx-auto flex max-w-[44rem] flex-col gap-5 {sliding
          ? "transition-transform duration-200 ease-out"
          : ""}"
        style:transform={offset === 0 ? undefined : `translateX(${offset}px)`}
      >
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between gap-3">
            <p class="min-w-0 font-mono text-xs text-muted">{byline}</p>
            <div class="-my-2 -mr-2 hidden shrink-0 md:flex">
              <button
                type="button"
                class="grid size-8 place-items-center rounded-md text-muted hover:bg-side hover:text-ink"
                aria-label={copy.star}
                aria-pressed={entry.starred}
                title={copy.star}
                onclick={() => logFailure(reader.toggleStar())}
              >
                <Star
                  class="size-4 {entry.starred ? "fill-star text-star" : ""}"
                />
              </button>
              <button
                type="button"
                class="grid size-8 place-items-center rounded-md text-muted hover:bg-side hover:text-ink"
                aria-label={entry.read ? copy.markUnread : copy.markRead}
                title={entry.read ? copy.markUnread : copy.markRead}
                onclick={() => logFailure(reader.toggleRead())}
              >
                {#if entry.read}
                  <Undo2 class="size-4" />
                {:else}
                  <Check class="size-4" />
                {/if}
              </button>
            </div>
          </div>
          <h1 class="font-serif text-3xl font-bold leading-tight text-balance">
            {#if entry.url}
              <a
                href={entry.url}
                target="_blank"
                rel="noopener noreferrer"
                class="hover:underline"
                >{entry.title}</a
              >
            {:else}
              {entry.title}
            {/if}
          </h1>
        </div>
        {#if showImage}
          <img
            src={entry.imageUrl}
            alt=""
            referrerpolicy="no-referrer"
            class="max-h-96 w-full rounded-lg object-cover"
          >
        {/if}
        <div class="prose max-w-none prose-img:rounded">
          <!-- biome-ignore lint/nursery/noSvelteAtHtmlTags: the body is sanitized in the reader -->
          {@html reader.body}
        </div>
        <div class="hidden flex-wrap gap-2 text-xs md:flex">
          {#if entry.url}
            <a
              class="flex items-center gap-1.5 rounded-lg border border-line px-3 py-2 hover:bg-side"
              href={entry.url}
              target="_blank"
              rel="noopener noreferrer"
            >
              <ExternalLink class="size-3.5" />
              {copy.openOriginal}
            </a>
            <button
              type="button"
              class="flex items-center gap-1.5 rounded-lg border border-line px-3 py-2 hover:bg-side"
              onclick={share}
            >
              <Share class="size-3.5" />
              {copied ? copy.copied : copy.share}
            </button>
          {/if}
        </div>
        {#if entry.categories.length > 0}
          <ul class="flex flex-wrap gap-1.5 text-xs text-muted">
            <!-- keyed by place: a feed can list a topic twice -->
            {#each entry.categories as category, index (index)}
              <li>
                {#if reader.filtersApply}
                  <button
                    type="button"
                    class="rounded-full border border-line px-2.5 py-0.5 {reader.topicOff(
                      category,
                    )
                      ? "text-faded line-through"
                      : "text-ink"}"
                    aria-pressed={!reader.topicOff(category)}
                    onclick={() =>
                      logFailure(reader.toggleTopic(category, true))}
                  >
                    {category}
                  </button>
                {:else}
                  <span
                    class="block rounded-full border border-line px-2.5 py-0.5 text-ink"
                    >{category}</span
                  >
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      </div>
    {:else}
      <div
        class="flex h-full min-h-40 flex-col items-center justify-center gap-4 p-8 text-sm text-muted"
      >
        <p>{copy.selectEntry}</p>
      </div>
    {/if}
  </div>
</article>
