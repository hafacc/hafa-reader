<script lang="ts" generics="Value extends string">
  const {
    options,
    value,
    onchange,
  }: {
    options: { value: Value; label: string }[];
    value: Value;
    onchange: (value: Value) => void;
  } = $props();
</script>

<fieldset class="m-0 flex min-w-0 rounded-lg border-0 bg-side p-0.5 text-sm">
  {#each options as option (option.value)}
    <button
      type="button"
      aria-pressed={option.value === value}
      class="flex-1 rounded-md px-3 py-1.5 transition-colors {option.value ===
      value
        ? "bg-page font-medium shadow-sm"
        : "text-muted"}"
      onclick={() => onchange(option.value)}
    >
      {option.label}
    </button>
  {/each}
</fieldset>
