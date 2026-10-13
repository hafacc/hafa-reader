<script lang="ts">
  import CheckCheck from "@lucide/svelte/icons/check-check";
  import CircleAlert from "@lucide/svelte/icons/circle-alert";
  import Eye from "@lucide/svelte/icons/eye";
  import EyeOff from "@lucide/svelte/icons/eye-off";
  import Menu from "@lucide/svelte/icons/menu";
  import RefreshCw from "@lucide/svelte/icons/refresh-cw";
  import SlidersHorizontal from "@lucide/svelte/icons/sliders-horizontal";
  import { copy } from "#lib/copy.ts";
  import { type Axis, touchAxis } from "#lib/gestures.ts";
  import { logFailure, type Reader, viewKey } from "#lib/reader.svelte.ts";
  import EmptyList from "./empty-list.svelte";
  import EntryCard from "./entry-card.svelte";

  const {
    reader,
    settingsOpen,
    shown,
    onopen,
    onmenu,
    onsettings,
  }: {
    reader: Reader;
    settingsOpen: boolean;
    /** Whether the list is on screen; on a phone it is hidden while reading. */
    shown: boolean;
    onopen: () => void;
    onmenu: () => void;
    onsettings: () => void;
  } = $props();

  // how far a pull must go to refresh, and the most the marker moves
  const PULL_DISTANCE = 70;
  const PULL_LIMIT = 120;
  // degrees the marker turns per pixel pulled, and how far it travels per pixel, as Android's pull to refresh
  const PULL_TURN = 3;
  const PULL_FOLLOW = 0.5;
  // where the marker rests while the feeds are checked
  const MARKER_REST = 16;
  // rows drawn at a time
  const PAGE = 60;

  let pull = $state(0);
  let scroller: HTMLDivElement | undefined = $state();
  let sentinel: HTMLDivElement | undefined = $state();
  let drawn = $state(PAGE);
  let startX = 0;
  let startY = 0;
  // which way the touch is going, once it is clear
  let axis: Axis = "none";
  // whether the touch began with the list at its top, where a pull may start
  let atTop = false;

  /** The feed on show, when the view is one feed. */
  const shownFeed = $derived.by(() => {
    const { selection } = reader;
    return selection.kind === "feed"
      ? reader.feeds.find((feed) => feed.id === selection.id)
      : undefined;
  });

  const feedTitles = $derived(
    new Map(reader.feeds.map((feed) => [feed.id, feed.title])),
  );

  /** Only the first rows are drawn; more come as the end of the list nears. */
  const rows = $derived(reader.visible.slice(0, drawn));

  /** Start again from the top whenever the list is for something else. */
  const listKey = $derived(
    `${viewKey(reader.selection)}|${reader.unreadOnly}|${reader.viewSettings.sort}`,
  );
  let lastKey = "";

  $effect(() => {
    if (listKey !== lastKey) {
      lastKey = listKey;
      drawn = PAGE;
      if (scroller) {
        scroller.scrollTop = 0;
      }
    }
  });

  /** Draw more rows whenever the end of the list comes within reach. */
  $effect(() => {
    if (scroller && sentinel && drawn < reader.visible.length) {
      const watcher = new IntersectionObserver(
        (seen) => {
          if (seen.some((item) => item.isIntersecting)) {
            drawn += PAGE;
          }
        },
        { root: scroller, rootMargin: "800px" },
      );
      watcher.observe(sentinel);
      return () => watcher.disconnect();
    }
  });

  /** Make sure the open article's row is drawn, so it can be scrolled to. */
  $effect(() => {
    const index = reader.visible.findIndex(
      (entry) => entry.id === reader.currentId,
    );
    if (index >= drawn) {
      drawn = index + PAGE;
    }
  });

  function onTouchStart(event: TouchEvent): void {
    const [touch] = event.touches;
    if (touch && scroller) {
      startX = touch.clientX;
      startY = touch.clientY;
      atTop = scroller.scrollTop <= 0;
      axis = "none";
    }
  }

  function onTouchMove(event: TouchEvent): void {
    const [touch] = event.touches;
    if (touch && scroller) {
      const down = touch.clientY - startY;
      if (axis === "none") {
        axis = touchAxis(touch.clientX - startX, down);
      }
      // a pull starts only from the top and only downward; a sideways swipe (a card's) or scrolling away from the top ends it
      if (atTop && axis !== "sideways" && down > 0 && scroller.scrollTop <= 0) {
        pull = Math.min(PULL_LIMIT, down);
      } else {
        pull = 0;
        atTop = false;
      }
    }
  }

  function onTouchEnd(): void {
    if (pull >= PULL_DISTANCE) {
      logFailure(reader.refreshView());
    }
    pull = 0;
    atTop = false;
  }

  $effect(() => {
    const id = reader.currentId;
    if (id && shown) {
      document
        .getElementById(`entry-${id}`)
        ?.scrollIntoView({ block: "nearest" });
    }
  });
