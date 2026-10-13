<script lang="ts">
  import "../app.css";
  import "@fontsource-variable/fraunces";
  import "@fontsource-variable/public-sans";
  import type { Snippet } from "svelte";

  const { children }: { children: Snippet } = $props();
</script>

{@render children()}
