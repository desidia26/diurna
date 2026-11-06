<script lang="ts">
 import { onMount, tick } from 'svelte';
 import type { Query, Week } from './bridge';
 export let query:Query;
 export let failed:()=>void = () => {};
 let week:Week|null = null;
 let error = false;
 let announced = false;
 async function refresh() {
  try {
   week = await query('week.get');
   if (!announced) { await tick(); await query('client.ready'); announced = true; }
  }
  catch { error = true; failed(); }
 }
 onMount(() => { refresh(); const timer = setInterval(refresh, 30000); return () => clearInterval(timer); });
</script>
<main>
 <h1>Week <small>Preview · read only</small></h1>
 {#if error}<p role="alert">Switching to native Week…</p>
 {:else if !week}<p role="status">Loading week…</p>
 {:else}
 <p>Week of {week.start} · {week.onTime}/{week.scoredCount} mornings on time</p>
 {#each week.days as day}
  <article aria-label={day.date}>
   <h2>{day.date}{day.date === week.today ? ' · Today' : ''}</h2>
   <p>{day.morning.replaceAll('_', ' ').toLowerCase()}</p>
   <p>{day.workoutName ?? day.plannedName ?? 'No workout planned'}</p>
   <p>{day.kcal ?? '—'} kcal · {day.steps ?? '—'} steps</p>
  </article>
 {/each}
 <button onclick={refresh}>Refresh</button>
 {/if}
</main>
<style>
 :global(body) { margin:0; background:#121212; color:#eee; font:16px system-ui,sans-serif; }
 main { padding:16px; } h1 { font-size:24px; } h2 {font-size:18px} small {display:block;font-size:13px;color:#c4a7ff}
 article {background:#252525;border-radius:12px;padding:12px;margin:12px 0} p {line-height:1.5}
 button {background:#c4a7ff;color:#181020;border:0;border-radius:8px;padding:12px;font:inherit}
</style>
