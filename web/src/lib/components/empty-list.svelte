<script lang="ts">
  const { class: className = "" }: { class?: string } = $props();
</script>

<!-- The feed dot asleep, its waves faint: nothing new to read. -->
<svg
  class={className}
  viewBox="40 40 160 128"
  fill="none"
  aria-hidden="true"
  focusable="false"
>
  <path
    d="M98 78A46 46 0 0 1 144 124"
    stroke="#8fb4ec"
    stroke-width="9"
    stroke-linecap="round"
    opacity="0.45"
  ></path>
  <path
    d="M98 56A68 68 0 0 1 166 124"
    stroke="#8fb4ec"
    stroke-width="9"
    stroke-linecap="round"
    opacity="0.25"
  ></path>
  <circle cx="98" cy="124" r="26" fill="#f39a1e"></circle>
  <rect x="83" y="122" width="11" height="4.5" rx="2.25" fill="#16233f"></rect>
  <rect x="102" y="122" width="11" height="4.5" rx="2.25" fill="#16233f"></rect>
</svg>
