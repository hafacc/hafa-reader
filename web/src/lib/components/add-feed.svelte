<script lang="ts">
  import CircleAlert from "@lucide/svelte/icons/circle-alert";
  import Download from "@lucide/svelte/icons/download";
  import Search from "@lucide/svelte/icons/search";
  import Upload from "@lucide/svelte/icons/upload";
  import { copy } from "#lib/copy.ts";
  import { buildOpml } from "#lib/opml.ts";
  import { type FeedHit, faviconFor, type Reader } from "#lib/reader.svelte.ts";
  import FeedIcon from "./feed-icon.svelte";
  import TextField from "./text-field.svelte";

  const {
    reader,
    first,
    ondone,
  }: { reader: Reader; first: boolean; ondone: () => void } = $props();

  const SCHEME_PATTERN = /^[a-z][a-z0-9+.-]*:\/\//iu;
  const SPACE_PATTERN = /\s/u;
  let query = $state("");
  let hits = $state<FeedHit[]>([]);
  // why the address in the field could not be added
  let fieldError = $state("");
  // what a search for the text in the field found; empty while there is nothing to say
  let fieldNote = $state("");
  let dragging = $state(false);
  // a topic search, as opposed to adding an address or a file
  let searching = $state(false);
  // the group imported feeds go into; empty keeps the file's folders
  let importGroup = $state("");

  function editImportGroup(event: Event): void {
    const { currentTarget } = event;
    if (currentTarget instanceof HTMLInputElement) {
      importGroup = currentTarget.value;
    }
  }

  function clearField(): void {
    fieldError = "";
    fieldNote = "";
  }

  function looksLikeAddress(text: string): boolean {
    return (
      SCHEME_PATTERN.test(text) ||
      (text.includes(".") && !SPACE_PATTERN.test(text))
    );
  }

  /** Search for `text`; when nothing is found, `problem` (why it could not be added as an address) is said instead of no results. */
  async function search(text: string, problem = ""): Promise<void> {
    searching = true;
    try {
      hits = await reader.searchFeeds(text);
    } finally {
      searching = false;
    }
    if (hits.length > 0) {
      clearField();
    } else if (problem) {
      fieldError = problem;
    } else {
      fieldNote = copy.noResults;
    }
  }

  async function subscribe(
    address: string,
    fromField: boolean,
    searchIfItFails = false,
  ): Promise<void> {
    reader.adding = true;
    clearField();
    try {
      await reader.addFeed(address);
      query = "";
      hits = [];
      ondone();
    } catch (error) {
      const problem = error instanceof Error ? error.message : String(error);
      if (searchIfItFails) {
        await search(address, problem);
      } else if (fromField) {
        fieldError = problem;
      } else {
        reader.notify(problem);
      }
    } finally {
      reader.adding = false;
    }
  }

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const text = query.trim();
    if (looksLikeAddress(text)) {
      await subscribe(text, true, !SCHEME_PATTERN.test(text));
    } else if (text !== "") {
      reader.adding = true;
      clearField();
      await search(text);
      reader.adding = false;
    }
  }

  /** A file's text; undefined when the browser cannot read it. */
  async function readText(file: File): Promise<string | undefined> {
    try {
      return await file.text();
    } catch {
      return undefined;
    }
  }

  async function importText(source: string): Promise<void> {
    try {
      const result = await reader.importOpml(
        source,
        importGroup.trim() || undefined,
      );
      if (result.found === 0) {
        reader.notify(copy.importNone);
      } else if (result.skipped > 0) {
        reader.notify(copy.importSkippedHttp(result.skipped));
      } else if (result.added === 0) {
        reader.notify(copy.importNothingNew);
      }
      if (result.added > 0) {
        ondone();
      }
    } catch {
      reader.notify(copy.importFailed);
    }
  }

  async function importFile(file: File | undefined): Promise<void> {
    if (file) {
      reader.adding = true;
      const source = await readText(file);
      if (source === undefined) {
        reader.notify(copy.importFailed);
      } else {
        await importText(source);
      }
      reader.adding = false;
    }
  }

  function exportFeeds(): void {
    const text = buildOpml(
      reader.feeds.map((feed) => ({
        url: feed.url,
        title: feed.title,
        folder: feed.folder,
        siteUrl: feed.siteUrl,
      })),
    );
    try {
      const link = document.createElement("a");
      link.href = URL.createObjectURL(
        new Blob([text], { type: "text/x-opml" }),
      );
      link.download = "reader.opml";
      link.click();
      URL.revokeObjectURL(link.href);
    } catch {
      reader.notify(copy.exportFailed);
    }
  }

  function onPick(event: Event): void {
    const { currentTarget } = event;
    if (currentTarget instanceof HTMLInputElement) {
      importFile(currentTarget.files?.[0]);
      currentTarget.value = "";
    }
  }

  function onDrop(event: DragEvent): void {
    event.preventDefault();
    dragging = false;
    importFile(event.dataTransfer?.files[0]);
  }

  function onDragOver(event: DragEvent): void {
    event.preventDefault();
    dragging = true;
  }

  function onDragLeave(): void {
    dragging = false;
  }
