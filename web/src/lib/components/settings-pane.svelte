<script lang="ts">
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import X from "@lucide/svelte/icons/x";
  import { copy } from "#lib/copy.ts";
  import { logFailure, type Reader } from "#lib/reader.svelte.ts";
  import { byCount } from "$shared/filter.ts";
  import ChipRows from "./chip-rows.svelte";
  import ConfirmButton from "./confirm-button.svelte";
  import PhoneBar from "./phone-bar.svelte";
  import Segmented from "./segmented.svelte";
  import TextField from "./text-field.svelte";
  import TopicChip from "./topic-chip.svelte";

  const { reader, onback }: { reader: Reader; onback: () => void } = $props();

  let mode = $state<"hide" | "only">("hide");
  let draft = $state("");

  const settings = $derived(reader.viewSettings);
  const words = $derived(
    mode === "hide" ? settings.hideWords : settings.onlyWords,
  );
  const orderedWords = $derived(
    byCount(words, (word) => reader.wordCount(word)),
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
    if (feed) {
      logFailure(reader.removeFeed(feed.id));
    } else if (folder !== "") {
      logFailure(reader.deleteFolder(folder));
    }
    onback();
  }
</script>

<section class="flex h-full min-h-0 flex-col">
  <PhoneBar title={copy.settings} label={copy.back} onpress={onback}>
    <ArrowLeft class="size-5" />
  </PhoneBar>
  <div class="min-h-0 flex-1 overflow-y-auto px-5 py-8 md:px-12 md:py-10">
    <div class="mx-auto flex max-w-xl flex-col gap-9 text-sm">
      <div class="flex items-start justify-between gap-4">
        <div class="flex min-w-0 flex-col gap-1">
          <span class="hidden font-mono text-xs text-muted md:block"
            >{copy.settings}</span
          >
          <h1 class="truncate font-reading text-3xl font-bold leading-tight">
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
        <TextField
          id="view-name"
          label={copy.nameLabel}
          value={reader.viewTitle}
          onchange={rename}
        />
      {/if}

      <div class="flex flex-col gap-2">
        <span class="font-medium">{copy.sortLabel}</span>
        <Segmented
          label={copy.sortLabel}
          options={[
            { value: "newest", label: copy.newest },
            { value: "oldest", label: copy.oldest },
          ]}
          value={settings.sort}
          onchange={(sort) => logFailure(reader.changeSettings({ sort }))}
        />
      </div>

      <div class="flex flex-col gap-2">
        <span class="font-medium">{copy.readingFontLabel}</span>
        <Segmented
          label={copy.readingFontLabel}
          options={[
            { value: "literata", label: copy.fontLiterata },
            { value: "public-sans", label: copy.fontPublicSans },
          ]}
          value={reader.readingFont}
          onchange={(font) => reader.setReadingFont(font)}
        />
        <p class="text-xs text-muted">{copy.readingFontHint}</p>
      </div>

      {#if reader.filtersApply}
        <div class="flex flex-col gap-3">
          <span class="font-medium">{copy.rulesLabel}</span>
          <Segmented
            label={copy.rulesLabel}
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
          {#if orderedWords.length > 0}
            <ChipRows items={orderedWords} key={(word) => word}>
              {#snippet chip(
                word: string,
              )}
                <!-- on a phone the remove button takes taps 44 pixels across around its small circle -->
                <span class="flex min-h-11 items-center md:min-h-0">
                  <span
                    class="flex h-8 items-center gap-1.5 whitespace-nowrap rounded-full border border-line pl-3 text-sm md:pr-1"
                  >
                    {word}
                    <span class="font-mono text-xs tabular-nums text-muted"
                      >{reader.wordCount(word)}</span
                    >
                    <button
                      type="button"
                      class="group -my-1.5 grid size-11 place-items-center rounded-full text-muted md:my-0 md:size-6"
                      aria-label={copy.removeWord(word)}
                      onclick={() => removeWord(word)}
                    >
                      <span
                        class="grid size-6 place-items-center rounded-full group-hover:bg-side"
                      >
                        <X class="size-3.5" />
                      </span>
                    </button>
                  </span>
                </span>
              {/snippet}
            </ChipRows>
          {/if}
          <p class="text-xs text-muted">{copy.filterHint}</p>
        </div>

        {#if reader.topics.length > 0}
          <div class="flex flex-col gap-3">
            <span class="font-medium">{copy.topics}</span>
            <ChipRows
              items={reader.topics}
              key={(item) => item.topic.trim().toLowerCase()}
            >
              {#snippet chip(item: {
                topic: string;
                count: number;
              })}
                <TopicChip
                  topic={item.topic}
                  count={item.count}
                  off={reader.topicOff(item.topic)}
                  ontoggle={() =>
                    logFailure(reader.toggleTopic(item.topic, false))}
                />
              {/snippet}
            </ChipRows>
            <p class="text-xs text-muted">{copy.topicsHint}</p>
          </div>
        {/if}
      {/if}

      {#if feed}
        <div class="flex flex-col gap-3 border-t border-line pt-8">
          <TextField
            id="feed-group"
            label={copy.groupLabel}
            placeholder={copy.noGroup}
            suggestions={reader.folders}
            value={feed.folder}
            onchange={changeGroup}
          />
          <p class="text-xs text-muted">{copy.groupHint}</p>
          <ConfirmButton
            label={copy.unsubscribe}
            confirmLabel={copy.confirmUnsubscribe}
            onconfirm={remove}
          />
        </div>
      {:else if folder !== ""}
        <div class="flex flex-col gap-3 border-t border-line pt-8">
          <p class="text-xs text-muted">{copy.deleteGroupHint}</p>
          <ConfirmButton
            label={copy.deleteGroup}
            confirmLabel={copy.confirmDeleteGroup}
            onconfirm={remove}
          />
        </div>
      {/if}
    </div>
  </div>
</section>
