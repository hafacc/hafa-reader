<script lang="ts">
  import type { Snippet } from "svelte";
  import { copy } from "#lib/copy.ts";
  import { asset, resolve } from "$app/paths";

  const { children }: { children: Snippet } = $props();
</script>

<header class="border-line border-b px-6 py-4">
  <a
    class="flex w-fit items-center gap-2 font-semibold text-ink tracking-tight no-underline"
    href={resolve("/")}
  >
    <img class="size-7" src={asset("favicon.svg")} alt="">
    {copy.appName}
  </a>
</header>
<main class="prose mx-auto px-6 pt-10 pb-20">{@render children()}</main>
