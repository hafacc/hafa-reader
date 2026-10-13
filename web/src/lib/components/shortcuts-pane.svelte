<script lang="ts">
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import LogIn from "@lucide/svelte/icons/log-in";
  import LogOut from "@lucide/svelte/icons/log-out";
  import X from "@lucide/svelte/icons/x";
  import { contactUrl, privacyUrl, termsUrl } from "#lib/config.ts";
  import { copy } from "#lib/copy.ts";
  import type { Reader } from "#lib/reader.svelte.ts";
  import { type Retention, retentionFromDays } from "#lib/sync/documents.ts";
  import type { SyncStatus as Status } from "#lib/sync/host.ts";
  import AccountAvatar from "./account-avatar.svelte";
  import PhoneBar from "./phone-bar.svelte";
  import Segmented from "./segmented.svelte";
  import SyncStatus from "./sync-status.svelte";

  const { reader, onback }: { reader: Reader; onback: () => void } = $props();

  const sync = $derived(reader.sync);

  $effect(() => {
    if (!sync.signedIn) {
      sync.preload();
    }
  });

  /** Show a failed account action in the usual sync notice. */
  function reportFailure(action: Promise<void>): void {
    action.catch((error: unknown) => {
      console.error("Reader sync:", error);
      reader.notify(copy.syncError);
    });
  }

  /** The status icon's color, matching the avatar's dot. */
  const statusColor: Record<Status, string> = {
    synced: "text-ok",
    syncing: "text-accent",
    connecting: "text-accent",
    error: "text-star",
    waiting: "text-star",
    offline: "text-muted",
    off: "text-muted",
  };

  type RetentionKey = "30" | "90" | "365" | "forever";

  const retentionOptions: { value: RetentionKey; label: string }[] = [
    { value: "30", label: copy.retention30 },
    { value: "90", label: copy.retention90 },
    { value: "365", label: copy.retention365 },
    { value: "forever", label: copy.retentionForever },
  ];

  function retentionKey(retention: Retention): RetentionKey {
    return (
      retentionOptions.find(({ value }) => value === String(retention))
        ?.value ?? "forever"
    );
  }

  function chooseRetention(key: RetentionKey): void {
    reportFailure(
      sync.setRetention(
        key === "forever" ? "forever" : retentionFromDays(Number(key)),
      ),
    );
  }

  // how long the delete button waits for its second press
  const CONFIRM_MS = 4000;
  let confirmingDelete = $state(false);
  let confirmTimer: ReturnType<typeof setTimeout> | undefined;

  /** The first press asks again; the second deletes the account. */
  function deleteClicked(): void {
    clearTimeout(confirmTimer);
    if (confirmingDelete) {
      confirmingDelete = false;
      sync.deleteAccount();
    } else {
      confirmingDelete = true;
      confirmTimer = setTimeout(() => {
        confirmingDelete = false;
      }, CONFIRM_MS);
    }
  }

  const shortcuts: { keys: string[]; label: string; either?: boolean }[] = [
    { keys: ["j"], label: copy.keyNext },
    { keys: ["k"], label: copy.keyPrevious },
    { keys: ["v", "o"], label: copy.keyOriginal, either: true },
    { keys: ["s"], label: copy.keyStar },
    { keys: ["m"], label: copy.keyRead },
    { keys: ["Shift", "A"], label: copy.keyAllRead },
    { keys: ["r"], label: copy.keyRefresh },
    { keys: ["Shift", "R"], label: copy.keyRefreshAll },
    { keys: ["u"], label: copy.keyUnread },
    { keys: ["a"], label: copy.keyAdd },
    { keys: [","], label: copy.keySettings },
    { keys: ["b"], label: copy.keySidebar },
    { keys: ["]"], label: copy.keyNextFeed },
    { keys: ["["], label: copy.keyPreviousFeed },
    { keys: ["g", "a"], label: copy.keyGoAll },
    { keys: ["g", "s"], label: copy.keyGoStarred },
    { keys: ["Esc"], label: copy.keyClose },
    { keys: ["?"], label: copy.keyHelp },
  ];
</script>

