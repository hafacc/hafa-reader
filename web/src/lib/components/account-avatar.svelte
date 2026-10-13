<script lang="ts">
  import type { SyncStatus } from "#lib/sync/host.ts";

  const {
    email,
    status,
    size = 22,
    big = false,
  }: {
    email: string;
    status: SyncStatus;
    /** Width and height in pixels. */
    size?: number;
    /** The larger dot, set against the panel rather than the sidebar. */
    big?: boolean;
  } = $props();

  /** The dot's color: green synced, pulsing blue at work, amber when it needs attention, gray otherwise. */
  const tone = $derived.by((): string => {
    const tones: Record<SyncStatus, string> = {
      synced: "bg-ok",
      syncing: "bg-accent motion-safe:animate-dot",
      connecting: "bg-accent motion-safe:animate-dot",
      error: "bg-star",
      waiting: "bg-star",
      offline: "bg-muted",
      off: "bg-muted",
    };
    return tones[status];
  });
</script>

<!-- the account's first letter: no photo, so nothing more is fetched -->
<span class="relative inline-flex" aria-hidden="true">
  <span
    class="inline-flex shrink-0 items-center justify-center rounded-full bg-accent font-[650] leading-none text-page"
    style:width="{size}px"
    style:height="{size}px"
    style:font-size="{size / 2}px"
    >{email.charAt(0).toUpperCase()}</span
  >
  <span
    class="absolute rounded-full border-2 {big
      ? "-right-px -bottom-px size-[13px] border-panel"
      : "-right-0.5 -bottom-0.5 size-[9px] border-side"} {tone}"
  ></span>
</span>
