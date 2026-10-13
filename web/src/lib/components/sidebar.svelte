<script lang="ts">
  import CircleAlert from "@lucide/svelte/icons/circle-alert";
  import Folder from "@lucide/svelte/icons/folder";
  import Inbox from "@lucide/svelte/icons/inbox";
  import PanelLeftClose from "@lucide/svelte/icons/panel-left-close";
  import PanelLeftOpen from "@lucide/svelte/icons/panel-left-open";
  import Plus from "@lucide/svelte/icons/plus";
  import Star from "@lucide/svelte/icons/star";
  import { copy } from "#lib/copy.ts";
  import {
    type FeedRow,
    faviconFor,
    logFailure,
    type Reader,
  } from "#lib/reader.svelte.ts";
  import type { Feed, Selection } from "#lib/types.ts";
  import AccountAvatar from "./account-avatar.svelte";
  import FeedIcon from "./feed-icon.svelte";
  import Logo from "./logo.svelte";
  import SyncStatus from "./sync-status.svelte";

  const {
    reader,
    collapsed = false,
    drawer = false,
    toggleable = true,
    ontoggle = () => undefined,
    ondismiss = () => undefined,
    onpick,
    onadd,
    onaccount,
  }: {
    reader: Reader;
    collapsed?: boolean;
    /** Shown in a phone's left drawer instead of beside the list. */
    drawer?: boolean;
    /** Close the drawer the sidebar is shown in. */
    ondismiss?: () => void;
    /** Whether the collapse button shows; off when the screen is too narrow to expand it. */
    toggleable?: boolean;
    ontoggle?: () => void;
    onpick: () => void;
    onadd: () => void;
    /** Show the sign-in and sync details. */
    onaccount: () => void;
  } = $props();

  const fade =
    "transition-opacity duration-200 group-data-[collapsed=true]/sidebar:opacity-0 group-data-[collapsed=true]/sidebar:group-hover/sidebar:opacity-100";

  function listedRows(rows: FeedRow[]): FeedRow[] {
    return rows.filter((row) => reader.listed(row.feed));
  }

  const starredCount = $derived(
    reader.entries.filter((entry) => entry.starred).length,
  );

  function isSelected(selection: Selection): boolean {
    const current = reader.selection;
    let same = current.kind === selection.kind;
    if (same && current.kind === "feed" && selection.kind === "feed") {
      same = current.id === selection.id;
    } else if (
      same &&
      current.kind === "folder" &&
      selection.kind === "folder"
    ) {
      same = current.name === selection.name;
    }
    return same;
  }

  /** The name is a button: it goes to All items and checks every feed. */
  function goHome(): void {
    reader.select({ kind: "all" });
    logFailure(reader.refresh());
    onpick();
  }

  function pick(selection: Selection): void {
    if (isSelected(selection)) {
      logFailure(reader.refreshView());
    } else {
      reader.select(selection);
    }
    onpick();
  }

  /** Download sign-in ahead of a click, so its window opens straight from it. */
  function preloadSync(): void {
    if (!reader.sync.signedIn) {
      reader.sync.preload();
    }
  }

  /** Open the account, or sign in; a failed sign-in opens the account to show why. */
  async function syncClicked(): Promise<void> {
    ondismiss();
    if (reader.sync.signedIn || (await reader.sync.signIn())) {
      onaccount();
    }
  }

  function problemOf(icon: "all" | "starred" | "folder" | Feed): string {
    return typeof icon === "object" ? icon.error : "";
  }
</script>

