<script lang="ts">
  import Menu from "@lucide/svelte/icons/menu";
  import { onMount, untrack } from "svelte";
  import { fade, fly } from "svelte/transition";
  import AddFeed from "#lib/components/add-feed.svelte";
  import AddPane from "#lib/components/add-pane.svelte";
  import Article from "#lib/components/article.svelte";
  import EntryList from "#lib/components/entry-list.svelte";
  import InstallExtension from "#lib/components/install-extension.svelte";
  import Logo from "#lib/components/logo.svelte";
  import SettingsPane from "#lib/components/settings-pane.svelte";
  import ShortcutsPane from "#lib/components/shortcuts-pane.svelte";
  import Sidebar from "#lib/components/sidebar.svelte";
  import { copy } from "#lib/copy.ts";
  import { isEdgeSwipe, type Point } from "#lib/gestures.ts";
  import { shortcutKey } from "#lib/keys.ts";
  import { logFailure, Reader, viewKey } from "#lib/reader.svelte.ts";
  import type { Place } from "#lib/types.ts";
  import { goto } from "$app/navigation";
  import { asset } from "$app/paths";
  import { page } from "$app/state";

  // narrower than this is a phone layout
  const NARROW_WIDTH = 768;
  // narrower than this, the sidebar stays an icon strip (opening over the list on hover) so the article keeps room
  const FULL_WIDTH = 1100;
  // how often to pick up what the extension found, and the slowest while the tab is hidden
  const DUE_CHECK_MS = 3000;
  const HIDDEN_CHECK_MS = 60_000;
  // how long after g the second key of a go-to shortcut is awaited
  const GO_MS = 1200;
  const SIDEBAR_KEY = "h-reader:sidebar";

  const reader = new Reader();
  let lastSyncStatus = "";
  let screen = $state<Place["screen"]>("list");
  let detail = $state<Place["detail"]>("article");
  let menuOpen = $state(false);
  let collapsed = $state(false);
  let narrow = $state(false);
  let compact = $state(false);
  const sideCollapsed = $derived(collapsed || compact);
  let ready = $state(false);

  onMount(() => {
    try {
      collapsed = localStorage.getItem(SIDEBAR_KEY) === "1";
    } catch {
      collapsed = false;
    }
    narrow = isNarrow();
    compact = isCompact();
    ready = true;
    const params = new URLSearchParams(location.search);
    let stop: (() => void) | undefined;
    if (import.meta.env.DEV && params.has("demo")) {
      logFailure(
        reader.demo(params.get("demo") ?? "", params.get("sync") ?? ""),
      );
      if (params.get("view") === "empty") {
        reader.selection = { kind: "folder", name: "Empty" };
      }
      // the account pane, open from the start, for looking at the sync screens
      if (params.get("pane") === "account") {
        detail = "help";
        screen = "detail";
      }
    } else {
      stop = watchExtension();
    }
    return stop;
  });

  /** Start the reader and keep picking up what the extension finds; returns how to stop. */
  function watchExtension(): () => void {
    logFailure(reader.init());
    let lastCheck = Date.now();
    function check(): void {
      lastCheck = Date.now();
      if (reader.extension.version) {
        logFailure(reader.collect());
      } else if (reader.loaded) {
        logFailure(reader.connect());
      }
    }
    function onVisibility(): void {
      if (!document.hidden) {
        check();
      }
    }
    const timer = setInterval(() => {
      if (!document.hidden || Date.now() - lastCheck >= HIDDEN_CHECK_MS) {
        check();
      }
    }, DUE_CHECK_MS);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }

  /** A sync error is said where the person is looking, not only in the sidebar. */
  $effect(() => {
    const { status } = reader.sync;
    if (status === "error" && lastSyncStatus !== "error") {
      reader.notify(copy.syncError);
    }
    lastSyncStatus = status;
  });

  $effect(() => {
    const place = here();
    if (ready) {
      remember(false, place);
    }
  });

  $effect(() => {
    const { place } = page.state;
    if (place && JSON.stringify(place) !== JSON.stringify(untrack(here))) {
      untrack(() => restore(place));
    }
  });

  function onResize(): void {
    narrow = isNarrow();
    compact = isCompact();
    if (!narrow) {
      menuOpen = false;
    }
  }

  function toggleCollapsed(): void {
    collapsed = !collapsed;
    try {
      localStorage.setItem(SIDEBAR_KEY, collapsed ? "1" : "0");
    } catch {
      // storage can be blocked; the choice just does not persist
    }
  }

  /** Whether the screen is wide enough for three columns only with the sidebar as an icon strip. */
  function isCompact(): boolean {
    return window.innerWidth >= NARROW_WIDTH && window.innerWidth < FULL_WIDTH;
  }

  function isNarrow(): boolean {
    return window.innerWidth < NARROW_WIDTH;
  }

  function here(): Place {
    return {
      screen,
      detail,
      selection: $state.snapshot(reader.selection),
      entryId: reader.currentId,
    };
  }

  /** Record where the app is in the browser's history, as a new entry when `push`; the page itself does not change. */
  function remember(push: boolean, place: Place = here()): void {
    if (!push) {
      logFailure(
        goto(page.url.href, { state: { place }, shallow: true, replace: true }),
      );
    } else if (JSON.stringify(page.state.place) !== JSON.stringify(place)) {
      logFailure(goto(page.url.href, { state: { place }, shallow: true }));
    }
  }

  function restore(place: Place | undefined): void {
    screen = place?.screen ?? "list";
    detail = place?.detail ?? "article";
    reader.select(place?.selection ?? { kind: "all" });
    menuOpen = false;
    if (
      place?.entryId &&
      reader.entries.some((entry) => entry.id === place.entryId)
    ) {
      logFailure(reader.open(place.entryId));
    } else {
      logFailure(reader.close());
    }
  }

  function showList(): void {
    const leaving = screen !== "list";
    screen = "list";
    menuOpen = false;
    if (detail === "add") {
      detail = "article";
    }
    remember(leaving && isNarrow());
  }

  function showArticle(): void {
    const entering = screen !== "detail";
    screen = "detail";
    detail = "article";
    remember(entering && isNarrow());
  }

  const settingsShown = $derived(detail === "settings");
  const addShown = $derived(detail === "add");
  const helpShown = $derived(detail === "help");

  function togglePane(kind: "settings" | "help"): void {
    if (detail === kind && (screen === "detail" || !isNarrow())) {
      back();
    } else {
      const entering = screen !== "detail";
      detail = kind;
      screen = "detail";
      remember(entering && isNarrow());
    }
  }

  function showAccount(): void {
    menuOpen = false;
    if (!(helpShown && (screen === "detail" || !isNarrow()))) {
      togglePane("help");
    }
  }

  function toggleSettings(): void {
    togglePane("settings");
  }

  function back(): void {
    if (isNarrow() && page.state.place !== undefined) {
      history.back();
    } else {
      detail = "article";
    }
  }

  function toggleMenu(): void {
    menuOpen = !menuOpen;
  }

  let edgeStart: Point | undefined;

  function onEdgeStart(event: TouchEvent): void {
    const [touch] = event.touches;
    edgeStart = touch && { x: touch.clientX, y: touch.clientY };
  }

  /** On a phone, a swipe in from the left edge opens the feed list. */
  function onEdgeEnd(event: TouchEvent): void {
    const [touch] = event.changedTouches;
    if (
      narrow &&
      touch &&
      edgeStart &&
      isEdgeSwipe(edgeStart, { x: touch.clientX, y: touch.clientY })
    ) {
      menuOpen = true;
    }
    edgeStart = undefined;
  }

  function startAdding(): void {
    const entering = screen !== "detail";
    menuOpen = false;
    detail = "add";
    screen = "detail";
    remember(entering && isNarrow());
  }

  function finishAdding(): void {
    detail = "article";
    screen = "list";
    remember(false);
  }

  let goPending = false;

  function startGo(): void {
    goPending = true;
    setTimeout(() => {
      goPending = false;
    }, GO_MS);
  }

  function goTo(key: string): void {
    const targets: Record<string, Place["selection"]> = {
      a: { kind: "all" },
      s: { kind: "starred" },
    };
    const target = targets[key];
    if (target) {
      reader.select(target);
      showList();
    }
  }

  function moveView(delta: number): void {
    reader.moveView(delta);
    showList();
  }

  const shortcuts: Record<string, () => void> = {
    j: () => logFailure(step(1)),
    k: () => logFailure(step(-1)),
    s: () => logFailure(reader.toggleStar()),
    m: () => logFailure(reader.toggleRead()),
    v: openOriginal,
    o: openOriginal,
    A: () => logFailure(reader.markAllRead()),
    r: () => logFailure(reader.refreshView()),
    R: () => logFailure(reader.refresh()),
    u: () => reader.setUnreadOnly(!reader.unreadOnly),
    a: startAdding,
    ",": toggleSettings,
    "?": () => togglePane("help"),
    b: toggleCollapsed,
    "]": () => moveView(1),
    "[": () => moveView(-1),
    g: startGo,
    Escape: () => {
      if (menuOpen) {
        menuOpen = false;
      } else if (screen === "detail" || detail !== "article") {
        back();
      }
    },
  };

  async function step(delta: number): Promise<void> {
    const open = await reader.step(delta);
    if (open) {
      detail = "article";
      if (screen === "list" && isNarrow()) {
        showArticle();
      }
    } else if (isNarrow() && screen === "detail") {
      back();
    }
  }

  function openOriginal(): void {
    if (reader.current?.url) {
      window.open(reader.current.url, "_blank", "noopener,noreferrer");
    }
  }

  function onKeydown(event: KeyboardEvent): void {
    const { target } = event;
    const typing =
      target instanceof HTMLElement &&
      ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName);
    const modifier = event.metaKey || event.ctrlKey || event.altKey;
    if (typing) {
      if (event.key === "Escape" && target instanceof HTMLElement) {
        target.blur();
      }
    } else if (!modifier) {
      const key = shortcutKey(event.key, event.shiftKey);
      const action = shortcuts[key];
      if (goPending) {
        goPending = false;
        goTo(key);
        event.preventDefault();
      } else if (action && (key === "Escape" || !menuOpen)) {
        event.preventDefault();
        action();
      }
    }
  }
