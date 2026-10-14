<script lang="ts">
  const {
    label,
    confirmLabel,
    onconfirm,
    disabled = false,
  }: {
    /** What the button says before it is pressed. */
    label: string;
    /** What it says after the first press, asking for a second. */
    confirmLabel: string;
    /** Called on the second press. */
    onconfirm: () => void;
    /** Whether the button is turned off. */
    disabled?: boolean;
  } = $props();

  // how long the button waits for its second press
  const CONFIRM_MS = 4000;

  let confirming = $state(false);
  let timer: ReturnType<typeof setTimeout> | undefined;

  function press(): void {
    clearTimeout(timer);
    if (confirming) {
      confirming = false;
      onconfirm();
    } else {
      confirming = true;
      timer = setTimeout(() => {
        confirming = false;
      }, CONFIRM_MS);
    }
  }

  $effect(() => () => {
    clearTimeout(timer);
  });
</script>

<button
  type="button"
  class="self-start rounded-lg border px-3 py-2 {confirming
    ? "border-danger text-danger"
    : "border-line hover:bg-side"}"
  {disabled}
  onclick={press}
>
  {confirming ? confirmLabel : label}
</button>
