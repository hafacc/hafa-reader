<script lang="ts">
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import X from "@lucide/svelte/icons/x";
  import { copy } from "#lib/copy.ts";
  import { logFailure, type Reader } from "#lib/reader.svelte.ts";
  import Segmented from "./segmented.svelte";

  const { reader, onback }: { reader: Reader; onback: () => void } = $props();

  const CONFIRM_MS = 4000;

  let mode = $state<"hide" | "only">("hide");
  let draft = $state("");
  let confirming = $state(false);

  const settings = $derived(reader.viewSettings);
  const words = $derived(
    mode === "hide" ? settings.hideWords : settings.onlyWords,
  );
  const feed = $derived.by(() => {
    const { selection } = reader;
    return selection.kind === "feed"
      ? reader.feeds.find((candidate) => candidate.id === selection.id)
      : undefined;
  });
  const folder = $derived(
    reader.selection.kind === "folder" ? reader.selection.name : "",
  );
  const renamable = $derived(feed !== undefined || folder !== "");

  function setMode(next: "hide" | "only"): void {
    mode = next;
  }

  function changeWords(next: string[]): void {
    logFailure(
      reader.changeSettings(
        mode === "hide" ? { hideWords: next } : { onlyWords: next },
      ),
    );
  }

  function addWord(): void {
    const word = draft.trim();
    if (
      word !== "" &&
      !words.some((known) => known.toLowerCase() === word.toLowerCase())
    ) {
      changeWords([...words, word]);
    }
    draft = "";
  }

  function onWordKeydown(event: KeyboardEvent): void {
    if (event.key === "Enter") {
      event.preventDefault();
      addWord();
    }
  }

  function removeWord(word: string): void {
    changeWords(words.filter((known) => known !== word));
  }

  function rename(event: Event): void {
    const { currentTarget } = event;
    if (currentTarget instanceof HTMLInputElement) {
      if (currentTarget.value.trim() === "") {
        currentTarget.value = reader.viewTitle;
      } else if (feed) {
        logFailure(reader.renameFeed(feed.id, currentTarget.value));
      } else if (folder !== "") {
        logFailure(reader.renameFolder(folder, currentTarget.value));
      }
    }
  }

  function changeGroup(event: Event): void {
    const { currentTarget } = event;
    if (feed && currentTarget instanceof HTMLInputElement) {
      logFailure(reader.setFolder(feed.id, currentTarget.value));
      currentTarget.value = feed.folder;
    }
  }

  function remove(): void {
    if (confirming) {
      if (feed) {
        logFailure(reader.removeFeed(feed.id));
      } else if (folder !== "") {
        logFailure(reader.deleteFolder(folder));
      }
      onback();
    } else {
      confirming = true;
      setTimeout(() => {
        confirming = false;
      }, CONFIRM_MS);
    }
  }
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
      >{copy.settings}</span
    >
  </header>
  <div class="min-h-0 flex-1 overflow-y-auto px-5 py-8 md:px-12 md:py-10">
    <div class="mx-auto flex max-w-xl flex-col gap-9 text-sm">
      <div class="flex items-start justify-between gap-4">
        <div class="flex min-w-0 flex-col gap-1">
          <span class="hidden font-mono text-xs text-muted md:block"
            >{copy.settings}</span
          >
          <h1 class="truncate font-serif text-3xl font-bold leading-tight">
            {reader.viewTitle}
          </h1>
        </div>
        <button
          type="button"
          class="hidden rounded-md p-1.5 text-muted hover:bg-side md:block"
          aria-label={copy.close}
          onclick={onback}
        >
          <X class="size-5" />
        </button>
      </div>

      {#if feed?.error}
        <p class="-mt-5 text-sm text-danger">{feed.error}</p>
      {/if}

      {#if renamable}
        <label class="flex flex-col gap-2" for="view-name">
          <span class="font-medium">{copy.nameLabel}</span>
          <input
            id="view-name"
            class="rounded-lg border border-line bg-panel px-3 py-2"
            value={reader.viewTitle}
            onchange={rename}
            autocomplete="off"
          >
        </label>
      {/if}

      <div class="flex flex-col gap-2">
        <span class="font-medium">{copy.sortLabel}</span>
        <Segmented
          options={[
            { value: "newest", label: copy.newest },
            { value: "oldest", label: copy.oldest },
          ]}
          value={settings.sort}
          onchange={(sort) => logFailure(reader.changeSettings({ sort }))}
        />
      </div>

      {#if reader.filtersApply}
        <div class="flex flex-col gap-3">
          <span class="font-medium">{copy.rulesLabel}</span>
          <Segmented
            options={[
              { value: "hide", label: copy.actionHide },
              { value: "only", label: copy.actionOnly },
            ]}
            value={mode}
            onchange={setMode}
          />
          <input
            class="rounded-lg border border-line bg-panel px-3 py-2"
            placeholder={copy.addPhrase}
            aria-label={copy.addPhrase}
            bind:value={draft}
            onkeydown={onWordKeydown}
            autocomplete="off"
          >
          {#if words.length > 0}
            <div class="flex flex-wrap gap-2">
              {#each words as word (word)}
                <span
                  class="flex items-center gap-1.5 rounded-full border border-line py-1 pl-3 pr-1.5"
                >
                  {word}
                  <span class="font-mono text-xs tabular-nums text-muted"
                    >{reader.wordCount(word)}</span
                  >
                  <button
                    type="button"
                    class="grid size-6 place-items-center rounded-full text-muted hover:bg-side"
                    aria-label={copy.removeRule}
                    onclick={() => removeWord(word)}
                  >
                    <X class="size-3.5" />
                  </button>
                </span>
              {/each}
            </div>
          {/if}
          <p class="text-xs text-muted">{copy.filterHint}</p>
        </div>

        {#if reader.topics.length > 0}
          <div class="flex flex-col gap-3">
            <span class="font-medium">{copy.topics}</span>
            <div class="flex flex-wrap gap-2">
              {#each reader.topics as item (item.topic.toLowerCase())}
                <button
                  type="button"
                  aria-pressed={!reader.topicOff(item.topic)}
                  class="flex items-center gap-1.5 rounded-full border border-line px-3 py-1 {reader.topicOff(
                    item.topic,
                  )
                    ? "text-faded line-through"
                    : ""}"
                  onclick={() =>
                    logFailure(reader.toggleTopic(item.topic, false))}
                >
                  {item.topic}
                  <span class="font-mono text-xs tabular-nums text-muted"
                    >{item.count}</span
                  >
                </button>
              {/each}
            </div>
            <p class="text-xs text-muted">{copy.topicsHint}</p>
          </div>
        {/if}
      {/if}

      {#if feed}
        <div class="flex flex-col gap-3 border-t border-line pt-8">
          <label class="flex flex-col gap-2" for="feed-group">
            <span class="font-medium">{copy.groupLabel}</span>
            <input
              id="feed-group"
              class="rounded-lg border border-line bg-panel px-3 py-2"
              list="group-names"
              placeholder={copy.noGroup}
              value={feed.folder}
              onchange={changeGroup}
              autocomplete="off"
            >
            <datalist id="group-names">
              {#each reader.folders as name (name)}
                <option value={name}></option>
              {/each}
            </datalist>
          </label>
          <p class="text-xs text-muted">{copy.groupHint}</p>
          <button
            type="button"
            class="self-start rounded-lg border px-3 py-2 {confirming
              ? "border-star text-star"
              : "border-line"}"
            onclick={remove}
          >
            {confirming ? copy.confirmUnsubscribe : copy.unsubscribe}
          </button>
        </div>
      {:else if folder !== ""}
        <div class="flex flex-col gap-3 border-t border-line pt-8">
          <p class="text-xs text-muted">{copy.deleteGroupHint}</p>
          <button
            type="button"
            class="self-start rounded-lg border px-3 py-2 {confirming
              ? "border-star text-star"
              : "border-line"}"
            onclick={remove}
          >
            {confirming ? copy.confirmDeleteGroup : copy.deleteGroup}
          </button>
        </div>
      {/if}
    </div>
  </div>
</section>