</script>

<section class="relative flex h-full min-h-0 flex-col border-line md:border-r">
  <!-- on a phone the bar sits at the bottom, where a thumb reaches it, as on Android -->
  <header
    class="order-last flex h-[calc(4rem+env(safe-area-inset-bottom))] shrink-0 items-center gap-1 border-t border-line bg-side px-1 pb-[env(safe-area-inset-bottom)] text-sm md:order-none md:h-14 md:gap-2 md:border-t-0 md:border-b md:bg-transparent md:px-4 md:pb-0"
  >
    <button
      type="button"
      class="grid size-12 place-items-center rounded-md text-ink hover:bg-side md:hidden"
      aria-label={copy.feeds}
      onclick={onmenu}
    >
      <Menu class="size-5" />
    </button>
    <span class="min-w-0 truncate px-1 text-lg font-semibold md:hidden"
      >{reader.viewTitle}</span
    >
    <span
      class="hidden whitespace-nowrap font-mono text-xs tabular-nums text-muted md:inline"
      >{copy.unread(reader.viewUnread)}</span
    >
    <span class="flex-1"></span>
    <button
      type="button"
      class="grid size-12 place-items-center rounded-md md:size-8 hover:bg-side {reader.unreadOnly
        ? "text-accent"
        : "text-muted"}"
      aria-label={copy.unreadOnly}
      aria-pressed={reader.unreadOnly}
      title={copy.unreadOnly}
      onclick={() => reader.setUnreadOnly(!reader.unreadOnly)}
    >
      {#if reader.unreadOnly}
        <EyeOff class="size-5 md:size-4" />
      {:else}
        <Eye class="size-5 md:size-4" />
      {/if}
    </button>
    <button
      type="button"
      class="grid size-12 place-items-center rounded-md md:size-8 text-muted hover:bg-side {settingsOpen
        ? "bg-side text-ink"
        : ""}"
      aria-label={copy.settings}
      aria-pressed={settingsOpen}
      title={copy.settings}
      onclick={onsettings}
    >
      <SlidersHorizontal class="size-5 md:size-4" />
    </button>
    <button
      type="button"
      class="grid size-12 place-items-center rounded-md md:size-8 text-muted hover:bg-side"
      aria-label={copy.markAllRead}
      title={copy.markAllRead}
      onclick={() => logFailure(reader.markAllRead())}
    >
      <CheckCheck class="size-5 md:size-4" />
    </button>
  </header>
  <div
    bind:this={scroller}
    role="presentation"
    class="relative min-h-0 flex-1 overflow-y-auto overscroll-y-contain"
    ontouchstart={onTouchStart}
    ontouchmove={onTouchMove}
    ontouchend={onTouchEnd}
    ontouchcancel={onTouchEnd}
  >
    {#if pull > 0 || reader.refreshing}
      <!-- the pull marker, which turns with the pull and then spins at the top while the feeds are checked; on a wide screen the sidebar logo shows that instead -->
      <div
        class="pointer-events-none sticky top-0 z-10 flex h-0 justify-center {pull ===
        0
          ? "md:hidden"
          : ""}"
        aria-hidden="true"
      >
        <span
          class="grid size-10 place-items-center rounded-full border border-line bg-page shadow-md {pull ===
            0 || pull >= PULL_DISTANCE
            ? "text-accent"
            : "text-muted"} {pull === 0 ? "transition-transform" : ""}"
          style:transform="translateY({pull === 0
            ? MARKER_REST
            : pull * PULL_FOLLOW - 24}px)"
        >
          <RefreshCw
            class="size-5 {pull === 0 ? "motion-safe:animate-spin" : ""}"
            style={pull === 0
              ? ""
              : `transform: rotate(${pull * PULL_TURN}deg)`}
          />
        </span>
      </div>
    {/if}
    <div>
      {#if shownFeed?.error}
        <p
          class="flex items-center gap-3 border-b border-line bg-danger/10 px-4 py-3 text-sm text-danger"
        >
          <CircleAlert class="size-5 shrink-0" aria-hidden="true" />
          {shownFeed.error}
        </p>
      {/if}
      {#each rows as entry (entry.id)}
        <EntryCard
          {entry}
          source={feedTitles.get(entry.feedId) ?? ""}
          active={entry.id === reader.currentId}
          onopen={() => {
            logFailure(reader.open(entry.id));
            onopen();
          }}
          ontoggle={() => logFailure(reader.toggleReadFor(entry.id))}
          onstar={() => logFailure(reader.toggleStarFor(entry.id))}
        />
      {:else}
        <div
          class="flex min-h-40 flex-col items-center justify-center gap-3 px-8 py-12 text-sm text-muted"
        >
          <EmptyList class="w-28 max-w-full md:w-32" />
          <p>{copy.noEntries}</p>
        </div>
      {/each}
      <div bind:this={sentinel} class="h-px"></div>
    </div>
  </div>
</section>
