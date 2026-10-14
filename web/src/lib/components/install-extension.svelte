<script lang="ts">
  import { chromeWebStoreUrl } from "#lib/config.ts";
  import { copy } from "#lib/copy.ts";
</script>

<main class="grid h-full place-items-center overflow-y-auto p-6">
  <div class="flex max-w-md flex-col gap-4">
    <h1 class="font-reading text-3xl font-bold text-balance">
      {copy.extensionTitle}
    </h1>
    <p class="text-muted">{copy.extensionBody}</p>
    <a
      class="self-start rounded bg-accent px-4 py-2 text-sm font-semibold text-page"
      href={chromeWebStoreUrl}
      target="_blank"
      rel="noopener noreferrer"
    >
      {copy.installExtension}
    </a>
  </div>
</main>