{#snippet row(
  label: string,
  count: number,
  selection: Selection,
  icon: "all" | "starred" | "folder" | Feed,
  nested = false,
)}
  <button
    type="button"
    class="flex h-9 w-full shrink-0 items-center gap-2.5 whitespace-nowrap rounded-lg pr-2.5 text-left text-sm transition-[padding] duration-200 hover:bg-panel {nested
      ? "pl-5 group-data-[collapsed=true]/sidebar:pl-2.5 group-data-[collapsed=true]/sidebar:group-hover/sidebar:pl-5"
      : "pl-2.5"}  {isSelected(selection)
      ? "bg-panel font-semibold shadow-[0_0_0_1px_var(--line)]"
      : ""}"
    aria-current={isSelected(selection) ? "page" : undefined}
    onclick={() => pick(selection)}
    title={problemOf(icon) || label}
  >
    <span
      class="flex size-5 shrink-0 items-center justify-center  {isSelected(
        selection,
      )
        ? "text-accent"
        : "text-muted"}"
    >
      {#if icon === "all"}
        <Inbox class="size-[18px]" />
      {:else if icon === "starred"}
        <Star class="size-[18px]" />
      {:else if icon === "folder"}
        <Folder class="size-[18px]" />
      {:else}
        <FeedIcon
          source={icon.icon || faviconFor(icon.siteUrl || icon.url)}
          title={icon.title}
          onfail={() => logFailure(reader.refreshIcon(icon.id))}
        />
      {/if}
    </span>
    <span class="min-w-0 flex-1 truncate {fade}">{label}</span>
    {#if problemOf(icon)}
      <CircleAlert class="size-3.5 shrink-0 text-muted {fade}" />
    {/if}
    {#if count > 0}
      <span class="font-mono text-xs tabular-nums text-muted {fade}"
        >{count}</span
      >
    {/if}
  </button>
{/snippet}

<nav
  data-collapsed={drawer ? false : collapsed}
  aria-label={copy.feeds}
  class="group/sidebar flex h-full flex-col gap-0.5 overflow-hidden bg-side px-2 pb-3 {drawer
    ? "w-full"
    : "absolute inset-y-0 left-0 z-20 w-56 border-r border-line transition-[width,box-shadow] duration-200 ease-out data-[collapsed=true]:w-14 data-[collapsed=true]:hover:w-56 data-[collapsed=true]:hover:shadow-xl"}"
>
  <div class="flex h-14 shrink-0 items-center gap-2.5 px-1">
    <button
      type="button"
      class="flex min-w-0 flex-1 items-center gap-2.5 rounded-lg text-left"
      onclick={goHome}
    >
      <Logo
        class="size-8 shrink-0"
        loading={!reader.loaded || reader.refreshing || reader.adding}
      />
      <span
        class="min-w-0 flex-1 truncate whitespace-nowrap font-semibold tracking-tight {fade}"
        >{copy.appName}</span
      >
    </button>
    {#if !drawer && toggleable}
      <button
        type="button"
        class="rounded-md p-1.5 text-muted hover:bg-panel hover:text-ink {fade}"
        aria-label={collapsed ? copy.expandSidebar : copy.collapseSidebar}
        onclick={ontoggle}
      >
        {#if collapsed}
          <PanelLeftOpen class="size-4" />
        {:else}
          <PanelLeftClose class="size-4" />
        {/if}
      </button>
    {/if}
  </div>
  <div
    class="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto overflow-x-hidden"
  >
    {@render row(copy.allItems, reader.unreadTotal, { kind: "all" }, "all")}
    {@render row(copy.starred, starredCount, { kind: "starred" }, "starred")}
    <div class="h-3 shrink-0"></div>
    {#each reader.tree as item (item.kind === "folder"
      ? `folder:${item.name}`
      : item.row.feed.id)}
      {#if item.kind === "folder"}
        {#if reader.folderListed(item.name, item.feeds)}
          {@render row(
            item.name,
            item.unread,
            { kind: "folder", name: item.name },
            "folder",
          )}
          {#each listedRows(item.feeds) as entry (entry.feed.id)}
            {@render row(
              entry.feed.title,
              entry.unread,
              { kind: "feed", id: entry.feed.id },
              entry.feed,
              true,
            )}
          {/each}
        {/if}
      {:else if reader.listed(item.row.feed)}
        {@render row(
          item.row.feed.title,
          item.row.unread,
          { kind: "feed", id: item.row.feed.id },
          item.row.feed,
        )}
      {/if}
    {/each}
  </div>

  <button
    type="button"
    class="mt-2 flex h-9 shrink-0 items-center gap-2.5 whitespace-nowrap rounded-lg px-2.5 text-sm text-muted hover:bg-panel"
    onclick={syncClicked}
    onpointerenter={preloadSync}
    onfocus={preloadSync}
    title={reader.sync.statusText}
  >
    <span class="flex size-5 shrink-0 items-center justify-center">
      {#if reader.sync.email}
        <AccountAvatar
          email={reader.sync.email}
          photo={reader.sync.photo}
          status={reader.sync.status}
        />
      {:else}
        <SyncStatus status={reader.sync.status} class="size-[18px]" />
      {/if}
    </span>
    <span class="truncate {fade}">{reader.sync.statusText}</span>
  </button>

  <button
    type="button"
    class="mt-2 flex h-9 shrink-0 items-center gap-2.5 whitespace-nowrap rounded-lg border border-line px-2.5 text-sm text-muted hover:bg-panel"
    onclick={onadd}
    title={copy.addFeed}
  >
    <span class="flex size-5 shrink-0 items-center justify-center"
      ><Plus class="size-[18px]" /></span
    >
    <span class="truncate {fade}">{copy.addFeed}</span>
  </button>
</nav>