</script>

<svelte:head>
  <title>H Reader</title>
  <meta name="description" content="An RSS reader.">
  <link rel="manifest" href={asset("manifest.webmanifest")}>
</svelte:head>

<svelte:window onkeydown={onKeydown} onresize={onResize} />

{#if !reader.checked}
  <main class="grid h-full place-items-center">
    <Logo class="size-12" loading />
  </main>
{:else if !reader.extension.version}
  <InstallExtension />
{:else}
  <main
    class="grid h-full grid-cols-1 md:grid-cols-[var(--side-width)_22rem_minmax(0,1fr)]"
    style:--side-width={sideCollapsed ? "3.5rem" : "14rem"}
  >
    <div class="relative hidden min-h-0 md:block">
      <Sidebar
        {reader}
        collapsed={sideCollapsed}
        toggleable={!compact}
        ontoggle={toggleCollapsed}
        onpick={showList}
        onadd={startAdding}
        onaccount={showAccount}
      />
    </div>
    {#if reader.feeds.length === 0}
      <div
        class="flex min-h-0 flex-col md:col-span-2"
        role="presentation"
        ontouchstart={onEdgeStart}
        ontouchend={onEdgeEnd}
      >
        <div class="grid min-h-0 flex-1 place-items-center overflow-y-auto">
          <AddFeed {reader} first ondone={finishAdding} />
        </div>
        <!-- on a phone the menu button, the way to sync, sits in a bottom bar as on every screen -->
        <header
          class="flex h-[calc(4rem+env(safe-area-inset-bottom))] shrink-0 items-center gap-1 border-t border-line bg-side px-1 pb-[env(safe-area-inset-bottom)] md:hidden"
        >
          <button
            type="button"
            class="grid size-12 place-items-center rounded-md"
            aria-label={copy.feeds}
            onclick={toggleMenu}
          >
            <Menu class="size-5" />
          </button>
          <span class="min-w-0 truncate px-1 text-lg font-semibold"
            >{copy.appName}</span
          >
        </header>
      </div>
    {:else}
      <div
        class="min-h-0 {screen === "list" ? "block" : "hidden"} md:block"
        role="presentation"
        ontouchstart={onEdgeStart}
        ontouchend={onEdgeEnd}
      >
        <EntryList
          {reader}
          settingsOpen={settingsShown}
          shown={screen === "list" || !narrow}
          onopen={showArticle}
          onmenu={toggleMenu}
          onsettings={toggleSettings}
        />
      </div>
      <div class="min-h-0 {screen === "detail" ? "block" : "hidden"} md:block">
        {#if helpShown}
          <ShortcutsPane {reader} onback={back} />
        {:else if addShown}
          <AddPane {reader} onback={back} ondone={finishAdding} />
        {:else if settingsShown}
          {#key viewKey(reader.selection)}
            <SettingsPane {reader} onback={back} />
          {/key}
        {:else}
          <Article {reader} onback={back} onstep={step} />
        {/if}
      </div>
    {/if}
  </main>
  {#if menuOpen}
    <div class="fixed inset-0 z-30 md:hidden">
      <button
        type="button"
        class="absolute inset-0 bg-black/40"
        aria-label={copy.close}
        onclick={toggleMenu}
        transition:fade={{ duration: 150 }}
      ></button>
      <div
        class="absolute inset-y-0 left-0 w-[85%] max-w-xs bg-side pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)] shadow-xl"
        transition:fly={{ x: -320, duration: 200 }}
      >
        <Sidebar
          {reader}
          drawer
          ondismiss={() => {
            menuOpen = false;
          }}
          onpick={showList}
          onadd={startAdding}
          onaccount={showAccount}
        />
      </div>
    </div>
  {/if}
{/if}

{#if reader.notice}
  <div
    class="pointer-events-none fixed inset-x-0 bottom-[calc(env(safe-area-inset-bottom)+5rem)] md:bottom-[calc(env(safe-area-inset-bottom)+1rem)] z-40 flex justify-center px-4"
    transition:fly={{ y: 16, duration: 150 }}
  >
    <p
      class="pointer-events-auto max-w-md rounded-lg bg-ink px-4 py-3 text-sm text-page shadow-lg"
      role="status"
    >
      {reader.notice}
    </p>
  </div>
{/if}
