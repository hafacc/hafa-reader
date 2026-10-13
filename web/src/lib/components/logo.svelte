<script lang="ts">
  import { untrack } from "svelte";
  import { copy } from "#lib/copy.ts";

  const {
    class: className = "",
    loading = false,
  }: {
    class?: string;
    /** Broadcast waves out from the dot while something loads. */
    loading?: boolean;
  } = $props();

  const CENTER_X = 19;
  const CENTER_Y = 45;
  const SPACING = 13.2;
  const FIRST_RADIUS = 16.8;
  const STROKE = 8.1;
  const STEP_SECONDS = 0.7;
  const SLOTS = [-1, 0, 1, 2];
  const WHITE = "#fff";
  const BLUE = "#8fb4ec";

  const clipId = `logo-clip-${untrack(() => Math.random().toString(36).slice(2))}`;

  // steps traveled; even whole numbers match the still logo
  let phase = $state(0);
  let frame = 0;
  let last = 0;
  let stopAt: number | undefined;

  const waves = $derived.by(() => {
    const whole = Math.floor(phase);
    const fraction = phase - whole;
    return SLOTS.map((slot) => {
      const radius = FIRST_RADIUS + SPACING * (slot + fraction);
      const grow = smooth((radius - (FIRST_RADIUS - SPACING)) / SPACING);
      const shrink = smooth(1 - (radius - (FIRST_RADIUS + SPACING)) / SPACING);
      return {
        slot,
        radius,
        width: STROKE * Math.min(grow, shrink),
        color: (slot - whole) % 2 === 0 ? WHITE : BLUE,
      };
    });
  });

  function smooth(value: number): number {
    const clamped = Math.min(1, Math.max(0, value));
    return clamped * clamped * (3 - 2 * clamped);
  }

  function tick(now: number): void {
    phase += (now - last) / 1000 / STEP_SECONDS;
    last = now;
    if (loading) {
      stopAt = undefined;
    } else if (stopAt === undefined) {
      stopAt = Math.ceil(phase / 2) * 2;
    }
    if (stopAt !== undefined && phase >= stopAt) {
      phase = stopAt;
      stopAt = undefined;
      frame = 0;
    } else {
      frame = requestAnimationFrame(tick);
    }
  }

  $effect(() => {
    if (
      loading &&
      frame === 0 &&
      !matchMedia("(prefers-reduced-motion: reduce)").matches
    ) {
      last = performance.now();
      frame = requestAnimationFrame(tick);
    }
  });

  $effect(() => () => {
    cancelAnimationFrame(frame);
    frame = 0;
  });
</script>

<svg
  class={className}
  viewBox="0 0 64 64"
  role="img"
  aria-label={copy.appName}
  aria-busy={loading}
>
  <defs>
    <clipPath id={clipId}>
      <rect width="64" height="64" rx="15"></rect>
    </clipPath>
  </defs>
  <rect width="64" height="64" rx="15" fill="#16233f"></rect>
  <g clip-path="url(#{clipId})">
    {#each waves as wave (wave.slot)}
      {#if wave.width > 0.01}
        <path
          d="M{CENTER_X} {CENTER_Y -
            wave.radius}A{wave.radius} {wave.radius} 0 0 1 {CENTER_X +
            wave.radius} {CENTER_Y}"
          fill="none"
          stroke={wave.color}
          stroke-width={wave.width}
          stroke-linecap="round"
        ></path>
      {/if}
    {/each}
  </g>
  <circle cx={CENTER_X} cy={CENTER_Y} r="6" fill="#f39a1e"></circle>
</svg>
