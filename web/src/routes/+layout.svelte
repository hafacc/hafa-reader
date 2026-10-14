<script lang="ts">
  import "../app.css";
  import "@fontsource-variable/literata";
  import "@fontsource-variable/literata/wght-italic.css";
  import "@fontsource-variable/public-sans";
  import "@fontsource-variable/public-sans/wght-italic.css";
  import type { Snippet } from "svelte";

  const { children }: { children: Snippet } = $props();
</script>

{@render children()}
