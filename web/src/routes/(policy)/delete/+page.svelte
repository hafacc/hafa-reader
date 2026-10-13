<script lang="ts">
  import { onMount } from "svelte";
  import { fade } from "svelte/transition";
  import { AccountDeletion } from "#lib/account-deletion.svelte.ts";
  import GoogleMark from "#lib/components/google-mark.svelte";
  import Logo from "#lib/components/logo.svelte";
  import { firebaseConfig } from "#lib/config.ts";
  import { copy } from "#lib/copy.ts";

  const deletion = new AccountDeletion(async () => {
    const { deletionBackend } = await import("#lib/sync/firebase.ts");
    return deletionBackend(firebaseConfig);
  });

  // written at build time with no account known, the page shows its states only once running
  let mounted = $state(false);
  let reduceMotion = $state(false);
  onMount(() => {
    reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
    mounted = true;
  });
  const shift = $derived({ duration: reduceMotion ? 0 : 200 });

  const button =
    "inline-flex min-h-11 w-full items-center justify-center gap-2.5 rounded-lg px-5 py-2.5 text-sm font-semibold transition-colors disabled:cursor-default disabled:opacity-60 sm:w-auto";
</script>

<svelte:head>
  <title>{copy.deletePageTitle}</title>
  <meta name="description" content={copy.deleteSignInLine}>
</svelte:head>

<h1>{copy.deletePageTitle}</h1>

<div class="grid">
  {#if !mounted}
    <!-- nothing until the page knows who is signed in -->
  {:else if deletion.stage === "deleted"}
    <div class="col-start-1 row-start-1" in:fade={shift}>
      <p role="status">{copy.deleteDone}</p>
      {@render android()}
    </div>
  {:else if deletion.view === "loading"}
    <div
      class="col-start-1 row-start-1 flex animate-[appear_200ms_ease-out_150ms_both] items-start justify-start motion-reduce:animate-[appear_1ms_linear_150ms_both] pt-1"
      role="status"
      out:fade={shift}
    >
      <span aria-hidden="true"><Logo class="size-10" loading /></span>
      <span class="sr-only">{copy.deleteLoading}</span>
    </div>
  {:else if deletion.view === "signedIn"}
    <div class="col-start-1 row-start-1" in:fade={shift}>
      <p>
        {copy.deleteSignedInAs}
        <strong class="block [overflow-wrap:anywhere]">{deletion.email}</strong>
      </p>
      <p class="not-prose -mt-3 mb-5">
        <button
          type="button"
          class="text-sm text-accent underline underline-offset-4 hover:no-underline disabled:opacity-60"
          disabled={deletion.stage === "working"}
          onclick={() => deletion.useOtherAccount()}
        >
          {copy.deleteOtherAccount}
        </button>
      </p>
      <p>{copy.deleteExplain}</p>
      {#if deletion.stage === "failed"}
        <p
          class="not-prose my-5 rounded-lg border border-danger/40 bg-danger/10 px-4 py-3 text-sm text-danger"
          role="alert"
        >
          {copy.deleteFailedPage}
        </p>
      {/if}
      <div class="not-prose mt-6">
        <button
          type="button"
          class="{button} bg-danger text-page hover:opacity-90"
          disabled={deletion.stage === "working"}
          onclick={() => deletion.remove()}
        >
          {copy.deleteEverything}
        </button>
      </div>
      {@render android()}
    </div>
  {:else}
    <div class="col-start-1 row-start-1" in:fade={shift}>
      <p>{copy.deleteSignInLine}</p>
      <div class="not-prose mt-6">
        <button
          type="button"
          class="{button} border border-line bg-panel text-ink hover:bg-side"
          onpointerenter={() => deletion.preload()}
          onfocus={() => deletion.preload()}
          onclick={() => deletion.signIn()}
        >
          <GoogleMark />
          {copy.deleteContinueGoogle}
        </button>
      </div>
      {#if deletion.signInProblem === "blocked"}
        <p class="text-sm text-muted" role="alert">{copy.syncPopupBlocked}</p>
      {:else if deletion.signInProblem === "failed"}
        <p class="text-sm text-muted" role="alert">{copy.syncSignInFailed}</p>
      {/if}
      {@render android()}
    </div>
  {/if}
</div>

{#snippet android()}
  <p class="mt-8 text-sm text-muted">{copy.deleteAndroid}</p>
{/snippet}
