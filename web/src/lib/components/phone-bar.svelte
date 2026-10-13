<!-- @component
Phone bottom bar: one button and the screen's name, at the bottom where a thumb reaches them, as on Android.
-->
<script lang="ts">
  import type { Snippet } from "svelte";

  const {
    title,
    label,
    onpress,
    children,
  }: {
    title: string;
    label: string;
    onpress: () => void;
    children: Snippet;
  } = $props();
</script>

<header
  class="order-last flex h-[calc(4rem+env(safe-area-inset-bottom))] shrink-0 items-center gap-1 border-t border-line bg-side px-1 pb-[env(safe-area-inset-bottom)] md:hidden"
>
  <button
    type="button"
    class="grid size-12 place-items-center rounded-md"
    aria-label={label}
    onclick={onpress}
  >
    {@render children()}
  </button>
  <span class="min-w-0 truncate px-1 text-lg font-semibold">{title}</span>
</header>
