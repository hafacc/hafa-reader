<script lang="ts">
  const {
    source,
    title,
    class: className = "size-5",
    onfail = () => undefined,
  }: {
    source: string;
    title: string;
    class?: string;
    /** Called when the picture cannot be loaded. */
    onfail?: () => void;
  } = $props();

  let failedSource = $state("");
</script>

{#if source && source !== failedSource}
  <!-- biome-ignore lint/a11y/noNoninteractiveElementInteractions: a picture that cannot load falls back to a letter -->
  <img
    class="{className} shrink-0 rounded-md bg-line object-contain"
    src={source}
    alt=""
    referrerpolicy="no-referrer"
    loading="lazy"
    onerror={() => {
      failedSource = source;
      onfail();
    }}
  >
{:else}
  <span
    class="{className} flex shrink-0 items-center justify-center rounded-md bg-line text-[0.7rem] font-semibold uppercase text-ink"
    >{title.slice(0, 1)}</span
  >
{/if}
