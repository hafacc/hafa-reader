<script lang="ts">
  import Cloud from "@lucide/svelte/icons/cloud";
  import CloudAlert from "@lucide/svelte/icons/cloud-alert";
  import CloudCheck from "@lucide/svelte/icons/cloud-check";
  import CloudOff from "@lucide/svelte/icons/cloud-off";
  import CloudSync from "@lucide/svelte/icons/cloud-sync";
  import type { SyncStatus } from "#lib/sync/host.ts";

  const {
    status,
    class: className = "",
  }: {
    status: SyncStatus;
    class?: string;
  } = $props();
</script>

{#if status === "connecting" || status === "syncing"}
  <CloudSync class="{className} motion-safe:animate-pulse" />
{:else if status === "synced"}
  <CloudCheck class={className} />
{:else if status === "offline"}
  <CloudOff class={className} />
{:else if status === "error"}
  <CloudAlert class={className} />
{:else}
  <Cloud class={className} />
{/if}