<section class="flex h-full min-h-0 flex-col">
  <PhoneBar title={copy.syncHeading} label={copy.back} onpress={onback}>
    <ArrowLeft class="size-5" />
  </PhoneBar>
  <div class="min-h-0 flex-1 overflow-y-auto px-5 py-8 md:px-12 md:py-10">
    <div class="mx-auto flex max-w-xl flex-col gap-6">
      <!-- on a phone the account screen's title is in its bottom bar alone -->
      <div class="hidden items-start justify-between gap-4 md:flex">
        <h1 class="font-serif text-3xl font-bold leading-tight">
          {copy.shortcutsTitle}
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

      <section
        class="flex flex-col items-start gap-3 rounded-lg border border-line p-4 text-sm"
      >
        <h2 class="hidden font-semibold md:block">{copy.syncHeading}</h2>
        {#if sync.status === "waiting"}
          <p class="text-muted">
            {copy.syncSignedInAs}
            <span class="text-ink">{sync.email}</span>
          </p>
          <p>{copy.syncSwitchBody}</p>
          <div class="flex flex-wrap gap-2">
            <button
              type="button"
              class="rounded-lg border border-line px-3 py-2 hover:bg-side"
              onclick={() => reportFailure(sync.replaceLocal())}
            >
              {copy.syncSwitchReplace}
            </button>
            <button
              type="button"
              class="rounded-lg border border-line px-3 py-2 hover:bg-side"
              onclick={() => reportFailure(sync.signOut())}
            >
              {copy.syncSwitchCancel}
            </button>
          </div>
        {:else if sync.email}
          <div class="flex w-full min-w-0 items-center gap-3">
            <AccountAvatar
              email={sync.email}
              photo={sync.photo}
              status={sync.status}
              size={40}
              big
            />
            <span class="flex min-w-0 flex-col leading-snug">
              <span class="text-muted">{copy.syncSignedInAs}</span>
              <span class="truncate text-ink">{sync.email}</span>
            </span>
          </div>
          <p class="flex items-center gap-2">
            <SyncStatus
              status={sync.status}
              class="size-4 {statusColor[sync.status]}"
            />
            {sync.statusText}
          </p>
          {#if sync.loadingOlder}
            <p class="text-muted">{copy.syncLoadingOlder}</p>
          {/if}
          <div class="flex w-full flex-col gap-2">
            <span class="font-medium">{copy.retentionLabel}</span>
            <Segmented
              options={retentionOptions}
              value={retentionKey(sync.retention)}
              onchange={chooseRetention}
            />
            <p class="text-muted">{copy.retentionNote}</p>
          </div>
          <button
            type="button"
            class="flex items-center gap-1.5 rounded-lg border border-line px-3 py-2 hover:bg-side"
            onclick={() => reportFailure(sync.signOut())}
          >
            <LogOut class="size-3.5" />
            {copy.syncSignOut}
          </button>
          <p class="text-muted">{copy.syncDeleteHint}</p>
          <button
            type="button"
            class="rounded-lg border px-3 py-2 {confirmingDelete
              ? "border-star text-star"
              : "border-line hover:bg-side"}"
            disabled={sync.deleting}
            onclick={deleteClicked}
          >
            {#if sync.deleting}
              {copy.syncDeleting}
            {:else if confirmingDelete}
              {copy.syncDeleteConfirm}
            {:else}
              {copy.syncDeleteAccount}
            {/if}
          </button>
          {#if sync.deleteFailed}
            <p class="text-muted">{copy.syncDeleteFailed}</p>
          {/if}
        {:else}
          <p class="text-muted">{copy.syncExplain}</p>
          <button
            type="button"
            class="flex items-center gap-1.5 rounded-lg border border-line px-3 py-2 hover:bg-side"
            disabled={sync.status === "connecting"}
            onclick={() => sync.signIn()}
          >
            <LogIn class="size-3.5" />
            {copy.syncSignIn}
          </button>
          {#if sync.popupBlocked}
            <p class="text-muted">{copy.syncPopupBlocked}</p>
          {:else if sync.signInFailed}
            <p class="text-muted">{copy.syncSignInFailed}</p>
          {/if}
        {/if}
        <p class="flex gap-4 text-xs text-muted">
          <a class="underline hover:text-ink" href={privacyUrl}
            >{copy.privacy}</a
          >
          <a class="underline hover:text-ink" href={termsUrl}>{copy.terms}</a>
          <a class="underline hover:text-ink" href={contactUrl}
            >{copy.contact}</a
          >
        </p>
      </section>

      <!-- on a phone this pane is the account screen: no key list -->
      <ul class="hidden flex-col divide-y divide-line text-sm md:flex">
        {#each shortcuts as shortcut (shortcut.label)}
          <li class="flex items-center justify-between gap-4 py-2.5">
            <span>{shortcut.label}</span>
            <span class="flex shrink-0 items-center gap-1.5">
              {#each shortcut.keys as key, index (index)}
                {#if shortcut.either && index > 0}
                  <span class="text-xs text-muted">/</span>
                {/if}
                <kbd
                  class="min-w-6 rounded-md border border-line bg-side px-1.5 py-0.5 text-center font-mono text-xs"
                  >{key}</kbd
                >
              {/each}
            </span>
          </li>
        {/each}
      </ul>
    </div>
  </div>
</section>