</script>

<div class="flex w-full max-w-md flex-col gap-6 {first ? "p-6" : ""}">
  {#if first}
    <div class="flex flex-col gap-2">
      <h2 class="font-reading text-2xl font-bold">{copy.emptyTitle}</h2>
      <p class="text-sm text-muted">{copy.emptyBody}</p>
    </div>
  {/if}

  <form class="flex flex-col gap-3" onsubmit={submit}>
    <label class="flex flex-col gap-1.5 text-sm" for="feed-query">
      {copy.findFeed}
      <span
        class="flex items-center gap-2 rounded-lg border bg-panel px-3 py-2 {fieldError
          ? "border-danger"
          : "border-line"}"
      >
        <Search class="size-4 shrink-0 text-muted" />
        <input
          id="feed-query"
          class="min-w-0 flex-1 bg-transparent outline-none"
          bind:value={query}
          oninput={clearField}
          required
          inputmode="url"
          enterkeyhint="go"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          aria-invalid={fieldError !== ""}
          aria-describedby="feed-query-note"
        >
        {#if fieldError}
          <CircleAlert class="size-4 shrink-0 text-danger" aria-hidden="true" />
        {/if}
      </span>
    </label>
    <p
      id="feed-query-note"
      class="text-xs {fieldError ? "text-danger" : "text-muted"}"
      aria-live="polite"
    >
      {fieldError || fieldNote || copy.findHint}
    </p>
    <button
      type="submit"
      class="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-page disabled:opacity-50"
      disabled={reader.adding}
    >
      {#if !reader.adding}
        {copy.addFeed}
      {:else if searching}
        {copy.searching}
      {:else}
        {copy.adding}
      {/if}
    </button>
  </form>

  {#if hits.length > 0}
    <ul
      class="flex flex-col divide-y divide-line rounded-lg border border-line"
    >
      {#each hits as hit (hit.url)}
        <li>
          <button
            type="button"
            class="flex w-full items-start gap-3 p-3 text-left hover:bg-side"
            disabled={reader.adding}
            onclick={() => subscribe(hit.url, false)}
          >
            <FeedIcon
              source={hit.icon || faviconFor(hit.url)}
              title={hit.title}
              class="mt-0.5 size-6"
            />
            <span class="flex min-w-0 flex-col gap-0.5">
              <span class="truncate text-sm font-semibold">{hit.title}</span>
              <span class="line-clamp-2 text-xs text-muted"
                >{hit.description}</span
              >
              <span class="font-mono text-xs text-muted"
                >{copy.subscribers(hit.subscribers)}</span
              >
            </span>
          </button>
        </li>
      {/each}
    </ul>
  {/if}

  <div class="flex flex-col gap-3 border-t border-line pt-6">
    <h3 class="font-reading text-lg font-bold">{copy.importTitle}</h3>
    <p class="text-sm text-muted">{copy.importBody}</p>
    <TextField
      id="import-group"
      label={copy.importGroupLabel}
      placeholder={copy.importKeepFolders}
      suggestions={reader.folders}
      value={importGroup}
      oninput={editImportGroup}
    />
    <!-- biome-ignore lint/a11y/noStaticElementInteractions: a drop target; the file picker inside is the keyboard route -->
    <div
      role="presentation"
      ondragover={onDragOver}
      ondragleave={onDragLeave}
      ondrop={onDrop}
    >
      <label
        class="flex cursor-pointer flex-col items-center gap-2 rounded-xl border-2 border-dashed px-4 py-8 text-center text-sm text-muted transition-colors hover:bg-side {dragging
          ? "border-accent bg-accent-soft text-ink"
          : "border-line"}"
      >
        <Upload class="size-5" />
        {copy.importDrop}
        <input
          class="sr-only"
          type="file"
          accept=".opml, .xml"
          onchange={onPick}
        >
      </label>
    </div>
  </div>

  {#if reader.feeds.length > 0}
    <button
      type="button"
      class="flex items-center justify-center gap-2 rounded-lg border border-line px-3 py-2 text-sm hover:bg-side"
      onclick={exportFeeds}
    >
      <Download class="size-4" />
      {copy.exportOpml}
    </button>
  {/if}
</div>
