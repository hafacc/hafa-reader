<script lang="ts">
  import type { SyncStatus } from "#lib/sync/host.ts";

  const {
    email,
    photo = "",
    status,
    size = 22,
    big = false,
  }: {
    email: string;
    /** The account's picture; the first letter shows until it loads, and instead when there is none or it fails. */
    photo?: string;
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

  let loadedPhoto = $state("");
  let failedPhoto = $state("");
</script>

<span class="relative inline-flex" aria-hidden="true">
  <span
    class="inline-flex shrink-0 items-center justify-center rounded-full bg-accent font-[650] leading-none text-page"
    style:width="{size}px"
    style:height="{size}px"
    style:font-size="{size / 2}px"
    >{email.charAt(0).toUpperCase()}</span
  >
  {#if photo !== "" && photo !== failedPhoto}
    <!-- biome-ignore lint/a11y/noNoninteractiveElementInteractions: the letter shows until the picture loads, and stays when it fails -->
    <img
      src={photo}
      alt=""
      referrerpolicy="no-referrer"
      class="absolute inset-0 size-full rounded-full object-cover {loadedPhoto ===
      photo
        ? ""
        : "opacity-0"}"
      onload={() => {
        loadedPhoto = photo;
      }}
      onerror={() => {
        failedPhoto = photo;
      }}
    >
  {/if}
  <span
    class="absolute rounded-full border-2 {big
      ? "-right-px -bottom-px size-[13px] border-panel"
      : "-right-0.5 -bottom-0.5 size-[9px] border-side"} {tone}"
  ></span>
</span>
