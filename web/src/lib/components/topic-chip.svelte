<script lang="ts">
  const {
    topic,
    off = false,
    count,
    ontoggle,
  }: {
    /** The topic's name. */
    topic: string;
    /** Whether the view has the topic turned off, shown struck through. */
    off?: boolean;
    /** How many of the view's articles carry it; not shown when left out. */
    count?: number;
    /** Turns the topic off or back on; without it the chip is only a label. */
    ontoggle?: () => void;
  } = $props();
</script>

{#snippet face()}
  <span
    class="flex h-8 items-center gap-1.5 whitespace-nowrap rounded-full border border-line px-3 text-sm {off
      ? "text-faded line-through"
      : "text-ink"}"
  >
    {topic}
    {#if count !== undefined}
      <span class="font-mono text-xs tabular-nums text-muted">{count}</span>
    {/if}
  </span>
{/snippet}

{#if ontoggle}
  <!-- on a phone the chip takes taps 44 pixels tall, as Android's chips take 48 dp -->
  <button
    type="button"
    class="flex min-h-11 items-center rounded-full md:min-h-0"
    aria-pressed={!off}
    onclick={ontoggle}
  >
    {@render face()}
  </button>
{:else}
  {@render face()}
{/if}
