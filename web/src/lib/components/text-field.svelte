<script lang="ts">
  const {
    id,
    label,
    placeholder = "",
    suggestions = [],
    value,
    onchange,
    oninput,
  }: {
    /** Id of the input; its list of suggestions takes this with "-names" after it. */
    id: string;
    /** The field's name, shown above it. */
    label: string;
    /** What the field says while empty. */
    placeholder?: string;
    /** Names suggested as the text is typed; none by default. */
    suggestions?: string[];
    /** The text the field starts with. */
    value: string;
    /** Called when the text is committed: Enter, or leaving the field. */
    onchange?: (event: Event) => void;
    /** Called on every edit. */
    oninput?: (event: Event) => void;
  } = $props();
</script>

<label class="flex flex-col gap-2 text-sm" for={id}>
  <span class="font-medium">{label}</span>
  <input
    {id}
    class="rounded-lg border border-line bg-panel px-3 py-2"
    list={suggestions.length > 0 ? `${id}-names` : undefined}
    {placeholder}
    {value}
    {onchange}
    {oninput}
    autocomplete="off"
  >
  {#if suggestions.length > 0}
    <datalist id="{id}-names">
      {#each suggestions as name (name)}
        <option value={name}></option>
      {/each}
    </datalist>
  {/if}
</label>
