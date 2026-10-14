<script lang="ts" generics="Item">
  import type { Snippet } from "svelte";
  import { measureWidth, TOPIC_ROWS, wrappedRows } from "#lib/topic-rows.ts";

  const {
    items,
    key,
    chip,
  }: {
    /** What the chips show, in order. */
    items: Item[];
    /** A key telling each item apart, the same while it stays. */
    key: (item: Item) => string;
    /** One item's chip. */
    chip: Snippet<[Item]>;
  } = $props();

  // Tailwind's gap-2 between chips.
  const GAP_PX = 8;
  // Tailwind's p-1 on each side of the scrolling box, which keeps focus rings from being cut off.
  const INSET_PX = 4;

  let boxWidth = $state(0);
  const chipWidths: number[] = $state([]);
  // Up to TOPIC_ROWS rows wrap as text does; more become that many rows read down each column, scrolled sideways.
  const scrolls = $derived(
    wrappedRows(
      chipWidths.slice(0, items.length),
      boxWidth - INSET_PX * 2,
      GAP_PX,
    ) > TOPIC_ROWS,
  );
</script>

<!-- data-scrolls lets a page leave sideways swipes that start here to the chips -->
<div
  class="-m-1 overflow-x-auto p-1"
  data-scrolls={scrolls ? "" : undefined}
  {@attach measureWidth((width) => {
    boxWidth = width;
  })}
>
  <ul
    class={scrolls
      ? "grid w-max grid-flow-col justify-items-start gap-2"
      : "flex flex-wrap gap-2"}
    style:grid-template-rows={scrolls
      ? `repeat(${TOPIC_ROWS}, minmax(0, 1fr))`
      : undefined}
  >
    {#each items as item, index (key(item))}
      <li
        class="flex shrink-0"
        {@attach measureWidth((width) => {
          chipWidths[index] = width;
        })}
      >
        {@render chip(item)}
      </li>
    {/each}
  </ul>
</div>
